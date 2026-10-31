package dev.huginn.ui.top

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTransformTest {

    private val eps = 0.001f

    // --- escala ---

    @Test
    fun `la escala se acota entre 1x y 4x`() {
        assertEquals(1f, ImageTransform.nextScale(1f, 0.5f), eps)
        assertEquals(4f, ImageTransform.nextScale(3f, 2f), eps)
        assertEquals(2f, ImageTransform.nextScale(1f, 2f), eps)
    }

    @Test
    fun `el doble toque alterna 1x y 2,5x`() {
        assertEquals(2.5f, ImageTransform.doubleTapScale(1f), eps)
        assertEquals(1f, ImageTransform.doubleTapScale(2.5f), eps)
        assertEquals(2.5f, ImageTransform.doubleTapScale(1.04f), eps)
    }

    // --- topes del arrastre ---

    @Test
    fun `sin zoom no hay desplazamiento`() {
        assertEquals(0f to 0f, ImageTransform.clampOffset(300f, 200f, 1f, 1000f, 500f))
    }

    @Test
    fun `el desplazamiento se acota al exceso por lado`() {
        // 2x sobre 1000x500: exceso de 500 px en X y 250 en Y por lado.
        assertEquals(500f to 250f, ImageTransform.clampOffset(9_000f, 9_000f, 2f, 1000f, 500f))
        assertEquals(-500f to -250f, ImageTransform.clampOffset(-9_000f, -9_000f, 2f, 1000f, 500f))
        assertEquals(120f to -30f, ImageTransform.clampOffset(120f, -30f, 2f, 1000f, 500f))
    }

    @Test
    fun `una vista sin tamano no desplaza`() {
        assertEquals(0f to 0f, ImageTransform.nextOffset(0f, 0f, 10f, 10f, 0f, 0f, 1f, 2f, 5f, 5f))
    }

    // --- zoom alrededor del punto pellizcado ---

    @Test
    fun `pellizcar en el centro no desplaza`() {
        val (x, y) = ImageTransform.nextOffset(
            offsetX = 0f, offsetY = 0f,
            centroidX = 500f, centroidY = 250f,
            viewWidth = 1000f, viewHeight = 500f,
            scaleBefore = 1f, scaleAfter = 2f,
            panX = 0f, panY = 0f,
        )
        assertEquals(0f, x, eps)
        assertEquals(0f, y, eps)
    }

    @Test
    fun `pellizcar a la derecha desplaza para mantener el punto bajo los dedos`() {
        // Centro en x=500; dedos en x=750 (p=+250). Al doblar la escala, t = p - s*q.
        // Antes q = 250; después t = 250 - 2*250 = -250.
        val (x, _) = ImageTransform.nextOffset(
            offsetX = 0f, offsetY = 0f,
            centroidX = 750f, centroidY = 250f,
            viewWidth = 1000f, viewHeight = 500f,
            scaleBefore = 1f, scaleAfter = 2f,
            panX = 0f, panY = 0f,
        )
        assertEquals(-250f, x, eps)
    }

    @Test
    fun `el arrastre se suma al zoom y queda acotado`() {
        val (x, _) = ImageTransform.nextOffset(
            offsetX = 0f, offsetY = 0f,
            centroidX = 500f, centroidY = 250f,
            viewWidth = 1000f, viewHeight = 500f,
            scaleBefore = 2f, scaleAfter = 2f,
            panX = 10_000f, panY = 0f,
        )
        assertEquals(500f, x, eps)
    }
}
