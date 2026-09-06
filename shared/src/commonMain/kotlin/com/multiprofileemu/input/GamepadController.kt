package com.multiprofileemu.input

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class GamepadAction {
    NAV_UP, NAV_DOWN, NAV_LEFT, NAV_RIGHT,
    CONFIRM, BACK, MENU,
    PAGE_UP, PAGE_DOWN,
}

/**
 * Abstraction over a physical gamepad (XInput-style layout: A/B/X/Y, bumpers, D-Pad, start/back).
 * Desktop implementation polls via GLFW/LWJGL; Android (future) will wrap InputManager/KeyEvent,
 * mirroring the state machine already proven in SaveState-App's GamepadManager.
 */
interface GamepadController {
    val connected: StateFlow<Boolean>
    val actions: SharedFlow<GamepadAction>

    fun start()
    fun stop()
}

expect fun createGamepadController(): GamepadController
