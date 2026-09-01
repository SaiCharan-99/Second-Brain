package com.secondbrain.app.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.unit.dp
import com.github.sarxos.webcam.WebcamPanel
import com.secondbrain.app.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage

/**
 * Stage 2 / D-096: the floating capture window. A [Camera] is opened fresh
 * every time this composable becomes visible and closed the instant it isn't
 * — [DisposableEffect] is what makes "camera resources stop when the window
 * closes" true regardless of which of Cancel / Use photo / an app-level
 * navigation away actually closed it, rather than trusting every button's own
 * click handler to remember to release the device.
 *
 * States, and what the plan's own checklist asked for each: no camera found
 * ([Camera.OpenResult.NoCamera]), the device already in use elsewhere
 * ([Camera.OpenResult.Busy]), any other open failure — permission denial on
 * Windows surfaces through the same native `IOException` path Sarxos gives no
 * finer-grained type for, so it lands in [Camera.OpenResult.Failed] with
 * whatever the OS actually said rather than a made-up "permission denied"
 * guess. [ImageIntake.pickFile] (the file picker from D-084) stays exactly as
 * it was — this is a second way in, not a replacement.
 */
@Composable
fun CameraWindow(controller: VoiceController) {
    val state by controller.state.collectAsState()
    if (!state.cameraWindowOpen) return

    val scope = rememberCoroutineScope()
    val camera = remember { Camera() }
    var openState by remember { mutableStateOf<Camera.OpenResult?>(null) }
    var cameraNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var frozenFrame by remember { mutableStateOf<BufferedImage?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    fun openSelected(name: String?) {
        scope.launch {
            openState = null
            frozenFrame = null
            val result = withContext(Dispatchers.IO) {
                if (camera.isOpen) camera.close()
                camera.open(name)
            }
            openState = result
        }
    }

    // Opens once when the window appears - not on every recomposition, and
    // deliberately off the UI thread (Camera.open()'s underlying open() call
    // is itself blocking, same discipline ImageIntake.pickFile already follows).
    LaunchedEffect(Unit) {
        cameraNames = withContext(Dispatchers.IO) { camera.listCameras() }
        openSelected(null)
    }

    // The one thing that must happen no matter how this composable stops
    // being shown - closing the window, switching screens, the app itself
    // exiting mid-capture.
    DisposableEffect(Unit) {
        onDispose { camera.close() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(560.dp).padding(24.dp),
            color = AppColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AppColors.BorderStrong),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("CAMERA", style = MaterialTheme.typography.labelLarge, color = AppColors.Blue)
                    if (cameraNames.size > 1) {
                        Box {
                            OutlinedButton(onClick = { menuExpanded = true }) { Text("Choose camera") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                cameraNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { menuExpanded = false; openSelected(name) },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier.fillMaxWidth().height(360.dp).background(Color.Black, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val frozen = frozenFrame
                    when {
                        frozen != null -> Image2(frozen)
                        openState is Camera.OpenResult.Opened -> {
                            val webcam = camera.handle()
                            if (webcam != null) {
                                SwingPanel(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = {
                                        WebcamPanel(webcam, false).apply {
                                            isFPSDisplayed = false
                                            isImageSizeDisplayed = false
                                            isFillArea = true
                                            start()
                                        }
                                    },
                                )
                            }
                        }
                        openState is Camera.OpenResult.NoCamera -> CameraMessage(
                            "No camera found. You can still attach a photo from a file instead.",
                        )
                        openState is Camera.OpenResult.Busy -> CameraMessage(
                            "The camera is already in use by something else. Close whatever else has it open and try again.",
                        )
                        openState is Camera.OpenResult.Failed -> CameraMessage(
                            "Couldn't open the camera: ${(openState as Camera.OpenResult.Failed).reason}",
                        )
                        else -> Text("Opening camera…", color = AppColors.Muted)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { camera.close(); controller.closeCameraWindow() }) { Text("Cancel") }

                    if (frozenFrame == null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    frozenFrame = withContext(Dispatchers.IO) { camera.captureFrame() }
                                }
                            },
                            enabled = openState is Camera.OpenResult.Opened,
                        ) { Text("Capture") }
                    } else {
                        OutlinedButton(onClick = { frozenFrame = null }) { Text("Retake") }
                        Button(onClick = { controller.attachCapturedFrame(frozenFrame!!) }) { Text("Use photo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraMessage(text: String) {
    Text(
        text,
        modifier = Modifier.padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.Muted,
    )
}

/** A plain `BufferedImage` as a Compose `Image` — the frozen-frame preview after Capture, before Use photo/Retake. */
@Composable
private fun Image2(frame: BufferedImage) {
    androidx.compose.foundation.Image(
        painter = frame.toPainter(),
        contentDescription = "Captured photo",
        modifier = Modifier.fillMaxSize(),
    )
}
