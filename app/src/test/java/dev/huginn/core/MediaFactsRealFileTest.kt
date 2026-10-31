package dev.huginn.core

import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFactsRealFileTest {

    /** Los ficheros v1 (separador 0x1F, se perdía en Android) se descartan. */
    @Test
    fun `un fichero v1 se ignora y se reconstruye`() {
        val v1 = javaClass.classLoader!!.getResourceAsStream("facts.properties")!!
            .bufferedReader().readText()
        assertTrue(MediaFactsCodec.decode(v1).isEmpty())
    }

    @Test
    fun `formato v2 sobrevive a tags con separador y unicode`() {
        val facts = MediaFacts(durationMs = 1000, title = "a|b\u001fñ", mimeType = "video/x-matroska")
        val text = MediaFactsCodec.encode(mapOf(MediaFactsCodec.hash("k") to facts))
        val decoded = MediaFactsCodec.decode(text).values.single()
        assertTrue(decoded.title == facts.title)
    }
}
