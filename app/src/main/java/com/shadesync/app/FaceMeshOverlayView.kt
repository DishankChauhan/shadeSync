package com.shadesync.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * AR overlay for blush and skin smoothing effects.
 * Lip rendering is handled by LipGLSurfaceView (OpenGL ES 2.0).
 */
class FaceMeshOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var result: FaceLandmarkerResult? = null
    private var sourceImageWidth: Int = 1
    private var sourceImageHeight: Int = 1

    // ── Coordinate transform cache ──
    private var tScale: Float = 1f
    private var tOffsetX: Float = 0f
    private var tOffsetY: Float = 0f
    private var lastViewW = 0; private var lastViewH = 0
    private var lastImgW = 0;  private var lastImgH = 0

    // ── Blush state ──
    private var blushR = 210; private var blushG = 100; private var blushB = 110
    private var blushAlpha = 0f   // 0 = off, 1 = full

    // ── Skin smoothing state ──
    private var smoothingLevel = 0f   // 0 = off, 1 = max

    // ── Temporal smoothing for stable landmark tracking ──
    private var smoothedX = FloatArray(478)
    private var smoothedY = FloatArray(478)
    private var hasSmoothedData = false
    private val SMOOTH_FACTOR = 0.45f

    // ── Blush paints ──
    private val blushPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
    }
    private val blushPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    // ── Skin smoothing paint ──
    private val smoothPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Pre-allocated Paths (reset each frame) ──
    private val leftCheekPath  = Path()
    private val rightCheekPath = Path()
    private val facePath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // required for BlurMaskFilter
        updateBlushPaint()
    }

    private fun updateBlushPaint() {
        val a1 = (blushAlpha * 28).toInt().coerceIn(0, 255)
        val a2 = (blushAlpha * 18).toInt().coerceIn(0, 255)
        blushPaint1.color = Color.argb(a1, blushR, blushG, blushB)
        blushPaint2.color = Color.argb(a2, blushR, blushG, blushB)
    }

    // ──────────── Public API ────────────

    /** Set blush colour and intensity (0–1). Pass intensity=0 to disable. */
    fun setBlush(r: Int, g: Int, b: Int, intensity: Float) {
        blushR = r; blushG = g; blushB = b
        blushAlpha = intensity.coerceIn(0f, 1f)
        updateBlushPaint()
        invalidate()
    }

    /** Set skin smoothing level (0 = off, 1 = max glass-skin). */
    fun setSmoothing(level: Float) {
        smoothingLevel = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun setResults(
        faceLandmarkerResult: FaceLandmarkerResult,
        inputImageWidth: Int,
        inputImageHeight: Int
    ) {
        result = faceLandmarkerResult
        sourceImageWidth = inputImageWidth
        sourceImageHeight = inputImageHeight
        invalidate()
    }

    fun clear() { result = null; hasSmoothedData = false; invalidate() }

    // ──────────── Landmark index sets ────────────

    companion object {
        // Cheek blush regions (apple of cheeks)
        val LEFT_CHEEK  = intArrayOf(50, 101, 118, 117, 111, 100, 36, 205, 187, 123, 116, 50)
        val RIGHT_CHEEK = intArrayOf(280, 330, 347, 346, 340, 329, 266, 425, 411, 352, 345, 280)

        // Face outline for smoothing overlay
        val FACE_OVAL = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109, 10
        )
    }

    // ──────────── Internal helpers ────────────

    /** Compute FILL_CENTER scale + offset so landmarks match the PreviewView. */
    private fun updateTransform() {
        val vw = width; val vh = height
        val iw = sourceImageWidth; val ih = sourceImageHeight
        if (vw == lastViewW && vh == lastViewH && iw == lastImgW && ih == lastImgH) return
        lastViewW = vw; lastViewH = vh; lastImgW = iw; lastImgH = ih
        if (iw.toFloat() / ih > vw.toFloat() / vh) {
            tScale = vh.toFloat() / ih; tOffsetX = (vw - iw * tScale) / 2f; tOffsetY = 0f
        } else {
            tScale = vw.toFloat() / iw; tOffsetX = 0f; tOffsetY = (vh - ih * tScale) / 2f
        }
    }

    /** Normalised X → screen X (mirror for front camera). */
    private fun sx(nx: Float): Float = (1f - nx) * sourceImageWidth * tScale + tOffsetX
    /** Normalised Y → screen Y. */
    private fun sy(ny: Float): Float = ny * sourceImageHeight * tScale + tOffsetY

    // ──────────── Drawing ────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = result ?: return
        if (res.faceLandmarks().isEmpty()) return
        val lm = res.faceLandmarks()[0]
        updateTransform()

        // Initialise temporal smoothing on first frame
        if (!hasSmoothedData) {
            for (i in 0 until minOf(lm.size, 478)) {
                smoothedX[i] = lm[i].x(); smoothedY[i] = lm[i].y()
            }
            hasSmoothedData = true
        }

        // ── Skin smoothing (drawn first, under everything) ──
        if (smoothingLevel > 0.05f) {
            facePath.reset(); pathFromIndices(facePath, lm, FACE_OVAL); facePath.close()
            val sAlpha = (smoothingLevel * 30).toInt().coerceIn(0, 60)
            smoothPaint.color = Color.argb(sAlpha, 220, 195, 175)
            smoothPaint.maskFilter = BlurMaskFilter(24f + smoothingLevel * 16f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(facePath, smoothPaint)
        }

        // ── Blush ──
        if (blushAlpha > 0.05f) {
            leftCheekPath.reset(); pathFromIndices(leftCheekPath, lm, LEFT_CHEEK); leftCheekPath.close()
            rightCheekPath.reset(); pathFromIndices(rightCheekPath, lm, RIGHT_CHEEK); rightCheekPath.close()
            canvas.drawPath(leftCheekPath, blushPaint1);  canvas.drawPath(rightCheekPath, blushPaint1)
            canvas.drawPath(leftCheekPath, blushPaint2);  canvas.drawPath(rightCheekPath, blushPaint2)
        }
    }

    // ──────────── Path helpers ────────────

    /** Apply temporal smoothing to a landmark and return screen-space coords. */
    private fun smoothedPoint(index: Int, lm: List<NormalizedLandmark>): Pair<Float, Float> {
        val rawX = lm[index].x(); val rawY = lm[index].y()
        smoothedX[index] += SMOOTH_FACTOR * (rawX - smoothedX[index])
        smoothedY[index] += SMOOTH_FACTOR * (rawY - smoothedY[index])
        return Pair(sx(smoothedX[index]), sy(smoothedY[index]))
    }

    /** Simple linear path from landmark indices (for blush, face oval). */
    private fun pathFromIndices(path: Path, lm: List<NormalizedLandmark>, idx: IntArray) {
        val p0 = smoothedPoint(idx[0], lm)
        path.moveTo(p0.first, p0.second)
        for (i in 1 until idx.size) {
            val p = smoothedPoint(idx[i], lm)
            path.lineTo(p.first, p.second)
        }
    }
}
