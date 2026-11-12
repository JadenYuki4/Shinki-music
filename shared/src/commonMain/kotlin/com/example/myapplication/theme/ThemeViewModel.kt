// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

sealed interface ThemeAction {
    data class SelectPreset(val preset: ThemePreset) : ThemeAction
    data class SetRoundness(val value: Float) : ThemeAction
    data class SetLowPower(val enabled: Boolean) : ThemeAction
    data class SetReduceMotion(val enabled: Boolean) : ThemeAction
}

/** Pure reducer keeps UI events deterministic and independent of the platform. */
fun reduceTheme(state: ThemeSettings, action: ThemeAction): ThemeSettings = when (action) {
    is ThemeAction.SelectPreset -> state.copy(preset = action.preset)
    is ThemeAction.SetRoundness -> state.copy(roundness =
        if (action.value.isFinite()) action.value.coerceIn(0f, 32f) else state.roundness)
    is ThemeAction.SetLowPower -> state.copy(lowPower = action.enabled)
    is ThemeAction.SetReduceMotion -> state.copy(reduceMotion = action.enabled)
}

class ThemeViewModel : ViewModel() {
    var state by mutableStateOf(ThemeSettings())
        private set

    fun dispatch(action: ThemeAction) { state = reduceTheme(state, action) }
}
