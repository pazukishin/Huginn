package dev.huginn.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.huginn.AppContainer
import dev.huginn.input.InputAction
import dev.huginn.ui.explorer.ExplorerScreen
import dev.huginn.ui.theme.HuginnTheme

/**
 * Explorador de archivos renderizado como `Presentation` en el display secundario
 * (en la Thor, la pantalla inferior = display 4).
 *
 * Motivo del cambio de arquitectura: si el launcher de la pantalla inferior usa su
 * propia `Presentation`
 * (ventana `TYPE_PRESENTATION`, capa por encima de las activities) queda delante de
 * cualquier activity que se lance en la pantalla inferior, así que un explorador en
 * forma de activity no se ve. En cambio, una `Presentation` propia se compone al mismo
 * nivel y sí se muestra (verificado en la Thor con un probe a pantalla completa).
 *
 * El display inferior no es el display por defecto, por lo que `TYPE_PRESENTATION` está
 * permitido aquí (lo que falló en M0 fue intentar un `Presentation` en el display 0).
 *
 * La ventana es `FLAG_NOT_FOCUSABLE` (igual que otros docks de pantalla secundaria): recibe toques,
 * pero deja el foco del mando en la activity principal (el contenido en display 0), que
 * es quien enruta las teclas al `ExplorerController` en cualquier caso.
 */
class ExplorerPresentation(
    context: Context,
    display: Display,
    private val container: AppContainer,
    private val onExit: () -> Unit,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )

        val lifecycleOwner = PresentationLifecycleOwner()

        // `Presentation` crea un contexto de display que pierde el locale aplicado en
        // la activity; lo re-aplicamos para el árbol Compose.
        val localizedContext = dev.huginn.core.LocaleConfig.apply(
            context,
            container.settings.language,
        )
        setContentView(
            ComposeView(localizedContext).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = lifecycleOwner.attach()
                    override fun onViewDetachedFromWindow(v: View) = lifecycleOwner.detach()
                })
                setContent {
                    val state by container.explorer.state.collectAsState()
                    val factsVersion by container.factsRevision.collectAsState()
                    HuginnTheme {
                        ExplorerScreen(
                            state = state,
                            onSelect = container.explorer::selectIndex,
                            onAccept = container.explorer::accept,
                            onBack = { if (!container.explorer.back()) onExit() },
                            onUp = container.explorer::goUp,
                            onRefresh = container.explorer::refresh,
                            onCycleView = container.explorer::cycleView,
                            onSort = container.explorer::openSortSheet,
                            onMore = container.explorer::openMoreSheet,
                            onBreadcrumb = container.explorer::navigateToBreadcrumb,
                            onSheetItem = container.explorer::acceptSheetItem,
                            onSheetDismiss = container.explorer::closeSheet,
                            onMiniPlayerToggle = { container.explorer.handle(InputAction.PLAY_PAUSE) },
                            onMiniPlayerFocus = { container.explorer.handle(InputAction.TOGGLE_FOCUS) },
                            onMiniPlayerScreenOff = container.explorer::toggleBottomScreenOff,
                            onWakeBottomScreen = container.explorer::wakeBottomScreen,
                            loadThumbnail = container.thumbnails::load,
                            factsVersion = factsVersion,
                            factsOf = container.mediaFacts::get,
                        )
                    }
                }
            },
        )
    }
}

/**
 * `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner` mínimo para poder
 * usar Compose dentro de una [Presentation] (un `Dialog` no es un lifecycle owner).
 *
 * Sigue el ciclo de vida de la vista: se marca STARTED/RESUMED cuando el `ComposeView`
 * se ancla a la ventana y PAUSED/STOPPED cuando se desancla (al `dismiss()`).
 */
private class PresentationLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performRestore(null)
    }

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun attach() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun detach() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
}
