package dev.huginn.core

import dev.huginn.ui.top.TopPanelState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado compartido de la pantalla de contenido entre la activity principal
 * ([dev.huginn.MainActivity]) y el explorador ([dev.huginn.display.ExplorerPresentation]).
 *
 * Motivo de la arquitectura (Thor): AOSP rechaza `TYPE_PRESENTATION` sobre el display
 * por defecto, así que el contenido vive en una activity (display 0) y el explorador en
 * una `Presentation` del display inferior. El estado de la pantalla superior vive en un
 * controlador app-scope compartido por ambos.
 */
class TopPanelController {

    private val _state = MutableStateFlow<TopPanelState>(
        TopPanelState.DirectoryInfo(title = "Huginn", path = "", directories = 0, files = 0),
    )
    val state: StateFlow<TopPanelState> = _state.asStateFlow()

    private val _closeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeRequests: SharedFlow<Unit> = _closeRequests.asSharedFlow()

    fun update(state: TopPanelState) {
        _state.value = state
    }

    /** Pide a la activity de contenido que se cierre (al salir de la app). */
    fun requestClose() {
        _closeRequests.tryEmit(Unit)
    }
}
