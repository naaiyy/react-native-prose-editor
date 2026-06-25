package com.openeditor.editor

import android.graphics.Paint
import android.text.Layout

/**
 * Vertical geometry for the text caret, clipped to the rendered glyph height.
 *
 * Android's [android.widget.Editor] draws the native caret from
 * `Layout.getLineTop(line)` to `Layout.getLineBottom(line)`. When a
 * [ParagraphSpacerSpan] inflates a line's descent to create inter-block
 * spacing, `getLineBottom` includes that gap and the caret stretches into it.
 * `getLineBottomWithoutSpacing` cannot help: the inflation lives in the line's
 * DESCENT column, not the line-spacing EXTRA column it subtracts.
 *
 * The baseline is provably independent of descent inflation
 * (`getLineBaseline(line) == getLineTop(line) - ascent`), so anchoring the
 * caret bottom at `baseline + raw font descent` clips it to the glyph height.
 * This mirrors the trim already used for blockquote stripes
 * ([BlockquoteSpan.resolvedStripeBottom]).
 */
object CaretGeometry {
    data class VerticalBounds(val top: Float, val bottom: Float)

    /**
     * Whether the manually-drawn caret should be visible. The native caret is
     * suppressed, so this gates our replacement: only when the field is focused,
     * its window is focused, and the selection is a collapsed insertion point
     * (a range selection shows the selection highlight instead).
     */
    fun shouldRender(
        focused: Boolean,
        windowFocused: Boolean,
        selectionStart: Int,
        selectionEnd: Int
    ): Boolean = focused &&
        windowFocused &&
        selectionStart >= 0 &&
        selectionStart == selectionEnd

    fun verticalBounds(layout: Layout, offset: Int, paint: Paint): VerticalBounds {
        val line = layout.getLineForOffset(offset.coerceIn(0, layout.text.length))
        val top = layout.getLineTop(line).toFloat()
        // Anchor the bottom at the glyph descent below the baseline. The baseline
        // is independent of any ReplacementSpan descent inflation, so this clips
        // the caret to the rendered text height instead of the inflated line bottom.
        val bottom = layout.getLineBaseline(line) + paint.fontMetrics.descent
        return VerticalBounds(top, bottom)
    }
}
