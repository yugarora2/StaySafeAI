package com.staysafeai.app.scanners

import android.content.Context
import com.staysafeai.app.models.IRSource
import kotlinx.coroutines.delay
import kotlin.math.sqrt

class IRScanner(private val context: Context) {

    private val irSources = mutableListOf<IRSource>()
    private var isScanning = false
    private var sharedCameraRef: SharedCameraSession? = null

    companion object {
        // IR illuminators are very bright — near saturation
        const val IR_BRIGHTNESS_THRESHOLD = 248
        // Real IR dot = small circular point source
        const val MIN_POINT_SIZE = 1
        const val MAX_POINT_SOURCE_SIZE = 25
        const val MIN_DIFFUSE_SIZE = 150
        // Merge detections within this distance
        const val MERGE_DISTANCE_PX = 50f
        // Must be seen in multiple frames
        const val MIN_DETECTIONS = 3
    }

    private val frameListener: (android.media.Image) -> Unit = { image ->
        if (isScanning) analyzeFrame(image)
    }

    suspend fun scan(sharedCamera: SharedCameraSession, durationMs: Long = 6000): List<IRSource> {
        irSources.clear()
        isScanning = true
        sharedCameraRef = sharedCamera

        sharedCamera.addFrameListener(frameListener)
        delay(durationMs)
        sharedCamera.removeFrameListener(frameListener)
        isScanning = false

        return mergeAndFilterSources(irSources)
    }

    private fun analyzeFrame(image: android.media.Image) {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val yData = ByteArray(yBuffer.remaining())
        yBuffer.get(yData)
        if (yData.isEmpty()) return

        val width = image.width
        val height = image.height
        val rawClusters = findBrightClusters(yData, width, height, rowStride)

        // Merge nearby raw clusters into single IR sources
        val merged = mergeRawClusters(rawClusters)

        synchronized(irSources) {
            for (cluster in merged) {
                val existing = irSources.find { src ->
                    distance(src.centerX, src.centerY, cluster.centerX, cluster.centerY) < MERGE_DISTANCE_PX
                }
                if (existing != null) {
                    existing.detectionCount++
                    existing.lastSeenMs = System.currentTimeMillis()
                } else {
                    irSources.add(cluster)
                }
            }
            // Notify UI with confirmed sources only
            val confirmed = irSources.filter { it.detectionCount >= MIN_DETECTIONS }
            if (confirmed.isNotEmpty()) sharedCameraRef?.notifyIRDetected(confirmed)
        }
    }

    private fun findBrightClusters(yData: ByteArray, width: Int, height: Int, rowStride: Int): List<IRSource> {
        val visited = BooleanArray(height * rowStride)
        val clusters = mutableListOf<IRSource>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * rowStride + x
                if (idx >= yData.size || visited[idx]) continue
                val brightness = yData[idx].toInt() and 0xFF
                if (brightness < IR_BRIGHTNESS_THRESHOLD) continue

                val clusterPixels = mutableListOf<Pair<Int, Int>>()
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(Pair(x, y))
                while (queue.isNotEmpty()) {
                    val (cx, cy) = queue.removeFirst()
                    val cidx = cy * rowStride + cx
                    if (cx < 0 || cx >= width || cy < 0 || cy >= height) continue
                    if (cidx >= yData.size || visited[cidx]) continue
                    if ((yData[cidx].toInt() and 0xFF) < IR_BRIGHTNESS_THRESHOLD - 20) continue
                    visited[cidx] = true
                    clusterPixels.add(Pair(cx, cy))
                    queue.add(Pair(cx+1,cy)); queue.add(Pair(cx-1,cy))
                    queue.add(Pair(cx,cy+1)); queue.add(Pair(cx,cy-1))
                }

                if (clusterPixels.size < MIN_POINT_SIZE) continue

                val centerX = clusterPixels.map { it.first }.average().toFloat()
                val centerY = clusterPixels.map { it.second }.average().toFloat()
                val size = clusterPixels.size
                val maxBrightness = clusterPixels.maxOf { (px, py) ->
                    val i = py * rowStride + px
                    if (i < yData.size) yData[i].toInt() and 0xFF else 0
                }
                val circularity = calculateCircularity(clusterPixels)

                val sourceType = when {
                    size <= MAX_POINT_SOURCE_SIZE && circularity > 0.6f -> IRSource.Type.POINT_SOURCE
                    size >= MIN_DIFFUSE_SIZE -> IRSource.Type.DIFFUSE
                    else -> IRSource.Type.UNKNOWN
                }

                // Skip large diffuse sources — likely lamps or windows, not IR cameras
                if (sourceType == IRSource.Type.DIFFUSE) continue

                clusters.add(IRSource(
                    centerX = centerX, centerY = centerY, size = size,
                    maxBrightness = maxBrightness, circularity = circularity,
                    type = sourceType, detectionCount = 1,
                    firstSeenMs = System.currentTimeMillis(), lastSeenMs = System.currentTimeMillis()
                ))
            }
        }
        return clusters
    }

    /**
     * Merge raw clusters within MERGE_DISTANCE_PX into one IR source.
     * Multiple overlapping detections of the same object = one detection.
     */
    private fun mergeRawClusters(clusters: List<IRSource>): List<IRSource> {
        if (clusters.isEmpty()) return emptyList()
        val used = BooleanArray(clusters.size)
        val result = mutableListOf<IRSource>()
        for (i in clusters.indices) {
            if (used[i]) continue
            val group = mutableListOf(clusters[i])
            used[i] = true
            for (j in i + 1 until clusters.size) {
                if (used[j]) continue
                if (distance(clusters[i].centerX, clusters[i].centerY, clusters[j].centerX, clusters[j].centerY) < MERGE_DISTANCE_PX) {
                    group.add(clusters[j]); used[j] = true
                }
            }
            // Use the brightest one as representative
            val best = group.maxByOrNull { it.maxBrightness }!!
            result.add(best)
        }
        return result
    }

    private fun calculateCircularity(pixels: List<Pair<Int, Int>>): Float {
        if (pixels.isEmpty()) return 0f
        val cx = pixels.map { it.first }.average()
        val cy = pixels.map { it.second }.average()
        val radius = pixels.map {
            distance(it.first.toFloat(), it.second.toFloat(), cx.toFloat(), cy.toFloat())
        }.average()
        val circumference = 2 * Math.PI * radius
        val perimeter = pixels.count { (x, y) ->
            listOf(x+1 to y, x-1 to y, x to y+1, x to y-1).any { it !in pixels.toHashSet() }
        }.toDouble()
        if (perimeter == 0.0) return 0f
        return (circumference / perimeter).toFloat().coerceIn(0f, 1f)
    }

    private fun mergeAndFilterSources(sources: List<IRSource>): List<IRSource> {
        val stable = sources.filter { it.detectionCount >= MIN_DETECTIONS }
        stable.forEach { source ->
            val durationSec = (source.lastSeenMs - source.firstSeenMs) / 1000.0
            if (durationSec > 0) {
                source.pulseFrequency = source.detectionCount / durationSec
                source.isPulsing = source.pulseFrequency in 8.0..12.0
            }
        }
        // Final merge pass on final list
        return mergeRawClusters(stable).take(5) // Max 5 real IR sources
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun stop() { isScanning = false }
}