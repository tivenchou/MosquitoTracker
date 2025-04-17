package com.example.mosquitotracker

import android.view.View
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min

@Composable
fun CameraPreview(
    controller: androidx.camera.view.CameraController,
    modifier: Modifier = Modifier,
    trackedPosition: Pair<Float, Float>? = null
) {
    val context = LocalContext.current

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(
            modifier = Modifier.fillMaxSize(),
            onDraw = {
                trackedPosition?.let { (x, y) ->
                    val crosshairSize = min(size.width, size.height) * 0.08f
                    // Horizontal line
                    drawLine(
                        color = Color.Red.copy(alpha = 0.7f),
                        start = Offset(x - crosshairSize, y),
                        end = Offset(x + crosshairSize, y),
                        strokeWidth = 2f
                    )
                    // Vertical line
                    drawLine(
                        color = Color.Red.copy(alpha = 0.7f),
                        start = Offset(x, y - crosshairSize),
                        end = Offset(x, y + crosshairSize),
                        strokeWidth = 2f
                    )
                }
            }
        )
    }
}