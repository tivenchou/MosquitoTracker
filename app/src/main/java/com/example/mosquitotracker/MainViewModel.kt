package com.example.mosquitotracker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
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
                delay(5000) // 5秒切換一次
                currentTrackingIndex = (currentTrackingIndex + 1) % detectedObjects.size
            }
        }
    }

    fun getObjectToTrack(): DetectedObject? {
        return if (detectedObjects.isNotEmpty()) {
            detectedObjects[currentTrackingIndex % detectedObjects.size]
        } else {
            null
        }
    }
}

fun detectMovingObjects(
    prevBitmap: Bitmap?,
    currentBitmap: Bitmap,
    threshold: Int = 30,
    minSize: Int = 10
): List<DetectedObject> {
    if (prevBitmap == null) return emptyList()

    // 確保圖像尺寸相同
    if (prevBitmap.width != currentBitmap.width ||
        prevBitmap.height != currentBitmap.height) {
        return emptyList()
    }

    // 縮小圖像以減少計算量
    val scaledWidth = 320
    val scaledHeight = (currentBitmap.height * (320f / currentBitmap.width)).toInt()

    val prevScaled = Bitmap.createScaledBitmap(prevBitmap, scaledWidth, scaledHeight, false)
    val currentScaled = Bitmap.createScaledBitmap(currentBitmap, scaledWidth, scaledHeight, false)

    // 實際的物體檢測邏輯
    val diffPixels = mutableListOf<Pair<Int, Int>>()

    for (x in 0 until scaledWidth step 3) { // 取樣檢測，減少計算量
        for (y in 0 until scaledHeight step 3) {
            val prevPixel = prevScaled.getPixel(x, y)
            val currentPixel = currentScaled.getPixel(x, y)

            val prevR = Color.red(prevPixel)
            val prevG = Color.green(prevPixel)
            val prevB = Color.blue(prevPixel)

            val currentR = Color.red(currentPixel)
            val currentG = Color.green(currentPixel)
            val currentB = Color.blue(currentPixel)

            val diff = sqrt(
                (prevR - currentR).toDouble().pow(2) +
                        (prevG - currentG).toDouble().pow(2) +
                        (prevB - currentB).toDouble().pow(2)
            )

            if (diff > threshold) {
                diffPixels.add(Pair(x, y))
            }
        }
    }

    // 分組相近的像素點
    val groups = mutableListOf<MutableList<Pair<Int, Int>>>()
    val visited = mutableSetOf<Pair<Int, Int>>()

    for (pixel in diffPixels) {
        if (pixel in visited) continue

        val queue = ArrayDeque<Pair<Int, Int>>()
        val group = mutableListOf<Pair<Int, Int>>()
        queue.add(pixel)
        visited.add(pixel)

        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            group.add(Pair(x, y))

            // 檢查相鄰像素
            for (dx in -3..3) {
                for (dy in -3..3) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until scaledWidth && ny in 0 until scaledHeight) {
                        val neighbor = Pair(nx, ny)
                        if (neighbor in diffPixels && neighbor !in visited) {
                            visited.add(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }
            }
        }

        if (group.size >= minSize) {
            groups.add(group)
        }
    }

    // 計算每個群組的邊界框
    return groups.mapIndexed { index, group ->
        val minX = group.minOf { it.first }
        val maxX = group.maxOf { it.first }
        val minY = group.minOf { it.second }
        val maxY = group.maxOf { it.second }

        DetectedObject(
            centerX = (minX + maxX) / 2f,
            centerY = (minY + maxY) / 2f,
            width = (maxX - minX).toFloat(),
            height = (maxY - minY).toFloat(),
            id = index
        ).let { obj ->
            // 將座標轉換回原始尺寸
            DetectedObject(
                centerX = obj.centerX * currentBitmap.width / scaledWidth,
                centerY = obj.centerY * currentBitmap.height / scaledHeight,
                width = obj.width * currentBitmap.width / scaledWidth,
                height = obj.height * currentBitmap.height / scaledHeight,
                id = obj.id
            )
        }
    }
}