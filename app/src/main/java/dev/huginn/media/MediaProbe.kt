package dev.huginn.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import dev.huginn.core.MediaFacts
import dev.huginn.data.FileSource
import dev.huginn.data.FsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sonda de metadatos caros (M4) con `MediaMetadataRetriever` sobre acceso aleatorio:
 * SAF y SMB sin descargar el fichero. Alimenta [dev.huginn.core.MediaFactsCache].
 */
object MediaProbe {

    private const val TAG = "HuginnProbe"

    /** Fotograma del póster: por defecto al 10 % de la duración (evita intros negras). */
    private const val FALLBACK_TIME_US = 5_000_000L
    private const val MIN_DARK_CHECK = 12.0

    suspend fun readFacts(source: FileSource, entry: FsEntry): MediaFacts? = withContext(Dispatchers.IO) {
        val access = runCatching { source.openRandomAccess(entry.path) }.getOrNull() ?: return@withContext null
        try {
            RandomAccessMediaDataSource(access).use { dataSource ->
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(dataSource)
                    val started = android.os.SystemClock.elapsedRealtime()
                    val facts = mediaFacts(retriever)
                    Log.i(TAG, "facts ${entry.name} en ${android.os.SystemClock.elapsedRealtime() - started} ms")
                    facts
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sin metadatos para ${entry.name}: ${e.message}")
            null
        } finally {
            runCatching { access.close() }
        }
    }

    /** Resultado de una pasada de sonda: fotograma (si es vídeo) y metadatos. */
    data class ProbeResult(val frame: Bitmap?, val facts: MediaFacts?)

    /**
     * Una sola apertura del medio para obtener **póster y metadatos** (evita abrir el
     * fichero dos veces por SMB).
     */
    suspend fun probe(source: FileSource, entry: FsEntry, targetPx: Int?): ProbeResult =
        withContext(Dispatchers.IO) {
            val access = runCatching { source.openRandomAccess(entry.path) }.getOrNull()
                ?: return@withContext ProbeResult(null, null)
            try {
                RandomAccessMediaDataSource(access).use { dataSource ->
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(dataSource)
                        val started = android.os.SystemClock.elapsedRealtime()
                        val facts = mediaFacts(retriever)
                        val frame = targetPx?.let { pickFrame(retriever, facts?.durationMs, it) }
                        Log.i(
                            TAG,
                            "sonda ${entry.name}: frame=${frame != null} facts=${facts != null} " +
                                "en ${android.os.SystemClock.elapsedRealtime() - started} ms",
                        )
                        ProbeResult(frame, facts)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "sonda fallida para ${entry.name}: ${e.message}")
                ProbeResult(null, null)
            } finally {
                runCatching { access.close() }
            }
        }

    /** Elige un fotograma representativo: 10 % de la duración, y 35 % si sale negro. */
    private fun pickFrame(retriever: MediaMetadataRetriever, durationMs: Long?, targetPx: Int): Bitmap? {
        val durationUs = durationMs?.takeIf { it > 0 }?.times(1000)
        val candidates = buildList {
            if (durationUs != null) {
                add((durationUs / 10).coerceAtLeast(FALLBACK_TIME_US))
                add((durationUs * 35 / 100).coerceAtMost(durationUs - 1))
            } else {
                add(FALLBACK_TIME_US)
                add(1_000_000L)
            }
        }.distinct()
        var fallback: Bitmap? = null
        for (timeUs in candidates) {
            val frame = frameAt(retriever, timeUs, targetPx) ?: continue
            if (fallback == null) fallback = frame
            if (averageLuma(frame) >= MIN_DARK_CHECK) return frame
        }
        return fallback
    }

    /**
     * Fotograma para el póster del vídeo, escalado a [targetPx].
     *
     * Muchas películas empiezan en negro: se busca el fotograma al **10 % de la
     * duración** (y si sale casi negro, se prueba al 35 %) en vez de a 1 s.
     */
    suspend fun readVideoFrame(source: FileSource, entry: FsEntry, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            probe(source, entry, targetPx).frame
        }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long, targetPx: Int): Bitmap? =
        runCatching {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetPx,
                targetPx,
            )
        }.getOrNull()

    /** Brillo medio (0-255) de una muestra pequeña, para descartar fotogramas negros. */
    private fun averageLuma(bitmap: Bitmap): Double {
        val step = (minOf(bitmap.width, bitmap.height) / 16).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                count++
                y += step
            }
            x += step
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** Extrae duración/resolución/tags del retriever ya configurado. */
    fun mediaFacts(retriever: MediaMetadataRetriever): MediaFacts? {
        fun meta(key: Int): String? = runCatching { retriever.extractMetadata(key) }.getOrNull()
        val facts = MediaFacts(
            durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
            width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 },
            height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 },
            title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
            artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() },
            album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
            mimeType = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.isNotBlank() },
        )
        return facts.takeIf { !it.isEmpty }
    }
}
