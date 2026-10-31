package dev.huginn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypesTest {

    @Test
    fun `clasifica por extension cuando el origen no da mime`() {
        assertEquals("video/x-matroska", MimeTypes.guess("Pelicula.MKV"))
        assertEquals("audio/flac", MimeTypes.guess("tema.flac"))
        assertEquals("image/jpeg", MimeTypes.guess("foto.JPG"))
        assertNull(MimeTypes.guess("sin_extension"))
    }

    @Test
    fun `media kind para carpetas y ficheros`() {
        assertEquals(MediaKind.DIRECTORY, MediaKind.from(null, "Descargas", isDirectory = true))
        assertEquals(MediaKind.IMAGE, MediaKind.from("image/png", "x", isDirectory = false))
        assertEquals(MediaKind.AUDIO, MediaKind.from(null, "tema.ogg", isDirectory = false))
        assertEquals(MediaKind.VIDEO, MediaKind.from(null, "clip.mkv", isDirectory = false))
        assertEquals(MediaKind.OTHER, MediaKind.from(null, "informe.pdf", isDirectory = false))
        assertEquals(MediaKind.OTHER, MediaKind.from(null, "raro.xyz", isDirectory = false))
    }

    @Test
    fun `subtitulos reconocidos`() {
        assertTrue(MimeTypes.isSubtitle("pelicula.srt"))
        assertTrue(MimeTypes.isSubtitle("Pelicula.ASS"))
        assertTrue(!MimeTypes.isSubtitle("pelicula.mkv"))
    }
}
