package dev.huginn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbConfigTest {

    @Test
    fun `url del share con barra final`() {
        val config = SmbConfig(host = "192.0.2.10", share = "/media/")
        assertEquals("smb://192.0.2.10/media/", config.shareUrl)
    }

    @Test
    fun `url reproducibles con credenciales escapadas`() {
        val config = SmbConfig(
            host = "192.0.2.10",
            share = "media",
            user = "usuario",
            password = "pa ss@word",
        )
        val url = config.playableUrl("smb://192.0.2.10/media/Disco%201/peli.mkv")
        assertTrue(url.startsWith("smb://usuario:pa%20ss%40word@192.0.2.10/media/"))
        assertTrue(url.endsWith("/peli.mkv"))
    }

    @Test
    fun `sin usuario no embebe credenciales`() {
        val config = SmbConfig(host = "nas", share = "publico")
        assertEquals("smb://nas/publico/x.mkv", config.playableUrl("smb://nas/publico/x.mkv"))
    }

    @Test
    fun `key no incluye la contrasena y cambia con el usuario`() {
        val a = SmbConfig(host = "nas", share = "s", user = "u", password = "secreto1")
        val b = SmbConfig(host = "nas", share = "s", user = "u", password = "secreto2")
        assertEquals(a.key, b.key)
        assertFalse(a.key.contains("secreto1"))
        assertFalse(a.toString().contains("secreto1"))
    }
}
