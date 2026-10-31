package dev.huginn.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {

    @Test
    fun `lrc sincronizada con metadatos y fracciones`() {
        val lrc = """
            [ar:Artista]
            [ti:Tema]
            [00:00.00]Primera línea
            [00:12.50]Segunda línea
            [01:02.123]Tercera línea
        """.trimIndent()
        val lyrics = LyricsParser.parse(lrc, synced = true)
        assertTrue(lyrics.synced)
        assertEquals(3, lyrics.lines.size)
        assertEquals(0L, lyrics.lines[0].timestampMs)
        assertEquals(12_500L, lyrics.lines[1].timestampMs)
        assertEquals(62_123L, lyrics.lines[2].timestampMs)
        assertEquals("Primera línea", lyrics.lines[0].text)
    }

    @Test
    fun `linea activa segun posicion y wrap al final`() {
        val lyrics = LyricsParser.parse(
            "[00:00.00]a\n[00:05.00]b\n[00:10.00]c",
            synced = true,
        )
        assertEquals(-1, LyricsParser.activeLineIndex(lyrics.lines, -1))
        assertEquals(0, LyricsParser.activeLineIndex(lyrics.lines, 0))
        assertEquals(0, LyricsParser.activeLineIndex(lyrics.lines, 4_999))
        assertEquals(1, LyricsParser.activeLineIndex(lyrics.lines, 5_000))
        assertEquals(2, LyricsParser.activeLineIndex(lyrics.lines, 60_000))
    }

    @Test
    fun `varias marcas de tiempo en una linea generan varias entradas`() {
        val lrc = "[00:46.78][01:03.49]estribillo\n[00:50.00]solo"
        val lyrics = LyricsParser.parse(lrc, synced = true)
        assertTrue(lyrics.synced)
        assertEquals(3, lyrics.lines.size)
        assertEquals(listOf(46_780L, 50_000L, 63_490L), lyrics.lines.map { it.timestampMs })
        assertEquals("estribillo", lyrics.lines[0].text)
        assertEquals("solo", lyrics.lines[1].text)
        assertEquals("estribillo", lyrics.lines[2].text)
    }

    @Test
    fun `txt estatica no esta sincronizada`() {
        val lyrics = LyricsParser.parse("Línea uno\nLínea dos\n", synced = false)
        assertFalse(lyrics.synced)
        assertEquals(listOf("Línea uno", "Línea dos", ""), lyrics.lines.map { it.text })
        assertEquals(-1, LyricsParser.activeLineIndex(lyrics.lines, 10_000))
    }

    @Test
    fun `detecta utf8 con bom y cp1252`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "canción".toByteArray(Charsets.UTF_8)
        val (textUtf8, encUtf8) = LyricsParser.decode(utf8Bom)
        assertEquals("canción", textUtf8)
        assertEquals("UTF-8", encUtf8)

        // "canción" en Windows-1252: la ó es 0xF3 (bytes inválidos como UTF-8)
        val cp1252 = byteArrayOf(0x63, 0x61, 0x6E, 0x63, 0x69, 0xF3.toByte(), 0x6E)
        val (textCp, encCp) = LyricsParser.decode(cp1252)
        assertEquals("canción", textCp)
        assertEquals("CP1252", encCp)
    }

    @Test
    fun `lrc sin marcas de tiempo queda estatica`() {
        val lyrics = LyricsParser.parse("solo texto\notra línea", synced = true)
        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
    }

    @Test
    fun `lrc vacia`() {
        val lyrics = LyricsParser.parse("", synced = true)
        assertTrue(lyrics.isEmpty)
    }
}
