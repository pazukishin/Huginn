package dev.huginn.core

import dev.huginn.input.InputAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMapCodecTest {

    @Test
    fun `roundtrip de overrides`() {
        val map = mapOf(
            InputAction.ACCEPT to 96,
            InputAction.BACK to 97,
            InputAction.MENU to 108,
        )
        assertEquals(map, KeyMapCodec.decode(KeyMapCodec.encode(map)))
    }

    @Test
    fun `texto vacio o invalido se ignora`() {
        assertTrue(KeyMapCodec.decode("").isEmpty())
        assertTrue(KeyMapCodec.decode("basura").isEmpty())
        assertTrue(KeyMapCodec.decode("NO_EXISTE=1").isEmpty())
        assertTrue(KeyMapCodec.decode("ACCEPT=abc").isEmpty())
    }
}
