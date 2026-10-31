package dev.huginn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFactsTest {

    @Test
    fun `duracion formateada en minutos y en horas`() {
        assertEquals("3:07", MediaFacts(durationMs = 187_000).durationText)
        assertEquals("1:02:03", MediaFacts(durationMs = 3_723_000).durationText)
        assertNull(MediaFacts().durationText)
        assertNull(MediaFacts(durationMs = 0).durationText)
    }

    @Test
    fun `resolucion solo con ancho y alto`() {
        assertEquals("1920×1080", MediaFacts(width = 1920, height = 1080).resolutionText)
        assertNull(MediaFacts(width = 1920).resolutionText)
    }

    @Test
    fun `etiquetas de audio unidas`() {
        assertEquals(
            "Canción · Artista · Álbum",
            MediaFacts(title = "Canción", artist = "Artista", album = "Álbum").tagsText,
        )
        assertNull(MediaFacts(title = "  ").tagsText)
    }

    @Test
    fun `roundtrip del codec con campos vacios y unicode`() {
        val facts = MediaFacts(
            durationMs = 187_000,
            width = 1920,
            height = 1080,
            title = "螺旋 - RASEN",
            artist = "9Lana",
            album = "Álbum de prueba",
            mimeType = "video/x-matroska",
        )
        val encoded = MediaFactsCodec.encode(mapOf(MediaFactsCodec.hash("k1") to facts))
        val decoded = MediaFactsCodec.decode(encoded)
        assertEquals(facts, decoded.values.single())
    }

    @Test
    fun `el codec ignora versiones desconocidas o basura`() {
        assertTrue(MediaFactsCodec.decode("").isEmpty())
        assertTrue(MediaFactsCodec.decode("version=99").isEmpty())
        assertTrue(MediaFactsCodec.decode("no es properties").isEmpty())
    }

    @Test
    fun `isEmpty detecta metadatos vacios`() {
        assertTrue(MediaFacts().isEmpty)
        assertTrue(!MediaFacts(durationMs = 1).isEmpty)
    }
}
