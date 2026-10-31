package dev.huginn.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.huginn.data.FileSource
import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import dev.huginn.data.RandomAccess
import dev.huginn.data.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Origen local vía **SAF** (ADR 4): sin permisos de almacenamiento, con
 * `OPEN_DOCUMENT_TREE` y permiso persistente concedido por la activity.
 *
 * Solo lectura: la v1 no crea, borra, renombra ni mueve nada.
 */
class LocalSource(private val context: Context) : FileSource {

    override suspend fun list(path: FsPath): List<FsEntry> = withContext(Dispatchers.IO) {
        require(path is FsPath.Local) { "LocalSource espera FsPath.Local, llegó $path" }
        val directory = documentFor(path.toUri())
            ?: return@withContext emptyList()

        val children = runCatching { directory.listFiles() }
            .onFailure { Log.w(TAG, "no se pudo listar ${path.documentUri}", it) }
            .getOrDefault(emptyArray())

        children.mapNotNull { child ->
            val uri = child.uri
            val name = child.name ?: uri.lastPathSegment ?: return@mapNotNull null
            val isDirectory = child.isDirectory
            FsEntry(
                path = FsPath.Local(uri.toString()),
                name = name,
                isDirectory = isDirectory,
                size = if (isDirectory) null else child.length().takeIf { it >= 0 },
                lastModified = child.lastModified().takeIf { it > 0 },
                mimeType = child.type ?: MimeTypes.guess(name),
            )
        }
            .sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: FsPath): FsEntry? = withContext(Dispatchers.IO) {
        require(path is FsPath.Local)
        val document = documentFor(path.toUri()) ?: return@withContext null
        val name = document.name ?: return@withContext null
        FsEntry(
            path = path,
            name = name,
            isDirectory = document.isDirectory,
            size = if (document.isDirectory) null else document.length().takeIf { it >= 0 },
            lastModified = document.lastModified().takeIf { it > 0 },
            mimeType = document.type ?: MimeTypes.guess(name),
        )
    }

    override suspend fun openStream(path: FsPath): java.io.InputStream? = withContext(Dispatchers.IO) {
        require(path is FsPath.Local)
        runCatching { context.contentResolver.openInputStream(path.toUri()) }.getOrNull()
    }

    override suspend fun openRandomAccess(path: FsPath): RandomAccess? = withContext(Dispatchers.IO) {
        require(path is FsPath.Local)
        runCatching {
            val descriptor = context.contentResolver.openAssetFileDescriptor(path.toUri(), "r")
                ?: return@runCatching null
            val channel = java.io.FileInputStream(descriptor.fileDescriptor).channel
            val length = if (descriptor.length >= 0) descriptor.length else channel.size()
            object : RandomAccess {
                override fun length(): Long = length
                override fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    channel.position(position)
                    return channel.read(java.nio.ByteBuffer.wrap(buffer, offset, size))
                }
                override fun close() {
                    runCatching { channel.close() }
                    runCatching { descriptor.close() }
                }
            }
        }.getOrNull()
    }

    override fun toPlayableUri(path: FsPath): Uri {
        require(path is FsPath.Local)
        return path.toUri()
    }

    override suspend fun toExternalUri(entry: FsEntry): Uri? =
        (entry.path as? FsPath.Local)?.toUri()

    private fun documentFor(uri: Uri): DocumentFile? =
        if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }

    private companion object {
        const val TAG = "HuginnLocal"
    }
}
