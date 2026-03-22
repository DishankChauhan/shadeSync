package com.shadesync.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceMeshOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Cached results to avoid re-processing ---
    private var result: FaceLandmarkerResult? = null
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // --- Pre-allocated Paint objects (never recreated) ---
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 255, 200)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private var lipR = 200
    private var lipG = 30
    private var lipB = 60
    private var lipAlpha = 100

    private val lipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(lipAlpha, lipR, lipG, lipB)
        style = Paint.Style.FILL
    }

    private val lipEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, lipR, lipG, lipB)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // --- Pre-allocated Path objects (reset each frame, never re-created) ---
    private val upperLipPath = Path()
    private val lowerLipPath = Path()
    private val contourPath = Path()

    fun setLipColor(r: Int, g: Int, b: Int, alpha: Int = 100) {
        lipR = r; lipG = g; lipB = b; lipAlpha = alpha
        lipFillPaint.color = Color.argb(alpha, r, g, b)
        lipEdgePaint.color = Color.argb(alpha / 2, r, g, b)
        invalidate()
    }

    companion object {
        // Using IntArrays instead of List<Int> to avoid boxing/iterator overhead
        val FACE_OVAL = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109, 10
        )
        val LEFT_EYE = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246, 33)
        val RIGHT_EYE = intArrayOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398, 362)
        val LEFT_EYEBROW = intArrayOf(46, 53, 52, 65, 55, 107, 66, 105, 63, 70, 46)
        val RIGHT_EYEBROW = intArrayOf(276, 283, 282, 295, 285, 336, 296, 334, 293, 300, 276)
        val NOSE = intArrayOf(168, 6, 197, 195, 5, 4, 1, 19, 94, 2)

        val LIPS_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185, 61)
        val LIPS_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191, 78)

        val UPPER_LIP_OUTER = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)
        val UPPER_LIP_INNER = intArrayOf(291, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78, 61)
        val LOWER_LIP_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291)
        val LOWER_LIP_INNER = intArrayOf(291, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191, 78, 61)

        // Pre-built set for O(1) lip landmark lookup — created once, never re-allocated
        val LIP_LANDMARK_SET = hashSetOf(
            61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0,
            37, 39, 40, 185, 78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415,
            310, 311, 312, 13, 82, 81, 80, 191
        )

        // All contour index arrays for batch iteration
        val CONTOURS = arrayOf(FACE_OVAL, LEFT_EYE, RIGHT_EYE, LEFT_EYEBROW, RIGHT_EYEBROW, NOSE)
    }

    fun setResults(
        faceLandmarkerResult: FaceLandmarkerResult,
        inputImageWidth: Int,
        inputImageHeight: Int
    ) {
        result = faceLandmarkerResult
        imageWidth = inputImageWidth
        imageHeight = inputImageHeight
        invalidate()
    }

    fun clear() {
        result = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = result ?: return
        if (res.faceLandmarks().isEmpty()) return

        val landmarks = res.faceLandmarks()[0]
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw lipstick fill (no saveLayer — just draw the paths directly)
        drawLipstick(canvas, landmarks, w, h)

        // 2. Draw contour lines using batched paths
        for (contour in CONTOURS) {
            drawContourAsPath(canvas, landmarks, contour, w, h, linePaint)
        }

        // 3. Draw lip contour edges
        drawContourAsPath(canvas, landmarks, LIPS_OUTER, w, h, lipEdgePaint)
        drawContourAsPath(canvas, landmarks, LIPS_INNER, w, h, lipEdgePaint)

        // 4. Draw landmark points — only every 3rd point to reduce draw calls by ~67%
        val size = landmarks.size
        var i = 0
        while (i < size) {
            if (i !in LIP_LANDMARK_SET) {
                val lm = landmarks[i]
                val x = (1f - lm.x()) * w
                val y = lm.y() * h
                canvas.drawCircle(x, y, 1.5f, pointPaint)
            }
            i += 3
        }
    }

    private fun drawLipstick(canvas: Canvas, landmarks: List<NormalizedLandmark>, w: Float, h: Float) {
        // Reset and reuse pre-allocated paths — zero allocation
        upperLipPath.reset()
        buildLipPathInto(upperLipPath, landmarks, UPPER_LIP_OUTER, UPPER_LIP_INNER, w, h)
        canvas.drawPath(upperLipPath, lipFillPaint)

        lowerLipPath.reset()
        buildLipPathInto(lowerLipPath, landmarks, LOWER_LIP_OUTER, LOWER_LIP_INNER, w, h)
        canvas.drawPath(lowerLipPath, lipFillPaint)
    }

    private fun buildLipPathInto(
        path: Path,
        landmarks: List<NormalizedLandmark>,
        outerIndices: IntArray,
        innerIndices: IntArray,
        w: Float,
        h: Float
    ) {
        var lm = landmarks[outerIndices[0]]
        path.moveTo((1f - lm.x()) * w, lm.y() * h)
        for (i in 1 until outerIndices.size) {
            lm = landmarks[outerIndices[i]]
            path.lineTo((1f - lm.x()) * w, lm.y() * h)
        }
        for (idx in innerIndices) {
            lm = landmarks[idx]
            path.lineTo((1f - lm.x()) * w, lm.y() * h)
        }
        path.close()
    }

    /**
     * Draw contour using a single Path + drawPath instead of N individual drawLine calls.
     * This batches the GPU draw calls into one operation.
     */
    private fun drawContourAsPath(
        canvas: Canvas,
        landmarks: List<NormalizedLandmark>,
        indices: IntArray,
        w: Float,
        h: Float,
        paint: Paint
    ) {
        if (indices.size < 2) return
        contourPath.reset()
        var lm = landmarks[indices[0]]
        contourPath.moveTo((1f - lm.x()) * w, lm.y() * h)
        for (i in 1 until indices.size) {
            lm = landmarks[indices[i]]
            contourPath.lineTo((1f - lm.x()) * w, lm.y() * h)
        }
        canvas.drawPath(contourPath, paint)
    }
}
