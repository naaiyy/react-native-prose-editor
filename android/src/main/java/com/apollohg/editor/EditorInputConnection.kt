package com.apollohg.editor

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
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
    baseConnection: InputConnection
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

    /** Tracks the current composing text for CJK/swipe input. */
    private var composingText: String? = null
    private var composingReplacementStartUtf16: Int? = null
    private var composingReplacementEndUtf16: Int? = null

    /**
     * Called when the IME commits finalized text (single character, word,
     * autocomplete selection, etc.).
     *
     * Routes the text through Rust instead of directly inserting into the EditText.
     */
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!editorView.isEditable) return false
        if (editorView.isApplyingRustState) {
            return super.commitText(text, newCursorPosition)
        }
        if (editorView.editorId == 0L) {
            return super.commitText(text, newCursorPosition)
        }

        val committedText = text?.toString()
        val replacementRange = trackedCompositionReplacementRange()
        if (replacementRange != null && committedText != null) {
            clearCompositionTracking()
            editorView.runWithTransientInputMutationGuard {
                super.finishComposingText()
            }
            editorView.handleCompositionCommit(
                committedText,
                replacementRange.first,
                replacementRange.second
            )
        } else {
            clearCompositionTracking()
            committedText?.let { editorView.handleTextCommit(it) }
        }
        return true
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
        if (!editorView.isEditable) return false
        if (editorView.isApplyingRustState) {
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        if (trackedCompositionReplacementRange() != null) {
            val result = editorView.runWithTransientInputMutationGuard {
                super.deleteSurroundingText(beforeLength, afterLength)
            }
            refreshComposingTextFromEditable()
            return result
        }
        editorView.handleDelete(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (!editorView.isEditable) return false
        if (editorView.isApplyingRustState) {
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }
        if (trackedCompositionReplacementRange() != null) {
            val result = editorView.runWithTransientInputMutationGuard {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
            refreshComposingTextFromEditable()
            return result
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
        if (!editorView.isEditable) return super.setComposingText(text, newCursorPosition)
        if (editorView.editorId == 0L) return super.setComposingText(text, newCursorPosition)
        captureCompositionReplacementRangeIfNeeded()
        composingText = text?.toString()
        return editorView.runWithTransientInputMutationGuard {
            super.setComposingText(text, newCursorPosition)
        }
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (!editorView.isEditable) return super.setComposingRegion(start, end)
        if (editorView.editorId == 0L) return super.setComposingRegion(start, end)
        val authorizedLength = editorView.text?.length ?: 0
        composingReplacementStartUtf16 = minOf(start, end).coerceIn(0, authorizedLength)
        composingReplacementEndUtf16 = maxOf(start, end).coerceIn(0, authorizedLength)
        return editorView.runWithTransientInputMutationGuard {
            super.setComposingRegion(start, end)
        }
    }

    /**
     * Called when IME composition is finalized (user selects a candidate or
     * presses space/enter to commit the composing text).
     *
     * At this point, the composed text is final. We notify the [EditorEditText]
     * so it can capture the result and send it to Rust.
     */
    override fun finishComposingText(): Boolean {
        if (!editorView.isEditable) return super.finishComposingText()
        if (editorView.editorId == 0L) return super.finishComposingText()
        val composed = composingText
        val replacementRange = trackedCompositionReplacementRange()
        clearCompositionTracking()

        // Prevent selection sync while the base connection commits the composed
        // text, since the Rust document doesn't have it yet.
        val result = editorView.runWithTransientInputMutationGuard {
            super.finishComposingText()
        }

        // Now route the composed text through Rust.
        if (replacementRange != null && !composed.isNullOrEmpty()) {
            editorView.handleCompositionCommit(
                composed,
                replacementRange.first,
                replacementRange.second
            )
        }
        return result
    }

    private fun captureCompositionReplacementRangeIfNeeded() {
        if (trackedCompositionReplacementRange() != null) return
        val start = editorView.selectionStart.coerceAtLeast(0)
        val end = editorView.selectionEnd.coerceAtLeast(0)
        val authorizedLength = editorView.text?.length ?: 0
        composingReplacementStartUtf16 = minOf(start, end).coerceIn(0, authorizedLength)
        composingReplacementEndUtf16 = maxOf(start, end).coerceIn(0, authorizedLength)
    }

    private fun trackedCompositionReplacementRange(): Pair<Int, Int>? {
        val start = composingReplacementStartUtf16 ?: return null
        val end = composingReplacementEndUtf16 ?: return null
        return start to end
    }

    private fun clearCompositionTracking() {
        composingText = null
        composingReplacementStartUtf16 = null
        composingReplacementEndUtf16 = null
    }

    private fun refreshComposingTextFromEditable() {
        val editable = editorView.text ?: return
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        if (start < 0 || end < 0 || start > end || end > editable.length) {
            composingText = null
            return
        }
        composingText = editable.subSequence(start, end).toString()
    }

    /**
     * Called for hardware keyboard key events.
     *
     * Intercepts DEL (backspace) and ENTER to route through Rust. Other key
     * events are passed through to the base connection.
     */
    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (!editorView.isEditable) return false
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            if (editorView.handleHardwareKeyDown(event.keyCode, event.isShiftPressed)) {
                return true
            }
        }
        if (event != null && event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_TAB -> return true
            }
        }
        return super.sendKeyEvent(event)
    }
}
