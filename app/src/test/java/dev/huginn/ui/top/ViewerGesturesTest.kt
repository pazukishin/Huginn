package dev.huginn.ui.top

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerGesturesTest {

    // --- doble toque: mitad izquierda = −10 s, derecha = +10 s ---

    @Test
    fun `doble toque en la mitad izquierda retrocede 10 s`() {
        assertEquals(-10_000L, ViewerGestures.doubleTapDelta(xPx = 100f, widthPx = 1920f))
        assertEquals(-10_000L, ViewerGestures.doubleTapDelta(xPx = 959f, widthPx = 1920f))
    }

    @Test
    fun `doble toque en la mitad derecha avanza 10 s`() {
        assertEquals(10_000L, ViewerGestures.doubleTapDelta(xPx = 960f, widthPx = 1920f))
        assertEquals(10_000L, ViewerGestures.doubleTapDelta(xPx = 1920f, widthPx = 1920f))
    }

    @Test
    fun `doble toque sin ancho util retrocede (no divide por cero)`() {
        assertEquals(-10_000L, ViewerGestures.doubleTapDelta(xPx = 0f, widthPx = 0f))
    }

    // --- arrastre: proporcional al recorrido y a la dirección ---

    @Test
    fun `arrastre a lo ancho de una pelicula larga mueve 2 minutos`() {
        val length = 2 * 60 * 60 * 1000L // 2 h
        assertEquals(120_000L, ViewerGestures.swipeDeltaMs(dragPx = 1920f, widthPx = 1920f, lengthMs = length))
        assertEquals(-60_000L, ViewerGestures.swipeDeltaMs(dragPx = -960f, widthPx = 1920f, lengthMs = length))
    }

    @Test
    fun `arrastre en un clip corto no supera su duracion`() {
        val length = 20_000L // 20 s
        assertEquals(20_000L, ViewerGestures.swipeDeltaMs(dragPx = 1920f, widthPx = 1920f, lengthMs = length))
        assertEquals(10_000L, ViewerGestures.swipeDeltaMs(dragPx = 960f, widthPx = 1920f, lengthMs = length))
    }

    @Test
    fun `arrastre nulo o sin dimensione devuelve cero`() {
        assertEquals(0L, ViewerGestures.swipeDeltaMs(dragPx = 0f, widthPx = 1920f, lengthMs = 60_000L))
        assertEquals(0L, ViewerGestures.swipeDeltaMs(dragPx = 500f, widthPx = 0f, lengthMs = 60_000L))
        assertEquals(0L, ViewerGestures.swipeDeltaMs(dragPx = 500f, widthPx = 1920f, lengthMs = 0L))
    }

    // --- posición destino: acotada al medio ---

    @Test
    fun `la posicion destino se acota entre cero y la duracion`() {
        val length = 100_000L
        assertEquals(0L, ViewerGestures.targetPositionMs(startMs = 1_000L, dragPx = -1920f, widthPx = 1920f, lengthMs = length))
        assertEquals(length, ViewerGestures.targetPositionMs(startMs = 99_000L, dragPx = 1920f, widthPx = 1920f, lengthMs = length))
    }

    @Test
    fun `la posicion destino conserva el punto de partida con desplazamiento pequeno`() {
        val target = ViewerGestures.targetPositionMs(startMs = 30_000L, dragPx = 192f, widthPx = 1920f, lengthMs = 100_000L)
        // 10 % del ancho = 10 % del rango (100 s) = 10 s.
        assertEquals(40_000L, target)
    }
}
