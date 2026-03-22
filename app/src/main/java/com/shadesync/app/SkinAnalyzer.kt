package com.shadesync.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Analyses skin tone and undertone from the camera frame using
 * MediaPipe face landmark positions.
 *
 * NOT ML-heavy — pure pixel sampling + HSV classification.
 *
 * Sampling regions:
 *   • Left cheek  (landmarks 50, 101, 118, 119, 100)
 *   • Right cheek (landmarks 280, 330, 347, 348, 329)
 *   • Forehead    (landmarks 10, 109, 67, 103, 54, 21)
 *
 * Pipeline:
 *   1. Convert normalised landmarks → pixel coords
 *   2. Sample a patch around each anchor point
 *   3. Average RGB across all samples
 *   4. RGB → HSV, classify lightness + undertone
 */
object SkinAnalyzer {

    // ── Result data class ──

    data class SkinAnalysis(
        val skinTone: SkinTone,
        val undertone: Undertone,
        val avgR: Int,
        val avgG: Int,
        val avgB: Int,
        val confidence: Float   // 0–1, based on sample consistency
    ) {
        val label: String get() = "${skinTone.label} · ${undertone.label} undertone"
    }

    enum class SkinTone(val label: String) {
        FAIR("Fair"),
        LIGHT("Light"),
        MEDIUM("Medium"),
        TAN("Tan"),
        DEEP("Deep");
    }

    enum class Undertone(val label: String) {
        WARM("Warm"),
        COOL("Cool"),
        NEUTRAL("Neutral");
    }

    // ── Landmark indices for sampling ──

    // Left cheek — stable region, avoids shadow from nose
    private val LEFT_CHEEK = intArrayOf(50, 101, 118, 119, 100, 36)
    // Right cheek
    private val RIGHT_CHEEK = intArrayOf(280, 330, 347, 348, 329, 266)
    // Forehead centre
    private val FOREHEAD = intArrayOf(10, 109, 67, 103, 54, 21, 151)

    private const val SAMPLE_RADIUS = 4   // sample a (2r+1)² patch

    /**
     * Analyse skin from a camera bitmap and face landmarks.
     *
     * @param bitmap  The camera frame (after rotation).
     * @param landmarks  478 normalised landmarks from MediaPipe.
     * @param isMirrored  true for front camera (x is flipped).
     * @return SkinAnalysis or null if sampling failed.
     */
    fun analyse(
        bitmap: Bitmap,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        isMirrored: Boolean = true
    ): SkinAnalysis? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 10 || h < 10 || landmarks.size < 468) return null

        // Collect all sample pixels
        val samples = mutableListOf<IntArray>()  // each = [r,g,b]

        val allAnchors = LEFT_CHEEK + RIGHT_CHEEK + FOREHEAD
        for (idx in allAnchors) {
            val lm = landmarks[idx]
            val px = if (isMirrored) ((1f - lm.x()) * w).toInt() else (lm.x() * w).toInt()
            val py = (lm.y() * h).toInt()
            samplePatch(bitmap, px, py, samples)
        }

        if (samples.size < 10) return null

        // Average RGB
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        for (s in samples) { sumR += s[0]; sumG += s[1]; sumB += s[2] }
        val n = samples.size
        val avgR = (sumR / n).toInt()
        val avgG = (sumG / n).toInt()
        val avgB = (sumB / n).toInt()

        // Confidence: measure consistency via standard deviation of luminance
        val avgLum = (avgR * 0.299 + avgG * 0.587 + avgB * 0.114)
        var variance = 0.0
        for (s in samples) {
            val lum = s[0] * 0.299 + s[1] * 0.587 + s[2] * 0.114
            variance += (lum - avgLum) * (lum - avgLum)
        }
        val stdDev = Math.sqrt(variance / n)
        // Lower std-dev = higher confidence. Perfect = 0 stddev → 1.0 conf.
        val confidence = (1.0 - (stdDev / 60.0)).coerceIn(0.3, 1.0).toFloat()

        // Classify
        val tone = classifyTone(avgR, avgG, avgB)
        val undertone = classifyUndertone(avgR, avgG, avgB)

        return SkinAnalysis(tone, undertone, avgR, avgG, avgB, confidence)
    }

    // ── Sampling ──

    private fun samplePatch(bitmap: Bitmap, cx: Int, cy: Int, out: MutableList<IntArray>) {
        val w = bitmap.width; val h = bitmap.height
        for (dy in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
            for (dx in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
                val x = (cx + dx).coerceIn(0, w - 1)
                val y = (cy + dy).coerceIn(0, h - 1)
                val pixel = bitmap.getPixel(x, y)
                out.add(intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)))
            }
        }
    }

    // ── Skin tone classification (HSV lightness-based) ──

    private fun classifyTone(r: Int, g: Int, b: Int): SkinTone {
        // Use perceived luminance (ITU-R BT.601)
        val luminance = r * 0.299 + g * 0.587 + b * 0.114

        // Also factor in saturation — deeper skin tones are often more saturated
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val value = hsv[2]       // 0–1

        return when {
            luminance > 195 && value > 0.78 -> SkinTone.FAIR
            luminance > 165 -> SkinTone.LIGHT
            luminance > 125 -> SkinTone.MEDIUM
            luminance > 85  -> SkinTone.TAN
            else            -> SkinTone.DEEP
        }
    }

    // ── Undertone classification (RGB ratio-based) ──

    private fun classifyUndertone(r: Int, g: Int, b: Int): Undertone {
        // Warm undertones: more red/yellow → r > b, g relatively high
        // Cool undertones: more pink/blue → b >= r, or r is pinkish with high b
        // Neutral: balanced

        val rNorm = r.toFloat()
        val gNorm = g.toFloat()
        val bNorm = b.toFloat()
        val total = rNorm + gNorm + bNorm + 1f

        val rRatio = rNorm / total
        val bRatio = bNorm / total

        // Also check in HSV — hue in warm range (0°–45° / 350°–360°) vs cool (280°–340°)
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]  // 0–360

        val warmScore = when {
            rRatio - bRatio > 0.08f -> 1f          // clearly more red than blue
            hue in 0f..45f || hue > 350f -> 0.5f   // warm hue range
            else -> 0f
        }

        val coolScore = when {
            bRatio - rRatio > 0.02f -> 1f           // blue dominates
            hue in 280f..340f -> 0.5f               // cool hue range
            else -> 0f
        }

        return when {
            warmScore - coolScore > 0.3f -> Undertone.WARM
            coolScore - warmScore > 0.3f -> Undertone.COOL
            else -> Undertone.NEUTRAL
        }
    }
}
