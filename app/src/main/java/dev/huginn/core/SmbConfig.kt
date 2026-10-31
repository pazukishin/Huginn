package dev.huginn.core

/**
 * Configuración de un servidor SMB. En M0 se inyecta por extras; en M3 pasa a
 * Room con la contraseña cifrada en Android Keystore (nunca se registra en logs).
 */
data class SmbConfig(
    val host: String,
    val share: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    /** SMB1 opt-in: por defecto se exige SMB2 o superior. */
    val smb1: Boolean = false,
) {

    /** URL del share, sin credenciales y con barra final. */
    val shareUrl: String
        get() {
            val hostPart = host.removePrefix("smb://").trim('/')
            val sharePart = share.trim('/')
            return if (sharePart.isEmpty()) "smb://$hostPart/" else "smb://$hostPart/$sharePart/"
        }

    val key: String
        get() = "$host|$share|$user|$domain|$smb1"

    /**
     * URL para libVLC: credenciales embebidas y **path escapado como URI**.
     *
     * Ojo: jcifs-ng (listado) trabaja con la ruta **sin codificar** (`smb://host/…/Sample Share/`),
     * mientras que libVLC necesita una URI válida (`%20`, `%23`…). Por eso la
     * conversión se hace aquí y no en `FsPath`.
     */
    fun playableUrl(url: String): String {
        val scheme = "smb://"
        if (!url.startsWith(scheme)) return url
        val rest = url.removePrefix(scheme)
        val slash = rest.indexOf('/')
        val hostPart = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash)
        val encodedPath = path.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) "" else escape(segment)
        }
        val credentials = if (user.isBlank()) {
            ""
        } else {
            buildString {
                append(escape(user))
                if (password.isNotEmpty()) {
                    append(':')
                    append(escape(password))
                }
                append('@')
            }
        }
        return scheme + credentials + hostPart + encodedPath
    }

    private fun escape(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    override fun toString(): String =
        "SmbConfig(host=$host, share=$share, user=$user, smb1=$smb1)" // sin contraseña
}
