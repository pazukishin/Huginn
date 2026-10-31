package dev.huginn.core

import dev.huginn.data.FsEntry
import java.security.MessageDigest

/**
 * Nombres de fichero seguros para la caché de archivos externos (M5). Puro y
 * testeable: sin rutas, sin caracteres problemáticos y con la extensión original.
 */
object ExternalNames {

    private val UNSAFE = Regex("[^A-Za-z0-9._ -]")

    /** Sustituye caracteres raros por `_` y limpia `..`/barras para evitar traversal. */
    fun sanitize(name: String): String {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val cleanBase = base.replace(UNSAFE, "_").trim('.', ' ', '_').ifBlank { "archivo" }
        val cleanExt = ext.replace(UNSAFE, "").take(12)
        val suffix = if (cleanExt.isNotEmpty()) ".$cleanExt" else ""
        val maxBase = (MAX_NAME - suffix.length).coerceAtLeast(16)
        return cleanBase.take(maxBase) + suffix
    }

    /** Identificador estable por ruta+tamaño+fecha, para no pisar ficheros distintos. */
    fun cacheFileName(entry: FsEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${entry.path}|${entry.size ?: -1}|${entry.lastModified ?: -1}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "${digest}_${sanitize(entry.name)}"
    }

    private const val MAX_NAME = 120
}
