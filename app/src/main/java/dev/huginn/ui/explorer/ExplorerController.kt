package dev.huginn.ui.explorer

import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dev.huginn.core.MediaFacts
import dev.huginn.core.LocalSearch
import dev.huginn.core.PathRef
import dev.huginn.core.SmbServer
import dev.huginn.external.ExternalAppChoice
import dev.huginn.AppContainer
import dev.huginn.data.FsEntry
import dev.huginn.data.smb.SmbFailure
import dev.huginn.data.FsPath
import dev.huginn.data.MediaKind
import dev.huginn.data.MimeTypes
import dev.huginn.media.MediaProbe
import dev.huginn.input.InputAction
import dev.huginn.input.InputRouter
import dev.huginn.core.AudioFocus
import dev.huginn.media.AudioMetadataReader
import dev.huginn.media.LyricsLoader
import dev.huginn.media.MediaTrack
import dev.huginn.media.PlaybackEvent
import dev.huginn.media.SubtitleMatcher
import dev.huginn.model.ExplorerView
import dev.huginn.model.SheetKind
import dev.huginn.model.SortDirection
import dev.huginn.model.SortKey
import dev.huginn.ui.top.TopPanelState
import dev.huginn.ui.top.TrackMenuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un nivel de las migas de pan (raíz → carpeta actual). */
data class Breadcrumb(val name: String, val path: FsPath)

/** Fila de un bottom sheet, con estado de marca para «Ordenar». */
data class SheetItemState(
    val id: String,
    val label: String,
    val checked: Boolean = false,
)

data class SheetState(
    val kind: SheetKind,
    val title: String,
    val items: List<SheetItemState>,
    val selection: Int = 0,
)

data class ExplorerUiState(
    val location: FsPath = FsPath.Roots,
    val title: String = "Huginn",
    val subtitle: String = "",
    val items: List<ExplorerItem> = emptyList(),
    /** -1 = sin selección: la superior muestra la ficha del directorio. */
    val selectedIndex: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val topPanel: TopPanelState = TopPanelState.DirectoryInfo("Huginn", "", 0, 0),
    val focus: FocusArea = FocusArea.EXPLORER,
    val view: ExplorerView = ExplorerView.LIST,
    val sortKey: SortKey = SortKey.NAME,
    val sortAscending: Boolean = true,
    /** Etiqueta localizada «Nombre · asc»; la fija el controlador al cargar/ordenar. */
    val sortLabel: String = "",
    val trail: List<Breadcrumb> = emptyList(),
    val sheet: SheetState? = null,
    val galleryEligible: Boolean = false,
    val galleryColumns: Int = 4,
    /** Pantalla inferior apagada mientras se ve un vídeo (botón del mini-player). */
    val bottomScreenOff: Boolean = false,
    /** M3: la lista inferior muestra los ajustes de servidores SMB. */
    val smbSettings: Boolean = false,
    /** M5: lista virtual (recientes/favoritos/ajustes) en la inferior. */
    val virtual: VirtualView? = null,
    /** M5: texto de estado vacío contextual (recientes, favoritos, búsqueda…). */
    val emptyHint: String? = null,
) {
    val selectedItem: ExplorerItem?
        get() = items.getOrNull(selectedIndex)

    val sortDirection: SortDirection
        get() = if (sortAscending) SortDirection.ASC else SortDirection.DESC

    val columns: Int
        get() = ExplorerLogic.columnsFor(view, galleryColumns)
}

enum class FocusArea { EXPLORER, PLAYER }

/** Listas virtuales de M5: recientes, favoritos y ajustes (sin carpeta real). */
enum class VirtualView { RECENTS, FAVORITES, SETTINGS, SEARCH, KEYMAP, LANGUAGE }

/**
 * Estado y acciones del explorador (MVVM + StateFlow, ADR 7).
 *
 * La selección y las vistas viven aquí (no en Compose) porque el mando lo gestiona
 * `InputRouter` a nivel de Activity: así el comportamiento es determinista y las dos
 * activities (explorador y contenido) ven exactamente el mismo estado.
 */
class ExplorerController(private val container: AppContainer) {

    /**
     * Ámbito propio app-scope: el explorador debe sobrevivir a la rotación/recarga
     * de la activity y ser accesible desde la activity de contenido (el mando puede
     * llegar a cualquiera de las dos ventanas, según qué display tenga el foco).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settings = container.settings
    private val engine = container.mediaEngine
    private val audioFocus = AudioFocus(container.app)

    private val _state = MutableStateFlow(
        ExplorerUiState(
            view = settings.view,
            sortKey = settings.sortKey,
            sortAscending = settings.sortAscending,
            galleryColumns = settings.galleryColumns,
        ),
    )
    val state: StateFlow<ExplorerUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ExplorerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ExplorerEvent> = _events.asSharedFlow()

    /** Elementos tal cual los da el origen; las vistas/orden se aplican sobre esto. */
    private var rawEntries: List<FsEntry> = emptyList()

    private var listJob: Job? = null
    private var previewJob: Job? = null
    private var factsJob: kotlinx.coroutines.Job? = null
    private var externalJob: kotlinx.coroutines.Job? = null
    private var pendingExternal: Triple<FsEntry, Uri, String>? = null

    /** Dev/smoke: abrir por nombre (`--es open_name …`). */
    private var pendingOpenName: String? = null

    /** Texto localizado (el idioma lo aplica la activity, ver `LocaleConfig`). */
    private fun str(id: Int, vararg args: Any): String = container.localizedContext().getString(id, *args)

    /** Etiqueta localizada de una acción del mando. */
    private fun actionLabel(action: InputAction): String = when (action) {
        InputAction.UP -> str(dev.huginn.R.string.action_up)
        InputAction.DOWN -> str(dev.huginn.R.string.action_down)
        InputAction.LEFT -> str(dev.huginn.R.string.action_left)
        InputAction.RIGHT -> str(dev.huginn.R.string.action_right)
        InputAction.ACCEPT -> str(dev.huginn.R.string.action_accept)
        InputAction.BACK -> str(dev.huginn.R.string.action_back)
        InputAction.PLAY_PAUSE -> str(dev.huginn.R.string.action_play_pause)
        InputAction.TOGGLE_CONTROLS -> str(dev.huginn.R.string.action_toggle_controls)
        InputAction.SEEK_BACK -> str(dev.huginn.R.string.action_seek_back)
        InputAction.SEEK_FORWARD -> str(dev.huginn.R.string.action_seek_forward)
        InputAction.PREV_ITEM -> str(dev.huginn.R.string.action_prev)
        InputAction.NEXT_ITEM -> str(dev.huginn.R.string.action_next)
        InputAction.TOGGLE_FOCUS -> str(dev.huginn.R.string.action_focus)
        InputAction.MENU -> str(dev.huginn.R.string.action_menu)
    }

    /** Etiqueta localizada de un criterio de orden. */
    private fun sortKeyLabel(key: SortKey): String = when (key) {
        SortKey.NAME -> str(dev.huginn.R.string.sort_name)
        SortKey.DATE -> str(dev.huginn.R.string.sort_date)
        SortKey.SIZE -> str(dev.huginn.R.string.sort_size)
        SortKey.TYPE -> str(dev.huginn.R.string.sort_type)
    }

    /** Etiqueta completa «Nombre · asc» con el idioma activo. */
    private fun currentSortLabel(): String {
        val direction = if (settings.sortAscending) {
            str(dev.huginn.R.string.sort_asc)
        } else {
            str(dev.huginn.R.string.sort_desc)
        }
        return "${sortKeyLabel(settings.sortKey)} · $direction"
    }

    /** Dev/smoke: búsqueda pendiente de que termine la carga inicial. */
    private var pendingSearchTerm: String? = null


    /** M5: búsqueda local. */
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchBasePath: FsPath? = null
    /** Vista que se aplicará en la próxima carga (atajo de desarrollo `--es view`). */
    private var pendingView: ExplorerView? = null
    private var pendingSheet: String? = null
    private var pendingSort: Pair<SortKey, Boolean>? = null
    private var pendingPlayFirstMedia = false
    private var pendingSelect: Int? = null
    private var pendingAccept = false

    private var lyricsJob: Job? = null
    private var controlsJob: Job? = null

    private var viewerImages: List<FsEntry> = emptyList()
    private var viewerIndex: Int = 0

    init {
        scope.launch {
            engine.events.collect { event ->
                // Al terminar el vídeo se enciende sola la pantalla inferior.
                if (event is PlaybackEvent.EndReached && _state.value.bottomScreenOff) {
                    _state.update { it.copy(bottomScreenOff = false) }
                }
            }
        }
        scope.launch {
            engine.state.collect { playback ->
                val playing = _state.value.topPanel as? TopPanelState.Playing ?: return@collect
                _state.update {
                    it.copy(
                        topPanel = playing.copy(
                            playing = playback.playing,
                            positionMs = playback.positionMs,
                            lengthMs = maxOf(playback.lengthMs, playing.lengthMs),
                        ),
                    )
                }
            }
        }
        loadRoots()
    }

    // ---------------------------------------------------------------- navegación

    fun loadRoots() {
        listJob?.cancel()
        closePlaybackIfNeeded(notifyEngine = true)
        rawEntries = emptyList()
        val items = buildRootItems()
        _state.update {
            it.copy(
                location = FsPath.Roots,
                title = "Huginn",
                subtitle = str(dev.huginn.R.string.source_home),
                items = items,
                selectedIndex = -1,
                loading = false,
                error = null,
                notice = null,
                sheet = null,
                trail = emptyList(),
                view = ExplorerView.LIST,
                galleryEligible = false,
                sortLabel = currentSortLabel(),
            )
        }
        showDirectoryInfo(FsPath.Roots, items)
        schedulePreview(items.firstOrNull())
    }

    /** Dev/smoke: búsqueda local; espera al final de la carga si aún está en curso. */
    fun requestSearch(term: String) {
        scope.launch {
            while (_state.value.loading) delay(50)
            delay(150)
            openSearch()
            updateSearchTerm(term)
        }
    }

    /** Dev/smoke: selecciona y abre el primer elemento cuyo nombre coincide. */
    fun requestOpenByName(name: String) {
        pendingOpenName = name
    }

    fun refresh() {
        val virtual = _state.value.virtual
        if (virtual == VirtualView.SEARCH) {
            val term = (_state.value.topPanel as? TopPanelState.SearchForm)?.term.orEmpty()
            updateSearchTerm(term)
            return
        }
        virtual?.let {
            openVirtual(it)
            return
        }
        if (_state.value.smbSettings) {
            openSmbSettings()
            return
        }
        val location = _state.value.location
        if (location is FsPath.Roots) {
            loadRoots()
        } else {
            _state.update { it.copy(topPanel = TopPanelState.DirectoryInfo(title = it.title, path = pathText(location), directories = 0, files = 0)) }
            loadLocation(location, pushTrail = false)
        }
    }

    private fun loadLocation(location: FsPath, pushTrail: Boolean, trailName: String? = null) {
        listJob?.cancel()
        // La reproducción (audio o vídeo) sigue al navegar: el mini-player permite
        // controlarla. Solo se cierra el visor de imagen, que no tiene mini-player.
        if (_state.value.topPanel is TopPanelState.ImageViewer) closePlaybackIfNeeded(notifyEngine = true)
        listJob = scope.launch {
            _state.update {
                it.copy(
                    location = location,
                    loading = true,
                    error = null,
                    notice = null,
                    sheet = null,
                    virtual = null,
                    smbSettings = false,
                    emptyHint = null,
                )
            }
            try {
                val entries = container.sourceFor(location).list(location)
                rawEntries = entries
                val trail = if (pushTrail) {
                    _state.value.trail + Breadcrumb(trailName ?: displayTitle(location), location)
                } else {
                    _state.value.trail
                }
                _state.update { it.copy(trail = trail, loading = false) }
                applyViewAndSort(firstLoad = true)
            } catch (e: IOException) {
                Log.w(TAG, "error listando $location", e)
                // SmbSource lanza SmbFailure con el mensaje ya traducido (sin detalles
                // internos ni credenciales).
                val message = when (e) {
                    is SmbFailure -> e.resId?.let { str(it) } ?: e.message.orEmpty()
                    else -> e.message.orEmpty()
                }.ifBlank { str(dev.huginn.R.string.notice_open_location_failed) }
                fail(message)
            } catch (e: Exception) {
                Log.e(TAG, "error inesperado listando $location", e)
                fail(str(dev.huginn.R.string.notice_unexpected, e.message ?: e::class.simpleName.orEmpty()))
            }
        }
    }

    /** Recalcula items según vista y orden, ajusta la selección y refresca la ficha. */
    private fun applyViewAndSort(firstLoad: Boolean) {
        val current = _state.value
        val eligible = ExplorerLogic.galleryAvailable(rawEntries)
        // Al entrar en una carpeta manda la vista preferida (persistida); dentro de
        // la misma carpeta se mantiene la vista actual aunque no se pudiera usar.
        var view = pendingView?.also { pendingView = null; settings.view = it }
            ?: if (firstLoad) settings.view else current.view
        var notice = current.notice
        if (view == ExplorerView.GALLERY && !eligible) {
            view = ExplorerView.LIST
            notice = str(dev.huginn.R.string.notice_gallery_only)
        }

        val sorted = ExplorerLogic.sort(rawEntries, current.sortKey, current.sortDirection)
        // Lista/grid muestran también los ocultos, pero no la basura de sistema
        // (Thumbs.db…), que no aporta nada y se podía seleccionar por error.
        val sortedItems = when (view) {
            ExplorerView.GALLERY -> ExplorerLogic.galleryEntries(sorted)
            else -> sorted.filterNot(ExplorerLogic::isSystemJunk)
        }
        val items = sortedItems.map { ExplorerItem.FileItem(it) }
        val selected = if (firstLoad) {
            -1 // al entrar no se preselecciona nada: arriba va la ficha de la carpeta
        } else {
            current.selectedIndex.coerceIn(-1, maxOf(-1, items.lastIndex))
        }

        _state.update {
            it.copy(
                items = items,
                selectedIndex = selected,
                view = view,
                galleryEligible = eligible,
                sortLabel = currentSortLabel(),
                notice = notice,
                title = if (it.location is FsPath.Roots) it.title else {
                    it.trail.lastOrNull()?.name ?: displayTitle(it.location)
                },
                subtitle = if (it.location is FsPath.Roots) str(dev.huginn.R.string.source_home) else pathText(it.location),
            )
        }
        // Si hay algo reproduciendo/visor, la superior sigue mostrando el contenido
        // (el mini-player permite controlarlo); cambiar de vista no lo cierra.
        val showingContent = _state.value.topPanel is TopPanelState.Playing ||
            _state.value.topPanel is TopPanelState.ImageViewer
        if (!showingContent) {
            showDirectoryInfo(_state.value.location, items)
            // Al abrir carpeta/cambiar de vista la superior muestra la ficha del
            // directorio (mockup 01); la ficha del elemento llega al mover la selección.
            if (!firstLoad && selected >= 0) schedulePreview(items.getOrNull(selected))
        }
        applyPendingDevActions()
    }

    /** Atajos de desarrollo que necesitan los datos ya cargados (smoke tests). */
    private fun applyPendingDevActions() {
        pendingSort?.let { (key, ascending) ->
            pendingSort = null
            settings.sortKey = key
            settings.sortAscending = ascending
            _state.update { it.copy(sortKey = key, sortAscending = ascending) }
            if (_state.value.location !is FsPath.Roots) applyViewAndSort(firstLoad = false) else refreshDirectoryTopPanel()
        }
        pendingSelect?.let { index ->
            pendingSelect = null
            selectIndex(index)
        }
        if (pendingAccept) {
            pendingAccept = false
            accept()
        }
        pendingSearchTerm?.let { term ->
            pendingSearchTerm = null
            openSearch()
            if (_state.value.virtual == VirtualView.SEARCH) updateSearchTerm(term)
        }
        pendingOpenName?.let { name ->
            pendingOpenName = null
            val index = rawEntries.indexOfFirst { it.name == name }
                .takeIf { it >= 0 }
                ?: rawEntries.indexOfFirst { it.name.startsWith(name, ignoreCase = true) }
            if (index >= 0) {
                val item = _state.value.items.getOrNull(index)
                if (item != null) {
                    selectIndex(index)
                    accept()
                }
            } else {
                Log.w(TAG, "open_name: no encontrado «$name»")
            }
        }
        if (pendingPlayFirstMedia) {
            pendingPlayFirstMedia = false
            rawEntries.firstOrNull { entry ->
                !entry.isDirectory && (entry.mediaKind == MediaKind.AUDIO || entry.mediaKind == MediaKind.VIDEO)
            }?.let { entry ->
                playEntry(entry)
                if (audioBackFlow) {
                    audioBackFlow = false
                    requestAudioBackFlow()
                }
                if (audioCycleCount > 0) {
                    val times = audioCycleCount
                    audioCycleCount = 0
                    scope.launch {
                        repeat(times) {
                            delay(2500)
                            Log.i(TAG, "dev audio: siguiente canción")
                            stepMedia(1)
                        }
                    }
                }
                if (cycleViewAfterPlay) {
                    cycleViewAfterPlay = false
                    scope.launch {
                        delay(2000)
                        cycleView()
                    }
                }
            }
        }
        pendingSheet?.let { target ->
            pendingSheet = null
            when (target) {
                "menu" -> openMoreSheet()
                "sort" -> openSortSheet()
                "track" -> openTrackMenu()
            }
        }
    }

    /** Atajo de desarrollo: hoja que se abrirá al terminar la próxima carga. */
    fun requestSheet(target: String?) {
        pendingSheet = target
    }

    /**
     * Atajo de desarrollo para reproducir el flujo del visor de imagen sin depender
     * del mando: abre la imagen [openIndex], espera y pulsa A sobre [nextIndex].
     */
    fun requestViewerFlow(openIndex: Int, nextIndex: Int) {
        scope.launch {
            delay(400)
            selectIndex(openIndex)
            accept()
            delay(700)
            Log.i(TAG, "dev viewer: antes de A top=${_state.value.topPanel::class.simpleName} sel=${_state.value.selectedIndex}")
            selectIndex(nextIndex)
            accept()
        }
    }

    /** Atajo de desarrollo: seleccionar un índice al terminar la próxima carga. */
    fun requestSelect(index: Int?, accept: Boolean = false) {
        pendingSelect = index
        pendingAccept = accept
    }

    /** Atajo de desarrollo: reproducir el primer audio/vídeo de la próxima carga. */
    fun requestPlayFirstMedia() {
        pendingPlayFirstMedia = true
    }

    /**
     * Atajo de desarrollo: tras empezar a reproducir el primer audio, salta de
     * canción [count] veces cada 2,5 s (prueba de que el audio no se pierde).
     */
    fun requestAudioCycle(count: Int) {
        audioCycleCount = count
    }

    private var audioCycleCount = 0

    /**
     * Flujo de prueba: reproducir → BACK (debe parar Y navegar) → reproducir otra vez
     * (el audio debe seguir funcionando). Dev de diagnóstico.
     */
    fun requestAudioBackFlow() {
        // Se guarda el elemento ANTES de atrás: al navegar, rawEntries cambia.
        val entry = rawEntries.firstOrNull { candidate ->
            !candidate.isDirectory &&
                (candidate.mediaKind == MediaKind.AUDIO || candidate.mediaKind == MediaKind.VIDEO)
        }
        scope.launch {
            delay(2500)
            Log.i(TAG, "dev back-flow: antes trail=${_state.value.trail.size} top=${_state.value.topPanel::class.simpleName}")
            back()
            delay(1200)
            Log.i(TAG, "dev back-flow: después trail=${_state.value.trail.size} top=${_state.value.topPanel::class.simpleName}")
            entry?.let {
                Log.i(TAG, "dev back-flow: reproduciendo otra vez «${it.name}»")
                playEntry(it)
            }
        }
    }

    /** Atajo de desarrollo: cambiar de vista 2 s después de empezar a reproducir. */
    fun requestCycleViewAfterPlay() {
        cycleViewAfterPlay = true
    }

    /** Atajo de desarrollo: flujo reproducción → atrás → reproducción. */
    fun requestAudioBackFlowFlag() {
        audioBackFlow = true
    }

    private var cycleViewAfterPlay = false
    private var audioBackFlow = false

    /** Atajo de desarrollo: orden a aplicar al terminar la próxima carga. */
    fun requestSort(key: SortKey?, ascending: Boolean?) {
        val resolvedKey = key ?: return
        pendingSort = resolvedKey to (ascending ?: true)
    }

    /** Volver: hoja abierta → ciérrala; visor → ciérralo; carpeta → sube; raíz → salir. */
    fun back(): Boolean {
        val st = _state.value
        if (st.topPanel is TopPanelState.ExternalDownload) {
            cancelExternalPreparation()
            return true
        }
        if (st.topPanel is TopPanelState.ServerForm) {
            cancelServerForm()
            return true
        }
        if (st.smbSettings) {
            closeSmbSettings()
            return true
        }
        if (st.virtual != null) {
            searchJob?.cancel()
            loadRoots()
            return true
        }
        // Con un error en pantalla, B vuelve a la raíz en vez de cerrar la app.
        if (st.error != null) {
            loadRoots()
            return true
        }
        if (st.sheet != null) {
            closeSheet()
            return true
        }
        val top = st.topPanel
        // El visor de imagen se cierra con B (se queda en la carpeta).
        if (top is TopPanelState.ImageViewer) {
            closePlaybackIfNeeded(notifyEngine = true)
            return true
        }
        // Reproduciendo (audio o vídeo): B hace las DOS cosas, parar y navegar.
        if (top is TopPanelState.Playing) {
            closePlaybackIfNeeded(notifyEngine = true)
        }
        // Desde una ubicación sin migas (p. ej. carga/error de un servidor) se vuelve
        // a la raíz en vez de cerrar la app, también mientras está cargando.
        if (st.trail.isEmpty() && st.location !is FsPath.Roots) {
            loadRoots()
            return true
        }
        if (st.trail.isEmpty()) return false
        val trail = st.trail.dropLast(1)
        if (trail.isEmpty()) {
            loadRoots()
        } else {
            _state.update { it.copy(trail = trail) }
            loadLocation(trail.last().path, pushTrail = false)
        }
        return true
    }

    /** Botón «subir» de la app bar: un nivel (sin cerrar la app en la raíz). */
    fun goUp() {
        if (_state.value.trail.isNotEmpty()) back()
    }

    /** Migas de pan: índices 0..n-1 de la trail; raíz = loadRoots. */
    fun navigateToBreadcrumb(trailIndex: Int) {
        val st = _state.value
        val trail = st.trail.take(trailIndex + 1)
        if (trail.isEmpty()) {
            loadRoots()
        } else {
            _state.update { it.copy(trail = trail) }
            loadLocation(trail.last().path, pushTrail = false)
        }
    }

    // ---------------------------------------------------------------- selección

    fun selectIndex(index: Int) {
        val st = _state.value
        if (st.items.isEmpty()) return
        val clamped = index.coerceIn(0, st.items.lastIndex)
        if (clamped == st.selectedIndex) return
        _state.update { it.copy(selectedIndex = clamped) }
        // Ficha inmediata (con «Generando…» si es imagen) para no dejar la preview
        // del elemento anterior mientras llega la nueva; la carga real va tras el
        // debounce de 150 ms.
        publishEntryInfo(st.items[clamped], loading = true)
        schedulePreview(st.items[clamped])
        scheduleFacts(st.items[clamped])
    }

    fun moveSelection(delta: Int) {
        val st = _state.value
        if (st.items.isEmpty()) return
        val current = st.selectedIndex
        val target = when {
            current < 0 && delta > 0 -> 0
            current < 0 && delta < 0 -> st.items.lastIndex
            else -> (current + delta).coerceIn(0, st.items.lastIndex)
        }
        selectIndex(target)
    }

    private fun moveGrid(move: ExplorerLogic.GridMove) {
        val st = _state.value
        if (st.items.isEmpty()) return
        if (st.selectedIndex < 0) {
            selectIndex(0)
            return
        }
        val target = ExplorerLogic.moveInGrid(
            index = st.selectedIndex,
            move = move,
            count = st.items.size,
            columns = st.columns,
        )
        selectIndex(target)
    }

    fun accept() {
        val st = _state.value
        // Con el formulario SMB abierto en la superior, el toque en la lista no navega.
        if (st.topPanel is TopPanelState.ServerForm) return
        // Con un error en pantalla, A (o el toque) reintenta la última ubicación.
        if (st.error != null) {
            refresh()
            return
        }
        if (st.sheet != null) {
            acceptSheet()
            return
        }
        if (st.selectedIndex < 0) return
        when (val item = st.selectedItem) {
            is ExplorerItem.ActionItem -> acceptAction(item)
            is ExplorerItem.FileItem -> acceptFile(item.entry)
            null -> Unit
        }
    }

    private fun acceptAction(item: ExplorerItem.ActionItem) {
        when (item.action) {
            RootAction.PICK_LOCAL_TREE -> emit(ExplorerEvent.LaunchSafPicker)
            RootAction.OPEN_SMB -> openSmbShare()
            RootAction.OPEN_SMB_SERVER -> item.serverId?.let { openSmbServer(it) }
            RootAction.SMB_SETTINGS -> openSmbSettings()
            RootAction.RECENTS -> openVirtual(VirtualView.RECENTS)
            RootAction.FAVORITES -> openVirtual(VirtualView.FAVORITES)
            RootAction.SETTINGS_CLEAR_CACHE -> clearCaches()
            RootAction.SETTINGS_ABOUT -> notify(str(dev.huginn.R.string.about_text))
            RootAction.SETTINGS_HOME -> openVirtual(VirtualView.SETTINGS)
            RootAction.KEYMAP_SETTINGS -> openVirtual(VirtualView.KEYMAP)
            RootAction.LANGUAGE_SETTINGS -> openVirtual(VirtualView.LANGUAGE)
            RootAction.LANGUAGE_EN -> applyLanguage("en")
            RootAction.LANGUAGE_ES -> applyLanguage("es")
            RootAction.KEYMAP_LEARN -> item.keyAction?.let { startLearnKey(it) }
            RootAction.KEYMAP_RESET -> {
                settings.clearKeyOverrides()
                notify(str(dev.huginn.R.string.keymap_restored))
                openVirtual(VirtualView.KEYMAP)
            }
            RootAction.SMB_SERVER_ADD -> openServerForm(null)
            RootAction.SMB_SERVER_EDIT -> item.serverId?.let { openServerForm(it) }
            RootAction.TEST_VIDEO -> playTestVideo()
            RootAction.TEST_IMAGE -> showTestImage()
            RootAction.TEST_EXTERNAL_VIDEO -> openTestVideoExternally()
        }
    }

    private fun acceptFile(entry: FsEntry) {
        _state.update { it.copy(notice = null) }
        runCatching { container.placesStore.recordRecent(entry) }
        when {
            entry.isDirectory -> loadLocation(entry.path, pushTrail = true, trailName = entry.name)
            entry.mediaKind == MediaKind.IMAGE -> openImageViewer(entry)
            entry.mediaKind == MediaKind.AUDIO || entry.mediaKind == MediaKind.VIDEO -> playEntry(entry)
            else -> openExternalEntry(entry)
        }
    }

    // ---------------------------------------------------------------- vistas/orden

    fun cycleView() {
        val st = _state.value
        if (st.location is FsPath.Roots) {
            notify(str(dev.huginn.R.string.notice_views_folder_only))
            return
        }
        setView(ExplorerLogic.cycleView(st.view, st.galleryEligible))
    }

    fun setView(view: ExplorerView) {
        val st = _state.value
        if (st.location is FsPath.Roots) return
        if (view == ExplorerView.GALLERY && !st.galleryEligible) {
            notify(str(dev.huginn.R.string.notice_gallery_only))
            return
        }
        settings.view = view
        _state.update { it.copy(view = view, notice = null) }
        applyViewAndSort(firstLoad = true)
    }

    /** Atajo de desarrollo: deja preparada la vista para la próxima carga. */
    fun setPendingView(view: ExplorerView?) {
        pendingView = view
    }

    fun setSort(key: SortKey) {
        settings.sortKey = key
        _state.update { it.copy(sortKey = key) }
        if (_state.value.location !is FsPath.Roots) applyViewAndSort(firstLoad = false)
        refreshDirectoryTopPanel()
    }

    fun setSortDirection(ascending: Boolean) {
        settings.sortAscending = ascending
        _state.update { it.copy(sortAscending = ascending) }
        if (_state.value.location !is FsPath.Roots) applyViewAndSort(firstLoad = false)
        refreshDirectoryTopPanel()
    }

    fun openSortSheet() {
        val st = _state.value
        if (st.location is FsPath.Roots) {
            notify(str(dev.huginn.R.string.notice_sort_folder_only))
            return
        }
        val items = buildList {
            SortKey.entries.forEach { key ->
                add(SheetItemState("sort:${key.name}", sortKeyLabel(key), checked = st.sortKey == key))
            }
            add(SheetItemState("dir:ASC", str(dev.huginn.R.string.sort_ascending), checked = st.sortAscending))
            add(SheetItemState("dir:DESC", str(dev.huginn.R.string.sort_descending), checked = !st.sortAscending))
        }
        val selection = items.indexOfFirst { it.id == "sort:${st.sortKey.name}" }.coerceAtLeast(0)
        _state.update { it.copy(sheet = SheetState(SheetKind.SORT, str(dev.huginn.R.string.sheet_sort), items, selection)) }
    }

    fun openMoreSheet() {
        val st = _state.value
        val viewName = when (st.view) {
            ExplorerView.LIST -> str(dev.huginn.R.string.view_list)
            ExplorerView.GRID -> str(dev.huginn.R.string.view_grid)
            ExplorerView.GALLERY -> str(dev.huginn.R.string.view_gallery)
        }
        val items = listOf(
            SheetItemState("menu:view", str(dev.huginn.R.string.menu_view, viewName)),
            SheetItemState("menu:sort", str(dev.huginn.R.string.menu_sort)),
            SheetItemState("menu:search", str(dev.huginn.R.string.menu_search)),
            SheetItemState("menu:cache", str(dev.huginn.R.string.menu_cache)),
            SheetItemState(
                "menu:fav",
                if (selectedIsFavorite()) str(dev.huginn.R.string.menu_fav_remove) else str(dev.huginn.R.string.menu_fav_add),
            ),
            SheetItemState("menu:settings", str(dev.huginn.R.string.menu_settings)),
            SheetItemState("menu:exit", str(dev.huginn.R.string.menu_exit)),
        )
        _state.update { it.copy(sheet = SheetState(SheetKind.MORE, str(dev.huginn.R.string.sheet_menu), items)) }
    }

    fun closeSheet() {
        pendingExternal = null
        _state.update { it.copy(sheet = null) }
    }

    fun moveSheet(delta: Int) {
        val sheet = _state.value.sheet ?: return
        if (sheet.items.isEmpty()) return
        val selection = (sheet.selection + delta).coerceIn(0, sheet.items.lastIndex)
        _state.update { it.copy(sheet = sheet.copy(selection = selection)) }
    }

    /** Toque en una fila de sheet: selecciona y acepta. */
    fun acceptSheetItem(index: Int) {
        val sheet = _state.value.sheet ?: return
        if (index !in sheet.items.indices) return
        _state.update { it.copy(sheet = sheet.copy(selection = index)) }
        acceptSheet()
    }

    private fun acceptSheet() {
        val sheet = _state.value.sheet ?: return
        val item = sheet.items.getOrNull(sheet.selection) ?: return
        when {
            item.id.startsWith("sort:") -> {
                val key = runCatching { SortKey.valueOf(item.id.removePrefix("sort:")) }.getOrNull()
                closeSheet()
                key?.let(::setSort)
            }

            item.id == "dir:ASC" -> {
                closeSheet()
                setSortDirection(true)
            }

            item.id == "dir:DESC" -> {
                closeSheet()
                setSortDirection(false)
            }

            item.id == "menu:view" -> {
                closeSheet()
                cycleView()
            }

            item.id == "menu:sort" -> {
                closeSheet()
                openSortSheet()
            }

            item.id == "menu:cache" -> {
                closeSheet()
                clearCache()
            }

            item.id == "menu:search" -> {
                closeSheet()
                openSearch()
            }

            item.id == "menu:settings" -> {
                closeSheet()
                openVirtual(VirtualView.SETTINGS)
            }

            item.id == "menu:fav" -> {
                closeSheet()
                toggleFavoriteSelected()
            }

            item.id == "menu:exit" -> {
                closeSheet()
                emit(ExplorerEvent.Finish)
            }

            item.id.startsWith("extapp:") -> {
                val packageName = item.id.removePrefix("extapp:")
                val remember = sheet.items.any { it.id == "extalways" && it.checked }
                pendingExternal?.third?.let { mime ->
                    if (remember) settings.rememberAppFor(mime, packageName)
                }
                launchExternalApp(packageName)
            }

            item.id == "extalways" -> {
                val items = sheet.items.map {
                    if (it.id == "extalways") it.copy(checked = !it.checked) else it
                }
                _state.update { it.copy(sheet = sheet.copy(items = items)) }
            }
        }
    }

    private fun clearCache() {
        scope.launch(Dispatchers.IO) {
            val files = container.app.cacheDir.listFiles().orEmpty()
            var deleted = 0
            files.forEach { file ->
                if (file.deleteRecursively()) deleted++
            }
            Log.i(TAG, "caché limpiada ($deleted entradas)")
            notify(str(dev.huginn.R.string.cache_cleared))
        }
    }

    // ---------------------------------------------------------------- visores

    private fun openImageViewer(entry: FsEntry) {
        Log.i(
            TAG,
            "dev viewer: abrir «${entry.name}» top=${_state.value.topPanel::class.simpleName} " +
                "sel=${_state.value.selectedIndex}",
        )
        val images = _state.value.items
            .filterIsInstance<ExplorerItem.FileItem>()
            .map { it.entry }
            .filter { it.mediaKind == MediaKind.IMAGE }
            .ifEmpty { listOf(entry) }
        val index = images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)

        // Cerrar lo que hubiera ANTES de fijar la lista nueva: closePlaybackIfNeeded
        // vaciaba viewerImages y publishImageViewer se quedaba sin imagen que pintar
        // (síntoma: se cerraba la anterior pero no se abría la nueva).
        closePlaybackIfNeeded(notifyEngine = true)
        viewerImages = images
        viewerIndex = index
        publishImageViewer()
        Log.i(TAG, "dev viewer: publicado top=${_state.value.topPanel::class.simpleName} idx=$viewerIndex")
    }

    private fun publishImageViewer() {
        val entry = viewerImages.getOrNull(viewerIndex) ?: return
        Log.i(TAG, "visor: ${viewerIndex + 1}/${viewerImages.size} «${entry.name}»")
        _state.update {
            it.copy(
                focus = FocusArea.PLAYER,
                topPanel = TopPanelState.ImageViewer(
                    entry = entry,
                    name = entry.name,
                    index = viewerIndex + 1,
                    total = viewerImages.size,
                ),
            )
        }
    }

    private fun stepImage(delta: Int) {
        if (viewerImages.isEmpty()) return
        viewerIndex = (viewerIndex + delta).mod(viewerImages.size)
        syncSelectionToViewer()
        publishImageViewer()
    }

    /** La fila resaltada abajo sigue a la imagen que se está viendo arriba. */
    private fun syncSelectionToViewer() {
        val path = viewerImages.getOrNull(viewerIndex)?.path ?: return
        val index = _state.value.items.indexOfFirst { item ->
            item is ExplorerItem.FileItem && item.entry.path == path
        }
        if (index >= 0) _state.update { it.copy(selectedIndex = index) }
    }

    private fun playEntry(entry: FsEntry) {
        viewerImages = emptyList()
        val source = container.sourceFor(entry.path)
        val uri = runCatching { source.toPlayableUri(entry.path) }.getOrElse {
            fail(str(dev.huginn.R.string.notice_prepare_failed, it.message.orEmpty()))
            return
        }
        _state.update {
            it.copy(
                focus = FocusArea.PLAYER,
                topPanel = TopPanelState.Playing(
                    name = entry.name,
                    isVideo = entry.mediaKind == MediaKind.VIDEO,
                    playing = false,
                    positionMs = 0,
                    lengthMs = 0,
                    uri = uri.toString(),
                ),
            )
        }
        // Foco de audio: si otra app lo pide, se pausa (spec §6).
        audioFocus.request(onLoss = { engine.pause() })
        engine.play(
            uri = uri,
            options = listOf(":network-caching=2000"),
            subtitleSlaves = if (entry.mediaKind == MediaKind.VIDEO) externalSubtitles(entry) else emptyList(),
        )
        if (entry.mediaKind == MediaKind.AUDIO) {
            loadLyrics(entry)
            loadMetadata(entry)
        }
        if (entry.mediaKind == MediaKind.VIDEO) scheduleControlsHide()
    }

    /** Metadatos y carátula del audio por acceso aleatorio (SAF y SMB). */
    private fun loadMetadata(entry: FsEntry) {
        scope.launch {
            val metadata = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val source = container.sourceFor(entry.path)
                val access = runCatching { source.openRandomAccess(entry.path) }.getOrNull()
                    ?: return@withContext null
                runCatching { AudioMetadataReader.read(access) }
                    .also { runCatching { access.close() } }
                    .getOrNull()
            } ?: return@launch
            val top = _state.value.topPanel
            if (top is TopPanelState.Playing && top.name == entry.name) {
                _state.update {
                    it.copy(topPanel = top.copy(metadata = metadata, artwork = metadata.artwork))
                }
                container.mediaFacts.put(
                    entry,
                    MediaFacts(
                        durationMs = metadata.durationMs,
                        title = metadata.title,
                        artist = metadata.artist,
                        album = metadata.album,
                        mimeType = metadata.mimeType,
                    ),
                )
                metadata.artwork?.let { container.thumbnails.cacheArtwork(entry, it) }
                container.notifyFactsUpdated()
            }
        }
    }

    /** Letra junto al audio: `.lrc` sincronizada o `.txt` estática (spec §4.5). */
    private fun loadLyrics(entry: FsEntry) {
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            val source = container.sourceFor(entry.path)
            val siblings = rawEntries.filter { !it.isDirectory }.map { it.name }
            val candidates = LyricsLoader.candidates(entry.name, siblings)
            Log.i(TAG, "letra: candidatos para «${entry.name}» = $candidates")
            val lyrics = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                LyricsLoader.load(entry.name, siblings) { name ->
                    val sibling = rawEntries.firstOrNull { it.name == name } ?: return@load null
                    val stream = runCatching { source.openStream(sibling.path) }
                        .onFailure { Log.w(TAG, "letra: openStream falló", it) }.getOrNull()
                    val bytes = runCatching { stream?.use { it.readBytes() } }
                        .onFailure { Log.w(TAG, "letra: lectura falló", it) }.getOrNull()
                    Log.i(TAG, "letra: leyendo «$name» stream=${stream != null} bytes=${bytes?.size ?: -1}")
                    bytes
                }
                }.onFailure { Log.w(TAG, "letra: fallo cargando", it) }.getOrNull()
            }
            Log.i(TAG, "letra: ${lyrics?.lines?.size ?: 0} líneas, sincronizada=${lyrics?.synced ?: false}")
            val top = _state.value.topPanel
            if (top is TopPanelState.Playing && top.name == entry.name) {
                _state.update { it.copy(topPanel = top.copy(lyrics = lyrics)) }
            }
        }
    }

    /** Subtítulos externos `.srt/.ass/...` que acompañan al vídeo en su carpeta. */
    private fun externalSubtitles(entry: FsEntry): List<String> {
        val source = container.sourceFor(entry.path)
        val siblings = rawEntries.filter { !it.isDirectory }.associateBy { it.name }
        val candidates = SubtitleMatcher.externalFor(entry.name, siblings.keys.toList())
        val uris = candidates.mapNotNull { name ->
            siblings[name]?.let { sibling ->
                runCatching { source.toPlayableUri(sibling.path).toString() }
                    .onFailure { Log.w(TAG, "subtítulo externo: no se pudo preparar «$name»", it) }
                    .getOrNull()
            }
        }
        Log.i(TAG, "subtítulos externos de «${entry.name}»: candidatos=$candidates uris=${uris.size}")
        return uris
    }

    /** Start (o toque en Audio/Subs) abre el menú de pistas en la superior. */
    fun openTrackMenu() {
        val playing = _state.value.topPanel as? TopPanelState.Playing ?: return
        if (!playing.isVideo) return
        val tracks = engine.tracks()
        val subtitles = listOf(MediaTrack(-1, "Desactivado")) + tracks.subtitles
        val audioIndex = tracks.audio.indexOfFirst { it.id == tracks.audioTrackId }.coerceAtLeast(0)
        val subtitleIndex = subtitles.indexOfFirst { it.id == tracks.subtitleTrackId }.coerceAtLeast(0)
        val menu = TrackMenuState(
            audio = tracks.audio,
            subtitles = subtitles,
            audioIndex = audioIndex,
            subtitleIndex = subtitleIndex,
            column = 0,
        )
        _state.update { it.copy(topPanel = playing.copy(trackMenu = menu)) }
    }

    fun closeTrackMenu() {
        val playing = _state.value.topPanel as? TopPanelState.Playing ?: return
        _state.update { it.copy(topPanel = playing.copy(trackMenu = null)) }
    }

    /** Selección (mando o toque) dentro del menú de pistas; se aplica al instante. */
    fun selectTrack(column: Int, index: Int) {
        val playing = _state.value.topPanel as? TopPanelState.Playing ?: return
        val menu = playing.trackMenu ?: return
        val items = if (column == 0) menu.audio else menu.subtitles
        if (index !in items.indices) return
        val updated = menu.withSelection(column, index)
        if (column == 0) {
            engine.selectAudioTrack(items[index].id)
        } else {
            engine.selectSubtitleTrack(items[index].id)
        }
        _state.update { it.copy(topPanel = playing.copy(trackMenu = updated)) }
    }

    private fun handleTrackMenu(action: InputAction): Boolean {
        val playing = _state.value.topPanel as? TopPanelState.Playing ?: return false
        val menu = playing.trackMenu ?: return false
        when (action) {
            InputAction.UP -> selectTrack(menu.column, (menu.selection - 1).coerceAtLeast(0))
            InputAction.DOWN -> selectTrack(menu.column, (menu.selection + 1).coerceAtMost(menu.items.lastIndex))
            InputAction.LEFT -> selectTrack(0, menu.audioIndex)
            InputAction.RIGHT -> selectTrack(1, menu.subtitleIndex)
            InputAction.ACCEPT, InputAction.BACK, InputAction.MENU -> closeTrackMenu()
            else -> Unit
        }
        return true
    }

    /**
     * Apaga/enciende la pantalla inferior mientras se ve un vídeo (botón ⏻ del
     * mini-player). La activity aplica brillo 0 + overlay negro; las teclas siguen
     * controlando el vídeo y un toque la enciende.
     */
    fun toggleBottomScreenOff() {
        val playing = _state.value.topPanel as? TopPanelState.Playing ?: return
        if (!playing.isVideo) return
        _state.update { it.copy(bottomScreenOff = !it.bottomScreenOff) }
    }

    fun wakeBottomScreen() {
        _state.update { it.copy(bottomScreenOff = false) }
    }

    /** La app pasa a segundo plano: pausar (sin stop, que rompe el aout). */
    fun onAppBackgrounded() {
        val playing = _state.value.topPanel as? TopPanelState.Playing ?: return
        if (playing.playing) {
            engine.pause()
            _state.update { it.copy(topPanel = playing.copy(playing = false)) }
        }
    }

    /**
     * Al volver a primer plano: el vídeo se pausó al salir (spec §1.4) y la Surface
     * quedaba en negro hasta pulsar play. Se muestran los controles para que la
     * pantalla superior conserve la ficha y el botón de reanudar (multiresume).
     */
    fun onAppForegrounded() {
        val playing = _state.value.topPanel as? TopPanelState.Playing ?: return
        if (playing.isVideo && !playing.playing) {
            controlsJob?.cancel()
            _state.update { it.copy(topPanel = playing.copy(controlsVisible = true)) }
        }
    }

    /** Cierra el visor/reproductor (botón ✕ del overlay de la pantalla superior). */
    fun closeViewer() {
        closePlaybackIfNeeded(notifyEngine = true)
    }

    /** Y / toque: mostrar u ocultar los controles del visor. */
    fun toggleControls() {
        val playing = _state.value.topPanel as? TopPanelState.Playing ?: return
        if (!playing.isVideo) return // audio: controles fijos (spec §4.5)
        controlsJob?.cancel()
        val visible = !playing.controlsVisible
        _state.update { it.copy(topPanel = playing.copy(controlsVisible = visible)) }
        if (visible) scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        if (settings.controlsAlwaysVisible) {
            val playing = _state.value.topPanel as? TopPanelState.Playing ?: return
            if (!playing.controlsVisible) {
                _state.update { it.copy(topPanel = playing.copy(controlsVisible = true)) }
            }
            return
        }
        controlsJob?.cancel()
        controlsJob = scope.launch {
            delay(CONTROLS_HIDE_MS)
            val playing = _state.value.topPanel as? TopPanelState.Playing ?: return@launch
            if (playing.isVideo) {
                _state.update { it.copy(topPanel = playing.copy(controlsVisible = false)) }
            }
        }
    }

    /** Devuelve true si había algo abierto en la pantalla superior y se ha cerrado. */
    private fun closePlaybackIfNeeded(notifyEngine: Boolean): Boolean {
        val top = _state.value.topPanel
        val wasOpen = top is TopPanelState.ImageViewer || top is TopPanelState.Playing
        if (!wasOpen) return false
        if (notifyEngine) {
            if (top is TopPanelState.Playing) engine.stop()
            audioFocus.abandon()
            viewerImages = emptyList()
        }
        _state.update {
            it.copy(
                focus = FocusArea.EXPLORER,
                bottomScreenOff = false,
                topPanel = entryInfoFor(_state.value.selectedItem),
            )
        }
        return true
    }

    // ------------------------------------------------------- M5: externos

    /**
     * Prepara un archivo no multimedia y lo abre arriba: SAF ya da un `content://`;
     * SMB se descarga a caché con progreso (cancelable con B) y se comparte por
     * FileProvider.
     */
    private fun openExternalEntry(entry: FsEntry) {
        externalJob?.cancel()
        val mime = entry.mimeType ?: MimeTypes.guess(entry.name) ?: "*/*"
        externalJob = scope.launch {
            _state.update { it.copy(topPanel = TopPanelState.ExternalDownload(entry.name, null)) }
            try {
                var lastUpdate = 0L
                val uri = container.externalCache.prepare(entry) { progress ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= PROGRESS_THROTTLE_MS || progress >= 1f) {
                        lastUpdate = now
                        _state.update {
                            it.copy(topPanel = TopPanelState.ExternalDownload(entry.name, progress))
                        }
                    }
                }
                if (uri == null) {
                    fail(str(dev.huginn.R.string.notice_prepare_named, entry.name))
                    return@launch
                }
                _state.update { it.copy(topPanel = entryInfoFor(_state.value.selectedItem)) }
                // Nunca lanzar a ciegas: si hay app recordada por tipo se usa; si no,
                // se pregunta SIEMPRE (selector «Abrir con» navegable con mando).
                val preferred = settings.preferredAppFor(mime)
                if (preferred != null) {
                    emit(ExplorerEvent.OpenExternal(entry, uri, mime, preferred))
                } else {
                    emit(ExplorerEvent.QueryExternalApps(entry, uri, mime))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(topPanel = entryInfoFor(_state.value.selectedItem)) }
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo preparar el externo ${entry.name}", e)
                fail(str(dev.huginn.R.string.notice_prepare_named_reason, entry.name, e.message.orEmpty()))
            }
        }
    }

    // --------------------------------------------------------- M5: búsqueda

    /** Búsqueda local recursiva (SAF) dentro de la carpeta actual o de la configurada. */
    fun openSearch() {
        val base = searchBasePathOf() ?: run {
            notify(str(dev.huginn.R.string.search_local_only))
            return
        }
        searchBasePath = base
        searchJob?.cancel()
        _state.update {
            it.copy(
                virtual = VirtualView.SEARCH,
                smbSettings = false,
                location = FsPath.Roots,
                title = str(dev.huginn.R.string.search_title),
                subtitle = str(dev.huginn.R.string.search_in, pathText(base)),
                items = emptyList(),
                selectedIndex = -1,
                loading = false,
                error = null,
                notice = null,
                sheet = null,
                trail = listOf(Breadcrumb("Huginn", FsPath.Roots)),
                view = ExplorerView.LIST,
                galleryEligible = false,
                topPanel = TopPanelState.SearchForm(scope = pathText(base)),
                emptyHint = str(dev.huginn.R.string.empty_search_hint),
            )
        }
    }

    private fun searchBasePathOf(): FsPath? =
        (_state.value.location as? FsPath.Local)
            ?: settings.localTreeUri?.let { FsPath.Local(it) }

    /** Texto de búsqueda: debounce, exploración recursiva acotada y resultados. */
    fun updateSearchTerm(term: String) {
        val form = _state.value.topPanel as? TopPanelState.SearchForm ?: return
        _state.update { it.copy(topPanel = form.copy(term = term)) }
        searchJob?.cancel()
        val needle = term.trim()
        if (needle.length < 2) {
            _state.update {
                it.copy(
                    items = emptyList(),
                    selectedIndex = -1,
                    topPanel = (it.topPanel as? TopPanelState.SearchForm)
                        ?.copy(searching = false, results = 0)
                        ?: it.topPanel,
                )
            }
            return
        }
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val base = searchBasePath ?: return@launch
            _state.update { st ->
                (st.topPanel as? TopPanelState.SearchForm)
                    ?.let { f -> st.copy(topPanel = f.copy(searching = true)) }
                    ?: st
            }
            val matches = runCatching {
                LocalSearch.find(base, container.sourceFor(base), needle)
            }.getOrDefault(emptyList())
            val items = matches.map { ExplorerItem.FileItem(it) }
            _state.update { st ->
                (st.topPanel as? TopPanelState.SearchForm)?.let { f ->
                    st.copy(
                        items = items,
                        selectedIndex = if (items.isEmpty()) -1 else 0,
                        topPanel = f.copy(
                            searching = false,
                            results = items.size,
                            hint = if (items.isEmpty()) {
                                str(dev.huginn.R.string.search_limit_hint, LocalSearch.Limits().maxEntries)
                            } else {
                                str(dev.huginn.R.string.search_hint)
                            },
                        ),
                    )
                } ?: st
            }
        }
    }

    // ------------------------------------------------------ M5: mapeo de botones

    private fun languageItems(): List<ExplorerItem> {
        val current = settings.language
        fun item(action: RootAction, label: String, tag: String) = ExplorerItem.ActionItem(
            action = action,
            title = label,
            subtitle = if (current == tag) str(dev.huginn.R.string.current) else "",
        )
        return listOf(
            item(RootAction.LANGUAGE_EN, "English", "en"),
            item(RootAction.LANGUAGE_ES, "Español", "es"),
        )
    }

    private fun applyLanguage(tag: String) {
        settings.language = tag
        container.invalidateLocalizedStrings()
        // Reconstruye la pantalla actual con los textos del idioma nuevo antes de
        // recrear la activity (que aplica el locale a Compose).
        refresh()
        notify(str(dev.huginn.R.string.language_changed))
        emit(ExplorerEvent.ApplyLanguage)
    }

    private fun keymapItems(): List<ExplorerItem> {
        val overrides = settings.keyOverrides()
        val items = InputAction.values().map { action ->
            val code = overrides[action] ?: InputRouter.defaultKey(action) ?: 0
            ExplorerItem.ActionItem(
                action = RootAction.KEYMAP_LEARN,
                title = actionLabel(action),
                subtitle = InputRouter.keyName(code),
                keyAction = action,
            )
        }
        return items + ExplorerItem.ActionItem(
            RootAction.KEYMAP_RESET,
            "Restaurar mapeo por defecto",
            str(dev.huginn.R.string.keymap_reset_sub),
        )
    }

    fun isLearningKey(): Boolean = _state.value.topPanel is TopPanelState.LearnKey

    fun startLearnKey(action: InputAction) {
        val code = settings.keyOverrides()[action] ?: InputRouter.defaultKey(action) ?: 0
        _state.update {
            it.copy(
                topPanel = TopPanelState.LearnKey(
                    action = action,
                    actionLabel = actionLabel(action),
                    currentKey = InputRouter.keyName(code),
                ),
            )
        }
    }

    /** La activity entrega aquí el keycode pulsado durante el aprendizaje. */
    fun assignLearnedKey(keyCode: Int) {
        val form = _state.value.topPanel as? TopPanelState.LearnKey ?: return
        if (keyCode <= 0) return
        settings.setKeyOverride(form.action, keyCode)
        notify(str(dev.huginn.R.string.keymap_assigned, form.actionLabel, InputRouter.keyName(keyCode)))
        openVirtual(VirtualView.KEYMAP)
    }

    fun cancelLearnKey() {
        openVirtual(VirtualView.KEYMAP)
    }

    // ------------------------------------------------------- M5: virtuales

    private fun selectedIsFavorite(): Boolean {
        val entry = (_state.value.selectedItem as? ExplorerItem.FileItem)?.entry ?: return false
        return runCatching { container.placesStore.isFavorite(entry) }.getOrDefault(false)
    }

    private fun toggleFavoriteSelected() {
        val entry = (_state.value.selectedItem as? ExplorerItem.FileItem)?.entry ?: run {
            notify(str(dev.huginn.R.string.notice_select_to_favorite))
            return
        }
        val nowFavorite = runCatching { container.placesStore.toggleFavorite(entry) }.getOrDefault(false)
        notify(
            if (nowFavorite) str(dev.huginn.R.string.notice_favorite_added, entry.name)
            else str(dev.huginn.R.string.notice_favorite_removed, entry.name),
        )
        _state.value.virtual?.let { openVirtual(it) }
    }

    /** Abre una lista virtual (recientes, favoritos o ajustes). */
    fun openVirtual(view: VirtualView) {
        val items = when (view) {
            VirtualView.RECENTS -> container.placesStore.recents().map { itemFor(it) }
            VirtualView.FAVORITES -> container.placesStore.favorites().map { itemFor(it) }
            VirtualView.SETTINGS -> settingsItems()
            VirtualView.SEARCH -> emptyList()
            VirtualView.KEYMAP -> keymapItems()
            VirtualView.LANGUAGE -> languageItems()
        }
        val title = when (view) {
            VirtualView.RECENTS -> str(dev.huginn.R.string.recents)
            VirtualView.FAVORITES -> str(dev.huginn.R.string.favorites)
            VirtualView.SETTINGS -> str(dev.huginn.R.string.settings)
            VirtualView.SEARCH -> str(dev.huginn.R.string.search_title)
            VirtualView.KEYMAP -> str(dev.huginn.R.string.keymap_title)
            VirtualView.LANGUAGE -> str(dev.huginn.R.string.language_title)
        }
        val subtitle = when (view) {
            VirtualView.RECENTS -> str(dev.huginn.R.string.recents_sub, items.size)
            VirtualView.FAVORITES -> str(dev.huginn.R.string.favorites_sub, items.size)
            VirtualView.SETTINGS -> str(dev.huginn.R.string.about_text)
            VirtualView.SEARCH -> str(dev.huginn.R.string.search_local_scope)
            VirtualView.KEYMAP -> str(dev.huginn.R.string.keymap_sub)
            VirtualView.LANGUAGE -> "English / Español"
        }
        _state.update {
            it.copy(
                virtual = view,
                smbSettings = false,
                location = FsPath.Roots,
                title = title,
                subtitle = subtitle,
                items = items,
                selectedIndex = when {
                    items.isEmpty() -> -1
                    view == VirtualView.LANGUAGE -> if (settings.language == "es") 1 else 0
                    else -> 0
                },
                loading = false,
                error = null,
                notice = null,
                sheet = null,
                trail = listOf(Breadcrumb("Huginn", FsPath.Roots)),
                view = ExplorerView.LIST,
                galleryEligible = false,
                emptyHint = when (view) {
                    VirtualView.RECENTS -> str(dev.huginn.R.string.empty_recents_hint)
                    VirtualView.FAVORITES -> str(dev.huginn.R.string.empty_favorites_hint)
                    VirtualView.SEARCH -> str(dev.huginn.R.string.empty_search_hint)
                    else -> null
                },
                topPanel = TopPanelState.DirectoryInfo(
                    title = title,
                    path = subtitle,
                    directories = 0,
                    files = 0,
                    readOnly = true,
                    chips = listOf(subtitle),
                    hint = when (view) {
                        VirtualView.SETTINGS -> str(dev.huginn.R.string.hint_flat_accept_back)
                        VirtualView.KEYMAP -> str(dev.huginn.R.string.keymap_hint)
                        else -> str(dev.huginn.R.string.hint_virtual_list)
                    },
                ),
            )
        }
    }

    private fun settingsItems(): List<ExplorerItem> = listOf(
        ExplorerItem.ActionItem(
            RootAction.SETTINGS_CLEAR_CACHE,
            str(dev.huginn.R.string.settings_clear_cache),
            str(dev.huginn.R.string.settings_clear_cache_sub),
        ),
        ExplorerItem.ActionItem(
            RootAction.SMB_SETTINGS,
            str(dev.huginn.R.string.settings_smb),
            str(dev.huginn.R.string.settings_smb_sub),
        ),
        ExplorerItem.ActionItem(
            RootAction.KEYMAP_SETTINGS,
            str(dev.huginn.R.string.settings_keymap),
            str(dev.huginn.R.string.settings_keymap_sub),
        ),
        ExplorerItem.ActionItem(
            RootAction.LANGUAGE_SETTINGS,
            str(dev.huginn.R.string.settings_language),
            str(dev.huginn.R.string.settings_language_sub),
        ),
        ExplorerItem.ActionItem(
            RootAction.SETTINGS_ABOUT,
            str(dev.huginn.R.string.settings_about),
            str(dev.huginn.R.string.settings_about_sub),
        ),
    )

    private fun itemFor(ref: PathRef): ExplorerItem.FileItem = ExplorerItem.FileItem(
        FsEntry(
            path = ref.path,
            name = ref.name,
            isDirectory = ref.isDirectory,
        ),
    )

    private fun clearCaches() {
        runCatching { container.thumbnails.clear() }
        runCatching { container.externalCache.clearAsync() }
        runCatching {
            container.mediaFacts.clear()
            container.notifyFactsUpdated()
        }
        notify(str(dev.huginn.R.string.cache_cleared))
    }

    /** Respuesta de la activity con las apps disponibles: abre el selector o lanza. */
    fun onExternalCandidates(
        entry: FsEntry,
        uri: Uri,
        mime: String,
        apps: List<ExternalAppChoice>,
    ) {
        pendingExternal = Triple(entry, uri, mime)
        if (apps.isEmpty()) {
            pendingExternal = null
            fail(str(dev.huginn.R.string.no_app_for, mime))
            return
        }
        if (apps.size == 1) {
            launchExternalApp(apps.single().packageName)
            return
        }
        val items = apps.map { SheetItemState("extapp:${it.packageName}", it.label) } +
            SheetItemState("extalways", str(dev.huginn.R.string.open_with_remember), checked = false)
        _state.update { it.copy(sheet = SheetState(SheetKind.EXTERNAL, str(dev.huginn.R.string.open_with), items, 0)) }
    }

    private fun launchExternalApp(packageName: String) {
        val pending = pendingExternal ?: return
        pendingExternal = null
        closeSheet()
        emit(ExplorerEvent.OpenExternal(pending.first, pending.second, pending.third, packageName))
    }

    fun cancelExternalPreparation() {
        externalJob?.cancel()
        externalJob = null
        _state.update { it.copy(topPanel = entryInfoFor(_state.value.selectedItem)) }
    }

    // ---------------------------------------------------------------- pruebas M0

    private fun playTestVideo(openTrackMenu: Boolean = false, screenOff: Boolean = false) {
        scope.launch {
            try {
                val target = copyAsset(TEST_VIDEO_ASSET, "test-video.mp4")
                playEntry(
                    FsEntry(
                        path = FsPath.Local(Uri.fromFile(target).toString()),
                        name = "Vídeo de prueba (assets)",
                        isDirectory = false,
                        size = target.length(),
                        mimeType = "video/mp4",
                    ),
                )
                if (openTrackMenu) {
                    delay(1200) // dejar que libVLC abra el medio y publique las pistas
                    openTrackMenu()
                }
                if (screenOff) {
                    delay(800)
                    toggleBottomScreenOff()
                }
            } catch (e: IOException) {
                Log.e(TAG, "no se pudo preparar el vídeo de prueba", e)
                fail("No se pudo preparar el vídeo de prueba: ${e.message}")
            }
        }
    }

    private fun showTestImage() {
        scope.launch {
            try {
                val target = copyAsset(TEST_IMAGE_ASSET, "test-image.png")
                val entry = FsEntry(
                    path = FsPath.Local(Uri.fromFile(target).toString()),
                    name = "Imagen de prueba (assets)",
                    isDirectory = false,
                    size = target.length(),
                    mimeType = "image/png",
                )
                closePlaybackIfNeeded(notifyEngine = true)
                viewerImages = listOf(entry)
                viewerIndex = 0
                publishImageViewer()
            } catch (e: IOException) {
                Log.e(TAG, "no se pudo preparar la imagen de prueba", e)
                fail("No se pudo preparar la imagen de prueba: ${e.message}")
            }
        }
    }

    private fun openTestVideoExternally() {
        scope.launch {
            try {
                val target = copyAsset(TEST_VIDEO_ASSET, "test-video.mp4")
                val authority = container.app.packageName + ".fileprovider"
                val contentUri = FileProvider.getUriForFile(container.app, authority, target)
                emit(
                    ExplorerEvent.OpenExternal(
                        entry = FsEntry(
                            path = FsPath.Local(contentUri.toString()),
                            name = "Vídeo de prueba · app externa",
                            isDirectory = false,
                            size = target.length(),
                            mimeType = "video/mp4",
                        ),
                        uri = contentUri,
                        mime = "video/mp4",
                    ),
                )
            } catch (e: IOException) {
                Log.e(TAG, "no se pudo preparar el vídeo externo", e)
                fail("No se pudo preparar el vídeo externo: ${e.message}")
            }
        }
    }

    private fun copyAsset(assetName: String, cacheName: String): File {
        val target = File(container.app.cacheDir, cacheName)
        container.app.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    // ---------------------------------------------------------------- mando

    fun handle(action: InputAction) {
        _state.update {
            it
        }
        if (handleSheet(action)) return
        if (handleTrackMenu(action)) return
        if (handleServerForm(action)) return
        if (_state.value.smbSettings || _state.value.virtual != null) {
            handleFlatListInput(action)
            return
        }

        when (action) {
            InputAction.UP -> if (_state.value.view == ExplorerView.LIST) moveSelection(-1) else moveGrid(ExplorerLogic.GridMove.UP)
            InputAction.DOWN -> if (_state.value.view == ExplorerView.LIST) moveSelection(1) else moveGrid(ExplorerLogic.GridMove.DOWN)
            InputAction.LEFT -> if (_state.value.view == ExplorerView.LIST) stepContent(-1) else moveGrid(ExplorerLogic.GridMove.LEFT)
            InputAction.RIGHT -> if (_state.value.view == ExplorerView.LIST) stepContent(1) else moveGrid(ExplorerLogic.GridMove.RIGHT)
            InputAction.ACCEPT -> accept()
            InputAction.BACK -> Unit // lo gestiona la activity (finish si back() == false)
            InputAction.PLAY_PAUSE -> {
                val wasPaused = (_state.value.topPanel as? TopPanelState.Playing)?.playing == false
                engine.playPause()
                // Al reanudar, los controles vuelven a autoocultarse; en pausa se quedan.
                if (wasPaused) scheduleControlsHide()
            }
            InputAction.SEEK_BACK -> engine.seekBy(-SEEK_STEP_MS)
            InputAction.SEEK_FORWARD -> engine.seekBy(SEEK_STEP_MS)
            InputAction.PREV_ITEM -> if (_state.value.topPanel is TopPanelState.ImageViewer) stepImage(-1) else stepMedia(-1)
            InputAction.NEXT_ITEM -> if (_state.value.topPanel is TopPanelState.ImageViewer) stepImage(1) else stepMedia(1)
            InputAction.TOGGLE_CONTROLS -> {
                // Y: muestra/oculta los controles del visor (no cualquier tecla).
                if (_state.value.topPanel is TopPanelState.Playing) toggleControls()
            }
            InputAction.TOGGLE_FOCUS -> _state.update {
                it.copy(focus = if (it.focus == FocusArea.EXPLORER) FocusArea.PLAYER else FocusArea.EXPLORER)
            }

            InputAction.MENU -> if (_state.value.topPanel is TopPanelState.Playing) {
                openTrackMenu()
            } else {
                openMoreSheet()
            }
        }
    }

    private fun handleSheet(action: InputAction): Boolean {
        val sheet = _state.value.sheet ?: return false
        when (action) {
            InputAction.UP -> moveSheet(-1)
            InputAction.DOWN -> moveSheet(1)
            InputAction.ACCEPT -> acceptSheet()
            InputAction.BACK, InputAction.MENU -> closeSheet()
            else -> Unit
        }
        return true
    }

    private fun stepContent(delta: Int) {
        when (_state.value.topPanel) {
            is TopPanelState.ImageViewer -> stepImage(delta)
            is TopPanelState.Playing -> engine.seekBy(delta * SEEK_STEP_MS)
            else -> Unit
        }
    }

    private fun stepMedia(delta: Int) {
        val media = _state.value.items
            .filterIsInstance<ExplorerItem.FileItem>()
            .map { it.entry }
            .filter { it.mediaKind == MediaKind.AUDIO || it.mediaKind == MediaKind.VIDEO }
        if (media.isEmpty()) return
        val current = (_state.value.topPanel as? TopPanelState.Playing)?.name
        val index = media.indexOfFirst { it.name == current }
        val next = if (index < 0) media.first() else media[(index + delta).mod(media.size)]
        playEntry(next)
    }

    // ---------------------------------------------------------------- top panel

    private fun emit(event: ExplorerEvent) {
        _events.tryEmit(event)
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, error = message, topPanel = TopPanelState.Error(message)) }
    }

    private fun notify(message: String) {
        _state.update { it.copy(notice = message) }
    }

    private fun showDirectoryInfo(location: FsPath, items: List<ExplorerItem>) {
        val directories = items.count { it is ExplorerItem.FileItem && it.entry.isDirectory }
        val files = items.count { it is ExplorerItem.FileItem && !it.entry.isDirectory }
        _state.update {
            it.copy(
                topPanel = TopPanelState.DirectoryInfo(
                    title = if (location is FsPath.Roots) "Huginn" else (it.trail.lastOrNull()?.name ?: displayTitle(location)),
                    path = if (location is FsPath.Roots) "" else pathText(location),
                    directories = directories,
                    files = files,
                    sourceLabel = sourceLabel(location),
                    sortLabel = currentSortLabel(),
                    hint = null,
                ),
            )
        }
    }

    private fun refreshDirectoryTopPanel() {
        val st = _state.value
        if (st.location is FsPath.Roots) return
        val top = st.topPanel
        if (top is TopPanelState.DirectoryInfo) {
            _state.update {
                it.copy(topPanel = top.copy(sortLabel = currentSortLabel()))
            }
        }
    }

    private fun publishEntryInfo(item: ExplorerItem?, loading: Boolean) {
        val current = _state.value.topPanel
        if (_state.value.smbSettings) return
        if (current is TopPanelState.Playing || current is TopPanelState.ImageViewer) return
        _state.update { it.copy(topPanel = entryInfoFor(item).withPreviewLoading(loading)) }
    }

    /**
     * M4: sonda de metadatos caros del elemento seleccionado (duración, resolución,
     * etiquetas) con debounce y caché persistente, sin bloquear la UI.
     */
    private fun scheduleFacts(item: ExplorerItem?) {
        factsJob?.cancel()
        val entry = (item as? ExplorerItem.FileItem)?.entry ?: return
        if (entry.isDirectory) return
        if (entry.mediaKind != MediaKind.VIDEO && entry.mediaKind != MediaKind.AUDIO) return
        if (container.mediaFacts.get(entry) != null) return
        factsJob = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val facts = MediaProbe.readFacts(container.sourceFor(entry.path), entry) ?: return@launch
            container.mediaFacts.put(entry, facts)
            container.notifyFactsUpdated()
        }
    }

    private fun schedulePreview(item: ExplorerItem?) {
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            // Mientras hay visor o reproducción, la superior sigue mostrando el
            // contenido; la ficha del seleccionado no pisa al reproductor (M2).
            if (_state.value.smbSettings) return@launch
            val current = _state.value.topPanel
            if (current is TopPanelState.Playing || current is TopPanelState.ImageViewer) return@launch
            _state.update { it.copy(topPanel = entryInfoFor(item)) }
        }
    }

    private fun TopPanelState.withPreviewLoading(loading: Boolean): TopPanelState =
        if (this is TopPanelState.EntryInfo) copy(previewLoading = loading) else this

    private fun entryInfoFor(item: ExplorerItem?): TopPanelState = when (item) {
        is ExplorerItem.FileItem -> {
            val entry = item.entry
            TopPanelState.EntryInfo(
                entry = entry,
                name = entry.name,
                kind = entry.mediaKind,
                sizeText = entry.size?.let(::formatSize),
                dateText = entry.lastModified?.let(::formatDate),
                path = previewPath(entry),
            )
        }

        is ExplorerItem.ActionItem -> TopPanelState.DirectoryInfo(
            title = item.title,
            path = item.subtitle,
            directories = 0,
            files = 0,
            hint = str(dev.huginn.R.string.notice_enter_folder_hint),
        )

        null -> TopPanelState.DirectoryInfo(
            title = _state.value.title,
            path = if (_state.value.location is FsPath.Roots) "" else pathText(_state.value.location),
            directories = 0,
            files = 0,
        )
    }

    // ---------------------------------------------------------------- SAF / externos

    fun onLocalTreePicked(uri: Uri) {
        settings.localTreeUri = uri.toString()
        loadRoots()
    }

    fun onExternalResult(result: dev.huginn.external.ExternalLauncher.Result) {
        when (result) {
            dev.huginn.external.ExternalLauncher.Result.Launched -> Unit
            is dev.huginn.external.ExternalLauncher.Result.NoApp ->
                fail(str(dev.huginn.R.string.notice_no_app, result.mime))
            is dev.huginn.external.ExternalLauncher.Result.Unavailable -> fail(result.message)
            is dev.huginn.external.ExternalLauncher.Result.Error -> fail(result.message)
        }
    }

    // ---------------------------------------------------------------- atajos dev

    /** Abre el share SMB de la sesión activa (también desde `--ez open_smb true`). */
    fun openSmbShare() {
        val smb = container.activeSmbConfig()
        if (smb == null) {
            fail(str(dev.huginn.R.string.smb_not_configured))
            return
        }
        _state.update { it.copy(trail = emptyList()) }
        loadLocation(FsPath.Smb(smb.shareUrl), pushTrail = true, trailName = smb.share.ifBlank { smb.host })
    }

    /** Abre un servidor SMB de la lista (M3): lo deja como sesión activa. */
    fun openSmbServer(serverId: String) {
        container.setActiveSmbServer(serverId)
        val smb = container.activeSmbConfig()
        if (smb == null) {
            fail(str(dev.huginn.R.string.smb_bad_password))
            return
        }
        _state.update { it.copy(trail = emptyList()) }
        loadLocation(FsPath.Smb(smb.shareUrl), pushTrail = true, trailName = smb.share.ifBlank { smb.host })
    }

    // --------------------------------------------------------- M3: ajustes SMB

    /** Lista de servidores en la pantalla inferior (alta/edición/borrado). */
    fun openSmbSettings() {
        val servers = container.smbServers.load()
        val items = buildSmbSettingsItems(servers)
        _state.update {
            it.copy(
                smbSettings = true,
                location = FsPath.Roots,
                title = str(dev.huginn.R.string.servers_title),
                subtitle = str(
                    dev.huginn.R.string.servers_sub,
                    servers.size,
                    str(
                        if (servers.size == 1) dev.huginn.R.string.server_one
                        else dev.huginn.R.string.server_many,
                    ),
                ),
                items = items,
                selectedIndex = if (items.isEmpty()) -1 else 0,
                loading = false,
                error = null,
                notice = null,
                sheet = null,
                trail = emptyList(),
                view = ExplorerView.LIST,
                galleryEligible = false,
                topPanel = serversInfoPanel(servers),
            )
        }
    }

    private fun buildSmbSettingsItems(servers: List<SmbServer>): List<ExplorerItem> = buildList {
        servers.forEach { server ->
            add(
                ExplorerItem.ActionItem(
                    action = RootAction.SMB_SERVER_EDIT,
                    title = server.displayLabel,
                    subtitle = listOfNotNull(
                        server.host,
                        server.share.takeIf { it.isNotBlank() },
                        if (server.user.isBlank()) "invitado" else server.user,
                        if (server.smb1) "SMB1" else null,
                    ).joinToString(" · "),
                    serverId = server.id,
                ),
            )
        }
        add(
            ExplorerItem.ActionItem(
                RootAction.SMB_SERVER_ADD,
                str(dev.huginn.R.string.servers_add),
                str(dev.huginn.R.string.server_add_sub),
            ),
        )
    }

    private fun serversInfoPanel(servers: List<SmbServer>): TopPanelState.DirectoryInfo =
        TopPanelState.DirectoryInfo(
            title = str(dev.huginn.R.string.servers_title),
            path = "",
            directories = servers.size,
            files = 0,
            readOnly = true,
            sourceLabel = "SMB · credenciales cifradas",
            hint = str(dev.huginn.R.string.servers_hint),
            chips = listOf("${servers.size} ${if (servers.size == 1) "servidor" else "servidores"}"),
        )

    private fun closeSmbSettings() {
        loadRoots()
    }

    /** En listas planas (ajustes SMB/virtuales): cruceta navega, A acepta, menú abre. */
    private fun handleFlatListInput(action: InputAction) {
        when (action) {
            InputAction.UP -> moveSelection(-1)
            InputAction.DOWN -> moveSelection(1)
            InputAction.ACCEPT -> accept()
            InputAction.MENU -> openMoreSheet()
            else -> Unit
        }
    }

    /** Abre el formulario (en la pantalla superior) para alta o edición. */
    fun openServerForm(serverId: String?) {
        val server = serverId?.let { id -> container.smbServers.load().firstOrNull { it.id == id } }
        _state.update {
            it.copy(
                topPanel = TopPanelState.ServerForm(
                    serverId = server?.id,
                    label = server?.label.orEmpty(),
                    host = server?.host.orEmpty(),
                    share = server?.share.orEmpty(),
                    user = server?.user.orEmpty(),
                    domain = server?.domain.orEmpty(),
                    smb1 = server?.smb1 ?: false,
                    hasStoredPassword = !server?.encryptedPassword.isNullOrEmpty(),
                ),
            )
        }
    }

    fun updateServerForm(form: TopPanelState.ServerForm) {
        if (_state.value.topPanel is TopPanelState.ServerForm) {
            _state.update { it.copy(topPanel = form) }
        }
    }

    fun cancelServerForm() {
        if (_state.value.topPanel !is TopPanelState.ServerForm) return
        openSmbSettings()
    }

    fun saveServerForm() {
        val form = _state.value.topPanel as? TopPanelState.ServerForm ?: return
        val host = form.host.trim()
        if (host.isBlank()) {
            updateServerForm(form.copy(error = str(dev.huginn.R.string.form_host_required)))
            return
        }
        if (form.user.isNotBlank() && form.password.isBlank() && !form.hasStoredPassword) {
            updateServerForm(form.copy(error = str(dev.huginn.R.string.form_password_required)))
            return
        }
        val id = form.serverId ?: java.util.UUID.randomUUID().toString()
        val server = SmbServer(
            id = id,
            label = form.label.trim(),
            host = host,
            share = form.share.trim(),
            user = form.user.trim(),
            domain = form.domain.trim(),
            smb1 = form.smb1,
        )
        val password = form.password.takeIf { it.isNotBlank() }
        container.saveSmbServer(server, password)
        if (settings.activeSmbServerId == null) container.setActiveSmbServer(id)
        notify(str(dev.huginn.R.string.server_saved, server.displayLabel))
        openSmbSettings()
    }

    fun deleteServerForm() {
        val form = _state.value.topPanel as? TopPanelState.ServerForm ?: return
        val id = form.serverId ?: return
        container.deleteSmbServer(id)
        notify(str(dev.huginn.R.string.server_deleted))
        openSmbSettings()
    }

    /** Formulario abierto: el mando actúa sobre él (A guarda, B cancela vía back()). */
    private fun handleServerForm(action: InputAction): Boolean {
        if (_state.value.topPanel !is TopPanelState.ServerForm) return false
        when (action) {
            InputAction.ACCEPT -> saveServerForm()
            else -> Unit
        }
        return true
    }

    /** Atajo de desarrollo: `--ez test_video true` (+ `track_menu`, `screen_off`). */
    fun startTestVideo(openTrackMenu: Boolean = false, screenOff: Boolean = false) =
        playTestVideo(openTrackMenu, screenOff)

    /** Atajo de desarrollo: `--ez test_image true`. */
    fun startTestImage() = showTestImage()

    /**
     * Abre una URL `smb://…` concreta (atajo de desarrollo: `--es open_path …`).
     * Se acepta percent-encoding porque los extras de `am start` parten la cadena en
     * los espacios; aquí se decodifica una vez y se guarda la ruta literal (jcifs).
     */
    fun openDevPath(url: String?) {
        val clean = runCatching {
            java.net.URLDecoder.decode(url?.trim().orEmpty(), "UTF-8")
        }.getOrDefault(url?.trim().orEmpty())
        if (clean.isEmpty()) return
        if (!clean.startsWith("smb://")) {
            fail(str(dev.huginn.R.string.dev_open_path_only_smb))
            return
        }
        val normalized = if (clean.endsWith('/')) clean else "$clean/"
        _state.update { it.copy(trail = emptyList()) }
        val name = decodeForDisplay(normalized).trimEnd('/').substringAfterLast('/')
        loadLocation(FsPath.Smb(normalized), pushTrail = true, trailName = name.ifBlank { "Red local" })
    }

    // ---------------------------------------------------------------- helpers

    private fun buildRootItems(): List<ExplorerItem> = buildList {
        settings.localTreeUri?.let { uri ->
            add(
                ExplorerItem.FileItem(
                    FsEntry(path = FsPath.Local(uri), name = str(dev.huginn.R.string.storage_local), isDirectory = true),
                ),
            )
        }
        add(
            ExplorerItem.ActionItem(
                RootAction.PICK_LOCAL_TREE,
                if (settings.localTreeUri != null) str(dev.huginn.R.string.choose_local) else str(dev.huginn.R.string.choose_local_first),
                str(dev.huginn.R.string.choose_local_sub),
            ),
        )
        val servers = container.smbServers.load()
        if (servers.isEmpty() && settings.smb != null) {
            // Modo desarrollo sin servidores persistidos: la config de dev sigue valiendo.
            val smb = settings.smb!!
            add(
                ExplorerItem.ActionItem(
                    RootAction.OPEN_SMB,
                    "Red local · ${smb.host}",
                    smb.share.ifBlank { str(dev.huginn.R.string.no_share) },
                ),
            )
        } else {
            servers.forEach { server ->
                add(
                    ExplorerItem.ActionItem(
                        action = RootAction.OPEN_SMB_SERVER,
                        title = server.displayLabel,
                        subtitle = listOfNotNull(
                            server.host,
                            server.share.takeIf { it.isNotBlank() },
                            if (server.user.isBlank()) str(dev.huginn.R.string.anonymous) else server.user,
                        ).joinToString(" · "),
                        serverId = server.id,
                    ),
                )
            }
        }
        if (container.placesStore.recents().isNotEmpty()) {
            add(
                ExplorerItem.ActionItem(
                    RootAction.RECENTS,
                    str(dev.huginn.R.string.recents),
                    str(dev.huginn.R.string.recents_sub, container.placesStore.recents().size),
                ),
            )
        }
        if (container.placesStore.favorites().isNotEmpty()) {
            add(
                ExplorerItem.ActionItem(
                    RootAction.FAVORITES,
                    str(dev.huginn.R.string.favorites),
                    str(dev.huginn.R.string.favorites_sub, container.placesStore.favorites().size),
                ),
            )
        }
        add(
            ExplorerItem.ActionItem(
                RootAction.SETTINGS_HOME,
                str(dev.huginn.R.string.settings),
                str(dev.huginn.R.string.settings_sub),
            ),
        )
    }

    private fun displayTitle(location: FsPath): String = when (location) {
        is FsPath.Roots -> "Huginn"
        is FsPath.Local -> "Almacenamiento local"
        is FsPath.Smb -> {
            val trimmed = decodeForDisplay(location.url).trimEnd('/').substringAfterLast('/')
            trimmed.ifBlank { location.url.substringAfter("smb://").substringBefore('/') }
        }
    }

    private fun sourceLabel(location: FsPath): String = when (location) {
        is FsPath.Roots -> str(dev.huginn.R.string.source_home)
        is FsPath.Local -> str(dev.huginn.R.string.source_local)
        is FsPath.Smb -> str(dev.huginn.R.string.source_smb)
    }

    private fun pathText(location: FsPath): String = when (location) {
        is FsPath.Roots -> ""
        is FsPath.Local -> location.documentUri
        is FsPath.Smb -> decodeForDisplay(location.url)
    }

    private fun previewPath(entry: FsEntry): String = when (val path = entry.path) {
        is FsPath.Roots -> ""
        is FsPath.Local -> path.documentUri
        is FsPath.Smb -> decodeForDisplay(path.url)
    }

    /** Para mostrar: `%20`, `%23`… se leen mal en pantalla. */
    private fun decodeForDisplay(url: String): String =
        runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(millis))

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L

        const val PROGRESS_THROTTLE_MS = 120L

        const val TAG = "HuginnVm"
        const val SEEK_STEP_MS = 10_000L
        const val PREVIEW_DEBOUNCE_MS = 150L
        const val CONTROLS_HIDE_MS = 3000L
        const val TEST_VIDEO_ASSET = "sample.mp4"
        const val TEST_IMAGE_ASSET = "sample.png"
    }
}
