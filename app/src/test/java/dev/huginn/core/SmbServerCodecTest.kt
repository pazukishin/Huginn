package dev.huginn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbServerCodecTest {

    private fun server(
        id: String = "srv-1",
        label: String = "NAS del salón",
        host: String = "192.0.2.10",
        share: String = "Data",
        user: String = "usuario",
        domain: String = "WORKGROUP",
        smb1: Boolean = false,
        secret: String = "Y2lwaGVyLXRleHQ=",
    ) = SmbServer(id, label, host, share, user, domain, smb1, secret)

    @Test
    fun `roundtrip de un servidor con todos los campos`() {
        val original = server()
        val decoded = SmbServerCodec.decode(SmbServerCodec.encode(listOf(original)))
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun `roundtrip con varios servidores y orden preservado`() {
        val list = listOf(
            server(id = "a", label = "NAS", host = "192.0.2.10"),
            server(id = "b", label = "Windows", host = "192.0.2.20", share = "", user = "", domain = ""),
            server(id = "c", label = "Samba viejo", host = "192.0.2.5", smb1 = true),
        )
        val decoded = SmbServerCodec.decode(SmbServerCodec.encode(list))
        assertEquals(list, decoded)
        assertEquals(listOf("a", "b", "c"), decoded.map { it.id })
    }

    @Test
    fun `roundtrip con unicode y caracteres raros en la etiqueta`() {
        val list = listOf(
            server(
                label = "NAS ñandú 🗄️ = raro: con ; y \\ barras",
                share = "Media/Music/日本",
                user = "usuario con espacio",
            ),
        )
        val decoded = SmbServerCodec.decode(SmbServerCodec.encode(list))
        assertEquals(list[0], decoded.single())
    }

    @Test
    fun `lista vacia ida y vuelta`() {
        assertTrue(SmbServerCodec.decode(SmbServerCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `texto vacio o basura devuelve lista vacia`() {
        assertTrue(SmbServerCodec.decode("").isEmpty())
        assertTrue(SmbServerCodec.decode("esto no es un fichero de propiedades").isEmpty())
        assertTrue(SmbServerCodec.decode("version=abc").isEmpty())
    }

    @Test
    fun `version desconocida se ignora`() {
        val text = SmbServerCodec.encode(listOf(server())).replace("version=1", "version=99")
        assertTrue(SmbServerCodec.decode(text).isEmpty())
    }

    @Test
    fun `servidor sin host o sin id se descarta`() {
        val text = """
            version=1
            count=2
            server.0.id=a
            server.0.host=
            server.1.id=b
            server.1.host=192.0.2.10
        """.trimIndent()
        val decoded = SmbServerCodec.decode(text)
        assertEquals(1, decoded.size)
        assertEquals("b", decoded.single().id)
    }

    @Test
    fun `sin contador usa los indices presentes`() {
        val text = """
            version=1
            server.0.id=a
            server.0.host=192.0.2.10
            server.1.id=b
            server.1.host=192.0.2.4
            server.1.smb1=true
        """.trimIndent()
        val decoded = SmbServerCodec.decode(text)
        assertEquals(2, decoded.size)
        assertTrue(decoded[1].smb1)
    }
}
