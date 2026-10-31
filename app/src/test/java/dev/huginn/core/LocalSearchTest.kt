package dev.huginn.core

import android.net.Uri
import dev.huginn.data.FileSource
import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchTest {

    /** Árbol falso: cada ruta apunta a sus hijos. */
    private class FakeSource(
        private val tree: Map<String, List<FsEntry>>,
    ) : FileSource {
        override suspend fun list(path: FsPath): List<FsEntry> =
            tree[(path as FsPath.Smb).url].orEmpty()

        override suspend fun stat(path: FsPath): FsEntry? = null

        override fun toPlayableUri(path: FsPath): Uri = error("no usado en búsqueda")
    }

    private fun file(base: String, name: String) = FsEntry(
        path = FsPath.Smb("$base/$name"),
        name = name,
        isDirectory = false,
    )

    private fun dir(base: String, name: String) = FsEntry(
        path = FsPath.Smb("$base/$name"),
        name = name,
        isDirectory = true,
    )

    private val root = "smb://host/share"
    private val base = FsPath.Smb(root)

    @Test
    fun `encuentra coincidencias recursivas sin importar mayusculas`() = runBlocking {
        val source = FakeSource(
            mapOf(
                root to listOf(file(root, "notas.txt"), dir(root, "Music")),
                "$root/Music" to listOf(file("$root/Music", "Cancion.MP3")),
            ),
        )
        val hits = LocalSearch.find(base, source, "cancion")
        assertEquals(1, hits.size)
        assertEquals("Cancion.MP3", hits.single().name)
    }

    @Test
    fun `respeta la profundidad maxima`() = runBlocking {
        val source = FakeSource(
            mapOf(
                root to listOf(dir(root, "n1")),
                "$root/n1" to listOf(dir("$root/n1", "n2")),
                "$root/n1/n2" to listOf(file("$root/n1/n2", "objetivo.txt")),
            ),
        )
        val conLimite = LocalSearch.find(base, source, "objetivo", LocalSearch.Limits(maxDepth = 1))
        assertTrue(conLimite.isEmpty())
        val sinLimite = LocalSearch.find(base, source, "objetivo", LocalSearch.Limits(maxDepth = 3))
        assertEquals(1, sinLimite.size)
    }

    @Test
    fun `respeta el maximo de entradas y de resultados`() = runBlocking {
        val many = (0 until 100).map { file(root, "f$it.txt") }
        val source = FakeSource(mapOf(root to many))
        val pocas = LocalSearch.find(base, source, ".txt", LocalSearch.Limits(maxEntries = 10))
        assertEquals(10, pocas.size)
        val limiteResultados = LocalSearch.find(base, source, ".txt", LocalSearch.Limits(maxResults = 5))
        assertEquals(5, limiteResultados.size)
    }

    @Test
    fun `termino vacio no lista nada`() = runBlocking {
        val source = FakeSource(mapOf(root to listOf(file(root, "a.txt"))))
        assertTrue(LocalSearch.find(base, source, "   ").isEmpty())
    }
}
