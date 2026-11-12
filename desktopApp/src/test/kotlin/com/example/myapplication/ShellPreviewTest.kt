// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication

import androidx.compose.ui.renderComposeScene
import com.example.myapplication.di.PreviewMusicSource
import com.example.myapplication.search.SearchViewModel
import com.example.myapplication.theme.OrbitTheme
import com.example.myapplication.theme.ThemeSettings
import java.io.File
import org.junit.Test
import kotlin.test.assertTrue

class ShellPreviewTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun renderResponsiveShell() {
        val model = SearchViewModel(PreviewMusicSource)
        val target = File("build/previews").apply { mkdirs() }
        for ((width, height) in listOf(1180 to 820, 392 to 820)) {
            renderComposeScene(width, height) {
                OrbitTheme(ThemeSettings()) { MusicShell(model, null) {} }
            }.use { image ->
                val bytes = requireNotNull(image.encodeToData()).use { it.bytes }
                assertTrue(bytes.isNotEmpty())
                File(target, "orbit-${width}.png").writeBytes(bytes)
            }
        }
    }
}
