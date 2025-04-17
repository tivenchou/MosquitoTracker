package com.example.mosquitotracker

import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState

@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val detectedObjects by produceState<List<DetectedObject>>(initialValue = emptyList()) {
        value = viewModel.detectedObjects
    }

    val currentTrackingIndex by produceState<Int>(initialValue = 0) {
        value = viewModel.currentTrackingIndex
    }

    val currentTrackedPosition by produceState<Pair<Float, Float>?>(initialValue = null) {
        value = viewModel.currentTrackedPosition
    }

    val analysisExecutor = remember {
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                    )
                )
                .build()

            setEnabledUseCases(
                CameraController.IMAGE_CAPTURE or
                        CameraController.IMAGE_ANALYSIS
            )
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }

    LaunchedEffect(controller) {
        controller.bindToLifecycle(lifecycleOwner)
        delay(500)
    }

    LaunchedEffect(currentTrackingIndex, detectedObjects) {
        detectedObjects.getOrNull(currentTrackingIndex)?.let { target ->
            viewModel.updateCameraPosition(
                target = target,
                screenWidth = context.resources.displayMetrics.widthPixels,
                screenHeight = context.resources.displayMetrics.heightPixels
            )
        }
    }

    LaunchedEffect(Unit) {
        controller.setImageAnalysisAnalyzer(
            analysisExecutor,
            object : ImageAnalysis.Analyzer {
                private var lastFrameTime = 0L
                private val frameInterval = 33 // ~30 FPS
                private val frameQueue = java.util.concurrent.ConcurrentLinkedQueue<ImageProxy>()

                override fun analyze(image: ImageProxy) {
                    val currentTime = SystemClock.elapsedRealtime()
                    if (currentTime - lastFrameTime < frameInterval) {
                        image.close()
                        return
                    }
                    lastFrameTime = currentTime

                    frameQueue.offer(image)
                    CoroutineScope(Dispatchers.IO).launch {
                        val dequeuedImage = frameQueue.poll()
                        dequeuedImage?.let { img ->
                            try {
                                viewModel.processYUVImage(img)
                            } catch (e: Exception) {
                                Log.e("CameraAnalysis", "Error processing image", e)
                            }
                        }
                    }
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        CameraPreview(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            trackedPosition = currentTrackedPosition
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.unbind()
            analysisExecutor.shutdown()
        }
    }
}