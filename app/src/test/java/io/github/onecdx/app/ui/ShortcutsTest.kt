package io.github.onecdx.app.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutsTest {

    private fun ctrl(key: Int, shift: Boolean = false) = Shortcuts.resolve(key, ctrl = true, shift = shift, alt = false)

    @Test
    fun mapsSpecShortcuts() {
        assertEquals(Command.SAVE, ctrl(KeyEvent.KEYCODE_S))
        assertEquals(Command.TOGGLE_SIDEBAR, ctrl(KeyEvent.KEYCODE_B))
        assertEquals(Command.CLOSE_TAB, ctrl(KeyEvent.KEYCODE_W))
        assertEquals(Command.QUICK_OPEN, ctrl(KeyEvent.KEYCODE_P))
        assertEquals(Command.FIND, ctrl(KeyEvent.KEYCODE_F))
        assertEquals(Command.TOGGLE_PANEL, ctrl(KeyEvent.KEYCODE_GRAVE))
        assertEquals(Command.NEXT_TAB, ctrl(KeyEvent.KEYCODE_TAB))
        assertEquals(Command.PREVIOUS_TAB, ctrl(KeyEvent.KEYCODE_TAB, shift = true))
    }

    @Test
    fun leavesEditingShortcutsToEditor() {
        assertNull(ctrl(KeyEvent.KEYCODE_C))
        assertNull(ctrl(KeyEvent.KEYCODE_V))
        assertNull(ctrl(KeyEvent.KEYCODE_Z))
        assertNull(ctrl(KeyEvent.KEYCODE_A))
        assertNull(Shortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = false, shift = false, alt = false))
        assertNull(Shortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = true, shift = false, alt = true))
    }
}
