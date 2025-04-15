package com.example.mosquitotracker

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import java.lang.Float.min

@Composable
fun CameraPreview(
    controller: androidx.camera.view.CameraController,
    modifier: Modifier = Modifier,
    trackedPosition: Pair<Float, Float>? = null
) {
    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            trackedPosition?.let { (x, y) ->
                val centerX = size.width * x
                val centerY = size.height * y

                // Draw crosshair
                val crosshairSize = min(size.width, size.height) * 0.1f
                drawLine(
                    color = Color.Red,
                    start = Offset(centerX - crosshairSize, centerY),
                    end = Offset(centerX + crosshairSize, centerY),
                    strokeWidth = 3f
                )
                drawLine(
                    color = Color.Red,
                    start = Offset(centerX, centerY - crosshairSize),
                    end = Offset(centerX, centerY + crosshairSize),
                    strokeWidth = 3f
                )

                // Draw circle around target
                drawCircle(
                    color = Color.Red,
                    center = Offset(centerX, centerY),
                    radius = crosshairSize * 0.8f,
                    style = Stroke(width = 3f)
                )
            }
        }
    }
}