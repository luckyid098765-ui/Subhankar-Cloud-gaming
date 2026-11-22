package com.example.ui.screens

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.local.TouchControlPlacementEntity
import com.example.data.local.UserSettings
import com.example.data.model.StartSessionResponse
import com.example.data.model.StreamQualityPreset
import com.example.data.model.StreamTelemetry
import com.example.streaming.ControllerInputBus
import com.example.streaming.GamepadButtonBits
import com.example.streaming.WebRtcStreamingManager
import com.example.ui.theme.AmberLatency
import com.example.ui.theme.EmeraldOnline
import org.webrtc.SurfaceViewRenderer
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun StreamPlayerScreen(
    session: StartSessionResponse?,
    remainingSeconds: Int,
    warningBanner: String?,
    telemetry: StreamTelemetry,
    settings: UserSettings,
    touchPlacements: List<TouchControlPlacementEntity>,
    webRtcManager: WebRtcStreamingManager,
    inputBus: ControllerInputBus,
    onSaveButtonPosition: (String, Float, Float) -> Unit,
    onExitStream: () -> Unit
) {
    var showTouchControls by remember { mutableStateOf(true) }
    var isEditingLayout by remember { mutableStateOf(false) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    val view = LocalView.current

    BackHandler {
        showExitConfirm = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Real WebRTC Video Renderer Surface
        var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { svr ->
                    rendererRef = svr
                    webRtcManager.attachRenderer(svr)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        DisposableEffect(Unit) {
            onDispose {
                rendererRef?.let { webRtcManager.detachRenderer(it) }
            }
        }

        // Right-side Camera Drag Touchpad (when not editing layout)
        if (!isEditingLayout) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.5f)
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { inputBus.resetRightStick() },
                            onDragCancel = { inputBus.resetRightStick() }
                        ) { change, dragAmount ->
                            change.consume()
                            inputBus.updateRightStickDelta(dragAmount.x, dragAmount.y)
                        }
                    }
            )
        }

        // Top HUD Bar (Telemetry, Remaining Coin Time, Quality Switcher, Layout Editor, Exit)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = session?.gameTitle ?: "Cloud Stream",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (settings.showTelemetryOverlay) {
                            Text(
                                text = "${telemetry.connectionState} • ${telemetry.rttMs}ms • ${telemetry.fps}FPS • ${telemetry.bitrateKbps}kbps • ${telemetry.activeCodec}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (telemetry.isReconnecting) AmberLatency else EmeraldOnline
                            )
                        }
                        if (!telemetry.activeGamepadName.isNullOrBlank()) {
                            Icon(
                                imageVector = Icons.Rounded.Gamepad,
                                contentDescription = "Hardware gamepad connected",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val mins = remainingSeconds / 60
                    val secs = remainingSeconds % 60
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (remainingSeconds <= 300) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
                        }
                    ) {
                        Text(
                            text = String.format("%02d:%02d", mins, secs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showQualityMenu = true },
                            modifier = Modifier.testTag("stream_quality_button")
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Switch stream quality")
                        }
                        DropdownMenu(
                            expanded = showQualityMenu,
                            onDismissRequest = { showQualityMenu = false }
                        ) {
                            StreamQualityPreset.entries.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.label) },
                                    onClick = {
                                        showQualityMenu = false
                                        webRtcManager.requestQualityChange(
                                            preset.resolution,
                                            preset.fps,
                                            preset.maxBitrateKbps
                                        )
                                    }
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { isEditingLayout = !isEditingLayout },
                        modifier = Modifier.testTag("stream_edit_controls_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Customize button positions",
                            tint = if (isEditingLayout) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = { showTouchControls = !showTouchControls },
                        modifier = Modifier.testTag("stream_toggle_touch_button")
                    ) {
                        Icon(
                            imageVector = if (showTouchControls) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            contentDescription = "Toggle touchscreen controls"
                        )
                    }

                    IconButton(
                        onClick = { showExitConfirm = true },
                        modifier = Modifier.testTag("stream_exit_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "End cloud session",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (!warningBanner.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(warningBanner, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            if (isEditingLayout) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                ) {
                    Text(
                        text = "Drag any virtual button or stick to reposition it. Tap Edit icon again when done.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Virtual Touchscreen Controls Overlay
        if (showTouchControls) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val heightPx = with(LocalDensity.current) { maxHeight.toPx() }

                touchPlacements.forEach { placement ->
                    if (placement.controlId == "left_stick") {
                        VirtualJoystickControl(
                            xFraction = placement.xFraction,
                            yFraction = placement.yFraction,
                            containerWidthPx = widthPx,
                            containerHeightPx = heightPx,
                            isEditMode = isEditingLayout,
                            onReposition = { nx, ny ->
                                onSaveButtonPosition(placement.controlId, nx, ny)
                            },
                            onStickMove = { lx, ly ->
                                inputBus.updateLeftStick(lx, ly)
                            }
                        )
                    } else {
                        val buttonBit = mapControlIdToBit(placement.controlId)
                        VirtualActionButton(
                            label = placement.label,
                            xFraction = placement.xFraction,
                            yFraction = placement.yFraction,
                            containerWidthPx = widthPx,
                            containerHeightPx = heightPx,
                            isEditMode = isEditingLayout,
                            onReposition = { nx, ny ->
                                onSaveButtonPosition(placement.controlId, nx, ny)
                            },
                            onPressedChange = { pressed ->
                                if (pressed && settings.hapticFeedback) {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                }
                                if (buttonBit != 0) {
                                    inputBus.setTouchButton(buttonBit, pressed)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("End Cloud Session?") },
            text = { Text("Disconnecting will close your remote PC game and settle unused coin minutes.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        onExitStream()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("End Session")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text("Keep Playing")
                }
            }
        )
    }
}

@Composable
private fun VirtualJoystickControl(
    xFraction: Float,
    yFraction: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
    isEditMode: Boolean,
    onReposition: (Float, Float) -> Unit,
    onStickMove: (Float, Float) -> Unit
) {
    val sizeDp = 112.dp
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }
    val maxRadiusPx = sizePx * 0.36f
    var knobX by remember { mutableFloatStateOf(0f) }
    var knobY by remember { mutableFloatStateOf(0f) }
    var curX by remember(xFraction) { mutableFloatStateOf(xFraction) }
    var curY by remember(yFraction) { mutableFloatStateOf(yFraction) }

    val offsetX = ((curX * containerWidthPx) - sizePx / 2f).roundToInt()
    val offsetY = ((curY * containerHeightPx) - sizePx / 2f).roundToInt()

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(sizeDp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.48f))
            .border(
                width = 2.dp,
                color = if (isEditMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                shape = CircleShape
            )
            .pointerInput(isEditMode) {
                detectDragGestures(
                    onDragEnd = {
                        if (isEditMode) {
                            onReposition(curX, curY)
                        } else {
                            knobX = 0f
                            knobY = 0f
                            onStickMove(0f, 0f)
                        }
                    },
                    onDragCancel = {
                        knobX = 0f
                        knobY = 0f
                        onStickMove(0f, 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    if (isEditMode) {
                        curX = (curX + dragAmount.x / containerWidthPx).coerceIn(0.08f, 0.92f)
                        curY = (curY + dragAmount.y / containerHeightPx).coerceIn(0.15f, 0.88f)
                    } else {
                        val nx = knobX + dragAmount.x
                        val ny = knobY + dragAmount.y
                        val dist = hypot(nx, ny)
                        if (dist > maxRadiusPx) {
                            knobX = (nx / dist) * maxRadiusPx
                            knobY = (ny / dist) * maxRadiusPx
                        } else {
                            knobX = nx
                            knobY = ny
                        }
                        onStickMove(knobX / maxRadiusPx, knobY / maxRadiusPx)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knobX.roundToInt(), knobY.roundToInt()) }
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.78f))
        )
    }
}

@Composable
private fun VirtualActionButton(
    label: String,
    xFraction: Float,
    yFraction: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
    isEditMode: Boolean,
    onReposition: (Float, Float) -> Unit,
    onPressedChange: (Boolean) -> Unit
) {
    val sizeDp = 58.dp
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }
    var curX by remember(xFraction) { mutableFloatStateOf(xFraction) }
    var curY by remember(yFraction) { mutableFloatStateOf(yFraction) }
    var isPressed by remember { mutableStateOf(false) }

    val offsetX = ((curX * containerWidthPx) - sizePx / 2f).roundToInt()
    val offsetY = ((curY * containerHeightPx) - sizePx / 2f).roundToInt()

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(sizeDp)
            .clip(CircleShape)
            .background(
                if (isPressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.56f)
            )
            .border(
                width = 1.5.dp,
                color = if (isEditMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                shape = CircleShape
            )
            .pointerInput(isEditMode) {
                if (isEditMode) {
                    detectDragGestures(
                        onDragEnd = { onReposition(curX, curY) }
                    ) { change, dragAmount ->
                        change.consume()
                        curX = (curX + dragAmount.x / containerWidthPx).coerceIn(0.05f, 0.95f)
                        curY = (curY + dragAmount.y / containerHeightPx).coerceIn(0.10f, 0.90f)
                    }
                } else {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.any { it.pressed }
                            if (down != isPressed) {
                                isPressed = down
                                onPressedChange(down)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun mapControlIdToBit(controlId: String): Int = when (controlId) {
    "btn_a" -> GamepadButtonBits.A
    "btn_b" -> GamepadButtonBits.B
    "btn_x" -> GamepadButtonBits.X
    "btn_y" -> GamepadButtonBits.Y
    "btn_lb" -> GamepadButtonBits.LB
    "btn_rb" -> GamepadButtonBits.RB
    "dpad_up" -> GamepadButtonBits.DPAD_UP
    else -> 0
}
