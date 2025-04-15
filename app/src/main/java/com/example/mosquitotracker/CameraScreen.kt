package com.example.mosquitotracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.min

@Composable
fun CameraScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp

    // 使用單線程執行器處理圖像分析
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val controller = remember {
        LifecycleCameraController(context).apply {
            // 設置分辨率選擇器（降低分辨率提升性能）
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                    )
                )
                .build()

            // 啟用必要的用例
            setEnabledUseCases(
                CameraController.IMAGE_CAPTURE or
                        CameraController.IMAGE_ANALYSIS
            )

            // 設置後置攝像頭
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    // 綁定生命週期
    LaunchedEffect(controller) {
        controller.bindToLifecycle(lifecycleOwner)
    }

    // 更新相機位置以跟蹤物體
    LaunchedEffect(viewModel.currentTrackingIndex, viewModel.detectedObjects) {
        viewModel.getObjectToTrack()?.let { target ->
            viewModel.updateCameraPosition(target, screenWidth, screenHeight)
        }
    }

    // 配置圖像分析器 (30 FPS)
    LaunchedEffect(Unit) {
        controller.setImageAnalysisAnalyzer(
            analysisExecutor,
            object : ImageAnalysis.Analyzer {
                private var lastFrameTime = 0L

                override fun analyze(image: ImageProxy) {
                    Log.d("Camera", "New frame received, format: ${image.format}, resolution: ${image.width}x${image.height}")
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastFrameTime < 33) { // ~30 FPS (1000ms/30 ≈ 33ms)
                        image.close()
                        return
                    }
                    lastFrameTime = currentTime

                    try {
                        viewModel.processImage(image)
                    } catch (e: Exception) {
                        Log.e("CameraAnalysis", "Error processing image", e)
                        image.close()
                    }
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            trackedPosition = viewModel.currentTrackedPosition
        )
    }
}

// 优化的Bitmap转换扩展函数
fun ImageProxy.toOptimizedBitmap(): Bitmap? {
    if (this.format != ImageFormat.YUV_420_888) {
        Log.w("Camera", "Unsupported image format: ${this.format}. Expected YUV_420_888.")
        this.close()
        return null
    }
    val yBuffer = planes[0].buffer
    val uvBuffer = planes[2].buffer // NV21格式中V数据在planes[2]

    return try {
        val ySize = yBuffer.remaining()
        val uvSize = uvBuffer.remaining()

        // 分配NV21格式数据
        val nv21 = ByteArray(ySize + uvSize)
        yBuffer.get(nv21, 0, ySize)
        uvBuffer.get(nv21, ySize, uvSize)

        val yuvImage = YuvImage(
            nv21, ImageFormat.NV21,
            this.width, this.height, null
        )

        ByteArrayOutputStream().use { output ->
            yuvImage.compressToJpeg(
                Rect(0, 0, width, height),
                70, // 降低质量提升速度
                output
            )
            BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())
        }
    } catch (e: Exception) {
        Log.e("ImageProxy", "Error converting to bitmap", e)
        null
    } finally {
        close()
    }
}