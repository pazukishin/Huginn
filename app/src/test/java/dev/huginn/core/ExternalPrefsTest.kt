package dev.huginn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPrefsTest {

    @Test
    fun `roundtrip de preferencias por tipo`() {
        val prefs = mapOf(
            "application/pdf" to "com.android.chrome",
            "application/zip" to "pl.solidexplorer2",
        )
        assertEquals(prefs, ExternalPrefs.decode(ExternalPrefs.encode(prefs)))
    }

    @Test
    fun `texto vacio o malformado se ignora`() {
        assertTrue(ExternalPrefs.decode("").isEmpty())
        assertTrue(ExternalPrefs.decode("sin-tab").isEmpty())
        assertTrue(ExternalPrefs.decode("\tcom.foo").isEmpty())
    }

    @Test
    fun `valores con tabuladores o saltos no rompen el formato`() {
        val prefs = mapOf("application/pdf" to "com.app\tcon\ntab")
        val decoded = ExternalPrefs.decode(ExternalPrefs.encode(prefs))
        assertEquals(1, decoded.size)
        assertTrue(decoded["application/pdf"]!!.isNotEmpty())
    }
}
