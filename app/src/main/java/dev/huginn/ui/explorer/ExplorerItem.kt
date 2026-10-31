package dev.huginn.ui.explorer

import dev.huginn.data.FsEntry

/** Qué hay en la lista del explorador: ficheros/carpetas o acciones de la raíz. */
sealed interface ExplorerItem {
    val title: String

    data class FileItem(val entry: FsEntry) : ExplorerItem {
        override val title: String get() = entry.name
    }

    data class ActionItem(
        val action: RootAction,
        override val title: String,
        val subtitle: String,
        /** Para `OPEN_SMB_SERVER`: id del servidor SMB al que apunta la acción. */
        val serverId: String? = null,
        /** Para `KEYMAP_LEARN`: acción del mando que se está reasignando. */
        val keyAction: dev.huginn.input.InputAction? = null,
    ) : ExplorerItem
}

/** Acciones disponibles en la raíz del explorador (M0). */
enum class RootAction {
    PICK_LOCAL_TREE,
    OPEN_SMB,
    OPEN_SMB_SERVER,
    SMB_SETTINGS,
    SMB_SERVER_ADD,
    SMB_SERVER_EDIT,
    RECENTS,
    FAVORITES,
    SETTINGS_HOME,
    SETTINGS_CLEAR_CACHE,
    SETTINGS_ABOUT,
    KEYMAP_SETTINGS,
    LANGUAGE_SETTINGS,
    LANGUAGE_EN,
    LANGUAGE_ES,
    KEYMAP_LEARN,
    KEYMAP_RESET,
    TEST_VIDEO,
    TEST_IMAGE,
    TEST_EXTERNAL_VIDEO,
}

/** Eventos puntuales que la activity debe ejecutar (UI/efectos de Android). */
sealed interface ExplorerEvent {
    /** Abrir el selector SAF (`OPEN_DOCUMENT_TREE`). */
    data object LaunchSafPicker : ExplorerEvent

    /** Abrir un fichero con una app externa en la pantalla de contenido. */
    data class OpenExternal(
        val entry: FsEntry,
        val uri: android.net.Uri,
        val mime: String,
        /** App elegida; null = hay que preguntar (selector «Abrir con»). */
        val packageName: String? = null,
    ) : ExplorerEvent

    /** M6: aplicar el idioma elegido (recrea la activity con el locale nuevo). */
    data object ApplyLanguage : ExplorerEvent

    /** Pedir a la activity las apps que pueden abrir [mime] (selector «Abrir con»). */
    data class QueryExternalApps(
        val entry: FsEntry,
        val uri: android.net.Uri,
        val mime: String,
    ) : ExplorerEvent

    /** Mensaje efímero para la barra de estado. */
    data class Message(val text: String) : ExplorerEvent

    /** El usuario pidió salir de la app desde el menú «más». */
    data object Finish : ExplorerEvent
}
