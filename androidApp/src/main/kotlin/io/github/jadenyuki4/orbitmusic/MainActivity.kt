// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.os.Bundle
import kotlinx.coroutines.*
import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import io.github.jadenyuki4.orbitmusic.data.NewPipeMusicSource

class MainActivity : ComponentActivity() {
    private val musicSource by lazy { NewPipeMusicSource() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine by lazy { AndroidEngine(this, scope) }
    private val playback by lazy { Playback(musicSource, engine, scope) }

    override fun onDestroy() {
        engine.close()
        scope.cancel()
        super.onDestroy()
    }
    private var systemReduceMotion by mutableStateOf(!ValueAnimator.areAnimatorsEnabled())

    override fun onResume() {
        super.onResume()
        systemReduceMotion = !ValueAnimator.areAnimatorsEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(systemReduceMotion = systemReduceMotion, source = musicSource, playback = playback)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
