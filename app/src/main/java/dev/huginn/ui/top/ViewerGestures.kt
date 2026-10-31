package dev.huginn.ui.top

/**
 * Reglas puras de los gestos táctiles del visor (spec §1.2.1 / §4).
 *
 * Vídeo, estilo YouTube (petición del usuario, 30-oct-2026):
 * - **doble toque** en la mitad izquierda = −10 s y en la derecha = +10 s;
 * - **arrastre horizontal** = desplaza el reproductor en proporción al recorrido y
 *   en la dirección del gesto (un ancho de pantalla equivale a
 *   [SWIPE_FULL_WIDTH_MS], acotado por la duración del medio).
 *
 * Imagen: pinch = zoom libre y arrastre = pan (lo resuelve Compose); el doble toque
 * alterna 1×/2,5× (ver `TopPanelContent`).
 *
 * Se mantiene aparte y sin dependencias de Compose para poder testearlo.
 */
object ViewerGestures {

    /** Paso del doble toque, igual que L1/R1 (10 s). */
    const val DOUBLE_TAP_STEP_MS: Long = 10_000L

    /** Un arrastre a lo ancho de la pantalla desplaza como máximo 2 min. */
    const val SWIPE_FULL_WIDTH_MS: Long = 120_000L

    /** Delta del doble toque según la mitad de la pantalla tocada. */
    fun doubleTapDelta(xPx: Float, widthPx: Float): Long =
        if (widthPx > 0f && xPx >= widthPx / 2f) DOUBLE_TAP_STEP_MS else -DOUBLE_TAP_STEP_MS

    /**
     * Delta de un arrastre horizontal: proporcional al recorrido ([dragPx]) respecto
     * al ancho de la vista, sobre un rango de [SWIPE_FULL_WIDTH_MS] (o la duración si
     * el medio es más corto). Derecha = adelante, izquierda = atrás.
     */
    fun swipeDeltaMs(dragPx: Float, widthPx: Float, lengthMs: Long): Long {
        if (widthPx <= 0f || lengthMs <= 0L || dragPx == 0f) return 0L
        val range = minOf(lengthMs, SWIPE_FULL_WIDTH_MS)
        return ((dragPx / widthPx) * range).toLong()
    }

    /** Posición destino de un arrastre que empezó en [startMs] (acotada al medio). */
    fun targetPositionMs(startMs: Long, dragPx: Float, widthPx: Float, lengthMs: Long): Long {
        if (lengthMs <= 0L) return 0L
        val target = startMs + swipeDeltaMs(dragPx, widthPx, lengthMs)
        return target.coerceIn(0L, lengthMs)
    }
}
