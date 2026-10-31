package dev.huginn.dev

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Sonda de diagnóstico (solo debug): reproduce el audio del vídeo de prueba con el
 * **MediaPlayer del sistema** (no libVLC) para distinguir si el silencio es del
 * motor multimedia o de la salida del dispositivo.
 *
 * Se lanza con `am start … --ez system_audio_probe true`.
 */
object SystemAudioProbe {

    private const val TAG = "HuginnProbe"
    private const val DURATION_MS = 180_000L

    fun play(context: Context) {
        Thread {
            runCatching {
                val file = File(context.cacheDir, "probe.mp4")
                context.assets.open("sample.mp4").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    setDataSource(file.absolutePath)
                    isLooping = true
                    prepare()
                    start()
                }
                Log.i(TAG, "MediaPlayer del sistema reproduciendo ${file.absolutePath} (${player.duration} ms)")
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        player.stop()
                        player.release()
                        Log.i(TAG, "sonda de audio terminada")
                    }
                }, DURATION_MS)
            }.onFailure { Log.e(TAG, "sonda de audio falló", it) }
        }.start()
    }
}
