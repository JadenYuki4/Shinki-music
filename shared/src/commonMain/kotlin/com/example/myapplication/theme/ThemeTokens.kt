// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** All authored visual values live here, never in a screen. */
@Immutable
data class Palette(
    val background: Color = Color(0xFF0A0E1A),
    val surface: Color = Color(0xFF121829),
    val elevated: Color = Color(0xFF1A2238),
    val primary: Color = Color(0xFF00E5FF),
    val secondary: Color = Color(0xFF7C4DFF),
    val highlight: Color = Color(0xFFFF2E93),
    val success: Color = Color(0xFF00E676),
    val warning: Color = Color(0xFFFFC400),
    val error: Color = Color(0xFFFF5252),
    val text: Color = Color(0xFFE6F1FF),
    val muted: Color = Color(0xFF8A9BB8),
    val onAccent: Color = Color(0xFF0A0E1A),
    val light: Boolean = false,
)

enum class ThemePreset(val label: String) {
    NeonCyan("Neon Cyan"), VioletVoid("Violet Void"), SolarGold("Solar Gold"),
    CrimsonCore("Crimson Core"), MonochromeLight("Monochrome Light"), Custom("Custom")
}
enum class BackgroundStyle { Solid, Gradient, AlbumArt }
enum class FontStyle { Geometric, Monospace, System }
enum class Density(val scale: Float) { Compact(.85f), Comfortable(1f), Spacious(1.15f) }

@Immutable
data class ThemeSettings(
    val preset: ThemePreset = ThemePreset.NeonCyan,
    val customPalette: Palette = Palette(),
    val background: BackgroundStyle = BackgroundStyle.Gradient,
    val font: FontStyle = FontStyle.Geometric,
    val density: Density = Density.Comfortable,
    val roundness: Float = 20f,
    val glow: Float = .25f,
    val blur: Float = 0f,
    val animationIntensity: Float = .5f,
    val animationSpeed: Float = 1f,
    val lowPower: Boolean = false,
    val reduceMotion: Boolean = false,
    val dynamicAlbumColor: Boolean = false,
)

fun paletteFor(preset: ThemePreset, custom: Palette = Palette()): Palette = when (preset) {
    ThemePreset.NeonCyan -> Palette()
    ThemePreset.VioletVoid -> Palette(background = Color(0xFF100B1F), surface = Color(0xFF1A122C),
        elevated = Color(0xFF281E40), primary = Color(0xFFC4A7FF), secondary = Color(0xFF00E5FF))
    ThemePreset.SolarGold -> Palette(background = Color(0xFF151109), surface = Color(0xFF211B10),
        elevated = Color(0xFF302819), primary = Color(0xFFFFD166), secondary = Color(0xFFFFA552))
    ThemePreset.CrimsonCore -> Palette(background = Color(0xFF180B13), surface = Color(0xFF26131E),
        elevated = Color(0xFF382031), primary = Color(0xFFFF7B9C), secondary = Color(0xFFC4A7FF))
    ThemePreset.MonochromeLight -> Palette(background = Color(0xFFF3F7FF), surface = Color(0xFFFFFFFF),
        elevated = Color(0xFFE5ECF7), primary = Color(0xFF006779), secondary = Color(0xFF6031CC),
        highlight = Color(0xFFB6005A), success = Color(0xFF007D40), warning = Color(0xFF835C00),
        error = Color(0xFFBC2536), text = Color(0xFF172238), muted = Color(0xFF4B5D78),
        onAccent = Color(0xFFFFFFFF), light = true)
    ThemePreset.Custom -> custom
}

@Immutable
data class Effects(val glow: Float, val blur: Float, val durationMillis: Int, val particles: Boolean = false)

/** OS preference and user preference both override decorative effects. No timers or particle loop. */
fun ThemeSettings.effects(systemReduceMotion: Boolean = false): Effects {
    val reduced = lowPower || reduceMotion || systemReduceMotion
    return Effects(
        glow = if (reduced) 0f else glow.coerceIn(0f, 1f),
        blur = if (reduced) 0f else blur.coerceIn(0f, 24f),
        durationMillis = if (reduced) 0 else
            (180 * animationIntensity.coerceIn(0f, 1f) / animationSpeed.coerceIn(.25f, 2f)).toInt(),
    )
}

/** Opaque semantic pairs; album art overlays require a separate contrast check. */
fun contrastRatio(foreground: Color, background: Color): Float {
    val a = foreground.luminance()
    val b = background.luminance()
    return (max(a, b) + .05f) / (min(a, b) + .05f)
}

fun Palette.contrastWarnings(): List<String> = buildList {
    listOf("Background" to background, "Surface" to surface, "Elevated" to elevated).forEach { (name, color) ->
        if (contrastRatio(text, color) < 4.5f) add("$name: primary text below AA (4.5:1)")
        if (contrastRatio(muted, color) < 4.5f) add("$name: secondary text below AA (4.5:1)")
        if (contrastRatio(primary, color) < 4.5f) add("$name: accent text below AA (4.5:1)")
    }
    if (contrastRatio(onAccent, primary) < 4.5f) add("Accent button text below AA (4.5:1)")
}

@Immutable
class LayoutTokens(density: Density, roundness: Float) {
    val hairline = 1.dp
    val micro = 4.dp
    val unit = (8 * density.scale).dp
    val small = (16 * density.scale).dp
    val medium = (24 * density.scale).dp
    val large = (32 * density.scale).dp
    val touch = 48.dp
    val radius = roundness.coerceIn(0f, 32f).dp
    val sidebar = 224.dp
    val wideBreakpoint = 840.dp
    val panesBreakpoint = 1100.dp
    val detailPane = 256.dp
    val contentMax = 1120.dp
    val artwork = 176.dp
    val swatch = 32.dp
    val windowWidth = 1180.dp
    val windowHeight = 820.dp
    val outlineAlpha = .25f
    val glowAlpha = .08f
    val minRoundness = 0f
    val maxRoundness = 32f
}
