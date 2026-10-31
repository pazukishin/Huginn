package dev.huginn.external

import android.app.Activity
import android.os.Bundle

/**
 * Lanzador de apps externas ya colocado en la pantalla de contenido.
 *
 * Motivo: el display de quien lanza es un hint más fuerte que
 * `ActivityOptions.launchDisplayId`; con apps `singleTask` (p. ej. VLC) el hint se
 * ignora. Patrón de dos pasos (trampolín): esta activity se abre
 * SIN contenido visible en la pantalla de destino y desde ahí lanza la app externa,
 * que hereda esa pantalla al ser el llamante.
 */
class TrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as? HuginnApplicationAccessor)?.externalLauncher?.launchPending(this)
        finish()
    }
}

/** Evita depender del tipo concreto de Application en este paquete. */
interface HuginnApplicationAccessor {
    val externalLauncher: ExternalLauncher
}
