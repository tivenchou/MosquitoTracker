package com.example.mosquitotracker

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
//import java.nio.ByteBuffer

@Composable
fun CameraScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(
                CameraController.IMAGE_ANALYSIS or
                        CameraController.IMAGE_CAPTURE
            )
        }
    }

    var prevBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        controller.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(context),
            object : ImageAnalysis.Analyzer {
                override fun analyze(image: ImageProxy) {
                    val currentBitmap = image.toBitmap()

                    if (prevBitmap != null) {
                        val detectedObjects = detectMovingObjects(
                            prevBitmap,
                            currentBitmap,
                            threshold = 30,
                            minSize = 5
                        )
                        viewModel.updateDetectedObjects(detectedObjects)
                    }

                    prevBitmap = currentBitmap
                    image.close()
                }
            }
        )
    }

    LaunchedEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(controller = controller, modifier = Modifier.fillMaxSize())

        val objectToTrack = viewModel.getObjectToTrack()

        if (objectToTrack != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = objectToTrack.centerX * size.width / 1000f
                val centerY = objectToTrack.centerY * size.height / 1000f

                // 繪製十字標線
                val crossSize = 30.dp.toPx()

                // 水平線
                drawLine(
                    color = Color.Red,
                    start = Offset(centerX - crossSize, centerY),
                    end = Offset(centerX + crossSize, centerY),
                    strokeWidth = 2.dp.toPx()
                )

                // 垂直線
                drawLine(
                    color = Color.Red,
                    start = Offset(centerX, centerY - crossSize),
                    end = Offset(centerX, centerY + crossSize),
                    strokeWidth = 2.dp.toPx()
                )

                // 繪製邊界框
                val width = objectToTrack.width * size.width / 1000f
                val height = objectToTrack.height * size.height / 1000f

                drawRect(
                    color = Color.Red,
                    topLeft = Offset(centerX - width/2, centerY - height/2),
                    size = androidx.compose.ui.geometry.Size(width, height),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

private fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer // Y
    val uBuffer = planes[1].buffer // U
    val vBuffer = planes[2].buffer // V

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = android.graphics.YuvImage(
        nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null
    )

    val outputStream = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(
        android.graphics.Rect(0, 0, this.width, this.height), 100, outputStream
    )
    val jpegArray = outputStream.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(jpegArray, 0, jpegArray.size)
}