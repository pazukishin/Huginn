package dev.huginn.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.huginn.data.RandomAccess
import java.nio.ByteBuffer

/** Metadatos de audio que pinta el panel de la pantalla superior (spec §4.5). */
data class AudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val mimeType: String?,
    /** Carátula incrustada, ya decodificada para el panel de la superior. */
    val artwork: ImageBitmap? = null,
) {
    val hasAny: Boolean
        get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()
}

/**
 * Lectura de metadatos con `MediaMetadataRetriever` sobre URIs locales (`content://`
 * de SAF o `file://`). Para SMB habrá que cachear una copia temporal (M4).
 */
object AudioMetadataReader {

    private const val TAG = "HuginnMeta"

    /**
     * Metadatos + carátula por **acceso aleatorio** (SAF y SMB). Es la vía principal:
     * `MediaMetadataRetriever` acepta un `MediaDataSource` y extrae la portada
     * incrustada sin descargar el fichero completo.
     */
    fun read(access: RandomAccess): AudioMetadata? = runCatching {
        val dataSource = dev.huginn.media.RandomAccessMediaDataSource(access)
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(dataSource)
            AudioMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                artwork = retriever.embeddedPicture?.let(::decodeArtwork),
            ).takeIf { it.hasAny || it.durationMs != null || it.artwork != null }
        }
    }.onFailure { Log.w(TAG, "sin metadatos/carátula: ${it.message}") }
        .getOrNull()

    private fun decodeArtwork(bytes: ByteArray): ImageBitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val sample = (maxOf(info.size.width, info.size.height) / 600).coerceAtLeast(1)
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }.asImageBitmap()
    }.getOrNull()

    fun read(context: Context, uri: Uri): AudioMetadata? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            AudioMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            ).takeIf { it.hasAny || it.durationMs != null }
        }
    }.onFailure { Log.w(TAG, "sin metadatos para $uri: ${it.message}") }
        .getOrNull()
}
