package com.multiprofileemu.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tracks which row of a linear list is "focused" for gamepad/keyboard navigation,
 * independent of mouse hover. Mirrors the index math SaveState-App's GamepadManager
 * uses for its profile list (wrap-around single-step nav, +/-5 page jumps).
 */
class FocusListState(initialItemCount: Int) {
    var itemCount: Int = initialItemCount
        private set

    var focusedIndex by mutableStateOf(if (initialItemCount > 0) 0 else -1)
        private set

    fun updateItemCount(count: Int) {
        itemCount = count
        focusedIndex = when {
            count == 0 -> -1
            focusedIndex == -1 -> 0
            focusedIndex >= count -> count - 1
            else -> focusedIndex
        }
    }

    fun moveUp() {
        if (itemCount == 0) return
        focusedIndex = if (focusedIndex <= 0) itemCount - 1 else focusedIndex - 1
    }

    fun moveDown() {
        if (itemCount == 0) return
        focusedIndex = if (focusedIndex == -1 || focusedIndex >= itemCount - 1) 0 else focusedIndex + 1
    }

    fun pageUp() {
        if (itemCount == 0) return
        focusedIndex = if (focusedIndex <= 0) itemCount - 1 else (focusedIndex - 5).coerceAtLeast(0)
    }

    fun pageDown() {
        if (itemCount == 0) return
        focusedIndex = if (focusedIndex == -1 || focusedIndex >= itemCount - 1) 0 else (focusedIndex + 5).coerceAtMost(itemCount - 1)
    }
}
