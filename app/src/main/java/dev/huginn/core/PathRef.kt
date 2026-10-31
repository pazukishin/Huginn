package dev.huginn.core

import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath

/**
 * Referencia persistible a un elemento (recientes y favoritos de M5): no guarda
 * secretos ni credenciales, solo el origen y la ruta.
 */
data class PathRef(
    val path: FsPath,
    val name: String,
    val isDirectory: Boolean,
    /** Milisegundos; para recortar los recientes más antiguos. */
    val addedAt: Long,
)

/** Codificación estable de un [FsPath] a texto (JVM-testable). */
object PathRefCodec {

    fun encodePath(path: FsPath): String = when (path) {
        is FsPath.Local -> "L:${path.documentUri}"
        is FsPath.Smb -> "S:${path.url}"
        FsPath.Roots -> "R:"
    }

    fun decodePath(text: String): FsPath? = when {
        text.startsWith("L:") -> FsPath.Local(text.removePrefix("L:"))
            .takeIf { it.documentUri.isNotBlank() }
        text.startsWith("S:") -> FsPath.Smb(text.removePrefix("S:"))
            .takeIf { it.url.isNotBlank() }
        text == "R:" -> FsPath.Roots
        else -> null
    }

    fun fromEntry(entry: FsEntry, now: Long): PathRef = PathRef(
        path = entry.path,
        name = entry.name,
        isDirectory = entry.isDirectory,
        addedAt = now,
    )
}
