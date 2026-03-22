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
 * Production AR overlay — multi-layer lipstick rendering with
 * texture blending, feathered edges, and gloss/matte toggle.
 *
 * Rendering pipeline (bottom → top):
 *   ① Edge feather 2 — wide soft colour fringe
 *   ② Edge feather 1 — tighter colour fringe
 *   ③ Base fill      — sheer, lets skin texture show through (~85 % transparent)
 *   ④ Core fill      — builds pigment density (~75 % transparent)
 *   ⑤ Depth stroke   — darkened contour edges for dimension
 *   ⑥ Gloss lower    — white specular band on lower lip  (glossy only)
 *   ⑦ Gloss upper    — white Cupid's bow highlight       (glossy only)
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

    // ── Lip colour state ──
    private var lipR = 200; private var lipG = 30; private var lipB = 60

    /** Switch between glossy (specular highlights) and matte (higher pigment, no shine). */
    var isGlossy: Boolean = true
        set(value) { field = value; invalidate() }

    // ──────────── Paint objects ────────────
    // Layer 1 — sheer base (skin texture visible through low alpha)
    private val lipBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // Layer 2 — core colour
    private val lipCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // Depth— darkened contour stroke for 3-D edge definition
    private val lipDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
        maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    }
    // Edge feather ring 1 — tight
    private val edgeFeather1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }
    // Edge feather ring 2 — wide, very soft
    private val edgeFeather2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 10f
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    // Gloss — lower lip specular highlight
    private val glossLowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
        color = Color.argb(65, 255, 255, 255)
        maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
    }
    // Gloss — Cupid's-bow highlight
    private val glossUpperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
        color = Color.argb(40, 255, 255, 255)
        maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    }

    // ── Pre-allocated Paths (reset each frame) ──
    private val upperLipPath = Path()
    private val lowerLipPath = Path()
    private val outerPath    = Path()
    private val glossLowerPath = Path()
    private val glossUpperPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // required for BlurMaskFilter
        applyColourToPaints()
    }

    // ──────────── Public API ────────────

    fun setLipColor(r: Int, g: Int, b: Int) {
        lipR = r; lipG = g; lipB = b
        applyColourToPaints()
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

    fun clear() { result = null; invalidate() }

    // ──────────── Landmark index sets ────────────

    companion object {
        val LIPS_OUTER = intArrayOf(
            61, 146, 91, 181, 84, 17, 314, 405, 321, 375,
            291, 409, 270, 269, 267, 0, 37, 39, 40, 185, 61
        )
        val UPPER_LIP_TOP = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)
        val UPPER_LIP_BTM = intArrayOf(291, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78, 61)
        val LOWER_LIP_TOP = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291)
        val LOWER_LIP_BTM = intArrayOf(291, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191, 78, 61)

        // Gloss highlight curves
        val GLOSS_LOWER = intArrayOf(80, 81, 82, 13, 312, 311, 310)   // inner lower-lip ridge
        val GLOSS_UPPER = intArrayOf(88, 178, 87, 14, 317, 402, 318)  // Cupid's bow ridge
    }

    // ──────────── Internal helpers ────────────

    /** Push current lipR/G/B into every paint that depends on colour. */
    private fun applyColourToPaints() {
        val r = lipR; val g = lipG; val b = lipB
        lipBasePaint.color  = Color.argb(38, r, g, b)
        lipCorePaint.color  = Color.argb(58, r, g, b)
        lipDepthPaint.color = Color.argb(30,
            (r * 0.55f).toInt().coerceIn(0, 255),
            (g * 0.35f).toInt().coerceIn(0, 255),
            (b * 0.35f).toInt().coerceIn(0, 255))
        edgeFeather1.color  = Color.argb(35, r, g, b)
        edgeFeather2.color  = Color.argb(15, r, g, b)
    }

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

        // Adjust per-frame alphas based on finish mode
        if (isGlossy) {
            lipBasePaint.alpha = 38;  lipCorePaint.alpha = 58
            edgeFeather1.alpha = 35;  edgeFeather2.alpha = 15
        } else {                       // matte — higher pigment, tighter edge
            lipBasePaint.alpha = 55;  lipCorePaint.alpha = 82
            edgeFeather1.alpha = 28;  edgeFeather2.alpha = 10
        }

        // Build reusable paths
        upperLipPath.reset();   bandPath(upperLipPath, lm, UPPER_LIP_TOP, UPPER_LIP_BTM)
        lowerLipPath.reset();   bandPath(lowerLipPath, lm, LOWER_LIP_TOP, LOWER_LIP_BTM)
        outerPath.reset();      pathFromIndices(outerPath, lm, LIPS_OUTER)

        // ① + ② Edge feathering (soft outer glow — draws under everything)
        canvas.drawPath(outerPath, edgeFeather2)
        canvas.drawPath(outerPath, edgeFeather1)

        // ③ Sheer base (skin texture bleeds through)
        canvas.drawPath(upperLipPath, lipBasePaint)
        canvas.drawPath(lowerLipPath, lipBasePaint)

        // ④ Core colour build-up
        canvas.drawPath(upperLipPath, lipCorePaint)
        canvas.drawPath(lowerLipPath, lipCorePaint)

        // ⑤ Depth contour (darkened edge for 3-D shape)
        canvas.drawPath(outerPath, lipDepthPaint)

        // ⑥ + ⑦ Gloss highlights
        if (isGlossy) {
            glossLowerPath.reset()
            pathFromIndices(glossLowerPath, lm, GLOSS_LOWER)
            canvas.drawPath(glossLowerPath, glossLowerPaint)

            glossUpperPath.reset()
            pathFromIndices(glossUpperPath, lm, GLOSS_UPPER)
            canvas.drawPath(glossUpperPath, glossUpperPaint)
        }
    }

    private fun pathFromIndices(path: Path, lm: List<NormalizedLandmark>, idx: IntArray) {
        var p = lm[idx[0]]; path.moveTo(sx(p.x()), sy(p.y()))
        for (i in 1 until idx.size) { p = lm[idx[i]]; path.lineTo(sx(p.x()), sy(p.y())) }
    }

    private fun bandPath(path: Path, lm: List<NormalizedLandmark>, outer: IntArray, inner: IntArray) {
        var p = lm[outer[0]]; path.moveTo(sx(p.x()), sy(p.y()))
        for (i in 1 until outer.size) { p = lm[outer[i]]; path.lineTo(sx(p.x()), sy(p.y())) }
        for (idx in inner) { p = lm[idx]; path.lineTo(sx(p.x()), sy(p.y())) }
        path.close()
    }
}
