package com.staysafeai.app.scanners

import android.content.Context
import com.staysafeai.app.models.StaticObject
import com.staysafeai.app.models.TrackedPoint
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt

class StaticObjectTracker(private val context: Context) {

    private val trackedPoints = mutableMapOf<String, TrackedPoint>()
    private var motionDx = 0f
    private var motionDy = 0f
    private var frameCount = 0
    private var isTracking = false

    companion object {
        const val BRIGHTNESS_THRESHOLD = 200
        const val MAX_CLUSTER_SIZE = 40
        const val STATIC_TOLERANCE_PX = 8f
        const val MIN_FRAMES_FOR_STATIC = 20
        const val SCAN_DURATION_MS = 8000L
        const val GRID_CELL_SIZE = 20
    }

    private var sharedCameraRef: SharedCameraSession? = null

    private val frameListener: (android.media.Image) -> Unit = { image ->
        if (isTracking) processFrame(image)
    }

    suspend fun track(sharedCamera: SharedCameraSession): List<StaticObject> {
        trackedPoints.clear()
        frameCount = 0
        isTracking = true
        sharedCameraRef = sharedCamera

        sharedCamera.addFrameListener(frameListener)
        delay(SCAN_DURATION_MS)
        sharedCamera.removeFrameListener(frameListener)
        isTracking = false

        return classifyStaticObjects()
    }

    private fun processFrame(image: android.media.Image) {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        if (data.isEmpty()) return
        val width = image.width; val height = image.height
        val currentBrights = findBrightClusters(data, width, height, rowStride)
        val motionEstimate = estimateGlobalMotion(data, width, height, rowStride)
        motionDx = motionEstimate.first; motionDy = motionEstimate.second
        frameCount++

        synchronized(trackedPoints) {
            val matched = mutableSetOf<String>()
            for (bright in currentBrights) {
                val key = findMatchingPoint(bright.first, bright.second, motionDx, motionDy)
                if (key != null) {
                    val point = trackedPoints[key]!!
                    point.detectionCount++; point.lastFrameSeen = frameCount
                    point.positionHistory.add(Pair(bright.first, bright.second))
                    if (point.positionHistory.size > 30) point.positionHistory.removeAt(0)
                    matched.add(key)
                } else {
                    val newKey = "${(bright.first / GRID_CELL_SIZE).toInt()}_${(bright.second / GRID_CELL_SIZE).toInt()}_$frameCount"
                    trackedPoints[newKey] = TrackedPoint(
                        initialX = bright.first, initialY = bright.second,
                        currentX = bright.first, currentY = bright.second,
                        firstFrameSeen = frameCount, lastFrameSeen = frameCount,
                        detectionCount = 1,
                        positionHistory = mutableListOf(Pair(bright.first, bright.second)),
                        brightness = bright.third, size = bright.fourth
                    )
                }
            }
            for ((key, point) in trackedPoints) {
                if (key in matched && point.positionHistory.isNotEmpty()) {
                    point.currentX = point.positionHistory.last().first
                    point.currentY = point.positionHistory.last().second
                }
            }
            // Notify UI with current camera candidates every 10 frames
            if (frameCount % 10 == 0) {
                val candidates = trackedPoints.values
                    .filter { it.detectionCount >= 5 && it.brightness > 220 }
                    .map { point ->
                        StaticObject(
                            centerX = point.currentX, centerY = point.currentY,
                            isStatic = true, persistenceRatio = point.detectionCount.toFloat() / frameCount.toFloat(),
                            detectionCount = point.detectionCount, totalFrames = frameCount,
                            positionVariance = 0f, brightness = point.brightness, size = point.size,
                            isCameraCandidate = true, confidence = 0.5f
                        )
                    }
                if (candidates.isNotEmpty()) sharedCameraRef?.notifySuspectDetected(candidates)
            }
        }
    }

    private fun findBrightClusters(data: ByteArray, width: Int, height: Int, rowStride: Int = width): List<Quad<Float, Float, Int, Int>> {
        val visited = BooleanArray(width * height)
        val clusters = mutableListOf<Quad<Float, Float, Int, Int>>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * rowStride + x
                if (idx >= data.size) continue
                val brightness = data[idx].toInt() and 0xFF
                if (brightness >= BRIGHTNESS_THRESHOLD && !visited[idx]) {
                    val pixels = floodFill(data, visited, x, y, width, height, BRIGHTNESS_THRESHOLD - 20, rowStride)
                    if (pixels.isNotEmpty() && pixels.size <= MAX_CLUSTER_SIZE * 4) {
                        val cx = pixels.map { it.first.toFloat() }.average().toFloat()
                        val cy = pixels.map { it.second.toFloat() }.average().toFloat()
                        val maxBright = pixels.maxOf { (px, py) -> val i = py * rowStride + px; if (i < data.size) data[i].toInt() and 0xFF else 0 }
                        clusters.add(Quad(cx, cy, maxBright, pixels.size))
                    }
                }
            }
        }
        return clusters
    }

    private var previousFrameData: ByteArray? = null

    private fun estimateGlobalMotion(data: ByteArray, width: Int, height: Int, rowStride: Int = width): Pair<Float, Float> {
        val prev = previousFrameData
        previousFrameData = data.copyOf()
        if (prev == null || prev.size != data.size) return Pair(0f, 0f)
        val step = 20; var totalDx = 0f; var totalDy = 0f; var count = 0
        for (y in step until height - step step step) {
            for (x in step until width - step step step) {
                val brightness = data[y * rowStride + x].toInt() and 0xFF
                if (brightness < 50 || brightness > 200) continue
                var bestDx = 0; var bestDy = 0; var bestMatch = Int.MAX_VALUE
                for (dy in -4..4) for (dx in -4..4) {
                    val ny = y + dy; val nx = x + dx
                    if (ny < 0 || ny >= height || nx < 0 || nx >= width) continue
                    val pi = ny * rowStride + nx; if (pi >= prev.size) continue
                        val diff = abs((prev[pi].toInt() and 0xFF) - brightness)
                    if (diff < bestMatch) { bestMatch = diff; bestDx = dx; bestDy = dy }
                }
                if (bestMatch < 20) { totalDx += bestDx; totalDy += bestDy; count++ }
            }
        }
        return if (count > 0) Pair(totalDx / count, totalDy / count) else Pair(0f, 0f)
    }

    private fun findMatchingPoint(x: Float, y: Float, motionDx: Float, motionDy: Float): String? {
        val compensatedX = x - motionDx; val compensatedY = y - motionDy
        return trackedPoints.entries
            .filter { (_, point) -> frameCount - point.lastFrameSeen <= 3 }
            .minByOrNull { (_, point) -> distance(compensatedX, compensatedY, point.currentX, point.currentY) }
            ?.takeIf { (_, point) -> distance(compensatedX, compensatedY, point.currentX, point.currentY) < STATIC_TOLERANCE_PX * 2 }
            ?.key
    }

    private fun classifyStaticObjects(): List<StaticObject> {
        return trackedPoints.values
            .filter { it.detectionCount >= MIN_FRAMES_FOR_STATIC }
            .map { point ->
                val isStatic = measureStaticness(point)
                val persistenceRatio = point.detectionCount.toFloat() / frameCount.toFloat()
                StaticObject(
                    centerX = point.initialX, centerY = point.initialY,
                    isStatic = isStatic, persistenceRatio = persistenceRatio,
                    detectionCount = point.detectionCount, totalFrames = frameCount,
                    positionVariance = calculatePositionVariance(point.positionHistory),
                    brightness = point.brightness, size = point.size,
                    isCameraCandidate = isStatic && persistenceRatio > 0.6f && point.size <= MAX_CLUSTER_SIZE && point.brightness > 220,
                    confidence = calculateStaticConfidence(isStatic, persistenceRatio, point)
                )
            }
            .filter { it.isCameraCandidate || it.persistenceRatio > 0.7f }
            .sortedByDescending { it.confidence }
    }

    private fun measureStaticness(point: TrackedPoint): Boolean {
        if (point.positionHistory.size < 5) return false
        val positions = point.positionHistory
        val centroidX = positions.map { it.first }.average().toFloat()
        val centroidY = positions.map { it.second }.average().toFloat()
        val maxDeviation = positions.maxOf { (px, py) -> distance(px, py, centroidX, centroidY) }
        return maxDeviation <= STATIC_TOLERANCE_PX
    }

    private fun calculatePositionVariance(history: List<Pair<Float, Float>>): Float {
        if (history.size < 2) return 0f
        val cx = history.map { it.first }.average().toFloat()
        val cy = history.map { it.second }.average().toFloat()
        val variance = history.map { (x, y) -> val dx = x-cx; val dy = y-cy; dx*dx+dy*dy }.average()
        return sqrt(variance).toFloat()
    }

    private fun calculateStaticConfidence(isStatic: Boolean, persistenceRatio: Float, point: TrackedPoint): Float {
        var conf = 0f
        if (isStatic) conf += 0.4f
        conf += persistenceRatio * 0.3f
        if (point.size <= 15) conf += 0.15f
        if (point.brightness > 240) conf += 0.15f
        return conf.coerceIn(0f, 1f)
    }

    private fun floodFill(data: ByteArray, visited: BooleanArray, startX: Int, startY: Int, width: Int, height: Int, threshold: Int, rowStride: Int): List<Pair<Int, Int>> {
        val pixels = mutableListOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(startX, startY))
        while (queue.isNotEmpty() && pixels.size < 500) {
            val (x, y) = queue.removeFirst()
            if (x < 0 || x >= width || y < 0 || y >= height) continue
            val idx = y * rowStride + x
            if (idx >= data.size || visited[idx]) continue
            if ((data[idx].toInt() and 0xFF) < threshold) continue
            visited[idx] = true; pixels.add(Pair(x, y))
            queue.add(Pair(x+1,y)); queue.add(Pair(x-1,y))
            queue.add(Pair(x,y+1)); queue.add(Pair(x,y-1))
        }
        return pixels
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1-x2; val dy = y1-y2; return sqrt(dx*dx+dy*dy)
    }

    fun stop() { isTracking = false }

    data class Quad<A,B,C,D>(val first: A, val second: B, val third: C, val fourth: D)
}
