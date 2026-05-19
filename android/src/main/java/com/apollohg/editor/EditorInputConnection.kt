package com.apollohg.editor

import android.text.Selection
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * Custom [InputConnectionWrapper] that intercepts all text input from the soft keyboard
 * and routes it through the Rust editor-core engine via the hosting [EditorEditText].
 *
 * Instead of letting Android's EditText text storage handle insertions and deletions
 * directly, this class captures the user's intent (typing, deleting, IME composition)
 * and delegates to the Rust editor. The Rust editor returns render elements, which are
 * converted to [android.text.SpannableStringBuilder] via [RenderBridge] and applied
 * back to the EditText.
 *
 * ## Composition (IME) Handling
 *
 * For CJK input methods, swipe keyboards, and some autocorrect flows, [setComposingText],
 * [commitText], and [finishComposingText] are used together. During composition, we let
 * the base [InputConnection] render transient composing text, but keep the original
 * Rust-authorized replacement range so the final committed text lands at the correct
 * document position.
 *
 * ## Key Events
 *
 * Hardware keyboard events (backspace, enter) arrive via [sendKeyEvent]. We intercept
 * DEL and ENTER to route through the Rust editor.
 */
class EditorInputConnection(
    private val editorView: EditorEditText,
    baseConnection: InputConnection,
    private val boundEditorId: Long,
    private val boundGeneration: Long
) : InputConnectionWrapper(baseConnection, true) {

    companion object {
        internal fun codePointsToUtf16Length(
            text: String,
            fromUtf16Offset: Int,
            codePointCount: Int,
            forward: Boolean
        ): Int {
            if (codePointCount <= 0 || text.isEmpty()) return 0

            var remaining = codePointCount
            var utf16Length = 0

            if (forward) {
                var index = fromUtf16Offset.coerceIn(0, text.length)
                while (index < text.length && remaining > 0) {
                    val codePoint = Character.codePointAt(text, index)
                    val charCount = Character.charCount(codePoint)
                    utf16Length += charCount
                    index += charCount
                    remaining--
                }
            } else {
                var index = fromUtf16Offset.coerceIn(0, text.length)
                while (index > 0 && remaining > 0) {
                    val codePoint = Character.codePointBefore(text, index)
                    val charCount = Character.charCount(codePoint)
                    utf16Length += charCount
                    index -= charCount
                    remaining--
                }
            }

            return utf16Length
        }
    }

    /**
     * Called when the IME commits finalized text (single character, word,
     * autocomplete selection, etc.).
     *
     * Routes the text through Rust instead of directly inserting into the EditText.
     */
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) return true
        if (editorView.isApplyingRustState) {
            return super.commitText(text, newCursorPosition)
        }
        if (editorView.editorId == 0L) {
            return super.commitText(text, newCursorPosition)
        }

        editorView.recordImeTraceForTesting(
            "commitText",
            "textLength=${text?.length ?: 0} cursor=$newCursorPosition"
        )
        commitTextToEditor(text?.toString(), newCursorPosition)
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) return true
        if (editorView.isApplyingRustState) {
            return super.commitCompletion(text)
        }
        if (editorView.editorId == 0L) {
            return super.commitCompletion(text)
        }
        editorView.recordImeTraceForTesting(
            "commitCompletion",
            "textLength=${text?.text?.length ?: 0}"
        )
        commitTextToEditor(text?.text?.toString(), 1)
        return true
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) return true
        if (editorView.isApplyingRustState) {
            return super.commitCorrection(correctionInfo)
        }
        if (editorView.editorId == 0L) {
            return super.commitCorrection(correctionInfo)
        }
        val newText = correctionInfo?.newText?.toString()
        if (newText == null) return true
        editorView.recordImeTraceForTesting(
            "commitCorrection",
            "offset=${correctionInfo.offset} oldMissing=${correctionInfo.oldText == null} newLength=${newText.length}"
        )
        if (trackedCompositionReplacementRange() != null) {
            editorView.recordImeTraceForTesting(
                "commitCorrectionComposition",
                "newLength=${newText.length}"
            )
            commitTextToEditor(newText, 1)
            return true
        }
        if (consumeInvalidatedCompositionReplacementRangeAndRestore()) {
            editorView.recordImeTraceForTesting("commitCorrectionRestoredInvalidComposition")
            return true
        }
        val oldText = correctionInfo.oldText?.toString()
        val offset = correctionInfo.offset
        val applied = if (oldText != null && offset >= 0) {
            editorView.handleCorrectionCommit(offset, oldText, newText)
        } else if (oldText == null) {
            editorView.handleMissingOldTextCorrectionCommit(offset, newText)
        } else {
            false
        }
        editorView.recordImeTraceForTesting(
            "commitCorrectionResult",
            "applied=$applied"
        )
        return true
    }

    private fun commitTextToEditor(committedText: String?, newCursorPosition: Int) {
        val replacementRange = trackedCompositionReplacementRange()
        if (replacementRange != null) {
            clearCompositionTracking()
            editorView.runWithTransientInputMutationGuard {
                super.finishComposingText()
            }
            if (committedText != null) {
                editorView.handleCompositionCommit(
                    committedText,
                    replacementRange.first,
                    replacementRange.second,
                    newCursorPosition
                )
            } else {
                editorView.restoreAuthorizedTextIfNeeded()
            }
        } else {
            if (consumeInvalidatedCompositionReplacementRangeAndRestore()) {
                return
            }
            clearCompositionTracking()
            committedText?.let { editorView.handleTextCommit(it, newCursorPosition) }
        }
    }

    /**
     * Called when the IME requests deletion of text surrounding the cursor.
     *
     * Routes the deletion through Rust instead of directly modifying the EditText.
     *
     * @param beforeLength Number of UTF-16 code units to delete before the cursor.
     * @param afterLength Number of UTF-16 code units to delete after the cursor.
     */
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) return true
        if (editorView.isApplyingRustState) {
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        if (
            editorView.hasInvalidatedCompositionReplacementRangeForEditor() &&
            isNoOpSurroundingDelete(beforeLength, afterLength)
        ) {
            return finishStaleComposingUpdateAfterInvalidation()
        }
        if (consumeInvalidatedCompositionReplacementRangeAndRestore()) {
            return true
        }
        if (trackedCompositionReplacementRange() != null) {
            val beforeText = editorView.text?.toString()
            var didFallbackDelete = false
            val result = editorView.runWithTransientInputMutationGuard {
                val baseResult = super.deleteSurroundingText(beforeLength, afterLength)
                if (
                    beforeText != null &&
                    beforeText == editorView.text?.toString() &&
                    (beforeLength > 0 || afterLength > 0)
                ) {
                    didFallbackDelete = deleteTransientTextAroundSelection(beforeLength, afterLength)
                }
                baseResult
            }
            refreshComposingTextFromEditable()
            return result || didFallbackDelete
        }
        editorView.handleDelete(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) return true
        if (editorView.isApplyingRustState) {
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }
        if (
            editorView.hasInvalidatedCompositionReplacementRangeForEditor() &&
            isNoOpSurroundingDelete(beforeLength, afterLength)
        ) {
            return finishStaleComposingUpdateAfterInvalidation()
        }
        if (consumeInvalidatedCompositionReplacementRangeAndRestore()) {
            return true
        }
        if (trackedCompositionReplacementRange() != null) {
            val beforeText = editorView.text?.toString()
            var didFallbackDelete = false
            val result = editorView.runWithTransientInputMutationGuard {
                val baseResult = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                if (
                    beforeText != null &&
                    beforeText == editorView.text?.toString() &&
                    (beforeLength > 0 || afterLength > 0)
                ) {
                    didFallbackDelete = deleteTransientTextAroundSelectionInCodePoints(
                        beforeLength,
                        afterLength
                    )
                }
                baseResult
            }
            refreshComposingTextFromEditable()
            return result || didFallbackDelete
        }

        val currentText = editorView.text?.toString().orEmpty()
        val cursor = editorView.selectionStart.coerceAtLeast(0)
        val beforeUtf16Length = codePointsToUtf16Length(
            text = currentText,
            fromUtf16Offset = cursor,
            codePointCount = beforeLength,
            forward = false
        )
        val afterUtf16Length = codePointsToUtf16Length(
            text = currentText,
            fromUtf16Offset = editorView.selectionEnd.coerceAtLeast(cursor),
            codePointCount = afterLength,
            forward = true
        )
        editorView.handleDelete(beforeUtf16Length, afterUtf16Length)
        return true
    }

    /**
     * Called when the IME sets composing (in-progress) text for CJK/swipe input.
     *
     * We let the base InputConnection handle this normally so the user sees
     * the composing text with its underline decoration. The text is NOT sent
     * to Rust during composition — only when the IME commits or finishes it.
     */
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) return true
        if (editorView.editorId == 0L) return super.setComposingText(text, newCursorPosition)
        if (editorView.hasInvalidatedCompositionReplacementRangeForEditor()) {
            return finishStaleComposingUpdateAfterInvalidation()
        }
        captureCompositionReplacementRangeIfNeeded()
        editorView.recordImeTraceForTesting(
            "setComposingText",
            "textLength=${text?.length ?: 0} cursor=$newCursorPosition"
        )
        editorView.setComposingTextForEditor(text?.toString())
        return editorView.runWithTransientInputMutationGuard {
            super.setComposingText(text, newCursorPosition)
        }
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) return true
        if (editorView.editorId == 0L) return super.setComposingRegion(start, end)
        if (editorView.hasInvalidatedCompositionReplacementRangeForEditor()) {
            return finishStaleComposingUpdateAfterInvalidation()
        }
        if (editorView.isCurrentTextAuthorizedForEditor()) {
            editorView.setCompositionReplacementRange(start, end)
        }
        editorView.recordImeTraceForTesting(
            "setComposingRegion",
            "range=$start..$end"
        )
        return editorView.runWithTransientInputMutationGuard {
            super.setComposingRegion(start, end)
        }
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) {
            consumeInvalidatedCompositionReplacementRangeAndRestore()
            return true
        }
        if (editorView.isApplyingRustState) {
            return super.setSelection(start, end)
        }
        if (editorView.editorId == 0L) {
            return super.setSelection(start, end)
        }
        if (editorView.hasInvalidatedCompositionReplacementRangeForEditor()) {
            return finishStaleComposingUpdateAfterInvalidation()
        }
        return super.setSelection(start, end)
    }

    /**
     * Called when IME composition is finalized (user selects a candidate or
     * presses space/enter to commit the composing text).
     *
     * At this point, the composed text is final. We notify the [EditorEditText]
     * so it can capture the result and send it to Rust.
     */
    override fun finishComposingText(): Boolean {
        return finishComposingTextInternal(blockWhenCompositionWasCancelled = false)
    }

    internal fun flushPendingCompositionForExternalMutation(): Boolean {
        if (!isCurrentInputSession()) return true
        if (!hasPendingComposition()) return true
        return finishComposingTextInternal(blockWhenCompositionWasCancelled = true)
    }

    internal fun hasPendingComposition(): Boolean {
        if (!isCurrentInputSession()) return false
        if (trackedCompositionReplacementRange() != null) return true
        val editable = editorView.text ?: return false
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        return start >= 0 && end >= 0 && start != end
    }

    internal fun refreshComposingTextFromEditableForEditor() {
        if (!isCurrentInputSession()) return
        refreshComposingTextFromEditable()
    }

    internal fun clearCompositionTrackingForEditor() {
        if (!isCurrentInputSession()) return
        clearCompositionTracking()
    }

    internal fun deleteTransientTextForHardwareKeyEvent(event: KeyEvent): Boolean =
        if (!isCurrentInputSession()) {
            false
        } else {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> deleteTransientTextAroundSelectionInCodePoints(1, 0)
                KeyEvent.KEYCODE_FORWARD_DEL -> deleteTransientTextAroundSelectionInCodePoints(0, 1)
                else -> false
            }
        }

    private fun finishComposingTextInternal(blockWhenCompositionWasCancelled: Boolean): Boolean {
        if (!isCurrentInputSession()) return true
        if (!editorView.isEditable) {
            clearCompositionTracking()
            editorView.restoreAuthorizedTextIfNeeded()
            return true
        }
        if (editorView.editorId == 0L) return super.finishComposingText()
        refreshComposingTextFromEditable()
        val composed = editorView.composingTextForEditor() ?: currentComposingSpanText()
        val trackedReplacementRange = trackedCompositionReplacementRange()
        val didInvalidateReplacementRange = consumeInvalidatedCompositionReplacementRange()
        val replacementRange = if (didInvalidateReplacementRange) {
            null
        } else {
            trackedReplacementRange ?: currentComposingSpanRange()
        }
        editorView.recordImeTraceForTesting(
            "finishComposingText",
            "replacement=${replacementRange?.first}..${replacementRange?.second} composedLength=${composed?.length ?: 0} invalidated=$didInvalidateReplacementRange"
        )
        clearCompositionTracking()

        // Prevent selection sync while the base connection commits the composed
        // text, since the Rust document doesn't have it yet.
        val result = editorView.runWithTransientInputMutationGuard {
            super.finishComposingText()
        }

        // Now route the composed text through Rust.
        if (
            replacementRange != null &&
            (!composed.isNullOrEmpty() || replacementRange.first != replacementRange.second)
        ) {
            editorView.handleCompositionCommit(
                composed.orEmpty(),
                replacementRange.first,
                replacementRange.second
            )
            return true
        } else if (replacementRange != null) {
            editorView.restoreAuthorizedTextIfNeeded()
            return !blockWhenCompositionWasCancelled
        } else if (didInvalidateReplacementRange) {
            editorView.restoreAuthorizedTextIfNeeded()
            return !blockWhenCompositionWasCancelled
        }
        return result
    }

    private fun captureCompositionReplacementRangeIfNeeded() {
        editorView.captureCompositionReplacementRangeIfNeeded()
    }

    private fun trackedCompositionReplacementRange(): Pair<Int, Int>? {
        return editorView.compositionReplacementRange()
    }

    private fun clearCompositionTracking() {
        editorView.clearCompositionTrackingForEditor()
    }

    private fun consumeInvalidatedCompositionReplacementRange(): Boolean =
        editorView.consumeInvalidatedCompositionReplacementRangeForEditor()

    private fun consumeInvalidatedCompositionReplacementRangeAndRestore(): Boolean {
        if (!consumeInvalidatedCompositionReplacementRange()) return false
        clearCompositionTracking()
        editorView.runWithTransientInputMutationGuard {
            super.finishComposingText()
        }
        editorView.restoreAuthorizedTextIfNeeded()
        return true
    }

    private fun finishStaleComposingUpdateAfterInvalidation(): Boolean {
        clearCompositionTracking()
        val result = editorView.runWithTransientInputMutationGuard {
            super.finishComposingText()
        }
        editorView.restoreAuthorizedTextIfNeeded()
        return result
    }

    private fun isCurrentInputSession(): Boolean =
        editorView.isInputConnectionCurrentForEditor(boundEditorId, boundGeneration)

    private fun refreshComposingTextFromEditable() {
        val editable = editorView.text ?: return
        val visibleReplacementText = editorView.composingTextFromVisibleReplacementForEditor()
        if (visibleReplacementText != null) {
            editorView.setComposingTextForEditor(visibleReplacementText)
            return
        }
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        if (start < 0 || end < 0 || start > end || end > editable.length) {
            editorView.setComposingTextForEditor(null)
            return
        }
        editorView.setComposingTextForEditor(editable.subSequence(start, end).toString())
    }

    private fun deleteTransientTextAroundSelection(beforeLength: Int, afterLength: Int): Boolean {
        val editable = editorView.text ?: return false
        val rawStart = editorView.selectionStart
        val rawEnd = editorView.selectionEnd
        if (rawStart < 0 || rawEnd < 0) return false
        val selectionStart = rawStart.coerceIn(0, editable.length)
        val selectionEnd = rawEnd.coerceIn(0, editable.length)
        val normalizedStart = minOf(selectionStart, selectionEnd)
        val normalizedEnd = maxOf(selectionStart, selectionEnd)
        val deleteStart: Int
        val deleteEnd: Int
        if (normalizedStart != normalizedEnd) {
            deleteStart = normalizedStart
            deleteEnd = normalizedEnd
        } else {
            deleteStart = maxOf(0, normalizedStart - beforeLength.coerceAtLeast(0))
            deleteEnd = minOf(editable.length, normalizedEnd + afterLength.coerceAtLeast(0))
        }
        if (deleteStart >= deleteEnd) return false
        val (snappedStart, snappedEnd) = PositionBridge.snapRangeToScalarBoundaries(
            deleteStart,
            deleteEnd,
            editable.toString()
        )
        if (snappedStart >= snappedEnd) return false
        editable.delete(snappedStart, snappedEnd)
        Selection.setSelection(editable, snappedStart.coerceIn(0, editable.length))
        return true
    }

    private fun deleteTransientTextAroundSelectionInCodePoints(
        beforeLength: Int,
        afterLength: Int
    ): Boolean {
        val currentText = editorView.text?.toString() ?: return false
        val rawStart = editorView.selectionStart
        val rawEnd = editorView.selectionEnd
        if (rawStart < 0 || rawEnd < 0) return false
        val selectionStart = rawStart.coerceIn(0, currentText.length)
        val selectionEnd = rawEnd.coerceIn(0, currentText.length)
        val normalizedStart = minOf(selectionStart, selectionEnd)
        val normalizedEnd = maxOf(selectionStart, selectionEnd)
        if (normalizedStart != normalizedEnd) {
            return deleteTransientTextAroundSelection(0, 0)
        }
        val beforeUtf16Length = codePointsToUtf16Length(
            text = currentText,
            fromUtf16Offset = normalizedStart,
            codePointCount = beforeLength,
            forward = false
        )
        val afterUtf16Length = codePointsToUtf16Length(
            text = currentText,
            fromUtf16Offset = normalizedEnd,
            codePointCount = afterLength,
            forward = true
        )
        return deleteTransientTextAroundSelection(beforeUtf16Length, afterUtf16Length)
    }

    private fun currentComposingSpanText(): String? {
        val editable = editorView.text ?: return null
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        if (start < 0 || end < 0 || start > end || end > editable.length) {
            return null
        }
        return editable.subSequence(start, end).toString()
    }

    private fun currentComposingSpanRange(): Pair<Int, Int>? {
        if (!editorView.isCurrentTextAuthorizedForEditor()) return null
        val editable = editorView.text ?: return null
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        if (start < 0 || end < 0 || start > end || end > editable.length) {
            return null
        }
        return editorView.authorizedUtf16Range(start, end)
    }

    /**
     * Called for hardware keyboard key events.
     *
     * Intercepts DEL (backspace) and ENTER to route through Rust. Other key
     * events are passed through to the base connection.
     */
    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (!isCurrentInputSession()) return true
        if (
            event?.action == KeyEvent.ACTION_UP &&
            editorView.hasInvalidatedCompositionReplacementRangeForEditor()
        ) {
            return finishStaleComposingUpdateAfterInvalidation()
        }
        if (
            shouldConsumeInvalidatedCompositionForKeyEvent(event) &&
            consumeInvalidatedCompositionReplacementRangeAndRestore()
        ) {
            return true
        }
        if (!editorView.isEditable && event?.let { editorView.isReadOnlyTextMutationKeyEvent(it) } == true) {
            return true
        }
        if (event != null && editorView.handleCompositionKeyEvent(event) {
                super.sendKeyEvent(event)
            }) {
            return true
        }
        if (event != null && editorView.handleHardwareKeyEvent(event)) {
            return true
        }
        if (event != null && editorView.handlePrintableHardwareKeyEvent(event) {
                super.sendKeyEvent(event)
            }) {
            return true
        }
        return super.sendKeyEvent(event)
    }

    private fun shouldConsumeInvalidatedCompositionForKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || event.action == KeyEvent.ACTION_UP) return false
        return editorView.isReadOnlyTextMutationKeyEvent(event)
    }

    private fun isNoOpSurroundingDelete(beforeLength: Int, afterLength: Int): Boolean =
        beforeLength <= 0 && afterLength <= 0
}
