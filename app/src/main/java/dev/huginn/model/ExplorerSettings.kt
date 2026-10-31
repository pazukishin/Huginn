package dev.huginn.model

/** Vistas del explorador (spec §5.3/§5.3b). Ciclo: Lista → Grid → Galería → Lista. */
enum class ExplorerView {
    LIST,
    GRID,
    GALLERY,
}

/** Criterios de orden del bottom sheet «Ordenar» (spec §5.4). */
enum class SortKey {
    NAME,
    DATE,
    SIZE,
    TYPE,
    ;

    val label: String
        get() = when (this) {
            NAME -> "Nombre"
            DATE -> "Fecha"
            SIZE -> "Tamaño"
            TYPE -> "Tipo"
        }
}

enum class SortDirection { ASC, DESC }

/** Tipo de bottom sheet abierto (M1: orden y menú «más»). */
enum class SheetKind { SORT, MORE, EXTERNAL }
