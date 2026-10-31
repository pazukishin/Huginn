package dev.huginn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** Cifrador falso para tests JVM (el real usa Android Keystore). */
private class FakeCipher : SecretCipher {
    override fun encrypt(plaintext: String): String =
        "c:" + Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

    override fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        if (!encoded.startsWith("c:")) return null
        return runCatching {
            String(Base64.getDecoder().decode(encoded.removePrefix("c:")), Charsets.UTF_8)
        }.getOrNull()
    }
}

class SmbServerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cipher = FakeCipher()

    private fun store(): SmbServerStore = SmbServerStore(File(tmp.root, "servers.properties"), cipher)

    private fun server(id: String = "s1", label: String = "NAS", host: String = "192.0.2.10") =
        SmbServer(id = id, label = label, host = host, user = "usuario")

    @Test
    fun `guardar y cargar conserva los servidores`() {
        val store = store()
        val list = listOf(server(), server(id = "s2", label = "Windows", host = "192.0.2.20"))
        store.save(list)
        assertEquals(list, store.load())
    }

    @Test
    fun `el fichero no contiene la contrasena en claro`() {
        val store = store()
        val plain = "s3cr3t-super-secreta"
        val saved = store.upsertWithPassword(server(), plain).single()
        val file = File(tmp.root, "servers.properties")
        val text = file.readText(Charsets.UTF_8)
        assertFalse("la contraseña no debe aparecer en claro", text.contains(plain))
        assertTrue("debe haber un criptograma", saved.encryptedPassword.isNotEmpty())
        assertTrue(
            "el criptograma no puede ser la contraseña",
            saved.encryptedPassword != plain,
        )
        assertEquals(plain, store.passwordOf(saved))
    }

    @Test
    fun `passwordOf descifra la contrasena guardada`() {
        val store = store()
        val saved = store.upsertWithPassword(server(), "clave-123")
        val server = saved.single()
        assertEquals("clave-123", store.passwordOf(server))
    }

    @Test
    fun `passwordOf devuelve null con criptograma invalido`() {
        val store = store()
        val saved = store.upsert(server().copy(encryptedPassword = "no-es-un-criptograma"))
        assertNull(store.passwordOf(saved.single()))
    }

    @Test
    fun `upsert reemplaza el existente y anade el nuevo`() {
        val store = store()
        store.save(listOf(server()))
        val updated = store.upsert(server(label = "NAS renombrado"))
        assertEquals(1, updated.size)
        assertEquals("NAS renombrado", updated.single().label)

        val two = store.upsert(server(id = "s2", label = "Otro", host = "192.0.2.9"))
        assertEquals(listOf("s1", "s2"), two.map { it.id })
        assertEquals(2, store.load().size)
    }

    @Test
    fun `delete elimina solo el servidor indicado`() {
        val store = store()
        store.save(listOf(server(), server(id = "s2", label = "Otro", host = "192.0.2.9")))
        val remaining = store.delete("s1")
        assertEquals(listOf("s2"), remaining.map { it.id })
        assertEquals(1, store.load().size)
    }

    @Test
    fun `fichero corrupto carga como lista vacia sin lanzar`() {
        val file = File(tmp.root, "servers.properties")
        file.writeText("\u0000\u0001basura que no es properties", Charsets.UTF_8)
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `mergePreservingSecret conserva el criptograma al editar sin contrasena`() {
        val existing = server().copy(encryptedPassword = "c:c2VjcmV0")
        val edited = existing.copy(label = "renombrado", encryptedPassword = "")
        val merged = store().mergePreservingSecret(edited, existing)
        assertEquals("c:c2VjcmV0", merged.encryptedPassword)
        assertEquals("renombrado", merged.label)
    }

    @Test
    fun `mergePreservingSecret usa el secreto nuevo si lo hay`() {
        val existing = server().copy(encryptedPassword = "c:viejo")
        val edited = existing.copy(encryptedPassword = "c:nuevo")
        assertEquals("c:nuevo", store().mergePreservingSecret(edited, existing).encryptedPassword)
    }

    @Test
    fun `mergePreservingSecret sin servidor previo deja el servidor tal cual`() {
        val edited = server().copy(encryptedPassword = "")
        assertEquals(edited, store().mergePreservingSecret(edited, null))
    }

    @Test
    fun `save con lista vacia deja el fichero valido y vacio al cargar`() {
        val store = store()
        store.save(listOf(server()))
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
