package com.staysafeai.app.scanners

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.staysafeai.app.models.IRSource
import com.staysafeai.app.models.LensGlint
import com.staysafeai.app.models.StaticObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Single shared camera session — opens the back camera once,
 * broadcasts YUV frames to all registered scanners,
 * and streams live detection results back to the UI via callbacks.
 */
class SharedCameraSession(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private val handlerThread = HandlerThread("SharedCameraThread").also { it.start() }
    val handler = Handler(handlerThread.looper)

    // Frame listeners registered by scanners
    private val frameListeners = mutableListOf<(android.media.Image) -> Unit>()

    // Frame throttle — process max 10 frames/sec to avoid ANR
    private var lastFrameTimeMs = 0L
    private val FRAME_INTERVAL_MS = 100L

    var isOpen = false
        private set

    // ── Live detection callbacks (set by ScanActivity) ──────────────────────
    var onIRDetected: ((List<IRSource>) -> Unit)? = null
    var onGlintDetected: ((List<LensGlint>) -> Unit)? = null
    var onSuspectDetected: ((List<StaticObject>) -> Unit)? = null

    // Live detection state — updated by scanners directly
    @Volatile var liveIRSources = listOf<IRSource>()
    @Volatile var liveGlints = listOf<LensGlint>()
    @Volatile var liveSuspects = listOf<StaticObject>()

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
    }

    fun addFrameListener(listener: (android.media.Image) -> Unit) {
        synchronized(frameListeners) { frameListeners.add(listener) }
    }

    fun removeFrameListener(listener: (android.media.Image) -> Unit) {
        synchronized(frameListeners) { frameListeners.remove(listener) }
    }

    // Called by IRScanner when it finds IR sources
    fun notifyIRDetected(sources: List<IRSource>) {
        liveIRSources = sources
        onIRDetected?.invoke(sources)
    }

    // Called by LensReflectionScanner when it finds glints
    fun notifyGlintDetected(glints: List<LensGlint>) {
        liveGlints = glints
        onGlintDetected?.invoke(glints)
    }

    // Called by StaticObjectTracker when it finds suspects
    fun notifySuspectDetected(suspects: List<StaticObject>) {
        liveSuspects = suspects
        onSuspectDetected?.invoke(suspects)
    }

    suspend fun open(withFlash: Boolean = false) {
        val cameraId = getBackCameraId() ?: return

        imageReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 5)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < FRAME_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTimeMs = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                synchronized(frameListeners) {
                    frameListeners.forEach { it(image) }
                }
            } finally {
                image.close()
            }
        }, handler)

        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startSession(withFlash)
                    isOpen = true
                    cont.resume(Unit)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); isOpen = false
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); isOpen = false
                    cont.resume(Unit)
                }
            }, handler)
        }
    }

    private fun startSession(withFlash: Boolean) {
        val imageSurface = imageReader?.surface ?: return
        val surfaces = mutableListOf(imageSurface)
        previewSurface?.let { surfaces.add(it) }

        cameraDevice?.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val request = cameraDevice?.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        )?.apply {
                            addTarget(imageSurface)
                            previewSurface?.let { addTarget(it) }
                            if (withFlash) {
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 16666667L)
                                set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                            } else {
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 33333333L)
                                set(CaptureRequest.SENSOR_SENSITIVITY, 800)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                        }?.build()
                        request?.let { session.setRepeatingRequest(it, null, handler) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            handler
        )
    }

    fun close() {
        isOpen = false
        synchronized(frameListeners) { frameListeners.clear() }
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        captureSession = null
        cameraDevice = null
        imageReader = null
    }

    private fun getBackCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }
}
