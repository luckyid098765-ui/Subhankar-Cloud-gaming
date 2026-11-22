package com.example.streaming

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed class RemoteInputPacket {
    @Serializable
    data class GamepadState(
        val lx: Float = 0f,
        val ly: Float = 0f,
        val rx: Float = 0f,
        val ry: Float = 0f,
        val lt: Float = 0f,
        val rt: Float = 0f,
        val buttonsMask: Int = 0,
        val seq: Long = 0L
    ) : RemoteInputPacket()

    @Serializable
    data class KeyInput(
        val keyCode: Int,
        val isDown: Boolean,
        val seq: Long = 0L
    ) : RemoteInputPacket()

    @Serializable
    data class MouseMove(
        val dx: Float,
        val dy: Float,
        val buttonsMask: Int = 0,
        val scrollY: Float = 0f,
        val seq: Long = 0L
    ) : RemoteInputPacket()
}

object GamepadButtonBits {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val BACK = 1 shl 6
    const val START = 1 shl 7
    const val LS = 1 shl 8
    const val RS = 1 shl 9
    const val DPAD_UP = 1 shl 10
    const val DPAD_DOWN = 1 shl 11
    const val DPAD_LEFT = 1 shl 12
    const val DPAD_RIGHT = 1 shl 13
}

class ControllerInputBus {
    private val json = Json { encodeDefaults = true }
    private var seqCounter = 0L

    private val _packets = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val packets: SharedFlow<String> = _packets.asSharedFlow()

    private val _activeControllerName = MutableStateFlow<String?>(null)
    val activeControllerName: StateFlow<String?> = _activeControllerName.asStateFlow()

    private var touchLx = 0f
    private var touchLy = 0f
    private var touchRx = 0f
    private var touchRy = 0f
    private var touchButtonsMask = 0
    private var hardwareButtonsMask = 0
    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN

    fun updateLeftStick(x: Float, y: Float) {
        touchLx = x.coerceIn(-1f, 1f)
        touchLy = y.coerceIn(-1f, 1f)
        emitCombinedGamepad()
    }

    fun updateRightStickDelta(dx: Float, dy: Float) {
        touchRx = (dx / 35f).coerceIn(-1f, 1f)
        touchRy = (dy / 35f).coerceIn(-1f, 1f)
        emitCombinedGamepad()
    }

    fun resetRightStick() {
        touchRx = 0f
        touchRy = 0f
        emitCombinedGamepad()
    }

    fun setTouchButton(bit: Int, pressed: Boolean) {
        touchButtonsMask = if (pressed) {
            touchButtonsMask or bit
        } else {
            touchButtonsMask and bit.inv()
        }
        emitCombinedGamepad()
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val source = event.source
        val isGamepad = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        if (isGamepad) {
            event.device?.let { _activeControllerName.value = it.name }
            val bit = mapKeyCodeToGamepadBit(event.keyCode) ?: return false
            val isDown = event.action == KeyEvent.ACTION_DOWN
            hardwareButtonsMask = if (isDown) {
                hardwareButtonsMask or bit
            } else {
                hardwareButtonsMask and bit.inv()
            }
            emitCombinedGamepad()
            return true
        }

        if ((source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) {
            if (event.repeatCount == 0 &&
                (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
            ) {
                val packet = RemoteInputPacket.KeyInput(
                    keyCode = event.keyCode,
                    isDown = event.action == KeyEvent.ACTION_DOWN,
                    seq = ++seqCounter
                )
                _packets.tryEmit(json.encodeToString<RemoteInputPacket>(packet))
                return true
            }
        }
        return false
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            event.device?.let { _activeControllerName.value = it.name }
            val lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
            val ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
            val rx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z))
            val ry = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
            val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceIn(0f, 1f)
            val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceIn(0f, 1f)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            var hatMask = hardwareButtonsMask and
                (GamepadButtonBits.DPAD_LEFT or GamepadButtonBits.DPAD_RIGHT or
                    GamepadButtonBits.DPAD_UP or GamepadButtonBits.DPAD_DOWN).inv()
            if (hatX < -0.5f) hatMask = hatMask or GamepadButtonBits.DPAD_LEFT
            if (hatX > 0.5f) hatMask = hatMask or GamepadButtonBits.DPAD_RIGHT
            if (hatY < -0.5f) hatMask = hatMask or GamepadButtonBits.DPAD_UP
            if (hatY > 0.5f) hatMask = hatMask or GamepadButtonBits.DPAD_DOWN
            hardwareButtonsMask = hatMask

            val packet = RemoteInputPacket.GamepadState(
                lx = if (lx != 0f) lx else touchLx,
                ly = if (ly != 0f) ly else touchLy,
                rx = if (rx != 0f) rx else touchRx,
                ry = if (ry != 0f) ry else touchRy,
                lt = lt,
                rt = rt,
                buttonsMask = touchButtonsMask or hardwareButtonsMask,
                seq = ++seqCounter
            )
            _packets.tryEmit(json.encodeToString<RemoteInputPacket>(packet))
            return true
        }

        if ((event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            val x = event.x
            val y = event.y
            val dx = if (lastMouseX.isNaN()) 0f else x - lastMouseX
            val dy = if (lastMouseY.isNaN()) 0f else y - lastMouseY
            lastMouseX = x
            lastMouseY = y
            val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val packet = RemoteInputPacket.MouseMove(
                dx = dx,
                dy = dy,
                buttonsMask = event.buttonState,
                scrollY = scrollY,
                seq = ++seqCounter
            )
            _packets.tryEmit(json.encodeToString<RemoteInputPacket>(packet))
            return true
        }
        return false
    }

    private fun emitCombinedGamepad() {
        val packet = RemoteInputPacket.GamepadState(
            lx = touchLx,
            ly = touchLy,
            rx = touchRx,
            ry = touchRy,
            buttonsMask = touchButtonsMask or hardwareButtonsMask,
            seq = ++seqCounter
        )
        _packets.tryEmit(json.encodeToString<RemoteInputPacket>(packet))
    }

    private fun applyDeadzone(value: Float, deadzone: Float = 0.08f): Float {
        return if (kotlin.math.abs(value) < deadzone) 0f else value.coerceIn(-1f, 1f)
    }

    private fun mapKeyCodeToGamepadBit(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> GamepadButtonBits.A
        KeyEvent.KEYCODE_BUTTON_B -> GamepadButtonBits.B
        KeyEvent.KEYCODE_BUTTON_X -> GamepadButtonBits.X
        KeyEvent.KEYCODE_BUTTON_Y -> GamepadButtonBits.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButtonBits.LB
        KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButtonBits.RB
        KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadButtonBits.BACK
        KeyEvent.KEYCODE_BUTTON_START -> GamepadButtonBits.START
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadButtonBits.LS
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadButtonBits.RS
        KeyEvent.KEYCODE_DPAD_UP -> GamepadButtonBits.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GamepadButtonBits.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GamepadButtonBits.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadButtonBits.DPAD_RIGHT
        else -> null
    }
}
