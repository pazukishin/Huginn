package dev.huginn.ui.explorer

import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import dev.huginn.model.ExplorerView
import dev.huginn.model.SortDirection
import dev.huginn.model.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas de M1: orden, elegibilidad de la Vista Galería y navegación 2D. Son puras:
 * no necesitan Android y cubren los criterios de aceptación del hito.
 */
class ExplorerLogicTest {

    private fun entry(
        name: String,
        dir: Boolean = false,
        mime: String? = null,
        size: Long? = null,
        date: Long? = null,
    ) = FsEntry(
        path = FsPath.Local("content://test/$name"),
        name = name,
        isDirectory = dir,
        size = size,
        lastModified = date,
        mimeType = mime,
    )

    // ------------------------------------------------------------------ orden

    @Test
    fun `las carpetas van siempre primero`() {
        val entries = listOf(
            entry("b.txt", size = 10),
            entry("Zeta", dir = true),
            entry("a.jpg", mime = "image/jpeg", size = 5),
        )
        val sorted = ExplorerLogic.sort(entries, SortKey.NAME, SortDirection.ASC)
        assertEquals(listOf("Zeta", "a.jpg", "b.txt"), sorted.map { it.name })
    }

    @Test
    fun `orden por nombre ascendente y descendente`() {
        val entries = listOf(entry("b"), entry("A"), entry("c"))
        assertEquals(
            listOf("A", "b", "c"),
            ExplorerLogic.sort(entries, SortKey.NAME, SortDirection.ASC).map { it.name },
        )
        assertEquals(
            listOf("c", "b", "A"),
            ExplorerLogic.sort(entries, SortKey.NAME, SortDirection.DESC).map { it.name },
        )
    }

    @Test
    fun `orden por fecha tamano y tipo`() {
        val a = entry("a", size = 300, date = 100)
        val b = entry("b", size = 100, date = 300)
        val c = entry("c", mime = "image/png", size = 200, date = 200)

        assertEquals(
            listOf("a", "c", "b"),
            ExplorerLogic.sort(listOf(a, b, c), SortKey.DATE, SortDirection.ASC).map { it.name },
        )
        assertEquals(
            listOf("b", "c", "a"),
            ExplorerLogic.sort(listOf(a, b, c), SortKey.SIZE, SortDirection.ASC).map { it.name },
        )
        // TYPE ordena por tipo (image antes que null/otro) y luego por nombre
        assertEquals(
            "c",
            ExplorerLogic.sort(listOf(a, b, c), SortKey.TYPE, SortDirection.ASC).first().name,
        )
    }

    // -------------------------------------------------------- vista galería

    @Test
    fun `galeria disponible solo con imagenes visibles`() {
        assertTrue(ExplorerLogic.galleryEligible(listOf(entry("a.jpg", mime = "image/jpeg"), entry("b.png", mime = "image/png"))))
        assertFalse(ExplorerLogic.galleryEligible(listOf(entry("a.jpg", mime = "image/jpeg"), entry("v.mp4", mime = "video/mp4"))))
        assertFalse(ExplorerLogic.galleryEligible(listOf(entry("doc.pdf"))))
    }

    @Test
    fun `ocultos se ignoran y las subcarpetas no bloquean`() {
        val entries = listOf(
            entry(".thumbnails", dir = true),
            entry(".nomedia"),
            entry("._a.jpg"),
            entry("sub", dir = true),
            entry("foto.jpg", mime = "image/jpeg"),
        )
        assertTrue(ExplorerLogic.galleryEligible(entries))
        // La vista solo pinta imágenes visibles, no subcarpetas
        assertEquals(listOf("foto.jpg"), ExplorerLogic.galleryEntries(entries).map { it.name })
    }

    @Test
    fun `oculto con extension de imagen no cuenta como imagen visible`() {
        val entries = listOf(entry(".thumb.jpg", mime = "image/jpeg"), entry("a.mkv", mime = "video/x-matroska"))
        assertFalse(ExplorerLogic.galleryEligible(entries))
    }

    // ----------------------------------------------------------- ciclo de vista

    @Test
    fun `galeria no disponible si solo hay carpetas`() {
        val entries = listOf(entry("sub1", dir = true), entry("sub2", dir = true))
        assertTrue(ExplorerLogic.galleryEligible(entries))
        assertFalse(ExplorerLogic.galleryAvailable(entries))
    }

    @Test
    fun `galeria disponible con al menos una imagen`() {
        val entries = listOf(entry("sub", dir = true), entry("foto.jpg", mime = "image/jpeg"))
        assertTrue(ExplorerLogic.galleryAvailable(entries))
    }

    @Test
    fun `ciclo lista grid galeria`() {
        assertEquals(ExplorerView.GRID, ExplorerLogic.cycleView(ExplorerView.LIST, galleryEligible = false))
        assertEquals(ExplorerView.GALLERY, ExplorerLogic.cycleView(ExplorerView.GRID, galleryEligible = true))
        // Si la galería no aplica, el ciclo salta a lista
        assertEquals(ExplorerView.LIST, ExplorerLogic.cycleView(ExplorerView.GRID, galleryEligible = false))
        assertEquals(ExplorerView.LIST, ExplorerLogic.cycleView(ExplorerView.GALLERY, galleryEligible = true))
    }

    @Test
    fun `columnas por vista`() {
        assertEquals(1, ExplorerLogic.columnsFor(ExplorerView.LIST, 4))
        assertEquals(2, ExplorerLogic.columnsFor(ExplorerView.GRID, 4))
        assertEquals(4, ExplorerLogic.columnsFor(ExplorerView.GALLERY, 4))
        assertEquals(5, ExplorerLogic.columnsFor(ExplorerView.GALLERY, 5))
    }

    // ------------------------------------------------------- navegación 2D

    @Test
    fun `navegacion 2d en rejilla de dos columnas`() {
        val count = 5 // índices 0..4
        assertEquals(1, ExplorerLogic.moveInGrid(0, ExplorerLogic.GridMove.RIGHT, count, 2))
        assertEquals(1, ExplorerLogic.moveInGrid(1, ExplorerLogic.GridMove.RIGHT, count, 2)) // borde derecho de la fila
        assertEquals(3, ExplorerLogic.moveInGrid(1, ExplorerLogic.GridMove.DOWN, count, 2))
        assertEquals(1, ExplorerLogic.moveInGrid(3, ExplorerLogic.GridMove.UP, count, 2))
        assertEquals(2, ExplorerLogic.moveInGrid(4, ExplorerLogic.GridMove.UP, count, 2)) // fila 2, columna 0
        assertEquals(4, ExplorerLogic.moveInGrid(4, ExplorerLogic.GridMove.DOWN, count, 2)) // sin fila siguiente
        assertEquals(2, ExplorerLogic.moveInGrid(3, ExplorerLogic.GridMove.LEFT, count, 2))
        assertEquals(2, ExplorerLogic.moveInGrid(2, ExplorerLogic.GridMove.LEFT, count, 2)) // borde izquierdo
    }

    @Test
    fun `navegacion 2d en galeria de cuatro columnas`() {
        val count = 10
        assertEquals(4, ExplorerLogic.moveInGrid(0, ExplorerLogic.GridMove.DOWN, count, 4))
        assertEquals(5, ExplorerLogic.moveInGrid(4, ExplorerLogic.GridMove.RIGHT, count, 4))
        assertEquals(3, ExplorerLogic.moveInGrid(3, ExplorerLogic.GridMove.RIGHT, count, 4)) // no salta de fila
        assertEquals(8, ExplorerLogic.moveInGrid(8, ExplorerLogic.GridMove.DOWN, count, 4))
        assertEquals(8, ExplorerLogic.moveInGrid(8, ExplorerLogic.GridMove.DOWN, count, 4))
    }

    @Test
    fun `etiqueta de orden`() {
        assertEquals("Nombre · asc", ExplorerLogic.sortLabel(SortKey.NAME, SortDirection.ASC))
        assertEquals("Fecha · desc", ExplorerLogic.sortLabel(SortKey.DATE, SortDirection.DESC))
    }
}

/** M5: rendimiento/robustez con 5.000 entradas (ruta caliente de la lista). */
class ExplorerLogicBigFolderTest {

    private fun entry(index: Int, directory: Boolean = false) = dev.huginn.data.FsEntry(
        path = dev.huginn.data.FsPath.Smb("smb://host/share/item$index"),
        name = "item$index",
        isDirectory = directory,
        size = index.toLong(),
        mimeType = if (directory) null else "image/jpeg",
    )

    @Test
    fun `ordena 5000 entradas con carpetas primero sin fallos`() {
        val input = buildList {
            repeat(4_900) { add(entry(it)) }
            repeat(100) { add(entry(10_000 + it, directory = true)) }
        }
        val sorted = ExplorerLogic.sort(input, SortKey.NAME, SortDirection.ASC)
        assertEquals(5_000, sorted.size)
        // Las 100 carpetas van primero y el resto queda ordenado por nombre.
        assertTrue(sorted.take(100).all { it.isDirectory })
        assertTrue(sorted.drop(100).none { it.isDirectory })
        assertEquals(sorted.drop(100).map { it.name }.sortedBy { it.lowercase() }, sorted.drop(100).map { it.name })
    }

    @Test
    fun `la elegibilidad de galeria recorre 5000 entradas`() {
        val images = List(5_000) { entry(it) }
        assertTrue(ExplorerLogic.galleryAvailable(images))
        val mixed = images + entry(9_999).copy(name = "video.mp4", mimeType = "video/mp4")
        assertFalse(ExplorerLogic.galleryAvailable(mixed))
    }
}
