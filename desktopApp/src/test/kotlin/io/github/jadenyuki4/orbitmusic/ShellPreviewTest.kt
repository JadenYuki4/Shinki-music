// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.ui.renderComposeScene
import io.github.jadenyuki4.orbitmusic.di.PreviewMusicSource
import io.github.jadenyuki4.orbitmusic.search.SearchViewModel
import io.github.jadenyuki4.orbitmusic.theme.OrbitTheme
import io.github.jadenyuki4.orbitmusic.theme.ThemeSettings
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
