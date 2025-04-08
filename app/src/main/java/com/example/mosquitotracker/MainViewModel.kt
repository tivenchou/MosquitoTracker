package com.example.mosquitotracker

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

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
    var detectedObjects by mutableStateOf<List<DetectedObject>>(emptyList())
        private set

    var currentTrackingIndex by mutableStateOf(0)
        private set

    private var trackingJob: Job? = null
    var lastBitmap: Bitmap? = null
        private set

    fun processImage(image: ImageProxy) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = image.toOptimizedBitmap()
            bitmap?.let {
                val detected = detectMovingObjects(lastBitmap, it)
                withContext(Dispatchers.Main) {
                    updateDetectedObjects(detected)
                }
                lastBitmap = it
            }
        }
    }

    fun updateDetectedObjects(newObjects: List<DetectedObject>) {
        detectedObjects = newObjects.sortedBy { it.area() }

        if (trackingJob?.isActive != true && detectedObjects.size > 1) {
            startTrackingCycle()
        } else if (detectedObjects.size <= 1) {
            trackingJob?.cancel()
            currentTrackingIndex = 0
        }
    }

    private fun startTrackingCycle() {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            while (detectedObjects.size > 1) {
                delay(5000) // 5秒切换一次
                currentTrackingIndex = (currentTrackingIndex + 1) % detectedObjects.size
            }
        }
    }

    fun getObjectToTrack(): DetectedObject? {
        return detectedObjects.getOrNull(currentTrackingIndex)
    }
}

// 优化的物体检测函数
fun detectMovingObjects(
    prevBitmap: Bitmap?,
    currentBitmap: Bitmap,
    threshold: Int = 25,
    minSize: Int = 50
): List<DetectedObject> {
    if (prevBitmap == null) return emptyList()

    return try {
        // 使用缩小后的图像检测
        val scale = 0.25f
        val scaledWidth = (currentBitmap.width * scale).toInt()
        val scaledHeight = (currentBitmap.height * scale).toInt()

        val prevScaled = Bitmap.createScaledBitmap(prevBitmap, scaledWidth, scaledHeight, false)
        val currScaled = Bitmap.createScaledBitmap(currentBitmap, scaledWidth, scaledHeight, false)

        val diffPoints = detectDiffPoints(prevScaled, currScaled, threshold)
        groupDiffPoints(diffPoints, minSize)
            .map { rect ->
                // 转换回原始坐标
                DetectedObject(
                    centerX = rect.centerX() / scale,
                    centerY = rect.centerY() / scale,
                    width = rect.width() / scale,
                    height = rect.height() / scale,
                    id = rect.hashCode()
                )
            }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun detectDiffPoints(b1: Bitmap, b2: Bitmap, threshold: Int): List<Pair<Int, Int>> {
    val points = mutableListOf<Pair<Int, Int>>()
    val width = min(b1.width, b2.width)
    val height = min(b1.height, b2.height)

    for (x in 0 until width step 2) {  // 跳步采样
        for (y in 0 until height step 2) {
            if (isDifferent(b1.getPixel(x,y), b2.getPixel(x,y), threshold)) {
                points.add(Pair(x, y))
            }
        }
    }
    return points
}

private fun isDifferent(c1: Int, c2: Int, threshold: Int): Boolean {
    val diffR = abs(Color.red(c1) - Color.red(c2))
    val diffG = abs(Color.green(c1) - Color.green(c2))
    val diffB = abs(Color.blue(c1) - Color.blue(c2))
    return sqrt((diffR*diffR + diffG*diffG + diffB*diffB).toDouble()) > threshold
}

private fun groupDiffPoints(points: List<Pair<Int, Int>>, minArea: Int): List<android.graphics.Rect> {
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

            // 检查相邻像素
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
        val minX = group.minOf { it.first }
        val maxX = group.maxOf { it.first }
        val minY = group.minOf { it.second }
        val maxY = group.maxOf { it.second }
        android.graphics.Rect(minX, minY, maxX, maxY)
    }
}