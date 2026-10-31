package dev.huginn.media

import android.net.Uri
import android.view.SurfaceView
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Una pista del contenedor (audio o subtítulos). */
data class MediaTrack(val id: Int, val name: String)

/** Pistas disponibles y las activas en este momento. */
data class Tracks(
    val audio: List<MediaTrack>,
    val subtitles: List<MediaTrack>,
    val audioTrackId: Int,
    val subtitleTrackId: Int,
)

/** Estado observable de reproducción (lo consume la ficha de la pantalla superior). */
data class PlaybackState(
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val lengthMs: Long = 0L,
    val buffering: Float = 0f,
    val error: String? = null,
) {
    val hasMedia: Boolean
        get() = lengthMs > 0 || playing
}

sealed interface PlaybackEvent {
    data class Started(val name: String) : PlaybackEvent
    data class Playing(val positionMs: Long) : PlaybackEvent
    data class Paused(val positionMs: Long) : PlaybackEvent
    data class Progress(val positionMs: Long, val lengthMs: Long) : PlaybackEvent
    data class EndReached(val positionMs: Long) : PlaybackEvent
    data class Error(val message: String) : PlaybackEvent
}

/**
 * Abstracción del motor multimedia (ADR 2 del plan): libVLC en v1 y, si algún día
 * se prioriza el tamaño del APK, Media3 detrás de esta misma interfaz.
 *
 * Nota de M0: `attach` recibe un `SurfaceView` (no un `Surface` como en el plan)
 * porque `IVLCVout` de libVLC trabaja con vistas y así los subtítulos pueden pintarse
 * sobre el vídeo; el contrato conceptual (una superficie de vídeo por display) no cambia.
 */
interface MediaEngine {

    /** Engancha (o desengancha, con `null`) la superficie de vídeo del visor. */
    fun attach(videoView: SurfaceView?)

    /**
     * Reproduce una URI (`content://`, `smb://`, `file://`...).
     * [subtitleSlaves] son subtítulos externos (`.srt`, `.ass`…) que se añaden como
     * esclavos del medio; libVLC los pinta sobre el vídeo.
     */
    fun play(uri: Uri, options: List<String> = emptyList(), subtitleSlaves: List<String> = emptyList())

    fun playPause()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun stop()
    fun release()

    /** Pistas del contenedor actual (audio/subtítulos). */
    fun tracks(): Tracks

    fun selectAudioTrack(id: Int)

    /** id -1 desactiva los subtítulos. */
    fun selectSubtitleTrack(id: Int)

    val state: StateFlow<PlaybackState>
    val events: SharedFlow<PlaybackEvent>
}
