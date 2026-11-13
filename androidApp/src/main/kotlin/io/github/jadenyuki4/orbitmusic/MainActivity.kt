// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.os.Bundle

import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview


class MainActivity : ComponentActivity() {
    private val orbit get() = application as OrbitApplication
    private var systemReduceMotion by mutableStateOf(!ValueAnimator.areAnimatorsEnabled())

    override fun onResume() {
        super.onResume()
        systemReduceMotion = !ValueAnimator.areAnimatorsEnabled()
    }

    override fun onStop() {
        super.onStop()
        SessionStore().save(orbit.playback.snapshotSession())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(
                systemReduceMotion = systemReduceMotion,
                source = orbit.source,
                history = orbit.history,
                playback = orbit.playback,
            )
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
