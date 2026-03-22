package com.shadesync.app

import kotlin.math.abs

/**
 * Shade recommendation engine.
 *
 * Maps [SkinAnalyzer.SkinAnalysis] (tone + undertone) → best shades from the catalog.
 *
 * Logic (industry standard):
 *   Warm  → coral, orange-red, peach, nude-warm, terracotta
 *   Cool  → berry, plum, wine, pink, mauve, blue-red
 *   Neutral → most shades work; slightly favour rose, MLBB nudes, classic reds
 *
 *   Fair  → softer/lighter shades
 *   Deep  → richer/bolder shades
 *   Medium/Tan → widest range
 *
 * Additionally provides "contrast scoring" so the recommendation
 * list is sorted best-first.
 */
object ShadeRecommender {

    data class Recommendation(
        val shade: LipShade,
        val score: Float,       // 0–100, higher = better match
        val reason: String      // human-readable reason
    )

    /**
     * Return the top N recommended shades for the given skin analysis.
     */
    fun recommend(
        analysis: SkinAnalyzer.SkinAnalysis,
        catalog: List<LipShade> = ShadeCatalog.shades,
        limit: Int = 12
    ): List<Recommendation> {
        return catalog
            .map { shade -> score(shade, analysis) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    // ── Scoring engine ──

    private fun score(shade: LipShade, analysis: SkinAnalyzer.SkinAnalysis): Recommendation {
        var total = 50f   // base score — every shade starts at 50
        val reasons = mutableListOf<String>()

        // --- 1. Undertone match (up to +25 / -15) ---
        val undertoneScore = undertoneMatch(shade, analysis.undertone)
        total += undertoneScore
        if (undertoneScore > 10) reasons.add("Matches your ${analysis.undertone.label.lowercase()} undertone")

        // --- 2. Skin tone contrast (up to +20 / -10) ---
        val contrastScore = contrastMatch(shade, analysis)
        total += contrastScore
        if (contrastScore > 8) reasons.add("Great contrast for ${analysis.skinTone.label.lowercase()} skin")

        // --- 3. Colour harmony via hue distance (up to +15 / -5) ---
        val harmonyScore = harmonyMatch(shade, analysis)
        total += harmonyScore
        if (harmonyScore > 8) reasons.add("Harmonious colour match")

        // --- 4. Universally flattering bonus ---
        if (isUniversallyFlattering(shade)) {
            total += 8
            reasons.add("Universally flattering shade")
        }

        val reason = if (reasons.isEmpty()) "Decent match" else reasons.joinToString(" · ")
        return Recommendation(shade, total.coerceIn(0f, 100f), reason)
    }

    // ── Undertone matching ──

    private fun undertoneMatch(shade: LipShade, undertone: SkinAnalyzer.Undertone): Float {
        val r = shade.r; val g = shade.g; val b = shade.b

        // Classify shade's colour temperature
        val rDominance = r.toFloat() / (r + g + b + 1)
        val bDominance = b.toFloat() / (r + g + b + 1)
        val isWarmShade = rDominance > 0.40 && bDominance < 0.25   // orange/coral/red-warm
        val isCoolShade = bDominance > 0.22 || (r > 100 && b > g)  // berry/plum/blue-red
        val isNeutralShade = !isWarmShade && !isCoolShade

        return when (undertone) {
            SkinAnalyzer.Undertone.WARM -> when {
                isWarmShade    -> 25f    // warm shade on warm skin = perfect
                isNeutralShade -> 10f    // neutrals work well
                isCoolShade    -> -5f    // cool shade on warm skin = less ideal
                else           -> 0f
            }
            SkinAnalyzer.Undertone.COOL -> when {
                isCoolShade    -> 25f
                isNeutralShade -> 10f
                isWarmShade    -> -5f
                else           -> 0f
            }
            SkinAnalyzer.Undertone.NEUTRAL -> when {
                isNeutralShade -> 15f     // neutral on neutral is good
                else           -> 10f     // everything works on neutral
            }
        }
    }

    // ── Contrast matching ──

    private fun contrastMatch(shade: LipShade, analysis: SkinAnalyzer.SkinAnalysis): Float {
        // Luminance of shade
        val shadeLum = shade.r * 0.299 + shade.g * 0.587 + shade.b * 0.114
        // Luminance of skin
        val skinLum = analysis.avgR * 0.299 + analysis.avgG * 0.587 + analysis.avgB * 0.114

        val diff = abs(shadeLum - skinLum)

        return when (analysis.skinTone) {
            SkinAnalyzer.SkinTone.FAIR -> when {
                // Fair skin: medium-to-rich shades pop nicely
                diff in 40.0..120.0 -> 20f
                diff in 20.0..40.0  -> 10f
                diff > 120.0        -> 5f    // very dark on very fair can work dramatically
                else                -> -5f   // too similar = washed out
            }
            SkinAnalyzer.SkinTone.LIGHT -> when {
                diff in 30.0..100.0 -> 18f
                diff in 15.0..30.0  -> 10f
                else                -> 0f
            }
            SkinAnalyzer.SkinTone.MEDIUM -> when {
                diff in 20.0..90.0  -> 15f   // medium skin = widest range
                else                -> 5f
            }
            SkinAnalyzer.SkinTone.TAN -> when {
                diff in 25.0..100.0 -> 18f
                shadeLum < skinLum  -> 12f   // darker shades pop on tan skin
                else                -> 3f
            }
            SkinAnalyzer.SkinTone.DEEP -> when {
                shadeLum > skinLum + 20 -> 20f  // brighter shades pop beautifully
                diff in 10.0..60.0  -> 15f
                shadeLum < 60       -> -10f     // too similar = invisible
                else                -> 5f
            }
        }
    }

    // ── Harmony matching (complementary hue) ──

    private fun harmonyMatch(shade: LipShade, analysis: SkinAnalyzer.SkinAnalysis): Float {
        val shadeHsv = FloatArray(3)
        val skinHsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(shade.r, shade.g, shade.b, shadeHsv)
        android.graphics.Color.RGBToHSV(analysis.avgR, analysis.avgG, analysis.avgB, skinHsv)

        // Hue distance on the colour wheel (0–180)
        val hueDiff = minOf(
            abs(shadeHsv[0] - skinHsv[0]),
            360 - abs(shadeHsv[0] - skinHsv[0])
        )

        // Complementary (150–180°) or analogous (0–30°) harmonies score well
        return when {
            hueDiff in 150f..180f -> 15f   // complementary
            hueDiff in 0f..30f   -> 10f    // analogous
            hueDiff in 30f..60f  -> 8f     // split-analogous
            hueDiff in 120f..150f -> 5f    // triadic-ish
            else -> 0f
        }
    }

    // ── Universally flattering ──

    private fun isUniversallyFlattering(shade: LipShade): Boolean {
        // Certain types are known to work on virtually everyone:
        // Berry-reds, MLBB (my-lips-but-better) nudes, classic blue-based reds
        val name = shade.name.lowercase()
        val universalKeywords = listOf(
            "ruby", "berry", "rose", "pillow", "velvet", "twig",
            "coral", "nude", "classic", "red"
        )
        return universalKeywords.any { name.contains(it) }
    }

    // ── Quick summary helpers ──

    /** One-line recommendation for the top shade. */
    fun topPick(analysis: SkinAnalyzer.SkinAnalysis): Recommendation? {
        val recs = recommend(analysis, limit = 1)
        return recs.firstOrNull()
    }

    /** Quick text summary for display: "Best for you: Pillow Talk (CT)" */
    fun summary(analysis: SkinAnalyzer.SkinAnalysis): String {
        val top3 = recommend(analysis, limit = 3)
        if (top3.isEmpty()) return "No recommendations yet"
        val picks = top3.joinToString(", ") { "${it.shade.name}" }
        return "Top picks: $picks"
    }
}
