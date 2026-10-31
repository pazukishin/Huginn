package dev.huginn.core

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dev.huginn.data.FileSource
import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Preparación de archivos "no multimedia" para abrirlos con apps externas (M5).
 *
 * - SAF ya expone un `content://` con permiso de lectura: se usa tal cual.
 * - SMB **se descarga a caché** (con progreso y cancelable) y se comparte por
 *   `FileProvider`, porque ninguna app externa sabe leer `smb://`.
 * - La caché se poda por número/tamaño y se puede vaciar desde Ajustes.
 */
class ExternalCache(
    private val context: Context,
    private val sourceFor: (FsPath) -> FileSource,
) {

    private val dir: File get() = File(context.cacheDir, DIR).apply { mkdirs() }

    suspend fun prepare(entry: FsEntry, onProgress: (Float) -> Unit = {}): Uri? =
        withContext(Dispatchers.IO) {
            val source = sourceFor(entry.path)
            // SAF: ya vale con el permiso de lectura del intent.
            source.toExternalUri(entry)?.let { return@withContext it }

            val total = entry.size?.takeIf { it > 0 } ?: -1L
            val file = File(dir, ExternalNames.cacheFileName(entry))
            if (file.isFile && total > 0 && file.length() == total) {
                return@withContext providerUri(file)
            }

            val stream = source.openStream(entry.path) ?: return@withContext null
            val started = android.os.SystemClock.elapsedRealtime()
            var downloaded = 0L
            try {
                stream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied > MAX_BYTES) {
                                throw IllegalStateException(
                                    context.getString(
                                        dev.huginn.R.string.external_too_big,
                                        (MAX_BYTES / (1024 * 1024)).toInt(),
                                    ),
                                )
                            }
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                downloaded = file.length()
                Log.i(
                    TAG,
                    "descargado «${entry.name}»: ${downloaded / 1024} KiB en " +
                        "${android.os.SystemClock.elapsedRealtime() - started} ms",
                )
                onProgress(1f)
                prune()
                providerUri(file)
            } catch (t: Throwable) {
                runCatching { file.delete() }
                throw t
            }
        }

    /** URLs de los ficheros ya preparados (para abrir con un selector propio). */
    fun cachedUri(entry: FsEntry): Uri? {
        val file = File(dir, ExternalNames.cacheFileName(entry))
        return if (file.isFile) providerUri(file) else null
    }

    fun clear() {
        runCatching { dir.deleteRecursively() }
        Log.i(TAG, "caché de externos vaciada")
    }

    fun clearAsync() {
        Thread { clear() }.apply { isDaemon = true }.start()
    }

    private fun prune() {
        runCatching {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            var totalBytes = 0L
            files.forEachIndexed { index, file ->
                totalBytes += file.length()
                if (index >= MAX_FILES || totalBytes > MAX_TOTAL_BYTES) runCatching { file.delete() }
            }
        }.onFailure { Log.w(TAG, "no se pudo podar la caché de externos", it) }
    }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private companion object {
        const val TAG = "HuginnExternalCache"
        const val DIR = "external"
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_FILES = 40
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
