package com.apollohg.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeEditorModuleTest {
    @Test
    fun `native unsigned argument helpers reject negative values`() {
        assertNull(nativeULong(-1))
        assertNull(nativeUInt(-1))
    }

    @Test
    fun `native unsigned argument helpers keep non-negative values`() {
        assertEquals(0UL, nativeULong(0))
        assertEquals(42UL, nativeULong(42))
        assertEquals(0U, nativeUInt(0))
        assertEquals(42U, nativeUInt(42))
    }

    @Test
    fun `native argument error returns bridge parseable json`() {
        assertEquals("{\"error\":\"invalid editor id\"}", nativeArgumentError("editor id"))
        assertEquals("{\"error\":\"invalid position\"}", nativeArgumentError("position"))
    }
}
