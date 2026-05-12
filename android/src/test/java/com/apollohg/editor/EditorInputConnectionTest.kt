package com.apollohg.editor

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorInputConnectionTest {
    @Test
    fun `editor input traits use rich text defaults`() {
        val editText = EditorEditText(RuntimeEnvironment.getApplication())

        assertEquals(InputType.TYPE_CLASS_TEXT, editText.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(editText.inputType hasInputFlag InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertTrue(editText.inputType hasInputFlag InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertTrue(editText.inputType hasInputFlag InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    }

    @Test
    fun `editor input traits apply React keyboard props`() {
        val editText = EditorEditText(RuntimeEnvironment.getApplication())

        editText.setKeyboardType("email-address")
        editText.setAutoCapitalize("none")
        editText.setAutoCorrect(false)

        assertEquals(InputType.TYPE_CLASS_TEXT, editText.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(editText.inputType hasInputFlag InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertTrue(editText.inputType hasInputFlag InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertTrue(editText.inputType hasInputFlag InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertFalse(editText.inputType hasInputFlag InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertFalse(editText.inputType hasInputFlag InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    }

    @Test
    fun `editor numeric keyboard type maps to numeric input class`() {
        val editText = EditorEditText(RuntimeEnvironment.getApplication())

        editText.setKeyboardType("numeric")

        assertEquals(InputType.TYPE_CLASS_NUMBER, editText.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(editText.inputType hasInputFlag InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertTrue(editText.inputType hasInputFlag InputType.TYPE_NUMBER_FLAG_SIGNED)
    }

    @Test
    fun `code point delete length matches ascii backspace`() {
        val text = "Hello"
        val cursor = 5

        val beforeUtf16Length = EditorInputConnection.codePointsToUtf16Length(
            text = text,
            fromUtf16Offset = cursor,
            codePointCount = 1,
            forward = false
        )

        assertEquals(1, beforeUtf16Length)
    }

    @Test
    fun `code point delete length counts surrogate pair as two utf16 code units`() {
        val text = "A😀B"
        val cursor = 3

        val beforeUtf16Length = EditorInputConnection.codePointsToUtf16Length(
            text = text,
            fromUtf16Offset = cursor,
            codePointCount = 1,
            forward = false
        )

        assertEquals(2, beforeUtf16Length)
    }

    @Test
    fun `code point forward delete length counts surrogate pair as two utf16 code units`() {
        val text = "A😀B"
        val cursor = 1

        val afterUtf16Length = EditorInputConnection.codePointsToUtf16Length(
            text = text,
            fromUtf16Offset = cursor,
            codePointCount = 1,
            forward = true
        )

        assertEquals(2, afterUtf16Length)
    }

    @Test
    fun `composing text does not trigger reconciliation while edit text is transient`() {
        val editText = EditorEditText(RuntimeEnvironment.getApplication())
        editText.applyUpdateJSON(renderUpdateJson(""), notifyListener = false)
        editText.setSelection(0)
        editText.editorId = 1

        val inputConnection = editText.onCreateInputConnection(EditorInfo())
        assertNotNull(inputConnection)

        val handled = inputConnection!!.setComposingText("abc", 1)

        assertTrue(handled)
        assertEquals("abc", editText.text?.toString())
        assertEquals(0, editText.reconciliationCount)
    }

    @Test
    fun `commit text uses original authorized offset while composing text is visible`() {
        val editText = EditorEditText(RuntimeEnvironment.getApplication())
        editText.applyUpdateJSON(renderUpdateJson("Hello world"), notifyListener = false)
        editText.setSelection(6)
        editText.editorId = 1

        var insertedText: String? = null
        var insertedScalar: Int? = null
        editText.onInsertTextInRustForTesting = { text, scalar ->
            insertedText = text
            insertedScalar = scalar
        }

        val inputConnection = editText.onCreateInputConnection(EditorInfo())
        assertNotNull(inputConnection)
        inputConnection!!.setComposingText("brave ", 1)
        assertEquals("Hello brave world", editText.text?.toString())

        val handled = inputConnection.commitText("brave ", 1)

        assertTrue(handled)
        assertEquals("brave ", insertedText)
        assertEquals(6, insertedScalar)
    }

    @Test
    fun `commit text replaces original authorized selection while composing text is visible`() {
        val editText = EditorEditText(RuntimeEnvironment.getApplication())
        editText.applyUpdateJSON(renderUpdateJson("Hello world"), notifyListener = false)
        editText.setSelection(6, 11)
        editText.editorId = 1

        var replacement: Triple<Int, Int, String>? = null
        editText.onReplaceTextInRustForTesting = { scalarFrom, scalarTo, text ->
            replacement = Triple(scalarFrom, scalarTo, text)
        }

        val inputConnection = editText.onCreateInputConnection(EditorInfo())
        assertNotNull(inputConnection)
        inputConnection!!.setComposingText("there", 1)
        assertEquals("Hello there", editText.text?.toString())

        val handled = inputConnection.commitText("there", 1)

        assertTrue(handled)
        assertEquals(Triple(6, 11, "there"), replacement)
    }

    @Test
    fun `delete during composition edits transient text without mutating rust`() {
        val editText = EditorEditText(RuntimeEnvironment.getApplication())
        editText.applyUpdateJSON(renderUpdateJson(""), notifyListener = false)
        editText.setSelection(0)
        editText.editorId = 1

        var deleteCalled = false
        editText.onDeleteRangeInRustForTesting = { _, _ ->
            deleteCalled = true
        }
        var insertedText: String? = null
        var insertedScalar: Int? = null
        editText.onInsertTextInRustForTesting = { text, scalar ->
            insertedText = text
            insertedScalar = scalar
        }

        val inputConnection = editText.onCreateInputConnection(EditorInfo())
        assertNotNull(inputConnection)
        inputConnection!!.setComposingText("abc", 1)

        val deleteHandled = inputConnection.deleteSurroundingText(1, 0)
        val commitHandled = inputConnection.commitText("ab", 1)

        assertTrue(deleteHandled)
        assertTrue(commitHandled)
        assertFalse(deleteCalled)
        assertEquals("ab", insertedText)
        assertEquals(0, insertedScalar)
    }

    private fun renderUpdateJson(text: String): String =
        JSONObject()
            .put(
                "renderBlocks",
                JSONArray().put(
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "blockStart")
                                .put("nodeType", "paragraph")
                                .put("depth", 0)
                        )
                        .put(
                            JSONObject()
                                .put("type", "textRun")
                                .put("text", text)
                                .put("marks", JSONArray())
                        )
                        .put(JSONObject().put("type", "blockEnd"))
                )
            )
            .toString()

    private infix fun Int.hasInputFlag(flag: Int): Boolean = (this and flag) == flag
}
