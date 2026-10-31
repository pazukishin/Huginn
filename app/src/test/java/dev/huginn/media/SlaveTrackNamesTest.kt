package dev.huginn.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SlaveTrackNamesTest {

    @Test
    fun `renombra las ultimas pistas genericas con los nombres de los slaves`() {
        val tracks = listOf(
            1 to "Track 1 - [English]",
            18 to "Track 16",
            19 to "Track 17",
        )
        val result = SlaveTrackNames.relabel(tracks, listOf("peli.srt", "peli.es.srt"))
        assertEquals(
            listOf(
                1 to "Track 1 - [English]",
                18 to "peli.srt",
                19 to "peli.es.srt",
            ),
            result,
        )
    }

    @Test
    fun `sin slaves deja la lista como esta`() {
        val tracks = listOf(1 to "Track 1", 2 to "Track 2")
        assertEquals(tracks, SlaveTrackNames.relabel(tracks, emptyList()))
    }

    @Test
    fun `si no hay suficientes pistas genericas no etiqueta mal las incrustadas`() {
        val tracks = listOf(
            1 to "Track 1 - [English]",
            2 to "Spanish Subtitles - [Spanish]",
        )
        assertEquals(tracks, SlaveTrackNames.relabel(tracks, listOf("peli.srt")))
    }

    @Test
    fun `solo sustituye nombres genericos, no los que ya tienen titulo`() {
        val tracks = listOf(
            1 to "Track 1",
            2 to "English Subtitles (THORA) - [English]",
            3 to "Track 3",
        )
        val result = SlaveTrackNames.relabel(tracks, listOf("a.srt", "b.srt"))
        assertEquals(
            listOf(
                1 to "a.srt",
                2 to "English Subtitles (THORA) - [English]",
                3 to "b.srt",
            ),
            result,
        )
    }
}
