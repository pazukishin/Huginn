package dev.huginn.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import dev.huginn.data.FileSource
import dev.huginn.data.MediaKind
import dev.huginn.media.AudioMetadataReader
import dev.huginn.media.MediaProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Miniaturas y previsualizaciones de imagen para **cualquier origen**, incluido SMB
 * (Coil no sabe leer `smb://`).
 *
 * M4 parcial: streaming `jcifs → bytes → ImageDecoder` (respeta EXIF) con
 * **caché LRU en memoria** y concurrencia limitada para no saturar la red. Pendiente
 * para M4 completa: caché en disco, cola con prioridad y pósteres de vídeo.
 */
class ThumbnailStore(
    private val appContext: Context,
    private val sourceFor: (FsPath) -> FileSource,
    /** Caché de metadatos (M4): el póster de vídeo y las dimensiones la alimentan. */
    private val factsCache: MediaFactsCache? = null,
    /** Aviso para recomponer la UI cuando llegan metadatos nuevos. */
    private val onFactsUpdated: () -> Unit = {},
) {

    /** LRU en memoria acotada por bytes (las ImageBitmap de 512 px pesan ~1 MB). */
    private val cache = object : LruCache<String, ImageBitmap>(MEMORY_BUDGET_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val permits = Semaphore(MAX_CONCURRENT)

    /** Extraer fotogramas es caro: como mucho dos a la vez (spec: cola 1-2). */
    private val videoPermits = Semaphore(MAX_VIDEO_CONCURRENT)
    private val diskDir: File by lazy { File(appContext.cacheDir, "thumbs").apply { mkdirs() } }

    suspend fun load(entry: FsEntry, targetPx: Int): ImageBitmap? = when (entry.mediaKind) {
        MediaKind.IMAGE -> loadImage(entry, targetPx)
        MediaKind.VIDEO -> loadVideoFrame(entry, targetPx)
        MediaKind.AUDIO -> loadAudioArtwork(entry, targetPx)
        else -> null
    }

    private suspend fun loadImage(entry: FsEntry, targetPx: Int): ImageBitmap? {
        val key = "${entry.path}|img|$targetPx"
        cache.get(key)?.let { return it }
        return permits.withPermit {
            cache.get(key)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                // 1) caché en disco (sobrevive a cambios de carpeta y reinicios)
                diskFile(key)?.let { file ->
                    if (file.isFile) {
                        runCatching { decodeFileSampled(file, targetPx).asImageBitmap() }
                            .getOrNull()?.let { bitmap ->
                                cache.put(key, bitmap)
                                return@withContext bitmap
                            }
                    }
                }
                // 2) origen (SAF/SMB) y guardar en disco
                val bytes = runCatching { readLimited(entry) }.getOrNull() ?: return@withContext null
                val bitmap = runCatching {
                    val decoded = decodeSampled(bytes, targetPx)
                    saveToDisk(key, decoded.bitmap)
                    factsCache?.put(
                        entry,
                        MediaFacts(
                            width = decoded.width,
                            height = decoded.height,
                            mimeType = entry.mimeType,
                        ),
                    )
                    onFactsUpdated()
                    decoded.bitmap.asImageBitmap()
                }.onFailure { Log.w(TAG, "no se pudo decodificar ${entry.name}", it) }.getOrNull()
                    ?: return@withContext null
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    /**
     * Póster de vídeo (M4): primer fotograma significativo vía `MediaMetadataRetriever`
     * sobre acceso aleatorio (SAF/SMB), con la misma caché memoria+disco. Extrae
     * además duración/resolución y las guarda en [factsCache].
     */
    private suspend fun loadVideoFrame(entry: FsEntry, targetPx: Int): ImageBitmap? {
        // Un único póster por vídeo (900 px) compartido por grid y ficha: evita
        // sondear el fichero dos veces por SMB con tamaños distintos.
        val posterPx = POSTER_PX
        val key = "${entry.path}|vid3"
        cache.get(key)?.let { return it }
        return videoPermits.withPermit {
            cache.get(key)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                diskFile(key)?.let { file ->
                    if (file.isFile) {
                        runCatching { decodeFileSampled(file, posterPx).asImageBitmap() }
                            .getOrNull()?.let { bitmap ->
                                cache.put(key, bitmap)
                                return@withContext bitmap
                            }
                    }
                }
                // 1) Metadatos primero (rápido, solo cabecera): chip de duración ya.
                MediaProbe.readFacts(sourceFor(entry.path), entry)?.let { facts ->
                    factsCache?.put(entry, facts)
                    onFactsUpdated()
                }
                // 2) Fotograma del póster (más lento: busca en el fichero).
                val result = MediaProbe.probe(sourceFor(entry.path), entry, posterPx)
                result.facts?.let { facts ->
                    factsCache?.put(entry, facts)
                    onFactsUpdated()
                }
                result.frame?.let { frame ->
                    saveToDisk(key, frame)
                    val image = frame.asImageBitmap()
                    cache.put(key, image)
                    image
                }
            }
        }
    }

    /**
     * Carátula de audio (M4): se extrae con `MediaMetadataRetriever` por acceso
     * aleatorio (SAF/SMB) y se cachea en memoria y disco, para no releerla al volver
     * a la carpeta ni al reiniciar.
     */
    private suspend fun loadAudioArtwork(entry: FsEntry, targetPx: Int): ImageBitmap? {
        val key = "${entry.path}|art"
        cache.get(key)?.let { return it }
        return permits.withPermit {
            cache.get(key)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                diskFile(key)?.let { file ->
                    if (file.isFile) {
                        runCatching { decodeFileSampled(file, targetPx).asImageBitmap() }
                            .getOrNull()?.let { bitmap ->
                                cache.put(key, bitmap)
                                return@withContext bitmap
                            }
                    }
                }
                val access = runCatching { sourceFor(entry.path).openRandomAccess(entry.path) }
                    .getOrNull() ?: return@withContext null
                val artwork = try {
                    AudioMetadataReader.read(access)?.artwork
                } finally {
                    runCatching { access.close() }
                }
                artwork?.let { image ->
                    cache.put(key, image)
                    runCatching { saveToDisk(key, image.asAndroidBitmap()) }
                    image
                }
            }
        }
    }

    /** Primes the artwork cache from metadata already loaded (playback panel). */
    fun cacheArtwork(entry: FsEntry, artwork: ImageBitmap) {
        val key = "${entry.path}|art"
        cache.put(key, artwork)
        runCatching { saveToDisk(key, artwork.asAndroidBitmap()) }
    }

    fun clear() {
        cache.evictAll()
        runCatching { diskDir.deleteRecursively() }
    }

    private suspend fun readLimited(entry: FsEntry): ByteArray? {
        val stream = sourceFor(entry.path).openStream(entry.path) ?: return null
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_FILE_BYTES) {
                    Log.w(TAG, "imagen demasiado grande: ${entry.name}")
                    return null
                }
                output.write(chunk, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun diskFile(key: String): File? {
        val name = key.hashCode().toUInt().toString(16) + "_" + key.length + ".jpg"
        return File(diskDir, name)
    }

    private fun saveToDisk(key: String, bitmap: Bitmap) {
        runCatching {
            val file = diskFile(key) ?: return
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            pruneDiskCache()
        }.onFailure { Log.w(TAG, "no se pudo guardar en disco", it) }
    }

    private fun pruneDiskCache() {
        val files = diskDir.listFiles() ?: return
        if (files.size <= DISK_MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - DISK_MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun decodeFileSampled(file: File, targetPx: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("no se pudo leer la miniatura de disco")
    }

    private data class Decoded(val bitmap: Bitmap, val width: Int, val height: Int)

    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Decoded {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        var originalWidth = 0
        var originalHeight = 0
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            originalWidth = info.size.width
            originalHeight = info.size.height
            val sample = (maxOf(info.size.width, info.size.height) / targetPx.coerceAtLeast(1))
                .coerceAtLeast(1)
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        return Decoded(bitmap, originalWidth, originalHeight)
    }

    private companion object {
        const val TAG = "HuginnThumb"
        const val MEMORY_BUDGET_BYTES = 48 * 1024 * 1024
        const val MAX_CONCURRENT = 6
        const val MAX_VIDEO_CONCURRENT = 2

        /** Resolución única del póster de vídeo (grid y ficha comparten caché). */
        const val POSTER_PX = 900
        const val MAX_FILE_BYTES = 40 * 1024 * 1024
        const val DISK_MAX_FILES = 4000
    }
}
