package com.shadesync.app

import android.graphics.Color

/**
 * A single lipstick shade — can come from the built-in catalog,
 * a brand database, or user-entered HEX code.
 */
data class LipShade(
    val name: String,
    val hex: String,          // e.g. "#B23A48"
    val brand: String = "",   // e.g. "Nykaa", "MAC"
    val finish: String = "",  // e.g. "Matte", "Glossy", "Satin"
) {
    val colorInt: Int get() = Color.parseColor(hex)
    val r: Int get() = Color.red(colorInt)
    val g: Int get() = Color.green(colorInt)
    val b: Int get() = Color.blue(colorInt)
}

/**
 * Built-in shade catalog — 50+ real-world-inspired shades organised by brand.
 * Any shade can also be created dynamically from a HEX code.
 */
object ShadeCatalog {

    // ── Brands ──

    val brands: List<String> get() = shades.map { it.brand }.distinct().sorted()

    // ── Full catalog ──

    val shades: List<LipShade> = listOf(
        // ─── Nykaa ───
        LipShade("Wicked Wine",       "#722F37", "Nykaa",     "Matte"),
        LipShade("Naughty Nude",      "#B8806A", "Nykaa",     "Matte"),
        LipShade("Boss Babe",         "#8B1A1A", "Nykaa",     "Matte"),
        LipShade("Bombshell Berry",   "#6D2B5E", "Nykaa",     "Matte"),
        LipShade("Runway Red",        "#C81E3C", "Nykaa",     "Matte"),
        LipShade("Duchess Pink",      "#CC5B78", "Nykaa",     "Satin"),
        LipShade("Caramel Kiss",      "#A0522D", "Nykaa",     "Glossy"),
        LipShade("Sangria Night",     "#5E1224", "Nykaa",     "Matte"),

        // ─── MAC ───
        LipShade("Ruby Woo",          "#C60018", "MAC",       "Matte"),
        LipShade("Velvet Teddy",      "#9C5A4B", "MAC",       "Matte"),
        LipShade("Diva",              "#6B1230", "MAC",       "Matte"),
        LipShade("Mehr",              "#A65172", "MAC",       "Matte"),
        LipShade("Whirl",             "#8C5050", "MAC",       "Matte"),
        LipShade("Chili",             "#B5332E", "MAC",       "Matte"),
        LipShade("Twig",              "#A0646C", "MAC",       "Satin"),
        LipShade("Modesty",           "#B38484", "MAC",       "Satin"),
        LipShade("Russian Red",       "#B01C2E", "MAC",       "Matte"),
        LipShade("Candy Yum-Yum",     "#E94E77", "MAC",       "Matte"),

        // ─── Maybelline ───
        LipShade("Touch of Spice",    "#8C4A5A", "Maybelline","Matte"),
        LipShade("Almond Pink",       "#C19476", "Maybelline","Satin"),
        LipShade("Burgundy Blush",    "#6C1F3C", "Maybelline","Matte"),
        LipShade("Lover",             "#D46B78", "Maybelline","Satin"),
        LipShade("Divine Wine",       "#751A2C", "Maybelline","Matte"),
        LipShade("Nude Nuance",       "#B78C76", "Maybelline","Matte"),
        LipShade("Fierce Fuchsia",    "#C2185B", "Maybelline","Matte"),
        LipShade("Raw Chocolate",     "#6B3534", "Maybelline","Matte"),

        // ─── Lakme ───
        LipShade("Red Coat",          "#D0202F", "Lakme",     "Matte"),
        LipShade("Plum Pick",         "#6E2C5A", "Lakme",     "Matte"),
        LipShade("Pink Charm",        "#E06080", "Lakme",     "Satin"),
        LipShade("Berry Base",        "#8B2252", "Lakme",     "Matte"),
        LipShade("Coffee Command",    "#7B4B3A", "Lakme",     "Matte"),
        LipShade("Nude Hue",          "#C49882", "Lakme",     "Satin"),
        LipShade("Crimson Call",      "#9B111E", "Lakme",     "Matte"),
        LipShade("Rosy Sunday",       "#E8929A", "Lakme",     "Glossy"),

        // ─── Charlotte Tilbury ───
        LipShade("Pillow Talk",       "#B46E6E", "Charlotte Tilbury", "Matte"),
        LipShade("Walk of No Shame",  "#B0253F", "Charlotte Tilbury", "Matte"),
        LipShade("Very Victoria",     "#9E6B6B", "Charlotte Tilbury", "Matte"),
        LipShade("Bond Girl",         "#7A182E", "Charlotte Tilbury", "Matte"),
        LipShade("Amazing Grace",     "#C27C7C", "Charlotte Tilbury", "Satin"),

        // ─── Rare Beauty (Selena Gomez) ───
        LipShade("Inspire",           "#A23E48", "Rare Beauty", "Matte"),
        LipShade("Encourage",         "#BC6F76", "Rare Beauty", "Satin"),
        LipShade("Worthy",            "#8C3A3A", "Rare Beauty", "Matte"),
        LipShade("Grateful",          "#CF7A7A", "Rare Beauty", "Glossy"),
        LipShade("Nearly Apricot",    "#D4886E", "Rare Beauty", "Satin"),

        // ─── Fenty Beauty (Rihanna) ───
        LipShade("Uncensored",        "#C21028", "Fenty Beauty", "Matte"),
        LipShade("Shawty",            "#DA7070", "Fenty Beauty", "Glossy"),
        LipShade("PMS",               "#8C3050", "Fenty Beauty", "Matte"),
        LipShade("Griselda",          "#6E1A2E", "Fenty Beauty", "Matte"),
        LipShade("S1ngle",            "#C08878", "Fenty Beauty", "Satin"),

        // ─── Trending / Generic ───
        LipShade("Cherry Crush",      "#C81E3C", "ShadeSync", "Matte"),
        LipShade("Rose Petal",        "#DC5078", "ShadeSync", "Satin"),
        LipShade("Berry Noir",        "#8C1E64", "ShadeSync", "Matte"),
        LipShade("Nude Silk",         "#B46E5A", "ShadeSync", "Satin"),
        LipShade("Sunset Coral",      "#DC6432", "ShadeSync", "Glossy"),
    )

    /** Search shades by name, brand, or hex (case-insensitive). */
    fun search(query: String): List<LipShade> {
        if (query.isBlank()) return shades
        val q = query.trim().lowercase()
        return shades.filter {
            it.name.lowercase().contains(q) ||
            it.brand.lowercase().contains(q) ||
            it.hex.lowercase().contains(q)
        }
    }

    /** Get shades for a specific brand. */
    fun byBrand(brand: String): List<LipShade> =
        shades.filter { it.brand.equals(brand, ignoreCase = true) }

    /** Create a custom shade from a HEX code. */
    fun fromHex(hex: String, name: String = "Custom"): LipShade {
        val normalized = if (hex.startsWith("#")) hex else "#$hex"
        return LipShade(name, normalized, "Custom", "")
    }
}
