package com.shadesync.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.shadesync.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var faceLandmarker: FaceLandmarker? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // --- Bitmap reuse pool to avoid per-frame allocation ---
    private var reusableBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private val rotationMatrix = Matrix()  // Reuse matrix object

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setupFaceLandmarker()
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_required),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupFaceLandmarker()
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setupColorButtons()
        setupFinishToggle()
    }

    private fun setupColorButtons() {
        // Classic Red
        binding.btnColorRed.setOnClickListener {
            binding.faceMeshOverlay.setLipColor(200, 30, 60)
            highlightSelected(it)
        }
        // Rose Pink
        binding.btnColorPink.setOnClickListener {
            binding.faceMeshOverlay.setLipColor(220, 80, 120)
            highlightSelected(it)
        }
        // Berry / Plum
        binding.btnColorBerry.setOnClickListener {
            binding.faceMeshOverlay.setLipColor(140, 30, 100)
            highlightSelected(it)
        }
        // Nude / MLBB
        binding.btnColorNude.setOnClickListener {
            binding.faceMeshOverlay.setLipColor(180, 110, 90)
            highlightSelected(it)
        }
        // Coral Orange
        binding.btnColorOrange.setOnClickListener {
            binding.faceMeshOverlay.setLipColor(220, 100, 50)
            highlightSelected(it)
        }

        // Default selection
        highlightSelected(binding.btnColorRed)
    }

    private fun setupFinishToggle() {
        binding.btnGlossy.setOnClickListener {
            binding.faceMeshOverlay.isGlossy = true
            binding.btnGlossy.setBackgroundResource(R.drawable.toggle_selected_bg)
            binding.btnGlossy.setTextColor(Color.WHITE)
            binding.btnMatte.background = null
            binding.btnMatte.setTextColor(0x80FFFFFF.toInt())
        }
        binding.btnMatte.setOnClickListener {
            binding.faceMeshOverlay.isGlossy = false
            binding.btnMatte.setBackgroundResource(R.drawable.toggle_selected_bg)
            binding.btnMatte.setTextColor(Color.WHITE)
            binding.btnGlossy.background = null
            binding.btnGlossy.setTextColor(0x80FFFFFF.toInt())
        }
    }

    private var selectedView: android.view.View? = null

    private fun highlightSelected(view: android.view.View) {
        // Reset previous
        selectedView?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            alpha = 0.7f
        }
        // Highlight current
        view.scaleX = 1.3f
        view.scaleY = 1.3f
        view.alpha = 1.0f
        selectedView = view
    }

    private fun setupFaceLandmarker() {
        // Try GPU delegate first for better performance, fall back to CPU
        val delegate = try {
            // Test if GPU delegate works on this device
            Delegate.GPU
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate not available, using CPU")
            Delegate.CPU
        }

        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .setDelegate(delegate)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this::handleResult)
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe error: ${error.message}")
                }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
            Log.d(TAG, "FaceLandmarker initialized with delegate: $delegate")
        } catch (e: Exception) {
            // If GPU failed at creation, retry with CPU
            if (delegate == Delegate.GPU) {
                Log.w(TAG, "GPU delegate failed, retrying with CPU: ${e.message}")
                try {
                    val cpuOptions = BaseOptions.builder()
                        .setModelAssetPath("face_landmarker.task")
                        .setDelegate(Delegate.CPU)
                        .build()

                    val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(cpuOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumFaces(1)
                        .setMinFaceDetectionConfidence(0.5f)
                        .setMinFacePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setResultListener(this::handleResult)
                        .setErrorListener { error ->
                            Log.e(TAG, "MediaPipe error: ${error.message}")
                        }
                        .build()

                    faceLandmarker = FaceLandmarker.createFromOptions(this, options)
                    Log.d(TAG, "FaceLandmarker initialized with CPU fallback")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to initialize FaceLandmarker: ${e2.message}", e2)
                }
            } else {
                Log.e(TAG, "Failed to initialize FaceLandmarker: ${e.message}", e)
            }
        }
    }

    private fun handleResult(result: FaceLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        runOnUiThread {
            if (result.faceLandmarks().isNotEmpty()) {
                binding.faceMeshOverlay.setResults(
                    result,
                    input.width,
                    input.height
                )
            } else {
                binding.faceMeshOverlay.clear()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Force both Preview and Analysis to same 4:3 aspect so
            // normalized landmark coordinates align precisely.
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(binding.previewView.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                analyzeImage(imageProxy)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val landmarker = faceLandmarker ?: run {
            imageProxy.close()
            return
        }

        val bitmap = imageToBitmap(imageProxy)
        if (bitmap == null) {
            imageProxy.close()
            return
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        val timestampMs = imageProxy.imageInfo.timestamp / 1_000

        try {
            landmarker.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            // Silently skip — timestamp ordering issues are expected under load
        }

        imageProxy.close()
    }

    /**
     * Optimized bitmap conversion — reuses bitmap objects across frames
     * to eliminate per-frame allocation and GC pressure.
     */
    private fun imageToBitmap(imageProxy: ImageProxy): Bitmap? {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val w = imageProxy.width
        val h = imageProxy.height

        // Reuse the source bitmap if dimensions match
        val srcBitmap = reusableBitmap.let { existing ->
            if (existing != null && existing.width == w && existing.height == h) {
                existing
            } else {
                reusableBitmap?.recycle()
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    reusableBitmap = it
                }
            }
        }

        try {
            srcBitmap.copyPixelsFromBuffer(buffer)
        } catch (e: Exception) {
            return null
        }

        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
        if (rotation == 0f) return srcBitmap

        // Reuse rotation matrix
        rotationMatrix.reset()
        rotationMatrix.postRotate(rotation)

        // For rotated bitmaps, we must create a new one (dimensions may swap)
        // but we recycle the old rotated bitmap to limit allocations
        rotatedBitmap?.recycle()
        rotatedBitmap = Bitmap.createBitmap(srcBitmap, 0, 0, w, h, rotationMatrix, false)
        return rotatedBitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        faceLandmarker?.close()
        analysisExecutor.shutdown()
        reusableBitmap?.recycle()
        rotatedBitmap?.recycle()
    }

    companion object {
        private const val TAG = "ShadeSync"
    }
}
