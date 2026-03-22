package com.shadesync.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Production-quality overlay that renders lipstick with precise coordinate
 * mapping that matches PreviewView's FILL_CENTER behavior.
 */
class FaceMeshOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var result: FaceLandmarkerResult? = null
    private var sourceImageWidth: Int = 1
    private var sourceImageHeight: Int = 1

    // --- Coordinate transform cache (recalculated when view/image size changes) ---
    private var tScale: Float = 1f
    private var tOffsetX: Float = 0f
    private var tOffsetY: Float = 0f
    private var lastViewW: Int = 0
    private var lastViewH: Int = 0
    private var lastImgW: Int = 0
    private var lastImgH: Int = 0

    // --- Lip color state ---
    private var lipR = 200
    private var lipG = 30
    private var lipB = 60
    private var lipAlpha = 110

    // Main lip fill
    private val lipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(lipAlpha, lipR, lipG, lipB)
        style = Paint.Style.FILL
    }

    // Soft glow around lip edges for natural blending
    private val lipGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, lipR, lipG, lipB)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }

    // --- Pre-allocated Paths (reset each frame, never recreated) ---
    private val upperLipPath = Path()
    private val lowerLipPath = Path()
    private val glowPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setLipColor(r: Int, g: Int, b: Int, alpha: Int = 110) {
        lipR = r; lipG = g; lipB = b; lipAlpha = alpha
        lipFillPaint.color = Color.argb(alpha, r, g, b)
        lipGlowPaint.color = Color.argb(50, r, g, b)
        invalidate()
    }

    companion object {
        // Outer lip contour
        val LIPS_OUTER = intArrayOf(
            61, 146, 91, 181, 84, 17, 314, 405, 321, 375,
            291, 409, 270, 269, 267, 0, 37, 39, 40, 185, 61
        )
        // Upper lip band
        val UPPER_LIP_TOP = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)
        val UPPER_LIP_BTM = intArrayOf(291, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78, 61)
        // Lower lip band
        val LOWER_LIP_TOP = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291)
        val LOWER_LIP_BTM = intArrayOf(291, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191, 78, 61)
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

    fun clear() {
        result = null
        invalidate()
    }

    /**
     * Compute the FILL_CENTER transform so landmark coordinates
     * align precisely with the PreviewView's visible camera feed.
     */
    private fun updateTransform() {
        val vw = width; val vh = height
        val iw = sourceImageWidth; val ih = sourceImageHeight
        if (vw == lastViewW && vh == lastViewH && iw == lastImgW && ih == lastImgH) return
        lastViewW = vw; lastViewH = vh; lastImgW = iw; lastImgH = ih

        val viewAspect = vw.toFloat() / vh.toFloat()
        val imageAspect = iw.toFloat() / ih.toFloat()

        if (imageAspect > viewAspect) {
            tScale = vh.toFloat() / ih
            tOffsetX = (vw - iw * tScale) / 2f
            tOffsetY = 0f
        } else {
            tScale = vw.toFloat() / iw
            tOffsetX = 0f
            tOffsetY = (vh - ih * tScale) / 2f
        }
    }

    private fun sx(nx: Float): Float = (1f - nx) * sourceImageWidth * tScale + tOffsetX
    private fun sy(ny: Float): Float = ny * sourceImageHeight * tScale + tOffsetY

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = result ?: return
        if (res.faceLandmarks().isEmpty()) return
        val lm = res.faceLandmarks()[0]
        updateTransform()

        // 1. Soft glow on outer contour
        glowPath.reset()
        pathFromIndices(glowPath, lm, LIPS_OUTER)
        canvas.drawPath(glowPath, lipGlowPaint)

        // 2. Upper lip fill
        upperLipPath.reset()
        bandPath(upperLipPath, lm, UPPER_LIP_TOP, UPPER_LIP_BTM)
        canvas.drawPath(upperLipPath, lipFillPaint)

        // 3. Lower lip fill
        lowerLipPath.reset()
        bandPath(lowerLipPath, lm, LOWER_LIP_TOP, LOWER_LIP_BTM)
        canvas.drawPath(lowerLipPath, lipFillPaint)
    }

    private fun pathFromIndices(path: Path, lm: List<NormalizedLandmark>, idx: IntArray) {
        var p = lm[idx[0]]
        path.moveTo(sx(p.x()), sy(p.y()))
        for (i in 1 until idx.size) { p = lm[idx[i]]; path.lineTo(sx(p.x()), sy(p.y())) }
    }

    private fun bandPath(path: Path, lm: List<NormalizedLandmark>, outer: IntArray, inner: IntArray) {
        var p = lm[outer[0]]
        path.moveTo(sx(p.x()), sy(p.y()))
        for (i in 1 until outer.size) { p = lm[outer[i]]; path.lineTo(sx(p.x()), sy(p.y())) }
        for (idx in inner) { p = lm[idx]; path.lineTo(sx(p.x()), sy(p.y())) }
        path.close()
    }
}
