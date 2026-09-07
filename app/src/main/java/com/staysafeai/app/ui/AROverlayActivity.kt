package com.staysafeai.app.ui

import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.staysafeai.app.analyzers.SignalTriangulator
import com.staysafeai.app.databinding.ActivityArOverlayBinding
import com.staysafeai.app.models.ScanResult

class AROverlayActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityArOverlayBinding
    private lateinit var scanResult: ScanResult
    private lateinit var triangulator: SignalTriangulator
    private lateinit var sensorManager: SensorManager

    private var currentEMF = 0f
    private var peakEMF = 0f
    private var userX = 0f
    private var userY = 0f
    private var guidance = "Walk slowly around the room"

    // AR overlay drawing
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 48f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arrowPaint = Paint().apply {
        isAntiAlias = true
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanResult = intent.getParcelableExtra("scan_result") ?: ScanResult()
        triangulator = SignalTriangulator(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        startCamera()
        startTracking()
        setupUI()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                // Camera failed
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startTracking() {
        // Register magnetometer for EMF tracking
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)

        // Start triangulator
        triangulator.startTracking { strength, guide ->
            currentEMF = strength
            guidance = guide
            if (strength > peakEMF) peakEMF = strength
            runOnUiThread { updateAROverlay() }
        }
    }

    private fun setupUI() {
        binding.tvARGuidance.text = guidance
        binding.tvARStatus.text = "Scanning..."

        binding.btnARStop.setOnClickListener {
            val result = triangulator.stopTracking()
            showTriangulationResult(result)
        }

        binding.btnARBack.setOnClickListener {
            finish()
        }

        // Overlay surface setup
        binding.overlaySurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun updateAROverlay() {
        // Update UI text
        binding.tvARGuidance.text = guidance
        binding.tvEMFValue.text = "${currentEMF.toInt()} µT"

        // Color based on strength
        val normalizedStrength = if (peakEMF > 0) currentEMF / peakEMF else 0f
        val color = when {
            normalizedStrength > 0.8f -> Color.RED
            normalizedStrength > 0.5f -> Color.rgb(255, 165, 0) // Orange
            normalizedStrength > 0.3f -> Color.YELLOW
            else -> Color.GREEN
        }
        binding.tvEMFValue.setTextColor(color)

        // Pulse animation when signal is strong
        if (normalizedStrength > 0.7f) {
            binding.ivPulseRing.visibility = View.VISIBLE
            binding.ivPulseRing.animate()
                .scaleX(1.5f).scaleY(1.5f).alpha(0f)
                .setDuration(800)
                .withEndAction {
                    binding.ivPulseRing.scaleX = 1f
                    binding.ivPulseRing.scaleY = 1f
                    binding.ivPulseRing.alpha = 1f
                }
                .start()
        } else {
            binding.ivPulseRing.visibility = View.GONE
        }

        // Draw on overlay canvas
        drawAROverlay(normalizedStrength, color)

        // Signal bar
        binding.progressSignal.progress = (normalizedStrength * 100).toInt()
        binding.progressSignal.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun drawAROverlay(strength: Float, color: Int) {
        val holder = binding.overlaySurface.holder
        val canvas = holder.lockCanvas() ?: return

        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            val cx = width / 2
            val cy = height / 2

            if (strength > 0.3f) {
                // Draw radial heat circle
                val radius = 60f + (strength * 120f)
                val alpha = (strength * 180).toInt()

                paint.color = Color.argb(alpha / 4, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(cx, cy, radius * 2, paint)

                paint.color = Color.argb(alpha / 2, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(cx, cy, radius, paint)

                paint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(cx, cy, radius / 2, paint)
            }

            // Draw crosshair when very strong
            if (strength > 0.7f) {
                arrowPaint.color = Color.RED
                arrowPaint.alpha = 200
                canvas.drawLine(cx - 50, cy, cx + 50, cy, arrowPaint)
                canvas.drawLine(cx, cy - 50, cx, cy + 50, arrowPaint)
                canvas.drawCircle(cx, cy, 30f, arrowPaint)

                textPaint.textSize = 36f
                textPaint.color = Color.RED
                canvas.drawText("⚠ SIGNAL PEAK", cx - 130, cy - 60, textPaint)
            }

        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun showTriangulationResult(result: com.staysafeai.app.models.TriangulationResult) {
        sensorManager.unregisterListener(this)

        android.app.AlertDialog.Builder(this)
            .setTitle(if (result.success) "📍 Device Located" else "Location Uncertain")
            .setMessage(
                if (result.success) {
                    "${result.message}\n\n" +
                    "Direction: ${result.directionFromUser}\n" +
                    "Est. distance: ${String.format("%.1f", result.distanceMeters)}m\n" +
                    "Confidence: ${(result.confidence * 100).toInt()}%\n" +
                    "Readings taken: ${result.readingCount}"
                } else {
                    "${result.message}\n\nTry walking around more of the room and scanning again."
                }
            )
            .setPositiveButton("Done") { _, _ -> finish() }
            .setNegativeButton("Scan Again") { _, _ -> startTracking() }
            .show()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val magnitude = kotlin.math.sqrt(
                event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
            )
            triangulator.updateUserPosition(userX, userY)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        triangulator.stopTracking()
    }
}
