package dev.huginn.display

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * Detección de pantallas y decisión de dónde va cada cosa (ADR 1 del plan).
 *
 * En la AYN Thor la pantalla **superior es el display primario (0)** y la inferior
 * es un panel público aparte. Los IDs no son fijos (se han visto 2 y 4), así que
 * nunca se codifican: se resuelven con `DisplayManager` y se registran para
 * diagnóstico.
 */
data class DisplayDescriptor(
    val id: Int,
    val name: String,
    val isPublic: Boolean,
    val isOn: Boolean,
) {
    val label: String
        get() = "display $id «$name» (público=$isPublic, encendido=$isOn)"
}

data class DisplayTopology(
    /** Display donde se pinta el contenido: ficha, miniaturas, visores. */
    val contentDisplayId: Int,
    /** Display donde vive el explorador (activity principal). */
    val explorerDisplayId: Int,
    /** `false` en dispositivos de una sola pantalla (modo degradado). */
    val dualScreen: Boolean,
    val displays: List<DisplayDescriptor>,
) {
    fun describe(): String =
        "contenido=$contentDisplayId · explorador=$explorerDisplayId · doble=${if (dualScreen) "sí" else "no"} · " +
            displays.joinToString(" | ") { "${it.id}:${it.name}" }
}

object DisplayRouter {

    private const val TAG = "HuginnDisplays"

    fun resolve(context: Context, contentOnPrimary: Boolean): DisplayTopology {
        val displays = describeDisplays(context)
        val primaryId = displays.firstOrNull { it.id == DEFAULT_DISPLAY_ID }?.id ?: DEFAULT_DISPLAY_ID
        val topology = pick(displays, primaryId, contentOnPrimary)
        Log.i(TAG, "topología: ${topology.describe()}")
        return topology
    }

    fun describeDisplays(context: Context): List<DisplayDescriptor> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return dm.displays
            .map { d ->
                DisplayDescriptor(
                    id = d.displayId,
                    name = d.name.orEmpty(),
                    // «Público» = no privado: FLAG_PRIVATE solo lo tienen los displays privados.
                    isPublic = (d.flags and Display.FLAG_PRIVATE) == 0,
                    isOn = d.state != Display.STATE_OFF,
                )
            }
            .sortedBy { it.id }
    }

    /**
     * Regla pura y testeable:
     * - contenido = primario (Thor: arriba) si `contentOnPrimary`, si no otro público;
     * - explorador = el otro display público encendido; si no hay, el mismo (modo
     *   una sola pantalla, sin `Presentation`).
     */
    fun pick(
        displays: List<DisplayDescriptor>,
        primaryId: Int,
        contentOnPrimary: Boolean,
    ): DisplayTopology {
        val usable = displays.filter { it.isPublic && it.isOn }
        if (usable.isEmpty()) {
            return DisplayTopology(primaryId, primaryId, dualScreen = false, displays = displays)
        }
        val content = when {
            contentOnPrimary ->
                usable.firstOrNull { it.id == primaryId }
                    ?: usable.firstOrNull { it.id == DEFAULT_DISPLAY_ID }
                    ?: usable.first()
            else ->
                usable.firstOrNull { it.id != primaryId } ?: usable.first()
        }
        val explorer = usable.firstOrNull { it.id != content.id } ?: content
        return DisplayTopology(
            contentDisplayId = content.id,
            explorerDisplayId = explorer.id,
            dualScreen = explorer.id != content.id,
            displays = displays,
        )
    }

    /** Display real del sistema que debe usar el contenido. */
    fun contentDisplay(context: Context, topology: DisplayTopology): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(topology.contentDisplayId)

    const val DEFAULT_DISPLAY_ID = Display.DEFAULT_DISPLAY
}

/** ID del display en el que está dibujada la activity (API 30 tiene ya `display`). */
fun Activity.currentDisplayId(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.displayId ?: Display.DEFAULT_DISPLAY
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.displayId
    }
