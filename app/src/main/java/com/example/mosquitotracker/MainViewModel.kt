package com.example.mosquitotracker

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.Log
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

    var cameraShouldFollow by mutableStateOf(true)
    var currentTrackedPosition by mutableStateOf<Pair<Float, Float>?>(null)

    private var trackingJob: Job? = null
    var lastBitmap: Bitmap? = null
        private set

    fun processImage(image: ImageProxy) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = try {
                // 移除 setConfig 直接使用原始 bitmap
                image.toOptimizedBitmap()?.also { originalBitmap ->
                    // 創建可修改的副本（如果需要改變配置）
                    if (originalBitmap.config != Bitmap.Config.RGB_565) {
                        originalBitmap.copy(Bitmap.Config.RGB_565, false)?.also {
                            originalBitmap.recycle() // 回收原始位圖
                        }
                    } else {
                        originalBitmap
                    }
                }
            } catch (e: Exception) {
                Log.e("Bitmap", "Error processing bitmap", e)
                null
            } finally {
                image.close()
            }

            bitmap?.let {
                try {
                    val detected = detectMovingObjects(lastBitmap, it)
                    withContext(Dispatchers.Main) {
                        updateDetectedObjects(detected)
                    }
                    lastBitmap?.recycle() // 確保回收舊位圖
                    lastBitmap = it
                } catch (e: Exception) {
                    it.recycle() // 處理失敗時回收位圖
                    Log.e("Detection", "Object detection failed", e)
                }
            }
        }
    }


    fun updateDetectedObjects(newObjects: List<DetectedObject>) {
        // 由小到大排序，只取前2個最小的物體
        detectedObjects = newObjects.sortedBy { it.area() }.take(2)

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
                delay(5000) // 5秒切換一次
                currentTrackingIndex = (currentTrackingIndex + 1) % detectedObjects.size
            }
        }
    }

    fun getObjectToTrack(): DetectedObject? {
        return detectedObjects.getOrNull(currentTrackingIndex)
    }

    fun updateCameraPosition(target: DetectedObject, screenWidth: Int, screenHeight: Int) {
        if (!cameraShouldFollow) return

        // 計算物體偏離中心的程度 (標準化坐標 -1到1)
        val normX = (target.centerX / screenWidth) * 2 - 1
        val normY = (target.centerY / screenHeight) * 2 - 1

        // 只跟蹤偏離中心較遠的物體 (閾值0.3)
        if (abs(normX) > 0.3 || abs(normY) > 0.3) {
            currentTrackedPosition = Pair(normX, normY)
        } else {
            currentTrackedPosition = null
        }
    }
}

// 优化的物体检测函数 - 修改阈值以更好检测小黑点
fun detectMovingObjects(
    prevBitmap: Bitmap?,
    currentBitmap: Bitmap,
    threshold: Int = 40,  // 提高阈值以更好检测黑色物体
    minSize: Int = 20     // 减小最小尺寸以检测小蚊子
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
        prevScaled.recycle() // 確保釋放資源
        currScaled.recycle()
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
    // 更关注亮度变化而不是颜色变化
    val brightness1 = (Color.red(c1) + Color.green(c1) + Color.blue(c1)) / 3
    val brightness2 = (Color.red(c2) + Color.green(c2) + Color.blue(c2)) / 3
    return abs(brightness1 - brightness2) > threshold
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

            // 檢查相鄰像素
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