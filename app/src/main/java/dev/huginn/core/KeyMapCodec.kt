package dev.huginn.core

import dev.huginn.input.InputAction

/**
 * Mapeo de botones configurable (M5): acción → keycode. Formato propio y testeable.
 * Se guardan solo los overrides; el resto sigue el mapa por defecto de `InputRouter`.
 */
object KeyMapCodec {

    fun encode(map: Map<InputAction, Int>): String = map.entries
        .sortedBy { it.key.name }
        .joinToString("\n") { (action, code) -> "${action.name}=$code" }

    fun decode(text: String): Map<InputAction, Int> = text.lineSequence()
        .mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val action = runCatching { InputAction.valueOf(line.substring(0, eq).trim()) }.getOrNull()
                ?: return@mapNotNull null
            val code = line.substring(eq + 1).trim().toIntOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            action to code
        }
        .toMap()
}
