package dev.huginn.core

import android.util.Log
import java.io.File

/**
 * Persistencia de servidores SMB (M3) en un fichero privado de la app.
 *
 * - Lista pequeña (unos pocos servidores): un fichero de Properties versionado es
 *   suficiente y se testea en JVM; queda aislado tras este store por si más adelante
 *   se migra a Room (plan) por los «recientes».
 * - **Nunca** se escribe la contraseña en claro: se cifra con [SecretCipher]
 *   (Android Keystore AES-GCM en producción) antes de guardar.
 * - Escritura atómica (tmp + rename) para no corromper el fichero si se corta.
 */
class SmbServerStore(
    private val file: File,
    private val cipher: SecretCipher,
) {

    private val lock = Any()

    fun load(): List<SmbServer> = synchronized(lock) { read() }

    fun save(servers: List<SmbServer>) = synchronized(lock) { write(servers) }

    /** Alta o edición: sustituye el servidor con el mismo id y conserva el resto. */
    fun upsert(server: SmbServer): List<SmbServer> = synchronized(lock) {
        val current = read()
        val updated = if (current.any { it.id == server.id }) {
            current.map { if (it.id == server.id) server else it }
        } else {
            current + server
        }
        write(updated)
        updated
    }

    fun delete(id: String): List<SmbServer> = synchronized(lock) {
        val updated = read().filterNot { it.id == id }
        write(updated)
        updated
    }

    /** Contraseña en claro solo para construir el `SmbConfig` en memoria. */
    fun passwordOf(server: SmbServer): String? = cipher.decrypt(server.encryptedPassword)

    /**
     * Conserva el criptograma existente si [server] llega sin secreto (edición de
     * otros campos sin retipear la contraseña). Evita perder la credencial al
     * renombrar o cambiar host/share.
     */
    fun mergePreservingSecret(server: SmbServer, existing: SmbServer?): SmbServer =
        if (server.encryptedPassword.isNotEmpty() || existing == null) {
            server
        } else {
            server.copy(encryptedPassword = existing.encryptedPassword)
        }

    /** Devuelve el servidor con la contraseña cifrada lista para persistir. */
    fun withPassword(server: SmbServer, password: String): SmbServer =
        server.copy(encryptedPassword = cipher.encrypt(password))

    /** Cifra y persiste; comodidad para la UI. */
    fun upsertWithPassword(server: SmbServer, password: String): List<SmbServer> =
        upsert(withPassword(server, password))

    private fun read(): List<SmbServer> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            SmbServerCodec.decode(text)
        }.onFailure {
            // Sin volcar el contenido: podría incluir criptogramas.
            Log.w(TAG, "no se pudo leer la lista de servidores SMB", it)
        }.getOrDefault(emptyList())
    }

    private fun write(servers: List<SmbServer>) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(SmbServerCodec.encode(servers), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Fallback (p. ej. distinto sistema de ficheros): escritura directa.
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "HuginnSmbStore"
    }
}
