package com.staysafeai.app.scanners

import android.content.Context
import com.staysafeai.app.models.LensGlint
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt

class LensReflectionScanner(private val context: Context) {

    private val glints = mutableListOf<LensGlint>()
    private var isScanning = false
    private var onGlintCallback: ((LensGlint) -> Unit)? = null
    private var sharedCameraRef: SharedCameraSession? = null

    companion object {
        // Only pure white near-saturated spots qualify — NOT bright walls
        const val GLINT_BRIGHTNESS_THRESHOLD = 252  // Nearly maxed out (0-255)
        // Real camera lens = very small circular spot, 1–12px
        const val MIN_GLINT_SIZE = 1
        const val MAX_GLINT_SIZE = 12
        // Must be very circular
        const val MIN_GLINT_CIRCULARITY = 0.75f
        // Merge clusters within this pixel distance into one detection
        const val MERGE_DISTANCE_PX = 60f
        // Must see it in multiple frames to count (reduces noise)
        const val MIN_FRAME_CONFIRMATIONS = 3
    }

    // Track how many frames each glint position has been confirmed
    private val glintConfirmations = mutableMapOf<String, Int>()
    private var frameCount = 0

    private val frameListener: (android.media.Image) -> Unit = { image ->
        if (isScanning) analyzeForGlints(image)
    }

    suspend fun scan(
        sharedCamera: SharedCameraSession,
        onGlintFound: ((LensGlint) -> Unit)? = null
    ): List<LensGlint> {
        glints.clear()
        glintConfirmations.clear()
        frameCount = 0
        isScanning = true
        onGlintCallback = onGlintFound
        sharedCameraRef = sharedCamera

        sharedCamera.addFrameListener(frameListener)
        delay(5000)
        sharedCamera.removeFrameListener(frameListener)
        isScanning = false

        return filterAndRankGlints(glints)
    }

    private fun analyzeForGlints(image: android.media.Image) {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        if (data.isEmpty()) return

        val width = image.width
        val height = image.height
        frameCount++

        // Step 1: Find all near-saturated pixel clusters this frame
        val visited = BooleanArray(height * rowStride)
        val frameCandidates = mutableListOf<RawCandidate>()

        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val idx = y * rowStride + x
                if (idx >= data.size || visited[idx]) continue
                val brightness = data[idx].toInt() and 0xFF
                if (brightness < GLINT_BRIGHTNESS_THRESHOLD) continue

                // Flood fill to find the full cluster
                val cluster = floodFill(data, visited, x, y, width, height, rowStride)
                if (cluster.size < MIN_GLINT_SIZE) continue
                if (cluster.size > MAX_GLINT_SIZE * MAX_GLINT_SIZE * 4) continue // too large = wall/surface

                val cx = cluster.map { it.first.toFloat() }.average().toFloat()
                val cy = cluster.map { it.second.toFloat() }.average().toFloat()
                val circularity = measureCircularity(cluster)
                val aspectRatio = measureAspectRatio(cluster)

                // Must be small AND circular
                if (cluster.size > MAX_GLINT_SIZE * MAX_GLINT_SIZE) continue
                if (circularity < MIN_GLINT_CIRCULARITY) continue
                if (abs(aspectRatio - 1f) > 0.4f) continue // not circular enough

                frameCandidates.add(RawCandidate(cx, cy, cluster.size, circularity, brightness))
            }
        }

        // Step 2: Merge nearby candidates into single detections
        val merged = mergeCandidates(frameCandidates)

        // Step 3: Confirm across frames — only keep positions seen multiple frames
        val confirmedThisFrame = mutableSetOf<String>()
        for (candidate in merged) {
            val key = "${(candidate.cx / MERGE_DISTANCE_PX).toInt()}_${(candidate.cy / MERGE_DISTANCE_PX).toInt()}"
            glintConfirmations[key] = (glintConfirmations[key] ?: 0) + 1
            confirmedThisFrame.add(key)

            if ((glintConfirmations[key] ?: 0) >= MIN_FRAME_CONFIRMATIONS) {
                val hasDoubleReflection = checkForDoubleReflection(data, width, height, candidate.cx, candidate.cy, candidate.size, rowStride)
                val classification = if (hasDoubleReflection)
                    LensGlint.Classification.TWO_WAY_MIRROR_CAMERA
                else
                    LensGlint.Classification.CAMERA_LENS

                val glint = LensGlint(
                    centerX = candidate.cx,
                    centerY = candidate.cy,
                    size = candidate.size,
                    brightness = candidate.brightness,
                    circularity = candidate.circularity,
                    aspectRatio = 1f,
                    hasDoubleReflection = hasDoubleReflection,
                    classification = classification,
                    isLikelySurface = false,
                    confidence = calculateConfidence(candidate.circularity, candidate.size, hasDoubleReflection)
                )

                // Only add if not already tracked nearby
                synchronized(glints) {
                    val alreadyTracked = glints.any { existing ->
                        distance(existing.centerX, existing.centerY, glint.centerX, glint.centerY) < MERGE_DISTANCE_PX
                    }
                    if (!alreadyTracked) {
                        glints.add(glint)
                        sharedCameraRef?.notifyGlintDetected(glints.toList())
                        onGlintCallback?.invoke(glint)
                    }
                }
            }
        }
    }

    private data class RawCandidate(
        val cx: Float, val cy: Float,
        val size: Int, val circularity: Float, val brightness: Int
    )

    /**
     * Merge candidates within MERGE_DISTANCE_PX of each other into one —
     * the brightest/most circular one wins. This ensures one real object = one detection.
     */
    private fun mergeCandidates(candidates: List<RawCandidate>): List<RawCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val used = BooleanArray(candidates.size)
        val result = mutableListOf<RawCandidate>()

        for (i in candidates.indices) {
            if (used[i]) continue
            val group = mutableListOf(candidates[i])
            used[i] = true
            for (j in i + 1 until candidates.size) {
                if (used[j]) continue
                if (distance(candidates[i].cx, candidates[i].cy, candidates[j].cx, candidates[j].cy) < MERGE_DISTANCE_PX) {
                    group.add(candidates[j])
                    used[j] = true
                }
            }
            // Pick the most circular one from the group as the representative
            val best = group.maxByOrNull { it.circularity * (1f - it.size.toFloat() / (MAX_GLINT_SIZE * MAX_GLINT_SIZE)) }!!
            result.add(best)
        }
        return result
    }

    private fun floodFill(
        data: ByteArray, visited: BooleanArray,
        startX: Int, startY: Int, width: Int, height: Int, rowStride: Int
    ): List<Pair<Int, Int>> {
        val pixels = mutableListOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(startX, startY))
        while (queue.isNotEmpty() && pixels.size < 1000) {
            val (x, y) = queue.removeFirst()
            if (x < 0 || x >= width || y < 0 || y >= height) continue
            val idx = y * rowStride + x
            if (idx >= data.size || visited[idx]) continue
            val brightness = data[idx].toInt() and 0xFF
            if (brightness < GLINT_BRIGHTNESS_THRESHOLD - 10) continue
            visited[idx] = true
            pixels.add(Pair(x, y))
            queue.add(Pair(x + 1, y)); queue.add(Pair(x - 1, y))
            queue.add(Pair(x, y + 1)); queue.add(Pair(x, y - 1))
        }
        return pixels
    }

    private fun checkForDoubleReflection(
        data: ByteArray, width: Int, height: Int,
        centerX: Float, centerY: Float, primarySize: Int, rowStride: Int
    ): Boolean {
        val searchRadius = (sqrt(primarySize.toFloat()) * 4).toInt().coerceAtMost(30)
        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                val dist = sqrt((dx * dx + dy * dy).toFloat())
                if (dist < 3 || dist > searchRadius) continue
                val nx = (centerX + dx).toInt()
                val ny = (centerY + dy).toInt()
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                val i = ny * rowStride + nx
                if (i >= data.size) continue
                if ((data[i].toInt() and 0xFF) >= GLINT_BRIGHTNESS_THRESHOLD - 5) return true
            }
        }
        return false
    }

    private fun measureCircularity(pixels: List<Pair<Int, Int>>): Float {
        if (pixels.size < 3) return 0f
        val cx = pixels.map { it.first }.average()
        val cy = pixels.map { it.second }.average()
        val radii = pixels.map { (x, y) -> sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat()) }
        val meanR = radii.average()
        if (meanR < 0.001) return 1f
        val stdR = sqrt(radii.map { (it - meanR) * (it - meanR) }.average())
        return (1f - (stdR / (meanR + 0.001f))).toFloat().coerceIn(0f, 1f)
    }

    private fun measureAspectRatio(pixels: List<Pair<Int, Int>>): Float {
        val w = (pixels.maxOf { it.first } - pixels.minOf { it.first } + 1).toFloat()
        val h = (pixels.maxOf { it.second } - pixels.minOf { it.second } + 1).toFloat()
        return if (h > 0) w / h else 1f
    }

    private fun calculateConfidence(circularity: Float, size: Int, hasDoubleReflection: Boolean): Float {
        var c = circularity * 0.5f
        if (size in MIN_GLINT_SIZE..MAX_GLINT_SIZE) c += 0.3f
        if (hasDoubleReflection) c += 0.2f
        return c.coerceIn(0f, 1f)
    }

    private fun filterAndRankGlints(glints: List<LensGlint>): List<LensGlint> {
        return glints
            .filter { it.classification != LensGlint.Classification.SURFACE }
            .distinctBy { "${(it.centerX / MERGE_DISTANCE_PX).toInt()}_${(it.centerY / MERGE_DISTANCE_PX).toInt()}" }
            .sortedByDescending { it.confidence }
            .take(10) // Cap at 10 max real detections
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun stop() { isScanning = false }
}