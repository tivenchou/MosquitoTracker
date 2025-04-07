package com.example.mosquitotracker
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import android.util.Log
import android.view.Surface
import android.graphics.Bitmap
import android.graphics.Matrix
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
                CameraController.IMAGE_CAPTURE or
                        CameraController.IMAGE_ANALYSIS
            )

            // 使用後置攝像頭
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // 創建獨立的 Preview use case
            val preview = Preview.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .build()
                )
                .build()

            // 設置目標旋轉
            preview?.setTargetRotation(Surface.ROTATION_0)

            // 設置分辨率選擇器
            val resolutionSelector = ResolutionSelector.Builder()
                .build()

        }
    }

    var prevBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(controller) {
        controller.bindToLifecycle(lifecycleOwner)
    }

    LaunchedEffect(Unit) {
        controller.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(context),
            object : ImageAnalysis.Analyzer {
                private var lastAnalyzedTimestamp = 0L

                override fun analyze(image: ImageProxy) {
                    val currentTimestamp = System.currentTimeMillis()
                    if (currentTimestamp - lastAnalyzedTimestamp < 200) {
                        image.close()
                        return
                    }
                    lastAnalyzedTimestamp = currentTimestamp

                    try {
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
                    } catch (e: Exception) {
                        Log.e("CameraAnalysis", "Analysis error", e)
                    } finally {
                        image.close()
                    }
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(controller = controller, modifier = Modifier.fillMaxSize())

        // 其他UI元素...
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