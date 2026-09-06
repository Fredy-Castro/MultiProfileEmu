package com.multiprofileemu.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import com.multiprofileemu.MultiProfileEmuApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Port of SaveState-App's GamepadManager, adapted to emit the common [GamepadAction] set instead
 * of app-specific actions — same button layout as [com.multiprofileemu.input.DesktopGamepadController]
 * so behavior matches between platforms.
 *
 * Unlike the desktop controller (which polls via GLFW), Android delivers key events push-style
 * through `Activity.dispatchKeyEvent`, so [onKeyEvent] must be called from there — see
 * MainActivity. [start]/[stop] only manage the InputManager listener used for [connected].
 */
class AndroidGamepadController(private val context: Context) : GamepadController {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _actions = MutableSharedFlow<GamepadAction>(extraBufferCapacity = 16)
    override val actions: SharedFlow<GamepadAction> = _actions.asSharedFlow()

    private var repeatJob: Job? = null
    private var repeatKeyCode: Int? = null

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateConnected()
        override fun onInputDeviceRemoved(deviceId: Int) = updateConnected()
        override fun onInputDeviceChanged(deviceId: Int) = updateConnected()
    }

    private val buttonActions = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to GamepadAction.CONFIRM,
        KeyEvent.KEYCODE_BUTTON_B to GamepadAction.BACK,
        KeyEvent.KEYCODE_BUTTON_START to GamepadAction.MENU,
        KeyEvent.KEYCODE_BUTTON_L1 to GamepadAction.PAGE_UP,
        KeyEvent.KEYCODE_BUTTON_R1 to GamepadAction.PAGE_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to GamepadAction.NAV_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to GamepadAction.NAV_RIGHT,
    )
    private val repeatableKeyCodes = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to GamepadAction.NAV_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to GamepadAction.NAV_DOWN,
    )

    override fun start() {
        updateConnected()
        inputManager.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
    }

    override fun stop() {
        inputManager.unregisterInputDeviceListener(deviceListener)
        stopRepeat()
    }

    /** Called from `Activity.dispatchKeyEvent`. Returns true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isGamepadEvent(event)) return false

        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            repeatableKeyCodes[keyCode]?.let { action ->
                startRepeat(keyCode, action)
                return true
            }
            buttonActions[keyCode]?.let { action ->
                _actions.tryEmit(action)
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == repeatKeyCode) {
                stopRepeat()
                return true
            }
            if (keyCode in buttonActions || keyCode in repeatableKeyCodes) return true
        }
        return false
    }

    /** Called from `Activity.dispatchTouchEvent` — touch input cancels any in-flight D-Pad repeat. */
    fun onTouchEvent() {
        stopRepeat()
    }

    private fun isGamepadEvent(event: KeyEvent): Boolean {
        if (event.keyCode in buttonActions || event.keyCode in repeatableKeyCodes) return true
        val source = event.source
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun updateConnected() {
        val connected = InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id)
            device != null && !device.isVirtual &&
                ((device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        }
        _connected.value = connected
    }

    private fun startRepeat(keyCode: Int, action: GamepadAction) {
        if (repeatKeyCode == keyCode) return
        stopRepeat()
        repeatKeyCode = keyCode
        repeatJob = scope.launch {
            _actions.tryEmit(action)
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
        repeatKeyCode = null
    }
}

actual fun createGamepadController(): GamepadController = AndroidGamepadController(MultiProfileEmuApplication.instance)
