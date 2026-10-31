package dev.huginn.core

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * Servidor SMB persistido (M3): datos no sensibles en claro y **contraseña solo
 * cifrada** ([encryptedPassword]). El `SmbConfig` con la contraseña en memoria se
 * construye bajo demanda con [config] para jcifs/libVLC.
 */
data class SmbServer(
    val id: String,
    val label: String,
    val host: String,
    val share: String = "",
    val user: String = "",
    val domain: String = "",
    /** SMB1 opt-in: por defecto solo SMB2+. */
    val smb1: Boolean = false,
    /** Criptograma AES-GCM (base64); nunca la contraseña en claro. */
    val encryptedPassword: String = "",
) {

    /** Nombre visible: etiqueta si la hay, si no el host. */
    val displayLabel: String
        get() = label.ifBlank { host }

    fun config(password: String): SmbConfig = SmbConfig(
        host = host,
        share = share,
        user = user,
        password = password,
        domain = domain,
        smb1 = smb1,
    )

    override fun toString(): String =
        "SmbServer(id=$id, label=$label, host=$host, share=$share, user=$user, smb1=$smb1)" // sin secreto
}

/**
 * Serialización de la lista de servidores SMB en un fichero privado de la app.
 *
 * Formato `Properties` versionado (una clave por campo, `server.N.*`), sin
 * dependencias externas: es legible, tolera caracteres raros y se testea en JVM.
 * El fichero **nunca** contiene contraseñas en claro ([SmbServer.encryptedPassword]).
 */
object SmbServerCodec {

    const val VERSION = 1

    fun encode(servers: List<SmbServer>): String {
        val props = Properties()
        props.setProperty("version", VERSION.toString())
        props.setProperty("count", servers.size.toString())
        servers.forEachIndexed { index, server ->
            val prefix = "server.$index."
            props.setProperty(prefix + "id", server.id)
            props.setProperty(prefix + "label", server.label)
            props.setProperty(prefix + "host", server.host)
            props.setProperty(prefix + "share", server.share)
            props.setProperty(prefix + "user", server.user)
            props.setProperty(prefix + "domain", server.domain)
            props.setProperty(prefix + "smb1", server.smb1.toString())
            props.setProperty(prefix + "secret", server.encryptedPassword)
        }
        return StringWriter().also { props.store(it, "Huginn SMB servers (contraseñas cifradas)") }.toString()
    }

    /** Decodifica tolerante: datos corruptos o de otra versión devuelven lista vacía. */
    fun decode(text: String): List<SmbServer> {
        if (text.isBlank()) return emptyList()
        val props = runCatching { Properties().apply { load(StringReader(text)) } }.getOrNull()
            ?: return emptyList()
        val version = props.getProperty("version")?.trim()?.toIntOrNull() ?: return emptyList()
        if (version != VERSION) return emptyList()
        val count = props.getProperty("count")?.trim()?.toIntOrNull()
            ?: countFromKeys(props)
        return (0 until count).mapNotNull { index ->
            val prefix = "server.$index."
            val id = props.getProperty(prefix + "id")?.trim().orEmpty()
            val host = props.getProperty(prefix + "host")?.trim().orEmpty()
            if (id.isEmpty() || host.isEmpty()) return@mapNotNull null
            SmbServer(
                id = id,
                label = props.getProperty(prefix + "label").orEmpty(),
                host = host,
                share = props.getProperty(prefix + "share").orEmpty(),
                user = props.getProperty(prefix + "user").orEmpty(),
                domain = props.getProperty(prefix + "domain").orEmpty(),
                smb1 = props.getProperty(prefix + "smb1")?.trim()?.toBooleanStrictOrNull() ?: false,
                encryptedPassword = props.getProperty(prefix + "secret").orEmpty(),
            )
        }
    }

    private fun countFromKeys(props: Properties): Int =
        props.stringPropertyNames()
            .mapNotNull { name -> Regex("^server\\.(\\d+)\\.").find(name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 0
}
