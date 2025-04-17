package com.example.mosquitotracker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.math.abs
import kotlin.math.min
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections

data class DetectedObject(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val id: Int
) {
    fun area(): Float = width * height
}

class MainViewModel : ViewModel() {
    private val _detectedObjects = mutableStateOf<List<DetectedObject>>(emptyList())
    val detectedObjects: List<DetectedObject> get() = _detectedObjects.value

    var currentTrackingIndex by mutableStateOf(0)
        private set

    var cameraShouldFollow by mutableStateOf(true)
    var currentTrackedPosition by mutableStateOf<Pair<Float, Float>?>(null)
        private set

    private var trackingJob: Job? = null
    private val isProcessing = AtomicBoolean(false)
    private val PROCESSING_THREADS = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    fun processYUVImage(image: ImageProxy) {
        if (isProcessing.getAndSet(true)) {
            image.close()
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            image.use { img ->  // 使用 use 確保自動關閉
                val yuvData = img.toYUVByteArray()
                yuvData?.let {
                    try {
                        val detected = withContext(Dispatchers.Default) {
                            detectMovingObjectsYUVParallel(it, img.width, img.height)
                        }
                        withContext(Dispatchers.Main) {
                            updateDetectedObjects(detected)
                        }
                    } catch (e: Exception) {
                        Log.e("YUV_Processing", "Error in YUV detection", e)
                    } finally {
                        isProcessing.set(false)
                    }
                }
            }
        }
    }

    private suspend fun detectMovingObjectsYUVParallel(
        yuvData: ByteArray,
        width: Int,
        height: Int,
        threshold: Int = 25
    ): List<DetectedObject> = withContext(Dispatchers.Default) {
        val luma = yuvData.take(width * height).toByteArray()
        val diffPoints = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val step = 8

        (0 until height step step).map { y ->
            async {
                val rowPoints = mutableListOf<Pair<Int, Int>>()
                for (x in 0 until width step step) {
                    val index = y * width + x
                    if (index >= luma.size) continue
                    val brightness = luma[index].toInt() and 0xFF
                    if (brightness < 50) {
                        rowPoints.add(Pair(x, y))
                    }
                }
                diffPoints.addAll(rowPoints)
            }
        }.awaitAll()

        return@withContext groupDiffPoints(diffPoints, minArea = 20).map { rect ->
            DetectedObject(
                centerX = rect.centerX().toFloat(),
                centerY = rect.centerY().toFloat(),
                width = rect.width().toFloat(),
                height = rect.height().toFloat(),
                id = rect.hashCode()
            )
        }
    }

    private fun groupDiffPoints(
        points: List<Pair<Int, Int>>,
        minArea: Int
    ): List<Rect> {
        val groups = mutableListOf<MutableList<Pair<Int, Int>>>()
        val visited = mutableSetOf<Pair<Int, Int>>()

        for (point in points) {
            if (point in visited) continue

            val queue = ArrayDeque<Pair<Int, Int>>()
            val group = mutableListOf<Pair<Int, Int>>()
            queue.add(point)
            visited.add(point)

            while (queue.isNotEmpty()) {
                val (x, y) = queue.removeFirst()
                group.add(Pair(x, y))

                for (dx in -2..2) {
                    for (dy in -2..2) {
                        if (dx == 0 && dy == 0) continue
                        val neighbor = Pair(x + dx, y + dy)
                        if (neighbor in points && neighbor !in visited) {
                            visited.add(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }
            }

            if (group.size >= minArea) {
                groups.add(group)
            }
        }

        return groups.map { group ->
            Rect(
                group.minOf { it.first },
                group.minOf { it.second },
                group.maxOf { it.first },
                group.maxOf { it.second }
            )
        }
    }

    fun updateDetectedObjects(newObjects: List<DetectedObject>) {
        _detectedObjects.value = newObjects.sortedBy { it.area() }.take(2)

        if (trackingJob?.isActive != true && detectedObjects.isNotEmpty()) {
            startTrackingCycle()
        } else if (detectedObjects.isEmpty()) {
            trackingJob?.cancel()
            currentTrackingIndex = 0
            currentTrackedPosition = null
        }
    }

    private fun startTrackingCycle() {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            while (detectedObjects.isNotEmpty()) {
                delay(5000)
                currentTrackingIndex = (currentTrackingIndex + 1) % detectedObjects.size
            }
        }
    }

    fun getObjectToTrack(): DetectedObject? {
        return detectedObjects.getOrNull(currentTrackingIndex)
    }

    fun updateCameraPosition(target: DetectedObject, screenWidth: Int, screenHeight: Int) {
        if (!cameraShouldFollow) return

        val normX = (target.centerX / screenWidth) * 2 - 1
        val normY = (target.centerY / screenHeight) * 2 - 1

        currentTrackedPosition = if (abs(normX) > 0.3 || abs(normY) > 0.3) {
            Pair(target.centerX / screenWidth, target.centerY / screenHeight)
        } else {
            null
        }
    }
}

fun ImageProxy.toYUVByteArray(): ByteArray? {
    if (format != ImageFormat.YUV_420_888) return null

    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    return nv21
}