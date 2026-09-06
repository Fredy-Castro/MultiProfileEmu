package com.multiprofileemu.input

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_A
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_B
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER
import org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_START
import org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_1
import org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_16
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetGamepadState
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwJoystickIsGamepad
import org.lwjgl.glfw.GLFW.glfwJoystickPresent
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFWGamepadState
import java.util.concurrent.Executors

/**
 * Polls the first connected gamepad via GLFW. GLFW exposes the same "standard gamepad"
 * button layout (A/B/X/Y, bumpers, D-Pad, start) on top of XInput on Windows and
 * evdev/joystick on Linux, so this one implementation covers both desktop targets
 * without any native XInput/DirectInput code of our own.
 *
 * All GLFW calls are confined to a single dedicated thread — GLFW's threading story on
 * Windows/Linux tolerates sequential (never-concurrent) calls from one consistent thread,
 * which a shared coroutine dispatcher cannot guarantee across suspension points.
 */
class DesktopGamepadController : GamepadController {
    private val gamepadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "multiprofileemu-gamepad").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(gamepadExecutor.asCoroutineDispatcher() + SupervisorJob())

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _actions = MutableSharedFlow<GamepadAction>(extraBufferCapacity = 16)
    override val actions: SharedFlow<GamepadAction> = _actions.asSharedFlow()

    private var pollJob: Job? = null
    private val prevPressed = BooleanArray(GLFW_GAMEPAD_BUTTON_LAST + 1)
    private var repeatJob: Job? = null
    private var repeatButton: Int? = null

    private val buttonActions = mapOf(
        GLFW_GAMEPAD_BUTTON_DPAD_UP to GamepadAction.NAV_UP,
        GLFW_GAMEPAD_BUTTON_DPAD_DOWN to GamepadAction.NAV_DOWN,
        GLFW_GAMEPAD_BUTTON_DPAD_LEFT to GamepadAction.NAV_LEFT,
        GLFW_GAMEPAD_BUTTON_DPAD_RIGHT to GamepadAction.NAV_RIGHT,
        GLFW_GAMEPAD_BUTTON_A to GamepadAction.CONFIRM,
        GLFW_GAMEPAD_BUTTON_B to GamepadAction.BACK,
        GLFW_GAMEPAD_BUTTON_START to GamepadAction.MENU,
        GLFW_GAMEPAD_BUTTON_LEFT_BUMPER to GamepadAction.PAGE_UP,
        GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER to GamepadAction.PAGE_DOWN,
    )
    private val repeatableActions = setOf(GamepadAction.NAV_UP, GamepadAction.NAV_DOWN)

    override fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            if (!glfwInit()) return@launch

            glfwWindowHint(GLFW_VISIBLE, 0)
            val window = glfwCreateWindow(1, 1, "multiprofileemu-gamepad", 0L, 0L)
            try {
                val state = GLFWGamepadState.create()
                while (isActive) {
                    glfwPollEvents()
                    pollGamepad(state)
                    delay(16)
                }
            } finally {
                if (window != 0L) glfwDestroyWindow(window)
                glfwTerminate()
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun pollGamepad(state: GLFWGamepadState) {
        var found = false
        for (jid in GLFW_JOYSTICK_1..GLFW_JOYSTICK_16) {
            if (glfwJoystickPresent(jid) && glfwJoystickIsGamepad(jid)) {
                found = true
                if (glfwGetGamepadState(jid, state)) {
                    handleButtons(state)
                }
                break
            }
        }
        _connected.value = found
    }

    private fun handleButtons(state: GLFWGamepadState) {
        val buttons = state.buttons()
        for ((glfwButton, action) in buttonActions) {
            val pressed = buttons.get(glfwButton).toInt() == GLFW_PRESS
            val wasPressed = prevPressed[glfwButton]
            if (pressed && !wasPressed) {
                _actions.tryEmit(action)
                if (action in repeatableActions) startRepeat(glfwButton, action)
            } else if (!pressed && wasPressed && repeatButton == glfwButton) {
                stopRepeat()
            }
            prevPressed[glfwButton] = pressed
        }
    }

    private fun startRepeat(button: Int, action: GamepadAction) {
        if (repeatButton == button) return
        stopRepeat()
        repeatButton = button
        repeatJob = scope.launch {
            delay(450)
            while (isActive) {
                _actions.tryEmit(action)
                delay(120)
            }
        }
    }

    private fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        repeatButton = null
    }
}

actual fun createGamepadController(): GamepadController = DesktopGamepadController()
