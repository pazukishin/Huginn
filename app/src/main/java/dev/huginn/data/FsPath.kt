package dev.huginn.data

import android.net.Uri

/**
 * Ruta de un elemento dentro de un origen de ficheros.
 *
 * M0 soporta dos orígenes (SAF local y SMB2/3). Se modela como sealed para que
 * `FileSource` y el motor multimedia no dependan de strings mágicos, y para que
 * añadir orígenes en v1.1 no obligue a tocar los consumidores.
 */
sealed interface FsPath {

    /** Raíz ficticia: lista de orígenes disponibles (local, red local...). */
    data object Roots : FsPath

    /** Documento o árbol SAF. `documentUri` procede de DocumentsContract. */
    data class Local(val documentUri: String) : FsPath {
        fun toUri(): Uri = Uri.parse(documentUri)
    }

    /** URL SMB normalizada, **sin credenciales**. */
    data class Smb(val url: String) : FsPath
}
