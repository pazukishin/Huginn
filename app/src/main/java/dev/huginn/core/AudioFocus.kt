package dev.huginn.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Foco de audio para la reproducción (spec §6/§1.4): se pide al empezar a sonar y se
 * suelta al cerrar el visor. Si otra app pide foco, `onLoss` permite pausar.
 */
class AudioFocus(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    private var request: AudioFocusRequest? = null

    fun request(onLoss: () -> Unit = {}): Boolean {
        val manager = audioManager ?: return false
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                Log.i(TAG, "pérdida de foco de audio ($change); pausando")
                onLoss()
            }
        }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(false)
            .build()
        request = focusRequest
        val result = manager.requestAudioFocus(focusRequest)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        val manager = audioManager ?: return
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }

    private companion object {
        const val TAG = "HuginnAudioFocus"
    }
}
