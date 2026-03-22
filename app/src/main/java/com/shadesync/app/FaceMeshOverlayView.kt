package com.shadesync.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceMeshOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var result: FaceLandmarkerResult? = null
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.argb(180, 0, 255, 200)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Lipstick fill paint — semi-transparent red
    private val lipFillPaint = Paint().apply {
        color = Color.argb(100, 200, 30, 60)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Lip edge softening paint
    private val lipEdgePaint = Paint().apply {
        color = Color.argb(60, 200, 30, 60)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // Paint used to cut out the inner mouth opening
    private val clearPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    companion object {
        // Face oval
        val FACE_OVAL = listOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109, 10
        )
        val LEFT_EYE = listOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246, 33)
        val RIGHT_EYE = listOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398, 362)

        // Outer lip contour — full closed loop for polygon fill
        val LIPS_OUTER = listOf(
            61, 146, 91, 181, 84, 17, 314, 405, 321, 375,
            291, 409, 270, 269, 267, 0, 37, 39, 40, 185, 61
        )

        // Inner lip contour (mouth opening) — closed loop
        val LIPS_INNER = listOf(
            78, 95, 88, 178, 87, 14, 317, 402, 318, 324,
            308, 415, 310, 311, 312, 13, 82, 81, 80, 191, 78
        )

        // Upper lip fill: outer upper edge → inner upper edge (reversed) to form closed polygon
        val UPPER_LIP_OUTER = listOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)
        val UPPER_LIP_INNER = listOf(291, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78, 61)

        // Lower lip fill: outer lower edge → inner lower edge (reversed) to form closed polygon
        val LOWER_LIP_OUTER = listOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291)
        val LOWER_LIP_INNER = listOf(291, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191, 78, 61)

        val LEFT_EYEBROW = listOf(46, 53, 52, 65, 55, 107, 66, 105, 63, 70, 46)
        val RIGHT_EYEBROW = listOf(276, 283, 282, 295, 285, 336, 296, 334, 293, 300, 276)
        val NOSE = listOf(168, 6, 197, 195, 5, 4, 1, 19, 94, 2)
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

        for (landmarks in res.faceLandmarks()) {
            // --- Draw lipstick fill ---
            drawLipstick(canvas, landmarks)

            // --- Draw contour lines ---
            for (contour in listOf(FACE_OVAL, LEFT_EYE, RIGHT_EYE, LEFT_EYEBROW, RIGHT_EYEBROW, NOSE)) {
                drawContour(canvas, landmarks, contour)
            }

            // Draw lip contour edges with softer color to blend
            drawContourWithPaint(canvas, landmarks, LIPS_OUTER, lipEdgePaint)
            drawContourWithPaint(canvas, landmarks, LIPS_INNER, lipEdgePaint)

            // Draw all 478 landmark points (skip lip landmarks to keep lipstick clean)
            for ((i, landmark) in landmarks.withIndex()) {
                // Skip drawing dots on lip region landmarks to avoid clutter
                if (isLipLandmark(i)) continue
                val x = (1f - landmark.x()) * width
                val y = landmark.y() * height
                canvas.drawCircle(x, y, 2f, pointPaint)
            }
        }
    }

    private fun drawLipstick(canvas: Canvas, landmarks: List<NormalizedLandmark>) {
        // Use a layer so we can composite properly
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // Draw upper lip (area between outer upper edge and inner upper edge)
        val upperPath = buildLipPath(landmarks, UPPER_LIP_OUTER, UPPER_LIP_INNER)
        canvas.drawPath(upperPath, lipFillPaint)

        // Draw lower lip
        val lowerPath = buildLipPath(landmarks, LOWER_LIP_OUTER, LOWER_LIP_INNER)
        canvas.drawPath(lowerPath, lipFillPaint)

        canvas.restoreToCount(saveCount)
    }

    /**
     * Builds a closed Path tracing the outer edge forward then the inner edge
     * (which acts as the other boundary), forming the lip band.
     */
    private fun buildLipPath(
        landmarks: List<NormalizedLandmark>,
        outerIndices: List<Int>,
        innerIndices: List<Int>
    ): Path {
        val path = Path()

        // Trace outer edge
        for ((i, idx) in outerIndices.withIndex()) {
            val lm = landmarks[idx]
            val x = (1f - lm.x()) * width
            val y = lm.y() * height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Continue tracing inner edge (reverse direction to close the band)
        for (idx in innerIndices) {
            val lm = landmarks[idx]
            val x = (1f - lm.x()) * width
            val y = lm.y() * height
            path.lineTo(x, y)
        }

        path.close()
        return path
    }

    private fun isLipLandmark(index: Int): Boolean {
        // All landmark indices that are part of lip contours
        return index in setOf(
            61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0,
            37, 39, 40, 185, 78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415,
            310, 311, 312, 13, 82, 81, 80, 191
        )
    }

    private fun drawContour(
        canvas: Canvas,
        landmarks: List<NormalizedLandmark>,
        indices: List<Int>
    ) {
        drawContourWithPaint(canvas, landmarks, indices, linePaint)
    }

    private fun drawContourWithPaint(
        canvas: Canvas,
        landmarks: List<NormalizedLandmark>,
        indices: List<Int>,
        paint: Paint
    ) {
        for (i in 0 until indices.size - 1) {
            val start = landmarks[indices[i]]
            val end = landmarks[indices[i + 1]]
            val startX = (1f - start.x()) * width
            val startY = start.y() * height
            val endX = (1f - end.x()) * width
            val endY = end.y() * height
            canvas.drawLine(startX, startY, endX, endY, paint)
        }
    }
}
