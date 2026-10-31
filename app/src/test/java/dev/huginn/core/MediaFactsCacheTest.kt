package dev.huginn.core

import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaFactsCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(name: String, size: Long = 10, modified: Long = 1_000) = FsEntry(
        path = FsPath.Smb("smb://host/share/$name"),
        name = name,
        isDirectory = false,
        size = size,
        lastModified = modified,
    )

    private fun cache(max: Int = 16) = MediaFactsCache(File(tmp.root, "facts.properties"), max)

    @Test
    fun `guarda y recupera metadatos`() {
        val c = cache()
        c.put(entry("peli.mkv"), MediaFacts(durationMs = 60_000, width = 1920, height = 1080))
        assertEquals(60_000L, c.get(entry("peli.mkv"))?.durationMs)
        assertEquals("1920×1080", c.get(entry("peli.mkv"))?.resolutionText)
    }

    @Test
    fun `la clave incluye tamano y fecha`() {
        val c = cache()
        c.put(entry("a.mkv", size = 1), MediaFacts(durationMs = 1_000))
        assertNull(c.get(entry("a.mkv", size = 2)))
        assertNull(c.get(entry("a.mkv", size = 1, modified = 2_000)))
    }

    @Test
    fun `persiste en disco y sobrevive a una instancia nueva`() {
        val c = cache()
        c.put(entry("peli.mkv"), MediaFacts(durationMs = 90_000, title = "Peli"))
        c.flush()
        val otra = cache()
        assertEquals(90_000L, otra.get(entry("peli.mkv"))?.durationMs)
        assertEquals("Peli", otra.get(entry("peli.mkv"))?.title)
    }

    @Test
    fun `los metadatos vacios no se guardan`() {
        val c = cache()
        c.put(entry("x.bin"), MediaFacts())
        assertNull(c.get(entry("x.bin")))
    }

    @Test
    fun `respeta el maximo de entradas (LRU)`() {
        val c = cache(max = 2)
        c.put(entry("a"), MediaFacts(durationMs = 1))
        c.put(entry("b"), MediaFacts(durationMs = 2))
        c.put(entry("c"), MediaFacts(durationMs = 3))
        // La más antigua se descarta al superar el máximo.
        assertNull(c.get(entry("a")))
        assertEquals(3L, c.get(entry("c"))?.durationMs)
    }

    @Test
    fun `clear vacia memoria y fichero`() {
        val file = File(tmp.root, "facts.properties")
        val c = MediaFactsCache(file)
        c.put(entry("a"), MediaFacts(durationMs = 5))
        c.flush()
        c.clear()
        assertNull(c.get(entry("a")))
        assertNull(file.takeIf { it.exists() }?.takeIf { it.length() > 0 })
    }
}
