package dev.huginn.core

import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlacesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L

    private fun store() = PlacesStore(File(tmp.root, "places.properties"), clock = { now })

    private fun entry(name: String, smb: Boolean = true) = FsEntry(
        path = if (smb) FsPath.Smb("smb://host/share/$name") else FsPath.Local("content://doc/$name"),
        name = name,
        isDirectory = false,
        size = 1,
    )

    @Test
    fun `roundtrip de paths locales y smb`() {
        assertEquals(
            FsPath.Smb("smb://host/share/x"),
            PathRefCodec.decodePath(PathRefCodec.encodePath(FsPath.Smb("smb://host/share/x"))),
        )
        assertEquals(
            FsPath.Local("content://doc/x"),
            PathRefCodec.decodePath(PathRefCodec.encodePath(FsPath.Local("content://doc/x"))),
        )
        assertEquals(FsPath.Roots, PathRefCodec.decodePath(PathRefCodec.encodePath(FsPath.Roots)))
        assertEquals(null, PathRefCodec.decodePath("basura"))
    }

    @Test
    fun `el reciente mas nuevo va primero y no se duplica`() {
        val s = store()
        s.recordRecent(entry("a"))
        now = 2_000
        s.recordRecent(entry("b"))
        now = 3_000
        s.recordRecent(entry("a"))
        val names = s.recents().map { it.name }
        assertEquals(listOf("a", "b"), names)
        assertEquals(2, s.recents().size)
    }

    @Test
    fun `los recientes persisten en disco`() {
        val s = store()
        s.recordRecent(entry("doc.pdf"))
        val otra = store()
        assertEquals(listOf("doc.pdf"), otra.recents().map { it.name })
    }

    @Test
    fun `los recientes se recortan al maximo`() {
        val s = store()
        repeat(40) { index ->
            now = 1_000L + index
            s.recordRecent(entry("f$index"))
        }
        assertEquals(30, s.recents().size)
        assertEquals("f39", s.recents().first().name)
    }

    @Test
    fun `favoritos se anaden y se quitan`() {
        val s = store()
        assertTrue(s.toggleFavorite(entry("fav")))
        assertTrue(s.isFavorite(entry("fav")))
        assertEquals(1, s.favorites().size)
        assertFalse(s.toggleFavorite(entry("fav")))
        assertFalse(s.isFavorite(entry("fav")))
        assertTrue(s.favorites().isEmpty())
    }

    @Test
    fun `un fichero corrupto no rompe ni lanza`() {
        val file = File(tmp.root, "places.properties")
        file.writeText("version=1\nrecents.count=3\nrecents.0.path=basura\nfavorites.count=x\n")
        val s = PlacesStore(file)
        assertTrue(s.recents().isEmpty())
        assertTrue(s.favorites().isEmpty())
        // Y se puede seguir usando: el estado se reescribe sano.
        s.recordRecent(entry("nuevo"))
        assertEquals(1, store().recents().size)
    }

    @Test
    fun `favoritos persisten`() {
        val s = store()
        s.toggleFavorite(entry("fav", smb = false))
        val otra = store()
        assertEquals(1, otra.favorites().size)
        assertTrue(otra.favorites().single().path is FsPath.Local)
    }
}
