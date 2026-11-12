// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LocalPalette = staticCompositionLocalOf { Palette() }
val LocalLayout = staticCompositionLocalOf { LayoutTokens(Density.Comfortable, 20f) }
val LocalEffects = staticCompositionLocalOf { Effects(0f, 0f, 0) }

@Composable
fun OrbitTheme(settings: ThemeSettings, systemReduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val palette = remember(settings.preset, settings.customPalette) { paletteFor(settings.preset, settings.customPalette) }
    val layout = remember(settings.density, settings.roundness) { LayoutTokens(settings.density, settings.roundness) }
    val effects = remember(settings, systemReduceMotion) { settings.effects(systemReduceMotion) }
    val scheme = remember(palette) {
        val base = if (palette.light) lightColorScheme() else darkColorScheme()
        base.copy(primary = palette.primary, onPrimary = palette.onAccent,
            primaryContainer = palette.elevated, onPrimaryContainer = palette.text,
            secondary = palette.secondary, onSecondary = palette.background,
            secondaryContainer = palette.elevated, onSecondaryContainer = palette.text,
            tertiary = palette.highlight, background = palette.background, onBackground = palette.text,
            surface = palette.surface, onSurface = palette.text, surfaceVariant = palette.elevated,
            onSurfaceVariant = palette.muted, surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.elevated, surfaceContainerHighest = palette.elevated,
            surfaceContainerLow = palette.surface, surfaceContainerLowest = palette.background,
            inverseSurface = palette.text, inverseOnSurface = palette.background,
            outline = palette.muted, outlineVariant = palette.muted.copy(alpha = layout.outlineAlpha),
            error = palette.error, onError = palette.background)
    }
    val typography = remember(settings.font) {
        val body = if (settings.font == FontStyle.Monospace) FontFamily.Monospace else FontFamily.SansSerif
        val heading = if (settings.font == FontStyle.System) FontFamily.SansSerif else FontFamily.Monospace
        Typography(
            headlineLarge = TextStyle(fontFamily = heading, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
            headlineMedium = TextStyle(fontFamily = heading, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
            titleLarge = TextStyle(fontFamily = heading, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
            bodyLarge = TextStyle(fontFamily = body, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = body, fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            labelSmall = TextStyle(fontFamily = heading, fontSize = 11.sp, letterSpacing = 1.sp),
        )
    }
    CompositionLocalProvider(LocalPalette provides palette, LocalLayout provides layout, LocalEffects provides effects,
        LocalContentColor provides palette.text) {
        MaterialTheme(colorScheme = scheme, typography = typography,
            shapes = Shapes(small = RoundedCornerShape(layout.unit), medium = RoundedCornerShape(layout.small),
                large = RoundedCornerShape(layout.radius)), content = content)
    }
}
