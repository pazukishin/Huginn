package dev.huginn


import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.huginn.data.FsEntry
import dev.huginn.display.DisplayRouter
import dev.huginn.display.DisplayTopology
import dev.huginn.display.ExplorerPresentation
import dev.huginn.display.currentDisplayId
import dev.huginn.external.ExternalLauncher
import dev.huginn.input.InputAction
import dev.huginn.input.InputRouter
import dev.huginn.model.SortKey
import dev.huginn.ui.explorer.ExplorerEvent
import dev.huginn.ui.explorer.ExplorerUiState
import dev.huginn.ui.theme.HuginnTheme
import dev.huginn.ui.top.TopPanelActions
import dev.huginn.ui.top.TopPanelContent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity de la **pantalla de contenido** (superior en la Thor, display 0).
 *
 * Arquitectura: el contenido es una activity en el display por defecto (0) y el
 * explorador es una [ExplorerPresentation] en el display secundario (inferior).
 *
 * El M0 descartó `Presentation` para el contenido porque AOSP prohíbe
 * `TYPE_PRESENTATION` sobre el display por defecto; por eso el contenido es una
 * activity. El explorador sí puede ser `Presentation` (el display inferior no es el
 * default), lo que además lo hace visible cuando el launcher de la pantalla inferior es una app de terceros (ver
 * [ExplorerPresentation]).
 *
 * Responsabilidades:
 * 1. enrutado de displays al arrancar y si cambian (`DisplayRouter`);
 * 2. `dispatchKeyEvent` → `InputRouter` → acciones del controlador (el mando siempre
 *    llega aquí porque la `Presentation` inferior es no-focuseable);
 * 3. puente entre eventos del controlador y APIs de Android (SAF, apps externas);
 * 4. crear/mantener la [ExplorerPresentation] en la pantalla inferior.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { (application as HuginnApp).container }
    private val explorer get() = container.explorer

    private lateinit var topology: DisplayTopology

    private var explorerPresentation: ExplorerPresentation? = null
    private var bottomScreenOffApplied = false

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { Log.w(TAG, "sin permiso persistente para $uri", it) }
            explorer.onLocalTreePicked(uri)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshRouting()
        override fun onDisplayRemoved(displayId: Int) = refreshRouting()
        override fun onDisplayChanged(displayId: Int) = refreshRouting()
    }

    /** M6: idioma elegido en Ajustes (inglés por defecto). */
    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences(
            dev.huginn.core.AppSettings.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        val tag = dev.huginn.core.LocaleConfig.normalize(
            prefs.getString(dev.huginn.core.AppSettings.KEY_LANGUAGE, null),
        )
        super.attachBaseContext(dev.huginn.core.LocaleConfig.apply(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.settings.applyIntentExtras(intent)

        topology = DisplayRouter.resolve(this, contentOnPrimary = true)
        Log.i(TAG, "topología: ${topology.describe()} · actual=${currentDisplayId()}")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Contenido (pantalla superior).
        setContent {
            val state by container.topPanel.state.collectAsStateWithLifecycle()
            val factsVersion by container.factsRevision.collectAsStateWithLifecycle()
            HuginnTheme {
                TopPanelContent(
                    state = state,
                    attachVideoView = container.mediaEngine::attach,
                    loadImage = container.thumbnails::load,
                    factsVersion = factsVersion,
                    factsOf = container.mediaFacts::get,
                    actions = TopPanelActions(
                        onPlayPause = { container.mediaEngine.playPause() },
                        onSeekBack = { container.mediaEngine.seekBy(-10_000) },
                        onSeekForward = { container.mediaEngine.seekBy(10_000) },
                        onSeekTo = { container.mediaEngine.seekTo(it) },
                        onPrev = { container.explorer.handle(InputAction.PREV_ITEM) },
                        onNext = { container.explorer.handle(InputAction.NEXT_ITEM) },
                        onClose = { container.explorer.closeViewer() },
                        onToggleControls = { container.explorer.toggleControls() },
                        onOpenTrackMenu = { container.explorer.openTrackMenu() },
                        onCloseTrackMenu = { container.explorer.closeTrackMenu() },
                        onServerFormChange = { container.explorer.updateServerForm(it) },
                        onServerFormSave = { container.explorer.saveServerForm() },
                        onServerFormDelete = { container.explorer.deleteServerForm() },
                        onServerFormCancel = { container.explorer.cancelServerForm() },
                        onCancelExternal = { container.explorer.cancelExternalPreparation() },
                        onSearchTermChange = { container.explorer.updateSearchTerm(it) },
                        onCancelLearnKey = { container.explorer.cancelLearnKey() },
                        onSelectTrack = { column, index -> container.explorer.selectTrack(column, index) },
                    ),
                )
            }
        }

        // Estado del explorador → pantalla superior compartida + diagnóstico + apagado inferior.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                explorer.state.collectLatest { state ->
                    container.topPanel.update(state.topPanel)
                    applyBottomScreenOff(state.bottomScreenOff)
                }
            }
        }

        // Eventos que necesitan APIs de Android.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                explorer.events.collect { event ->
                    when (event) {
                        ExplorerEvent.LaunchSafPicker -> openTree.launch(null)
                        is ExplorerEvent.OpenExternal ->
                            event.packageName?.let { launchExternal(event.entry, event.uri, it) }
                                ?: queryExternalApps(event.entry, event.uri, event.mime)
                        is ExplorerEvent.QueryExternalApps ->
                            queryExternalApps(event.entry, event.uri, event.mime)
                        ExplorerEvent.ApplyLanguage -> recreate()
                        is ExplorerEvent.Message -> Log.i(TAG, event.text)
                        ExplorerEvent.Finish -> finish()
                    }
                }
            }
        }

        // Salir de la app cierra también la inferior.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.topPanel.closeRequests.collect { finish() }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!explorer.back()) finish()
                }
            },
        )

        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        applyDevExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        ensureExplorerPresentation()
        // Al volver del segundo plano la reproducción se había pausado: mostrar la
        // ficha/controles del vídeo en vez de dejar la pantalla superior en negro.
        explorer.onAppForegrounded()
    }

    override fun onStop() {
        // Al salir de la app: pausar la reproducción (sin stop) y soltar la inferior.
        explorer.onAppBackgrounded()
        dismissExplorerPresentation()
        // M4: persistir metadatos nuevos (duración/dimensiones/tags) al salir.
        container.mediaFacts.flush()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        container.settings.applyIntentExtras(intent)
        topology = DisplayRouter.resolve(this, contentOnPrimary = true)
        ensureExplorerPresentation()
        applyDevExtras(intent, isNewIntent = true)
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        dismissExplorerPresentation()
        container.mediaEngine.attach(null)
        super.onDestroy()
    }

    // `ComponentActivity.dispatchKeyEvent` está marcado @RestrictedApi; aquí es
    // imprescindible interceptar las teclas del mando antes de que lleguen a la UI.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Aprendizaje de botones (Ajustes › Mapeo): el siguiente keycode se asigna.
        if (event.action == KeyEvent.ACTION_DOWN && explorer.isLearningKey()) {
            explorer.assignLearnedKey(event.keyCode)
            return true
        }
        val action = InputRouter.map(event.keyCode, container.settings.keyOverrides())
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Se registran también las teclas sin mapear: es como se descubre el
            // keycode real de cada botón en un mando nuevo (Thor, M0).
            Log.i(TAG, "tecla ${InputRouter.describe(event)} -> ${action?.name ?: "SIN MAPEAR"}")
        }
        // Con el teclado en pantalla abierto, las teclas van al sistema/teclado:
        // B cierra el IME en vez de cancelar el formulario y las letras se escriben.
        if (action == null || isImeVisible()) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 || action.isRepeatable()) {
                if (action == InputAction.BACK) {
                    if (!explorer.back()) finish()
                } else {
                    explorer.handle(action)
                }
            }
            return true
        }
        return true // consume también el UP de teclas que gestionamos
    }

    private fun isImeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /**
     * Extras de desarrollo usados por `scripts/smoke-device.sh` para capturas
     * deterministas sin depender de la inyección de teclas:
     * `open_smb`, `open_path`, `view`, `sort`, `sort_desc`, `test_video`,
     * `track_menu`, `keep_controls`, `test_image`, `open_menu`, `open_sort`.
     */
    private fun applyDevExtras(intent: Intent, isNewIntent: Boolean = false) {
        val hasLocationRequest =
            isOpenSmbRequested() || intent.getStringExtra("open_path") != null
        if (isNewIntent && !hasLocationRequest) explorer.refresh()
        if (intent.hasExtra("keep_controls")) {
            container.settings.controlsAlwaysVisible = intent.getBooleanExtra("keep_controls", false)
        }
        if (isOpenSmbRequested()) explorer.openSmbShare()
        intent.getStringExtra("open_path")?.let(explorer::openDevPath)
        intent.getStringExtra("open_name")?.let(explorer::requestOpenByName)
        intent.getStringExtra("search_term")?.let(explorer::requestSearch)
        devView(intent)?.let(explorer::setPendingView)
        intent.getStringExtra("sort")?.let { name ->
            val key = runCatching { SortKey.valueOf(name.uppercase()) }.getOrNull()
            val ascending = if (intent.hasExtra("sort_desc")) {
                !intent.getBooleanExtra("sort_desc", false)
            } else {
                null
            }
            explorer.requestSort(key, ascending)
        }
        intent.getStringExtra("sheet")?.let(explorer::requestSheet)
        if (intent.getBooleanExtra("play_first", false)) explorer.requestPlayFirstMedia()
        if (intent.getBooleanExtra("cycle_view_after", false)) explorer.requestCycleViewAfterPlay()
        if (intent.getBooleanExtra("audio_back_flow", false)) explorer.requestAudioBackFlowFlag()
        if (intent.hasExtra("audio_cycle")) {
            val count = intent.getStringExtra("audio_cycle")?.toIntOrNull()
                ?: intent.getIntExtra("audio_cycle", 0)
            if (count > 0) explorer.requestAudioCycle(count)
        }
        if (intent.hasExtra("viewer_open") && intent.hasExtra("viewer_next")) {
            explorer.requestViewerFlow(
                openIndex = intent.getStringExtra("viewer_open")?.toIntOrNull()
                    ?: intent.getIntExtra("viewer_open", 0),
                nextIndex = intent.getStringExtra("viewer_next")?.toIntOrNull()
                    ?: intent.getIntExtra("viewer_next", 1),
            )
        }
        if (intent.hasExtra("select")) {
            // Se acepta tanto `--es select 2` (String) como `--ei select 2` (Int).
            val index = intent.getStringExtra("select")?.toIntOrNull()
                ?: intent.getIntExtra("select", 0)
            explorer.requestSelect(
                index = index,
                accept = intent.getStringExtra("open_selected")?.toBooleanStrictOrNull()
                    ?: intent.getBooleanExtra("open_selected", false),
            )
        }
        if (intent.getBooleanExtra("test_video", false)) {
            explorer.startTestVideo(
                openTrackMenu = intent.getBooleanExtra("track_menu", false),
                screenOff = intent.getBooleanExtra("screen_off", false),
            )
        }
        if (intent.getBooleanExtra("test_image", false)) explorer.startTestImage()
        if (intent.getBooleanExtra("system_audio_probe", false)) {
            dev.huginn.dev.SystemAudioProbe.play(this)
        }
        if (intent.getBooleanExtra("open_menu", false)) explorer.requestSheet("menu")
        if (intent.getBooleanExtra("open_sort", false)) explorer.requestSheet("sort")
    }

    /** Atajo de desarrollo: `am start … --es view GRID|GALLERY`. */
    private fun devView(intent: Intent): dev.huginn.model.ExplorerView? =
        intent.getStringExtra("view")?.let { name ->
            runCatching { dev.huginn.model.ExplorerView.valueOf(name.uppercase()) }.getOrNull()
        }

    /** Atajo de desarrollo: `am start … --ez open_smb true`. */
    private fun isOpenSmbRequested(): Boolean =
        intent.getBooleanExtra("open_smb", false) || intent.getBooleanExtra(EXTRA_OPEN_SMB, false)

    /** Brillo 0 en la ventana del explorador mientras dura el «pantalla apagada». */
    private fun applyBottomScreenOff(off: Boolean) {
        if (off == bottomScreenOffApplied) return
        bottomScreenOffApplied = off
        val w = explorerPresentation?.window ?: return
        val attributes = w.attributes
        attributes.screenBrightness = if (off) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        w.attributes = attributes
    }

    // -------------------------------------------------------- pantalla inferior

    private fun ensureExplorerPresentation() {
        if (!topology.dualScreen) return
        if (explorerPresentation?.isShowing == true) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val display = dm.getDisplay(topology.explorerDisplayId) ?: return
        try {
            explorerPresentation = ExplorerPresentation(this, display, container) { finish() }
                .apply { show() }
            // Re-aplica el estado de «pantalla apagada» si ya estaba activo.
            bottomScreenOffApplied = false
            applyBottomScreenOff(container.explorer.state.value.bottomScreenOff)
            Log.i(TAG, "ExplorerPresentation mostrada en display ${topology.explorerDisplayId}")
        } catch (t: Throwable) {
            explorerPresentation = null
            Log.e(TAG, "no se pudo mostrar ExplorerPresentation", t)
        }
    }

    private fun dismissExplorerPresentation() {
        explorerPresentation?.dismiss()
        explorerPresentation = null
    }

    private fun refreshRouting() {
        val newTopology = DisplayRouter.resolve(this, contentOnPrimary = true)
        val changed = newTopology.contentDisplayId != topology.contentDisplayId ||
            newTopology.explorerDisplayId != topology.explorerDisplayId ||
            newTopology.dualScreen != topology.dualScreen
        topology = newTopology
        if (changed) {
            dismissExplorerPresentation()
            ensureExplorerPresentation()
        }
    }

    // -------------------------------------------------------------- externos

    private fun launchExternal(entry: FsEntry, uri: android.net.Uri, packageName: String) {
        val result: ExternalLauncher.Result = container.externalLauncher.open(
            activity = this@MainActivity,
            entry = entry,
            contentUri = uri,
            contentDisplayId = topology.contentDisplayId,
            packageName = packageName,
        )
        explorer.onExternalResult(result)
    }

    /** Apps instaladas que pueden abrir el tipo MIME (selector «Abrir con»). */
    private fun queryExternalApps(entry: FsEntry, uri: android.net.Uri, mime: String) {
        lifecycleScope.launch {
            val apps = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                // Solo apps que declaren ESTE tipo (sin fallback a */*, que metía
                // instaladores/Wallet). Sin MATCH_DEFAULT_ONLY: un handler no
                // predeterminado (p. ej. Solid Explorer para ZIP) también vale,
                // porque la elección aquí siempre es explícita del usuario.
                packageManager.queryIntentActivities(intent, 0)
                    .distinctBy { it.activityInfo.packageName }
                    .filter { it.activityInfo.packageName != packageName }
                    .map {
                        dev.huginn.external.ExternalAppChoice(
                            label = it.loadLabel(packageManager).toString(),
                            packageName = it.activityInfo.packageName,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
            explorer.onExternalCandidates(entry, uri, mime, apps)
        }
    }

    private companion object {
        const val TAG = "HuginnMain"
        const val EXTRA_OPEN_SMB = "dev.huginn.extra.OPEN_SMB"
    }
}
