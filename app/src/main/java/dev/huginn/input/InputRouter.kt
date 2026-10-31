package dev.huginn.input

import android.view.KeyEvent

/** Acciones de la app, independientes del keycode concreto (mapa configurable en M2). */
enum class InputAction {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    ACCEPT,
    BACK,
    PLAY_PAUSE,
    TOGGLE_CONTROLS,
    SEEK_BACK,
    SEEK_FORWARD,
    PREV_ITEM,
    NEXT_ITEM,
    TOGGLE_FOCUS,
    MENU,
    ;

    /** ¿Tiene sentido repetir la acción mientras la tecla está mantenida? */
    fun isRepeatable(): Boolean = when (this) {
        UP, DOWN, SEEK_BACK, SEEK_FORWARD -> true
        else -> false
    }
}

/**
 * Traduce keycodes de mando/teclado a acciones de la app (requisito 1.2).
 *
 * Vive a nivel de Activity (`dispatchKeyEvent`) y **no** depende del foco de
 * Compose: así la selección no se rompe con diálogos, bottom sheets ni con el
 * `Presentation` de la pantalla superior.
 *
 * Mapa por defecto (spec §6):
 * - cruceta ↑/↓ mover; ←/→ contenido (galería o seek);
 * - A abrir, B atrás, X play/pausa, Y controles;
 * - L1/R1 ±10 s, L2/R2 pista/foto anterior-siguiente;
 * - Select alterna foco, Start menú.
 */
object InputRouter {

    fun map(keyCode: Int, overrides: Map<InputAction, Int> = emptyMap()): InputAction? {
        // Los overrides del usuario mandan sobre el mapa por defecto.
        overrides.entries.firstOrNull { it.value == keyCode }?.let { return it.key }
        return mapDefault(keyCode)
    }

    /** Descripción legible de un keycode (para Ajustes › Mapeo de botones). */
    fun keyName(keyCode: Int): String = android.view.KeyEvent.keyCodeToString(keyCode)

    /** Keycode por defecto de una acción (la inversa del mapa, para mostrar en Ajustes). */
    fun defaultKey(action: InputAction): Int? =
        (0..MAX_KEYCODE).firstOrNull { mapDefault(it) == action }


    private const val MAX_KEYCODE = 300

    private fun mapDefault(keyCode: Int): InputAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> InputAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> InputAction.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> InputAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> InputAction.RIGHT

        // A del mando; también Enter/Dpad center para teclado o mandos genéricos.
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_1,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        -> InputAction.ACCEPT

        // B del mando; Escape como equivalente de teclado.
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_2,
        KeyEvent.KEYCODE_ESCAPE,
        -> InputAction.BACK

        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_3,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        -> InputAction.PLAY_PAUSE

        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_4,
        -> InputAction.TOGGLE_CONTROLS

        KeyEvent.KEYCODE_BUTTON_L1 -> InputAction.SEEK_BACK
        KeyEvent.KEYCODE_BUTTON_R1 -> InputAction.SEEK_FORWARD
        KeyEvent.KEYCODE_BUTTON_L2 -> InputAction.PREV_ITEM
        KeyEvent.KEYCODE_BUTTON_R2 -> InputAction.NEXT_ITEM
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_TAB,
        -> InputAction.TOGGLE_FOCUS

        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_MENU,
        -> InputAction.MENU

        else -> null
    }

    /** Texto de diagnóstico para validar el mapeo real de la Thor con `adb logcat`. */
    fun describe(event: KeyEvent): String =
        "keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) " +
            "scan=${event.scanCode} repeat=${event.repeatCount} source=0x${event.source.toString(16)}"
}
