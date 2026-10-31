package dev.huginn.media

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * Motor multimedia sobre libVLC 3.x (AAR de Maven, sin NDK propio).
 *
 * Decisiones de M0:
 * - una sola instancia de `LibVLC`/`MediaPlayer` app-scope (el plan fija una única
 *   `PlaybackController`; el foco de entrada lo decide `InputRouter`);
 * - el vídeo se engancha a la `SurfaceView` del visor de la pantalla superior;
 * - audio focus / MediaSession se posponen a M2.
 */
class VlcEngine(private val context: Context) : MediaEngine {

    private val libVlc: LibVLC = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--network-caching=2000",
            "--audio-time-stretch",
            "--avcodec-hw=any",
            // En la Thor el módulo android_audiotrack abre el AudioTrack pero este
            // queda mudo (DynamicsProcessing falla y el track se cierra); OpenSL ES
            // sí reproduce. Verificado en hardware: sin sonido con audiotrack,
            // con sonido con opensles.
            "--aout=opensles",
        ),
    )

    private val player = MediaPlayer(libVlc)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private var attachedView: SurfaceView? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    @Volatile
    private var currentName: String = ""

    /** Nombres de fichero de los subtítulos externos añadidos al medio actual. */
    private var subtitleSlaveNames: List<String> = emptyList()

    init {
        // Volumen explícito: en algunas versiones/equipos libVLC arranca a 0 y el
        // AudioTrack queda mudo aunque el sistema esté al máximo.
        player.volume = 100
        player.setEventListener(MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    _state.update { it.copy(playing = true, error = null) }
                    emit(PlaybackEvent.Playing(event.timeChanged))
                }

                MediaPlayer.Event.Paused -> {
                    _state.update { it.copy(playing = false) }
                    emit(PlaybackEvent.Paused(event.timeChanged))
                }

                MediaPlayer.Event.EndReached -> {
                    _state.update { it.copy(playing = false, positionMs = it.lengthMs) }
                    emit(PlaybackEvent.EndReached(event.timeChanged))
                }

                MediaPlayer.Event.EncounteredError -> {
                    _state.update { it.copy(playing = false, error = "No se pudo reproducir el archivo") }
                    emit(PlaybackEvent.Error("No se pudo reproducir el archivo"))
                }

                MediaPlayer.Event.TimeChanged ->
                    _state.update { it.copy(positionMs = event.timeChanged) }

                MediaPlayer.Event.LengthChanged ->
                    if (event.lengthChanged > 0) {
                        _state.update { it.copy(lengthMs = event.lengthChanged) }
                        emit(PlaybackEvent.Progress(event.timeChanged, event.lengthChanged))
                    }

                MediaPlayer.Event.Buffering ->
                    _state.update { it.copy(buffering = event.buffering / 100f) }

                else -> Unit
            }
        })
    }

    override fun attach(videoView: SurfaceView?) {
        if (attachedView === videoView) return
        detachCurrentView()
        attachedView = videoView ?: return
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                attachViews(videoView)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0 && player.vlcVout.areViewsAttached()) {
                    player.vlcVout.setWindowSize(width, height)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (player.vlcVout.areViewsAttached()) player.vlcVout.detachViews()
            }
        }
        surfaceCallback = callback
        videoView.holder.addCallback(callback)
        if (videoView.holder.surface?.isValid == true) attachViews(videoView)
    }

    private fun attachViews(view: SurfaceView) {
        val vout = player.vlcVout
        if (vout.areViewsAttached()) return
        vout.setVideoView(view)
        if (view.width > 0 && view.height > 0) vout.setWindowSize(view.width, view.height)
        vout.attachViews()
        Log.i(TAG, "superficie de vídeo enganchada (${view.width}x${view.height})")
    }

    private fun detachCurrentView() {
        surfaceCallback?.let { attachedView?.holder?.removeCallback(it) }
        surfaceCallback = null
        if (player.vlcVout.areViewsAttached()) player.vlcVout.detachViews()
        attachedView = null
    }

    override fun play(uri: Uri, options: List<String>, subtitleSlaves: List<String>) {
        try {
            // Cambiar el medio con setMedia (sin parar antes): parar el reproductor
            // dejaba el aout mudo en la Thor para la siguiente canción.
            subtitleSlaveNames = subtitleSlaves.map { url ->
                Uri.decode(url.substringAfterLast('/'))
            }
            Log.i(TAG, "slaves de subtítulos: $subtitleSlaveNames")
            val media = Media(libVlc, uri).apply {
                setHWDecoderEnabled(true, false)
                options.forEach(::addOption)
                subtitleSlaves.forEach { url ->
                    // libvlc_media_slave_type_subtitle = 0 (subtítulos externos)
                    addSlave(IMedia.Slave(SLAVE_TYPE_SUBTITLE, SLAVE_PRIORITY, url))
                }
            }
            player.media = media
            media.release()
            player.play()
            currentName = uri.lastPathSegment.orEmpty()
            _state.update { PlaybackState(playing = false, error = null) }
            emit(PlaybackEvent.Started(currentName))
            Log.i(TAG, "reproduciendo $uri")
        } catch (t: Throwable) {
            Log.e(TAG, "fallo al preparar la reproducción", t)
            _state.update { it.copy(error = t.message ?: context.getString(dev.huginn.R.string.playback_error)) }
            emit(PlaybackEvent.Error(t.message ?: context.getString(dev.huginn.R.string.playback_error)))
        }
    }

    override fun playPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun pause() {
        if (player.isPlaying) player.pause()
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, if (player.length > 0) player.length else Long.MAX_VALUE)
        player.time = target
        _state.update { it.copy(positionMs = target) }
    }

    override fun seekBy(deltaMs: Long) = seekTo(player.time + deltaMs)

    override fun tracks(): Tracks {
        fun toTracks(
            list: Array<MediaPlayer.TrackDescription>?,
            kind: String,
        ): List<MediaTrack> =
            list?.mapNotNull { description ->
                val name = description.name.orEmpty()
                if (description.id < 0 || name.equals("Disable", ignoreCase = true)) {
                    return@mapNotNull null
                }
                MediaTrack(description.id, localizeTrackName(name, description.id, kind))
            } ?: emptyList()

        val audioTracks = player.audioTracks
        val spuTracks = player.spuTracks
        // libVLC añade los .srt externos al final con nombre genérico («Track 16»);
        // se renombran con el nombre real del fichero para el menú de pistas.
        val subtitles = SlaveTrackNames.relabel(
            spuTracks?.map { it.id to it.name.orEmpty() }.orEmpty(),
            subtitleSlaveNames,
        )
            .filter { (id, name) -> id >= 0 && !name.equals("Disable", ignoreCase = true) }
            .map { (id, name) ->
                MediaTrack(id, localizeTrackName(name, id, context.getString(dev.huginn.R.string.track_label_subtitle)))
            }
        Log.i(
            TAG,
            "tracks audio=${player.audioTrack} " +
                audioTracks?.joinToString { "${it.id}:${it.name}" } +
                " · spu=${player.spuTrack} " +
                subtitles.joinToString { "${it.id}:${it.name}" },
        )
        return Tracks(
            audio = toTracks(audioTracks, kind = "Pista"),
            subtitles = subtitles,
            audioTrackId = player.audioTrack,
            subtitleTrackId = player.spuTrack,
        )
    }

    /** libVLC da nombres en inglés («Track 1», «Disable»); se muestran en español. */
    private fun localizeTrackName(name: String, id: Int, kind: String): String = when {
        name.isBlank() || name.equals("Disable", ignoreCase = true) ->
            context.getString(dev.huginn.R.string.track_disabled)
        name.startsWith("Track ") -> "$kind ${name.removePrefix("Track ")}"
        else -> name
    }

    override fun selectAudioTrack(id: Int) {
        player.audioTrack = id
        Log.i(TAG, "pista de audio -> $id")
    }

    override fun selectSubtitleTrack(id: Int) {
        player.spuTrack = id
        Log.i(TAG, "pista de subtítulos -> $id")
    }

    override fun stop() {
        // En la Thor, `MediaPlayer.stop()` deja el aout inservible para la siguiente
        // reproducción (no vuelve a sonar hasta reiniciar la app). Se pausa: el
        // audio se detiene igual y el siguiente `setMedia`+`play` sí funciona.
        runCatching { player.pause() }
        _state.update { PlaybackState() }
    }

    override fun release() {
        detachCurrentView()
        runCatching { player.stop() }
        runCatching { player.release() }
        runCatching { libVlc.release() }
    }

    private fun emit(event: PlaybackEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        const val TAG = "HuginnVlc"

        /** `libvlc_media_slave_type_subtitle`. */
        const val SLAVE_TYPE_SUBTITLE = 0
        const val SLAVE_PRIORITY = 4
    }
}
