package io.github.onecdx.app.ui

import android.view.KeyEvent

enum class Command {
    SAVE, TOGGLE_SIDEBAR, CLOSE_TAB, QUICK_OPEN, FIND, FIND_REPLACE, TOGGLE_PANEL, OPEN_FOLDER, SERVERS, NEXT_TAB, PREVIOUS_TAB,
}

/** Pintasan keyboard fisik (DeX). Kombinasi lain diteruskan ke editor (Ctrl+C/V/X/Z/Y/A, dll). */
object Shortcuts {
    fun resolve(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): Command? {
        if (!ctrl || alt) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_S -> if (shift) null else Command.SAVE
            KeyEvent.KEYCODE_B -> Command.TOGGLE_SIDEBAR
            KeyEvent.KEYCODE_W -> Command.CLOSE_TAB
            KeyEvent.KEYCODE_P -> Command.QUICK_OPEN
            KeyEvent.KEYCODE_F -> Command.FIND
            KeyEvent.KEYCODE_H -> Command.FIND_REPLACE
            KeyEvent.KEYCODE_GRAVE -> Command.TOGGLE_PANEL
            KeyEvent.KEYCODE_O -> if (shift) Command.SERVERS else Command.OPEN_FOLDER
            KeyEvent.KEYCODE_TAB -> if (shift) Command.PREVIOUS_TAB else Command.NEXT_TAB
            KeyEvent.KEYCODE_PAGE_DOWN -> Command.NEXT_TAB
            KeyEvent.KEYCODE_PAGE_UP -> Command.PREVIOUS_TAB
            else -> null
        }
    }
}
