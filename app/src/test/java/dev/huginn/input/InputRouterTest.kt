package dev.huginn.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapa de botones (requisito 1.2 y spec §6). Test puro: sin Android en ejecución. */
class InputRouterTest {

    @Test
    fun `cruceta mueve y navega`() {
        assertEquals(InputAction.UP, InputRouter.map(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(InputAction.DOWN, InputRouter.map(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(InputAction.LEFT, InputRouter.map(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(InputAction.RIGHT, InputRouter.map(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `botones del mando`() {
        assertEquals(InputAction.ACCEPT, InputRouter.map(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(InputAction.BACK, InputRouter.map(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(InputAction.PLAY_PAUSE, InputRouter.map(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(InputAction.TOGGLE_CONTROLS, InputRouter.map(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(InputAction.SEEK_BACK, InputRouter.map(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(InputAction.SEEK_FORWARD, InputRouter.map(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(InputAction.PREV_ITEM, InputRouter.map(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(InputAction.NEXT_ITEM, InputRouter.map(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(InputAction.TOGGLE_FOCUS, InputRouter.map(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(InputAction.MENU, InputRouter.map(KeyEvent.KEYCODE_BUTTON_START))
    }

    @Test
    fun `teclado equivalente y teclas ajenas`() {
        assertEquals(InputAction.ACCEPT, InputRouter.map(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(InputAction.ACCEPT, InputRouter.map(KeyEvent.KEYCODE_ENTER))
        assertEquals(InputAction.BACK, InputRouter.map(KeyEvent.KEYCODE_ESCAPE))
        assertNull(InputRouter.map(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(InputRouter.map(KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun `solo repetibles las de navegacion y seek`() {
        assertTrue(InputAction.UP.isRepeatable())
        assertTrue(InputAction.DOWN.isRepeatable())
        assertTrue(InputAction.SEEK_BACK.isRepeatable())
        assertTrue(InputAction.SEEK_FORWARD.isRepeatable())
        assertTrue(!InputAction.ACCEPT.isRepeatable())
        assertTrue(!InputAction.BACK.isRepeatable())
        assertTrue(!InputAction.MENU.isRepeatable())
    }
}
