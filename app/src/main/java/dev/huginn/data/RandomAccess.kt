package dev.huginn.data

import java.io.Closeable

/**
 * Acceso aleatorio de solo lectura a un fichero remoto o SAF (contrato del plan §4).
 * Lo usan la extracción de metadatos/carátula de audio y, más adelante, los
 * pósteres de vídeo.
 */
interface RandomAccess : Closeable {
    fun length(): Long

    /**
     * Lee hasta [size] bytes desde [position] en [buffer] a partir de [offset].
     * Devuelve el número de bytes leídos o -1 al final del fichero.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
}
