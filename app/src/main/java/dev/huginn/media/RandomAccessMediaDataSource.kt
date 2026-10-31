package dev.huginn.media

import android.media.MediaDataSource
import dev.huginn.data.RandomAccess

/** Adapta nuestro [RandomAccess] (SAF/SMB) al `MediaDataSource` de Android. */
class RandomAccessMediaDataSource(
    private val access: RandomAccess,
) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= access.length()) return -1
        return access.read(position, buffer, offset, size)
    }

    override fun getSize(): Long = access.length()

    override fun close() {
        runCatching { access.close() }
    }
}
