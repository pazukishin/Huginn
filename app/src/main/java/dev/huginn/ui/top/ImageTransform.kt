package dev.huginn.ui.top

/**
 * Matemática pura del zoom/pan del visor de imagen (spec: pinch = zoom, doble
 * toque alterna 1×/2,5×).
 *
 * Se mantiene sin tipos de Compose (`Float` y pares de floats) para poder testearla
 * en JVM. El visor aplica el resultado con `graphicsLayer` (escala + traslación).
 */
object ImageTransform {

    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Umbral a partir del cual se considera que la imagen está ampliada. */
    private const val ZOOMED_THRESHOLD = 1.05f

    /** Escala resultante de un pellizco, acotada al rango útil. */
    fun nextScale(scale: Float, zoomChange: Float): Float =
        (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)

    /** Escala objetivo del doble toque: alterna entre 1× y 2,5×. */
    fun doubleTapScale(scale: Float): Float =
        if (scale > ZOOMED_THRESHOLD) MIN_SCALE else DOUBLE_TAP_SCALE

    /**
     * Desplazamiento resultante de pellizcar alrededor de ([centroidX], [centroidY])
     * y arrastrar ([panX], [panY]) en una vista de [viewWidth]×[viewHeight].
     *
     * La fórmula mantiene quieto el punto bajo los dedos: para una escala `s` y una
     * traslación `t`, la coordenada de contenido bajo la pantalla `p` es
     * `q = ((p - centro) - t) / s`; se despeja `t'` para que `q` no cambie.
     */
    fun nextOffset(
        offsetX: Float,
        offsetY: Float,
        centroidX: Float,
        centroidY: Float,
        viewWidth: Float,
        viewHeight: Float,
        scaleBefore: Float,
        scaleAfter: Float,
        panX: Float,
        panY: Float,
    ): Pair<Float, Float> {
        if (viewWidth <= 0f || viewHeight <= 0f || scaleBefore <= 0f) return 0f to 0f
        val factor = scaleAfter / scaleBefore
        val pX = centroidX - viewWidth / 2f
        val pY = centroidY - viewHeight / 2f
        val zoomedX = pX * (1f - factor) + factor * offsetX
        val zoomedY = pY * (1f - factor) + factor * offsetY
        return clampOffset(zoomedX + panX, zoomedY + panY, scaleAfter, viewWidth, viewHeight)
    }

    /**
     * Acota la traslación para que la imagen ampliada no deje huecos: con escala `s`
     * el exceso por cada lado es `(ancho * (s - 1)) / 2`.
     */
    fun clampOffset(
        offsetX: Float,
        offsetY: Float,
        scale: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): Pair<Float, Float> {
        if (scale <= ZOOMED_THRESHOLD || viewWidth <= 0f || viewHeight <= 0f) return 0f to 0f
        val maxX = viewWidth * (scale - 1f) / 2f
        val maxY = viewHeight * (scale - 1f) / 2f
        return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
    }
}
