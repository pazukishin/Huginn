package dev.huginn.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLoaderTest {

    @Test
    fun `orden de candidatos lrc antes que txt y variantes de idioma`() {
        val candidates = LyricsLoader.candidates(
            "tema.flac",
            listOf("tema.txt", "tema.es.lrc", "tema.lrc", "portada.jpg", "otro.lrc"),
        )
        assertEquals(listOf("tema.lrc", "tema.txt", "tema.es.lrc"), candidates)
    }

    @Test
    fun `sin candidatos devuelve lista vacia`() {
        assertTrue(LyricsLoader.candidates("tema.mp3", listOf("otra.lrc", "foto.png")).isEmpty())
    }

    @Test
    fun `carga la primera letra legible y para si falla`() {
        val readCalls = mutableListOf<String>()
        val data: Map<String, ByteArray?> = mapOf(
            "tema.es.lrc" to "[00:01.00]hola".toByteArray(),
            "tema.lrc" to null, // simula fallo de lectura
        )
        val lyrics = kotlinx.coroutines.runBlocking {
            LyricsLoader.load("tema.flac", listOf("tema.lrc", "tema.es.lrc")) { name ->
                readCalls.add(name)
                data[name]
            }
        }
        assertEquals(listOf("tema.lrc", "tema.es.lrc"), readCalls)
        assertEquals(1, lyrics?.lines?.size)
        assertEquals(1_000L, lyrics?.lines?.first()?.timestampMs)
    }

    @Test
    fun `sin letras devuelve null`() {
        val lyrics = kotlinx.coroutines.runBlocking {
            LyricsLoader.load("tema.mp3", emptyList()) { null }
        }
        assertNull(lyrics)
    }
}

class SubtitleMatcherTest {

    @Test
    fun `detecta subtitulos con el mismo nombre e idioma`() {
        val subtitles = SubtitleMatcher.externalFor(
            "Pelicula.mkv",
            listOf("Pelicula.srt", "Pelicula.es.srt", "Pelicula.forced.ass", "Otra.srt", "Pelicula.mkv", "Pelicula.vtt"),
        )
        // Primero las de base exacta (srt, vtt) y luego las variantes de idioma.
        assertEquals(
            listOf("Pelicula.srt", "Pelicula.vtt", "Pelicula.es.srt", "Pelicula.forced.ass"),
            subtitles,
        )
    }

    @Test
    fun `sin coincidencias`() {
        assertTrue(SubtitleMatcher.externalFor("Pelicula.mkv", listOf("Otra.srt", "Notas.txt")).isEmpty())
    }

    @Test
    fun `no considera subtitulo el propio video`() {
        assertTrue(SubtitleMatcher.externalFor("video.mp4", listOf("video.mp4")).isEmpty())
    }
}
