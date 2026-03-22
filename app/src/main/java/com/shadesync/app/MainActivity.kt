package com.shadesync.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
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
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.shadesync.app.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var faceLandmarker: FaceLandmarker? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private lateinit var shadeAdapter: ShadeAdapter
    private var activeBrand: String? = null   // null = "All"

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

        setupShadePicker()
        setupFinishToggle()
        setupLightingControls()
        setupCaptureShare()
    }

    // ── Save & Share ──

    private var lastSavedUri: Uri? = null

    private fun setupCaptureShare() {
        binding.btnCapture.setOnClickListener { captureAndSave() }
        binding.btnShare.setOnClickListener {
            val uri = lastSavedUri
            if (uri != null) {
                shareImage(uri)
            } else {
                captureAndSave(thenShare = true)
            }
        }
    }

    private fun captureAndSave(thenShare: Boolean = false) {
        // Get the actual camera frame from PreviewView (not draw() which is blank)
        val cameraBitmap = binding.previewView.bitmap
        if (cameraBitmap == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Draw camera frame + overlay onto a single composite bitmap
        val bitmap = cameraBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        // Scale overlay to match the camera bitmap dimensions
        val scaleX = bitmap.width.toFloat() / binding.faceMeshOverlay.width
        val scaleY = bitmap.height.toFloat() / binding.faceMeshOverlay.height
        canvas.save()
        canvas.scale(scaleX, scaleY)
        binding.faceMeshOverlay.draw(canvas)
        canvas.restore()

        // Add watermark
        val wmPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 255, 255)
            textSize = 36f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val shadeTxt = binding.shadeName.text.toString()
        canvas.drawText("ShadeSync · $shadeTxt", 24f, bitmap.height - 32f, wmPaint)

        // Save to gallery via MediaStore (API 29+) or legacy file
        val uri = saveBitmapToGallery(bitmap)
        if (uri != null) {
            lastSavedUri = uri
            Toast.makeText(this, "Saved to gallery ✓", Toast.LENGTH_SHORT).show()
            if (thenShare) shareImage(uri)
        } else {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val filename = "ShadeSync_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ShadeSync")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(it, values, null, null)
            }
            uri
        } else {
            // Legacy: save to cache, return FileProvider URI
            val file = File(cacheDir, filename)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }
    }

    private fun shareImage(uri: Uri) {
        val shadeTxt = binding.shadeName.text.toString()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Trying on \"$shadeTxt\" with ShadeSync \uD83D\uDC84\nhttps://github.com/DishankChauhan/shadeSync")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share your look"))
    }

    // ── Dynamic shade picker ──

    private fun setupShadePicker() {
        // RecyclerView
        shadeAdapter = ShadeAdapter(ShadeCatalog.shades) { shade ->
            applyShade(shade)
        }
        binding.shadeRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.shadeRecycler.adapter = shadeAdapter

        // Brand filter chips
        buildBrandChips()

        // Search / HEX input
        binding.shadeSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterShades(s?.toString().orEmpty())
            }
        })

        // Apply first shade on launch
        shadeAdapter.selectFirst()
    }

    private fun applyShade(shade: LipShade) {
        binding.faceMeshOverlay.setLipColor(shade.r, shade.g, shade.b)
        binding.shadeName.text = shade.name
        binding.shadeBrand.text = shade.brand.ifEmpty { shade.hex }
        // Also set glossy/matte from shade finish
        if (shade.finish.equals("Glossy", true)) {
            binding.faceMeshOverlay.isGlossy = true
            binding.btnGlossy.setBackgroundResource(R.drawable.toggle_selected_bg)
            binding.btnGlossy.setTextColor(Color.WHITE)
            binding.btnMatte.background = null
            binding.btnMatte.setTextColor(0x80FFFFFF.toInt())
        }
    }

    private fun filterShades(query: String) {
        val trimmed = query.trim()

        // HEX code handling: if starts with # and length 7, create custom shade
        if (trimmed.startsWith("#") && trimmed.length == 7) {
            try {
                val custom = ShadeCatalog.fromHex(trimmed, "Custom")
                shadeAdapter.updateShades(listOf(custom) + ShadeCatalog.search(trimmed))
                shadeAdapter.selectFirst()
                return
            } catch (_: Exception) { }
        }

        // Normal search (filtered by active brand if set)
        val base = if (activeBrand != null) ShadeCatalog.byBrand(activeBrand!!) else ShadeCatalog.shades
        val results = if (trimmed.isEmpty()) base
        else base.filter {
            it.name.contains(trimmed, true) ||
            it.brand.contains(trimmed, true) ||
            it.hex.contains(trimmed, true)
        }
        shadeAdapter.updateShades(results)
    }

    private fun buildBrandChips() {
        val container = binding.brandChipsRow
        container.removeAllViews()

        val allBrands = listOf("All") + ShadeCatalog.brands
        val dp6 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
        val dp12 = dp6 * 2
        val chipViews = mutableListOf<TextView>()

        for (brand in allBrands) {
            val chip = TextView(this).apply {
                text = brand
                setTextColor(if (brand == "All") Color.WHITE else 0x90FFFFFF.toInt())
                textSize = 12f
                setPadding(dp12, dp6, dp12, dp6)
                if (brand == "All") setBackgroundResource(R.drawable.toggle_selected_bg)
                setOnClickListener { selectBrandChip(brand, chipViews) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp6 }
            container.addView(chip, lp)
            chipViews.add(chip)
        }
    }

    private fun selectBrandChip(brand: String, chips: List<TextView>) {
        activeBrand = if (brand == "All") null else brand
        for (chip in chips) {
            if (chip.text == brand) {
                chip.setBackgroundResource(R.drawable.toggle_selected_bg)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.background = null
                chip.setTextColor(0x90FFFFFF.toInt())
            }
        }
        // Re-filter with current search query
        filterShades(binding.shadeSearch.text?.toString().orEmpty())
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

    // ── Lighting controls ──

    private fun setupLightingControls() {
        // Brightness slider: SeekBar 0–150 maps to 0.3–1.8
        binding.brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.faceMeshOverlay.brightness = 0.3f + progress / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Tone toggle: Cool / Neutral / Warm
        val toneButtons = arrayOf(binding.btnCool, binding.btnNeutral, binding.btnWarm)
        val toneValues = floatArrayOf(-0.7f, 0f, 0.7f)

        fun selectTone(idx: Int) {
            binding.faceMeshOverlay.toneShift = toneValues[idx]
            for (i in toneButtons.indices) {
                if (i == idx) {
                    toneButtons[i].setBackgroundResource(R.drawable.toggle_selected_bg)
                    toneButtons[i].setTextColor(Color.WHITE)
                } else {
                    toneButtons[i].background = null
                    toneButtons[i].setTextColor(0x80FFFFFF.toInt())
                }
            }
        }

        binding.btnCool.setOnClickListener { selectTone(0) }
        binding.btnNeutral.setOnClickListener { selectTone(1) }
        binding.btnWarm.setOnClickListener { selectTone(2) }
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
