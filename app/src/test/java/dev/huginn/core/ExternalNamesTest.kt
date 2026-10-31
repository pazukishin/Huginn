package dev.huginn.core

import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalNamesTest {

    private fun entry(name: String, path: String = "smb://host/share/$name", size: Long = 10, modified: Long = 5) =
        FsEntry(
            path = FsPath.Smb(path),
            name = name,
            isDirectory = false,
            size = size,
            lastModified = modified,
        )

    @Test
    fun `quita barras y traversal del nombre`() {
        val safe = ExternalNames.sanitize("../../etc/passwd.pdf")
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains(".."))
        assertTrue(safe.endsWith(".pdf"))
    }

    @Test
    fun `conserva la extension y recorta nombres largos`() {
        val long = "a".repeat(500) + ".zip"
        val safe = ExternalNames.sanitize(long)
        assertTrue(safe.endsWith(".zip"))
        assertTrue(safe.length <= 120)
    }

    @Test
    fun `nombres sin extension o con unicode quedan validos`() {
        assertEquals("archivo", ExternalNames.sanitize(""))
        val unicode = ExternalNames.sanitize("documento_ñandú 2026.pdf")
        assertTrue(unicode.endsWith(".pdf"))
        assertFalse(unicode.contains("/"))
    }

    @Test
    fun `el nombre de cache depende de ruta tamano y fecha`() {
        val base = ExternalNames.cacheFileName(entry("doc.pdf"))
        assertEquals(base, ExternalNames.cacheFileName(entry("doc.pdf")))
        assertNotEquals(base, ExternalNames.cacheFileName(entry("doc.pdf", size = 11)))
        assertNotEquals(base, ExternalNames.cacheFileName(entry("doc.pdf", modified = 6)))
        assertNotEquals(base, ExternalNames.cacheFileName(entry("doc.pdf", path = "smb://host/share/otro.pdf")))
    }

    @Test
    fun `el nombre de cache empieza por hash y termina en la extension`() {
        val name = ExternalNames.cacheFileName(entry("informe.zip"))
        assertTrue(name.endsWith(".zip"))
        assertEquals(16, name.substringBefore('_').length)
    }
}
