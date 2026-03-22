package com.shadesync.app

/**
 * A "Full Look" preset that combines lipstick shade, blush, and
 * skin smoothing into a single one-tap look.
 */
data class LookPreset(
    val name: String,            // "Date Night"
    val emoji: String,           // emoji icon for the chip
    val description: String,     // short flavour text
    // Lipstick
    val lipShade: LipShade,
    // Blush (ARGB values + intensity 0-1)
    val blushR: Int,
    val blushG: Int,
    val blushB: Int,
    val blushIntensity: Float,   // 0 = no blush, 1 = full
    // Skin smoothing (0–1)
    val smoothing: Float,        // 0 = none, 1 = max
    // Finish override
    val glossy: Boolean,
    // Tone / brightness hints
    val brightness: Float = 1.0f,
    val toneShift: Float = 0f
)

/**
 * Built-in look presets — curated combinations of lip + blush + smoothing.
 */
object PresetCatalog {

    val presets: List<LookPreset> = listOf(

        // ── Date Night ──
        LookPreset(
            name = "Date Night",
            emoji = "🌹",
            description = "Bold red lip · rosy blush · soft glow",
            lipShade = LipShade("Russian Red", "#B01C2E", "MAC", "Matte"),
            blushR = 210, blushG = 100, blushB = 110,
            blushIntensity = 0.55f,
            smoothing = 0.6f,
            glossy = false,
            brightness = 1.05f,
            toneShift = 0.2f
        ),

        // ── Korean Glass ──
        LookPreset(
            name = "Korean Glass",
            emoji = "✨",
            description = "Dewy tint · glass skin · subtle flush",
            lipShade = LipShade("Shawty", "#DA7070", "Fenty Beauty", "Glossy"),
            blushR = 230, blushG = 140, blushB = 140,
            blushIntensity = 0.35f,
            smoothing = 0.85f,
            glossy = true,
            brightness = 1.15f,
            toneShift = 0f
        ),

        // ── Office Nude ──
        LookPreset(
            name = "Office Nude",
            emoji = "💼",
            description = "MLBB nude · natural blush · clean skin",
            lipShade = LipShade("Velvet Teddy", "#9C5A4B", "MAC", "Matte"),
            blushR = 190, blushG = 130, blushB = 120,
            blushIntensity = 0.3f,
            smoothing = 0.4f,
            glossy = false,
            brightness = 1.0f,
            toneShift = 0f
        ),

        // ── Berry Glam ──
        LookPreset(
            name = "Berry Glam",
            emoji = "🍇",
            description = "Deep berry lip · plum blush · evening glam",
            lipShade = LipShade("Berry Noir", "#8C1E64", "ShadeSync", "Matte"),
            blushR = 160, blushG = 70, blushB = 110,
            blushIntensity = 0.5f,
            smoothing = 0.65f,
            glossy = false,
            brightness = 0.95f,
            toneShift = -0.3f
        ),

        // ── Sunset Glow ──
        LookPreset(
            name = "Sunset Glow",
            emoji = "🌅",
            description = "Warm coral · peachy flush · sun-kissed",
            lipShade = LipShade("Sunset Coral", "#DC6432", "ShadeSync", "Glossy"),
            blushR = 220, blushG = 140, blushB = 100,
            blushIntensity = 0.5f,
            smoothing = 0.5f,
            glossy = true,
            brightness = 1.1f,
            toneShift = 0.5f
        ),

        // ── Soft Rosé ──
        LookPreset(
            name = "Soft Rosé",
            emoji = "🌸",
            description = "Dusty rose · pink blush · romantic",
            lipShade = LipShade("Pillow Talk", "#B46E6E", "Charlotte Tilbury", "Matte"),
            blushR = 200, blushG = 120, blushB = 130,
            blushIntensity = 0.45f,
            smoothing = 0.55f,
            glossy = false,
            brightness = 1.0f,
            toneShift = -0.1f
        ),

        // ── No-Makeup Makeup ──
        LookPreset(
            name = "No-Makeup",
            emoji = "🪞",
            description = "Barely-there tint · glass skin · invisible",
            lipShade = LipShade("Nude Silk", "#B46E5A", "ShadeSync", "Satin"),
            blushR = 200, blushG = 150, blushB = 140,
            blushIntensity = 0.2f,
            smoothing = 0.7f,
            glossy = true,
            brightness = 1.05f,
            toneShift = 0f
        ),

        // ── Bold Power ──
        LookPreset(
            name = "Bold Power",
            emoji = "💄",
            description = "Statement red · sculpted blush · flawless",
            lipShade = LipShade("Ruby Woo", "#C60018", "MAC", "Matte"),
            blushR = 180, blushG = 90, blushB = 90,
            blushIntensity = 0.4f,
            smoothing = 0.75f,
            glossy = false,
            brightness = 1.0f,
            toneShift = 0.1f
        ),
    )
}
