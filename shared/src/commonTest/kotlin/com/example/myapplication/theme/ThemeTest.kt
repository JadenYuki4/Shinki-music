// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.*

class ThemeTest {
    @Test fun presetSwitchPreservesAccessibilityAndReducerBoundsInput() {
        val state = ThemeSettings(lowPower = true, reduceMotion = true)
        val switched = reduceTheme(state, ThemeAction.SelectPreset(ThemePreset.SolarGold))
        assertTrue(switched.lowPower && switched.reduceMotion)
        assertEquals(ThemePreset.SolarGold, switched.preset)
        assertEquals(32f, reduceTheme(state, ThemeAction.SetRoundness(100f)).roundness)
        assertEquals(state.roundness, reduceTheme(state, ThemeAction.SetRoundness(Float.NaN)).roundness)
    }
    @Test fun wcagReferencePairs() {
        assertEquals(21f, contrastRatio(Color.White, Color.Black), .01f)
        assertEquals(1f, contrastRatio(Color.Black, Color.Black), .01f)
    }

    @Test fun presetBodyTextIsReadable() {
        ThemePreset.entries.filter { it != ThemePreset.Custom }.forEach { preset ->
            val p = paletteFor(preset)
            assertTrue(contrastRatio(p.text, p.surface) >= 4.5f, preset.label)
            assertTrue(contrastRatio(p.muted, p.surface) >= 4.5f, preset.label)
            assertTrue(contrastRatio(p.onAccent, p.primary) >= 4.5f, preset.label)
        }
    }

    @Test fun eachReductionSourceDisablesEffects() {
        val full = ThemeSettings(glow = 1f, blur = 24f, animationIntensity = 1f)
        listOf(full.copy(lowPower = true).effects(), full.copy(reduceMotion = true).effects(),
            full.effects(systemReduceMotion = true)).forEach {
            assertEquals(Effects(0f, 0f, 0), it)
        }
        assertTrue(full.effects().durationMillis > 0)
    }

    @Test fun customPalettePreservedAndPoorContrastWarned() {
        val custom = Palette(text = Color.Black, background = Color.Black)
        assertEquals(custom, paletteFor(ThemePreset.Custom, custom))
        assertTrue(custom.contrastWarnings().any { it.startsWith("Background: primary") })
    }

    @Test fun invalidEffectRangesClamped() {
        val effects = ThemeSettings(glow = -5f, blur = 500f, animationSpeed = 0f).effects()
        assertEquals(0f, effects.glow)
        assertEquals(24f, effects.blur)
        assertTrue(effects.durationMillis in 0..720)
    }
}
