package dev.huginn.ui.explorer

import dev.huginn.data.FsEntry
import dev.huginn.data.MediaKind
import dev.huginn.model.ExplorerView
import dev.huginn.model.SortDirection
import dev.huginn.model.SortKey

/**
 * Reglas puras del explorador: orden, elegibilidad de la Vista Galería y navegación
 * 2D. Viven fuera del controlador y de Compose para poder testearlas sin Android.
 */
object ExplorerLogic {

    /** Basura típica de sistemas operativos (no se muestra ni cuenta). */
    private val systemJunkNames = setOf("thumbs.db", "desktop.ini", "ehthumbs.db", ".ds_store".trimStart('.'))

    /** Nombre oculto: empieza por punto (`._*`, `.thumbnails`, `.nomedia`…). */
    fun isHidden(entry: FsEntry): Boolean = entry.name.startsWith(".")

    /** Fichero de sistema que no aporta nada al explorador (`Thumbs.db`…). */
    fun isSystemJunk(entry: FsEntry): Boolean = entry.name.lowercase() in systemJunkNames

    /** Se ignora para reglas de galería: oculto o basura de sistema. */
    fun isIgnored(entry: FsEntry): Boolean = isHidden(entry) || isSystemJunk(entry)

    fun isVisible(entry: FsEntry): Boolean = !isHidden(entry)

    /**
     * Vista Galería disponible (spec §5.3b): la carpeta no puede tener **archivos
     * visibles que no sean imágenes**. Los ocultos se ignoran y las subcarpetas no
     * bloquean (no se muestran en esta vista).
     */
    fun galleryEligible(entries: List<FsEntry>): Boolean =
        entries.none { !isIgnored(it) && !it.isDirectory && it.mediaKind != MediaKind.IMAGE }

    /**
     * Vista Galería disponible: sin archivos visibles que no sean imágenes **y con
     * al menos una imagen** (una carpeta con solo subcarpetas no ofrece la vista).
     */
    fun galleryAvailable(entries: List<FsEntry>): Boolean =
        galleryEligible(entries) && galleryEntries(entries).isNotEmpty()

    /** Elementos que pinta la Vista Galería: solo imágenes visibles. */
    fun galleryEntries(entries: List<FsEntry>): List<FsEntry> =
        entries.filter { !isIgnored(it) && !it.isDirectory && it.mediaKind == MediaKind.IMAGE }

    /**
     * Orden: las carpetas siempre primero (decisión de v1) y después el criterio
     * elegido en la dirección elegida. La Vista Galería respeta el mismo orden.
     */
    fun sort(entries: List<FsEntry>, key: SortKey, direction: SortDirection): List<FsEntry> {
        val comparator: Comparator<FsEntry> = when (key) {
            SortKey.NAME -> compareBy { it.name.lowercase() }
            SortKey.DATE -> compareBy { it.lastModified ?: Long.MIN_VALUE }
            SortKey.SIZE -> compareBy { it.size ?: Long.MIN_VALUE }
            SortKey.TYPE -> compareBy<FsEntry> { it.mediaKind.name }.thenBy { it.name.lowercase() }
        }.let { if (direction == SortDirection.DESC) it.reversed() else it }

        return entries.sortedWith(
            compareByDescending<FsEntry> { it.isDirectory }.then(comparator),
        )
    }

    fun cycleView(current: ExplorerView, galleryEligible: Boolean): ExplorerView = when (current) {
        ExplorerView.LIST -> ExplorerView.GRID
        ExplorerView.GRID -> if (galleryEligible) ExplorerView.GALLERY else ExplorerView.LIST
        ExplorerView.GALLERY -> ExplorerView.LIST
    }

    /** Columnas de la Vista Galería: 4 por defecto, 5 como ajuste (spec §5.3b). */
    fun galleryColumns(galleryColumnsSetting: Int): Int =
        galleryColumnsSetting.coerceIn(3, 6)

    /** Columnas de la vista grid: 2 fijas en v1 (spec §5.3). */
    const val GRID_COLUMNS = 2

    fun columnsFor(view: ExplorerView, galleryColumnsSetting: Int): Int = when (view) {
        ExplorerView.LIST -> 1
        ExplorerView.GRID -> GRID_COLUMNS
        ExplorerView.GALLERY -> galleryColumns(galleryColumnsSetting)
    }

    enum class GridMove { UP, DOWN, LEFT, RIGHT }

    /**
     * Mueve la selección en una rejilla sin salirse de los límites. En la primera
     * fila `UP` no hace nada (no hay wrap); en la última fila `DOWN` tampoco.
     */
    fun moveInGrid(index: Int, move: GridMove, count: Int, columns: Int): Int {
        if (count <= 0) return 0
        val cols = columns.coerceAtLeast(1)
        val current = index.coerceIn(0, count - 1)
        val column = current % cols
        val target = when (move) {
            GridMove.LEFT -> if (column > 0) current - 1 else current
            GridMove.RIGHT -> if (column < cols - 1 && current + 1 < count) current + 1 else current
            GridMove.UP -> if (current - cols >= 0) current - cols else current
            GridMove.DOWN -> if (current + cols < count) current + cols else current
        }
        return target
    }

    /** Texto para la ficha/estado: «Nombre · ascendente». */
    fun sortLabel(key: SortKey, direction: SortDirection): String =
        "${key.label} · ${if (direction == SortDirection.ASC) "asc" else "desc"}"
}
