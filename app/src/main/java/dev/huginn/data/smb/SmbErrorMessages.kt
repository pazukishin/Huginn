package dev.huginn.data.smb

import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Traduce fallos SMB a mensajes para el usuario (M3), sin filtrar detalles internos
 * ni credenciales, y decide si merece la pena reintentar.
 *
 * Sin Android: testeable en JVM con las excepciones de `java.net` y de jcifs-ng.
 */
object SmbErrorMessages {

    /** Mensaje corto y accionable para la pantalla inferior (recurso localizado). */
    fun describeRes(error: Throwable): Int {
        val chain = generateSequence(error) { it.cause }.toList()
        val smbAuth = chain.any { it is SmbAuthException }
        if (smbAuth) return dev.huginn.R.string.smb_err_auth
        val text = chain.joinToString(" ") { it.message.orEmpty().lowercase() }
        return when {
            chain.any { it is UnknownHostException } || "unknown host" in text ->
                dev.huginn.R.string.smb_err_host
            chain.any { it is NoRouteToHostException } || "no route to host" in text ->
                dev.huginn.R.string.smb_err_route
            chain.any { it is ConnectException } || "connection refused" in text ->
                dev.huginn.R.string.smb_err_refused
            chain.any { it is SocketTimeoutException } ||
                "timed out" in text || "timeout" in text ->
                dev.huginn.R.string.smb_err_timeout
            chain.any {
                it is SmbException && (
                    "not exist" in it.message.orEmpty().lowercase() ||
                        "bad network name" in it.message.orEmpty().lowercase() ||
                        "no such" in it.message.orEmpty().lowercase()
                    )
            } -> dev.huginn.R.string.smb_err_not_found
            chain.any { it is SmbException && "access denied" in it.message.orEmpty().lowercase() } ->
                dev.huginn.R.string.smb_err_denied
            "malformed" in text || "invalid url" in text || "invalid smb" in text ->
                dev.huginn.R.string.smb_err_invalid
            else -> dev.huginn.R.string.smb_err_generic
        }
    }

    /**
     * Reintentar solo tiene sentido en fallos de red/tiempo de espera: repetir con
     * credenciales incorrectas o un recurso inexistente no arregla nada.
     */
    fun isRetryable(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is SmbAuthException }) return false
        val text = chain.joinToString(" ") { it.message.orEmpty().lowercase() }
        if ("not exist" in text || "bad network name" in text || "no such" in text) return false
        if ("malformed" in text || "invalid url" in text) return false
        return true
    }
}
