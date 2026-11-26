package io.github.onecdx.app.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import androidx.core.view.updateLayoutParams

/**
 * Membuat [handle] bisa di-drag (mouse/touch) untuk mengubah lebar/tinggi [target].
 *
 * @param horizontal true = mengubah lebar (divider vertikal), false = mengubah tinggi.
 * @param inverted true jika menyeret ke kiri/atas justru memperbesar target (panel bawah).
 */
class DragResizer(
    private val handle: View,
    private val target: View,
    private val horizontal: Boolean,
    private val inverted: Boolean,
    private val minSize: () -> Int,
    private val maxSize: () -> Int,
    private val onResized: (Int) -> Unit,
) {
    private var startRaw = 0f
    private var startSize = 0

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        handle.pointerIcon = PointerIcon.getSystemIcon(
            handle.context,
            if (horizontal) PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW else PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,
        )
        handle.setOnTouchListener { v, e -> onTouch(v, e) }
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        val raw = if (horizontal) e.rawX else e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRaw = raw
                startSize = if (horizontal) target.width else target.height
                v.isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = (raw - startRaw).let { if (inverted) -it else it }
                val min = minSize()
                val size = (startSize + delta).toInt().coerceIn(min, maxSize().coerceAtLeast(min))
                target.updateLayoutParams { if (horizontal) width = size else height = size }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                onResized(if (horizontal) target.width else target.height)
            }
        }
        return true
    }
}
