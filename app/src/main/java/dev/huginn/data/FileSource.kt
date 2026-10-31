package dev.huginn.data

import android.net.Uri
import java.io.InputStream

/** Un elemento listado: carpeta o fichero, con los metadatos que da el origen. */
data class FsEntry(
    val path: FsPath,
    val name: String,
    val isDirectory: Boolean,
    /** Bytes, solo ficheros. Las carpetas no exponen tamaño (decisión de v1). */
    val size: Long? = null,
    /** Milisegundos desde epoch. */
    val lastModified: Long? = null,
    val mimeType: String? = null,
) {
    val mediaKind: MediaKind
        get() = MediaKind.from(mimeType, name, isDirectory)

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

/**
 * Contrato de un origen de ficheros (ADR 4/§4 del plan).
 *
 * **Solo lectura**: no hay create/delete/rename/move. La escritura llegará, si
 * llega, en v2 con una interfaz distinta.
 */
interface FileSource {

    /** Lista el contenido de `path`. Directorios primero, luego por nombre. */
    suspend fun list(path: FsPath): List<FsEntry>

    /** Metadatos de un elemento concreto, si el origen los puede dar. */
    suspend fun stat(path: FsPath): FsEntry?

    /**
     * Abre un flujo de lectura (letras, subtítulos, metadatos). `null` si el origen
     * no lo soporta. Solo lectura: nunca se escribe en el origen.
     */
    suspend fun openStream(path: FsPath): InputStream? = null

    /** Acceso aleatorio de solo lectura (metadatos de audio, pósteres…). */
    suspend fun openRandomAccess(path: FsPath): RandomAccess? = null

    /**
     * URI que puede consumir el motor multimedia tal cual:
     * `content://` para SAF y `smb://usuario:pass@host/share/ruta` para SMB.
     */
    fun toPlayableUri(path: FsPath): Uri

    /**
     * URI para lanzar una app externa (`content://` con permiso de lectura).
     * Devuelve `null` si el origen no puede exponerlo (SMB: requiere descarga,
     * prevista para M5).
     */
    suspend fun toExternalUri(entry: FsEntry): Uri? = null
}
