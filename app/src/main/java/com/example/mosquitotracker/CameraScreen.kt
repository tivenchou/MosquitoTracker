package com.example.mosquitotracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import android.view.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.min

@Composable
fun CameraScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 使用单线程执行器处理图像分析
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val controller = remember {
        LifecycleCameraController(context).apply {
            // 设置分辨率选择器（降低分辨率提升性能）
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                    )
                )
                .build()

            // 配置预览用例


            // 启用必要的用例
            setEnabledUseCases(
                CameraController.IMAGE_CAPTURE or
                        CameraController.IMAGE_ANALYSIS
            )

            // 设置后置摄像头
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    // 绑定生命周期
    LaunchedEffect(controller) {
        controller.bindToLifecycle(lifecycleOwner)
    }

    // 配置图像分析器
    LaunchedEffect(Unit) {
        controller.setImageAnalysisAnalyzer(
            analysisExecutor,
            object : ImageAnalysis.Analyzer {
                private var lastFrameTime = 0L
                private val frameInterval = 200L // 控制帧率

                override fun analyze(image: ImageProxy) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastFrameTime < frameInterval) {
                        image.close()
                        return
                    }
                    lastFrameTime = currentTime

                    try {
                        // 在后台处理图像
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
        CameraPreview(controller = controller, modifier = Modifier.fillMaxSize())
    }
}

// 优化的Bitmap转换扩展函数
fun ImageProxy.toOptimizedBitmap(): Bitmap? {
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