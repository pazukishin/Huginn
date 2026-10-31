package dev.huginn.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regla de enrutado de displays (ADR 1). Se prueba la función pura con topologías
 * reales: Thor (0 arriba primario + 4 abajo), una sola pantalla y un dispositivo
 * con primario abajo.
 */
class DisplayRouterTest {

    private fun display(id: Int, name: String = "display-$id", public: Boolean = true, on: Boolean = true) =
        DisplayDescriptor(id = id, name = name, isPublic = public, isOn = on)

    @Test
    fun `thor - contenido en la superior y explorador en la inferior`() {
        val topology = DisplayRouter.pick(
            displays = listOf(display(0, "panel superior"), display(4, "panel inferior")),
            primaryId = 0,
            contentOnPrimary = true,
        )
        assertEquals(0, topology.contentDisplayId)
        assertEquals(4, topology.explorerDisplayId)
        assertTrue(topology.dualScreen)
    }

    @Test
    fun `una sola pantalla - sin doble pantalla`() {
        val topology = DisplayRouter.pick(
            displays = listOf(display(0, "phone")),
            primaryId = 0,
            contentOnPrimary = true,
        )
        assertEquals(0, topology.contentDisplayId)
        assertEquals(0, topology.explorerDisplayId)
        assertFalse(topology.dualScreen)
    }

    @Test
    fun `contenido abajo en dispositivos con primario inferior`() {
        val topology = DisplayRouter.pick(
            displays = listOf(display(0, "inferior"), display(2, "superior")),
            primaryId = 0,
            contentOnPrimary = false,
        )
        assertEquals(2, topology.contentDisplayId)
        assertEquals(0, topology.explorerDisplayId)
        assertTrue(topology.dualScreen)
    }

    @Test
    fun `ignora displays privados y apagados`() {
        val topology = DisplayRouter.pick(
            displays = listOf(
                display(0, "primario"),
                display(5, "privado", public = false),
                display(6, "apagado", on = false),
                display(7, "secundario"),
            ),
            primaryId = 0,
            contentOnPrimary = true,
        )
        assertEquals(0, topology.contentDisplayId)
        assertEquals(7, topology.explorerDisplayId)
    }


    @Test
    fun `sin displays usables cae al primario`() {
        val topology = DisplayRouter.pick(emptyList(), primaryId = 0, contentOnPrimary = true)
        assertEquals(0, topology.contentDisplayId)
        assertEquals(0, topology.explorerDisplayId)
        assertFalse(topology.dualScreen)
    }
}
