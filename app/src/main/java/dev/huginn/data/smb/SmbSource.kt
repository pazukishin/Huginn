package dev.huginn.data.smb

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import dev.huginn.core.SmbConfig
import dev.huginn.data.FileSource
import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import dev.huginn.data.MimeTypes
import dev.huginn.data.RandomAccess
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Origen SMB2/3 con **jcifs-ng** (ADR 3): listado y metadatos; la reproducción la
 * hace libVLC con la URL `smb://` (no se descargan los multimedia).
 *
 * El contexto CIFS se cachea mientras no cambie la configuración del servidor.
 * Toda la E/S va en `Dispatchers.IO` y es cancelable desde el ViewModel.
 */
class SmbSource(private val configProvider: () -> SmbConfig?) : FileSource {

    private var context: CIFSContext? = null
    private var contextKey: String? = null

    override suspend fun list(path: FsPath): List<FsEntry> = withContext(Dispatchers.IO) {
        withReconnect {
            val (ctx, config) = cifsContext()
            val parentUrl = when (path) {
                is FsPath.Smb -> path.url.ensureTrailingSlash()
                FsPath.Roots -> config.shareUrl
                is FsPath.Local -> throw IllegalArgumentException("SmbSource no lista rutas locales")
            }
            // listFiles lanza SmbAuthException/SmbException si falla: mejor que
            // exists(), que devuelve false y perderíamos el motivo real.
            val files = SmbFile(parentUrl, ctx).listFiles() ?: emptyArray()
            files.mapNotNull { f ->
                val childName = runCatching { f.name }.getOrNull()?.trimEnd('/') ?: return@mapNotNull null
                if (childName.isEmpty()) return@mapNotNull null
                val isDirectory = runCatching { f.isDirectory }.getOrDefault(false)
                val mime = MimeTypes.guess(childName)
                FsEntry(
                    path = FsPath.Smb(childUrl(parentUrl, childName, isDirectory)),
                    name = childName,
                    isDirectory = isDirectory,
                    size = if (isDirectory) null else runCatching { f.length() }.getOrNull()?.takeIf { it >= 0 },
                    lastModified = runCatching { toMillis(f.lastModified()) }.getOrNull(),
                    mimeType = mime,
                )
            }.sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    override suspend fun stat(path: FsPath): FsEntry? = withContext(Dispatchers.IO) {
        withReconnect {
            val (ctx, _) = cifsContext()
            val url = (path as? FsPath.Smb)?.url ?: return@withReconnect null
            val file = SmbFile(url.ensureTrailingSlash(), ctx)
            if (!file.exists()) return@withReconnect null
            val name = file.name.trimEnd('/')
            val isDirectory = file.isDirectory
            FsEntry(
                path = path,
                name = name,
                isDirectory = isDirectory,
                size = if (isDirectory) null else file.length().takeIf { it >= 0 },
                lastModified = toMillis(file.lastModified()),
                mimeType = MimeTypes.guess(name),
            )
        }
    }

    override suspend fun openRandomAccess(path: FsPath): RandomAccess? = withContext(Dispatchers.IO) {
        withReconnect {
            val (ctx, _) = cifsContext()
            val url = (path as? FsPath.Smb)?.url ?: return@withReconnect null
            val raf = SmbRandomAccessFile(SmbFile(url, ctx), "r")
            val length = raf.length()
            object : RandomAccess {
                override fun length(): Long = length
                override fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    raf.seek(position)
                    return raf.read(buffer, offset, size)
                }

                override fun close() = runCatching { raf.close() }.let { }
            }
        }
    }

    override suspend fun openStream(path: FsPath): java.io.InputStream? = withContext(Dispatchers.IO) {
        withReconnect {
            val (ctx, _) = cifsContext()
            val url = (path as? FsPath.Smb)?.url ?: return@withReconnect null
            SmbFile(url, ctx).getInputStream()
        }
    }

    override fun toPlayableUri(path: FsPath): Uri {
        val url = (path as? FsPath.Smb)?.url ?: error("SmbSource espera FsPath.Smb")
        val config = configProvider() ?: throw SmbFailure("", null, dev.huginn.R.string.smb_not_configured)
        // Sin barra final: es un fichero reproducible (libVLC no acepta `x.mp4/`).
        return config.playableUrl(url.trimEnd('/')).toUri()
    }

    /** M5: los ficheros SMB no multimedia se descargan a caché y se comparten. */
    override suspend fun toExternalUri(entry: FsEntry): Uri? = null

    /**
     * Ejecuta [block] y, si falla por red, invalida el contexto CIFS y **reintenta
     * una vez** con uno nuevo (recuperación tras caída de Wi-Fi o reinicio del NAS).
     * Los fallos no reintentables (credenciales, ruta inexistente) y un segundo
     * fallo se convierten en [SmbFailure] con un mensaje para el usuario.
     */
    private inline fun <T> withReconnect(block: () -> T): T {
        return try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (first: Exception) {
            if (!SmbErrorMessages.isRetryable(first)) {
                throw SmbFailure("", first, SmbErrorMessages.describeRes(first))
            }
            Log.w(TAG, "fallo SMB reintentable; contexto nuevo y reintento")
            invalidate()
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (second: Exception) {
                throw SmbFailure("", second, SmbErrorMessages.describeRes(second))
            }
        }
    }

    /** Fuerza un contexto nuevo (p. ej. tras editar el servidor o una caída). */
    fun invalidate() {
        runCatching { context?.close() }
        context = null
        contextKey = null
    }

    private fun cifsContext(): Pair<CIFSContext, SmbConfig> {
        val config = configProvider() ?: throw SmbFailure("", null, dev.huginn.R.string.smb_not_configured)
        if (context != null && contextKey == config.key) return context!! to config

        runCatching { context?.close() }
        context = null

        val base = BaseContext(PropertyConfiguration(SmbJcifsProperties.build(config.smb1)))
        val ctx = if (config.user.isBlank()) {
            base.withAnonymousCredentials()
        } else {
            base.withCredentials(
                NtlmPasswordAuthenticator(config.domain, config.user, config.password),
            )
        }
        Log.i(TAG, "contexto SMB creado para ${config.host}/${config.share} (user=${config.user.ifBlank { "anónimo" }})")
        context = ctx
        contextKey = config.key
        return ctx to config
    }

    /** jcifs-ng devuelve segundos en algunas versiones y ms en otras: se normaliza aquí. */
    private fun toMillis(raw: Long): Long =
        if (raw in 1..9_999_999_999L) raw * 1000 else raw

    /**
     * URL del hijo. jcifs-ng trabaja con la ruta **literal** (espacios, `#`, unicode…)
     * y falla si se le pasa percent-encoding; la codificación para libVLC se hace en
     * `SmbConfig.playableUrl`.
     */
    private fun childUrl(parent: String, name: String, isDirectory: Boolean): String = buildString {
        append(parent.trimEnd('/'))
        append('/')
        append(name)
        if (isDirectory) append('/')
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith('/')) this else "$this/"

    private companion object {
        const val TAG = "HuginnSmb"
    }
}

/**
 * Fallo SMB con mensaje ya traducido para el usuario. El `cause` conserva el
 * detalle técnico para los logs (nunca se muestra ni contiene credenciales).
 */
class SmbFailure(
    message: String,
    cause: Throwable? = null,
    /** Mensaje localizado alternativo (si viene, la UI usa este recurso). */
    val resId: Int? = null,
) : IOException(message, cause)
