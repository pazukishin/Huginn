package dev.huginn.ui.top

import dev.huginn.data.FsEntry
import dev.huginn.data.MediaKind
import androidx.compose.ui.graphics.ImageBitmap
import dev.huginn.media.AudioMetadata
import dev.huginn.media.Lyrics
import dev.huginn.media.MediaTrack

/**
 * Estado de la pantalla superior (spec §7). M0 cubrió el subconjunto del spike; M1
 * añade la información de origen/orden en la ficha del directorio. Los estados de
 * descarga/letra/pistas llegan en M2-M5.
 */
sealed interface TopPanelState {

    /** Sin selección: ficha del directorio actual (arranque, mockup 01). */
    data class DirectoryInfo(
        val title: String,
        val path: String,
        val directories: Int,
        val files: Int,
        val readOnly: Boolean = true,
        /** «Local (SAF)» / «Red local (SMB)» / «Inicio». */
        val sourceLabel: String = "",
        /** «Nombre · asc» etc. */
        val sortLabel: String = "",
        /** Línea de ayuda cuando no hay selección (p. ej. acción resaltada). */
        val hint: String? = null,
        /** Chips a medida (M3, p. ej. «1 servidor»); si es null se usan recuentos. */
        val chips: List<String>? = null,
    ) : TopPanelState

    /** Elemento seleccionado: ficha + miniatura (mockup 02). */
    data class EntryInfo(
        val entry: FsEntry,
        val name: String,
        val kind: MediaKind,
        val sizeText: String?,
        val dateText: String?,
        val path: String,
        /** true mientras se prepara la miniatura (se muestra «Generando…»). */
        val previewLoading: Boolean = false,
    ) : TopPanelState

    /** Visor de imagen con galería de la carpeta (mockup 03). */
    data class ImageViewer(
        val entry: FsEntry,
        val name: String,
        val index: Int,
        val total: Int,
    ) : TopPanelState

    /** Reproducción en curso: vídeo en la superficie, audio con su ficha (mockups 04/05). */
    data class Playing(
        val name: String,
        val isVideo: Boolean,
        val playing: Boolean,
        val positionMs: Long,
        val lengthMs: Long,
        val uri: String?,
        /** Título/artista/álbum/duración si el origen los da (SAF y SMB). */
        val metadata: AudioMetadata? = null,
        /** Carátula incrustada, si existe. */
        val artwork: ImageBitmap? = null,
        /** Letra (.lrc sincronizada o .txt estática) si existe junto al audio. */
        val lyrics: Lyrics? = null,
        /** Controles superpuestos visibles (en vídeo se autoocultan a los 3 s). */
        val controlsVisible: Boolean = true,
        /** Menú de pistas abierto (overlay en la pantalla superior, mockup 09). */
        val trackMenu: TrackMenuState? = null,
    ) : TopPanelState

    /**
     * Formulario de alta/edición de servidor SMB (M3). Se pinta en la ventana
     * superior porque la `Presentation` del explorador no es focusable y no puede
     * recibir el teclado del sistema.
     */
    data class ServerForm(
        /** null = alta; si no, id del servidor que se edita. */
        val serverId: String? = null,
        val label: String = "",
        val host: String = "",
        val share: String = "",
        val user: String = "",
        val domain: String = "",
        val smb1: Boolean = false,
        /** Contraseña tecleada ahora mismo; solo vive en memoria hasta guardar. */
        val password: String = "",
        /** El servidor ya tiene contraseña guardada: dejarla en blanco la conserva. */
        val hasStoredPassword: Boolean = false,
        val error: String? = null,
    ) : TopPanelState

    /**
     * Captura de un botón para el mapeo (M5): se muestra en la superior y la
     * activity entrega el siguiente keycode pulsado.
     */
    data class LearnKey(
        val action: dev.huginn.input.InputAction,
        val actionLabel: String,
        val currentKey: String,
        val message: String = "",
    ) : TopPanelState

    /**
     * Búsqueda local (M5): formulario en la pantalla superior (la inferior no es
     * focusable, así que el teclado del sistema solo funciona aquí).
     */
    data class SearchForm(
        val term: String = "",
        val scope: String = "",
        val searching: Boolean = false,
        val results: Int = 0,
        val hint: String = "Escribe al menos 2 letras · B cierra",
    ) : TopPanelState

    /**
     * Preparando un archivo no multimedia para abrirlo con otra app (M5): los de SMB
     * se descargan a caché. `progress` null = tamaño desconocido (indeterminado).
     */
    data class ExternalDownload(
        val name: String,
        val progress: Float? = null,
        val message: String = "",
    ) : TopPanelState

    data class Error(val message: String) : TopPanelState
}

/**
 * Menú de pistas de audio/subtítulos (spec §4.4). Se navega con cruceta y se aplica
 * al instante: `column` 0 = audio, 1 = subtítulos; -1 en subtítulos = Desactivado.
 */
data class TrackMenuState(
    val audio: List<MediaTrack>,
    val subtitles: List<MediaTrack>,
    val audioIndex: Int,
    val subtitleIndex: Int,
    val column: Int = 0,
) {
    val items: List<MediaTrack>
        get() = if (column == 0) audio else subtitles

    val selection: Int
        get() = if (column == 0) audioIndex else subtitleIndex

    fun withSelection(column: Int, index: Int): TrackMenuState = if (column == 0) {
        copy(column = 0, audioIndex = index)
    } else {
        copy(column = 1, subtitleIndex = index)
    }
}
