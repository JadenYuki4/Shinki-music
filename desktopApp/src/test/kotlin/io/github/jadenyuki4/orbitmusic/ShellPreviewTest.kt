// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.jadenyuki4.orbitmusic.data.InMemoryHistoryStore
import io.github.jadenyuki4.orbitmusic.di.PreviewMusicSource
import io.github.jadenyuki4.orbitmusic.domain.AudioFormatPreference
import io.github.jadenyuki4.orbitmusic.domain.AudioQuality
import io.github.jadenyuki4.orbitmusic.domain.AudioStream
import io.github.jadenyuki4.orbitmusic.domain.MusicItem
import io.github.jadenyuki4.orbitmusic.domain.MusicKind
import io.github.jadenyuki4.orbitmusic.domain.MusicPage
import io.github.jadenyuki4.orbitmusic.domain.MusicSource
import io.github.jadenyuki4.orbitmusic.domain.SourceResult
import io.github.jadenyuki4.orbitmusic.search.SearchViewModel
import io.github.jadenyuki4.orbitmusic.theme.OrbitTheme
import io.github.jadenyuki4.orbitmusic.theme.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellPreviewTest {

    private class FakeAudioEngine(initialTrack: MusicItem) : AudioEngine {
        private val _state = MutableStateFlow(
            Transport(
                track = initialTrack,
                playing = true,
                position = 165L,
                duration = 197L,
            )
        )
        override val state: StateFlow<Transport> = _state
        override fun load(track: MusicItem, stream: AudioStream) {
            _state.value = Transport(
                track = track,
                playing = true,
                position = 42L,
                duration = track.durationSeconds ?: 200L,
            )
        }
        override fun toggle() {
            val cur = _state.value
            _state.value = cur.copy(playing = !cur.playing)
        }
        override fun seek(seconds: Long) {
            val cur = _state.value
            _state.value = cur.copy(position = seconds.coerceAtLeast(0L))
        }
        override fun volume(value: Float) {}
        override fun speed(value: Float) {}
    }

    private fun createSampleCoverFile(dir: File): File {
        val file = File(dir, "sample-cover.png")
        val size = 512
        val bmp = Bitmap()
        bmp.allocN32Pixels(size, size)
        val canvas = Canvas(bmp)
        val bgPaint = Paint().apply { color = 0xFF1F1638.toInt() }
        canvas.drawRect(Rect.makeWH(size.toFloat(), size.toFloat()), bgPaint)
        val accentPaint = Paint().apply { color = 0xFFC4A7FF.toInt() }
        canvas.drawCircle(256f, 220f, 128f, accentPaint)
        val pinkPaint = Paint().apply { color = 0xFFFF4AE2.toInt() }
        canvas.drawCircle(256f, 220f, 64f, pinkPaint)
        val img = org.jetbrains.skia.Image.makeFromBitmap(bmp)
        val bytes = requireNotNull(img.encodeToData()).use { it.bytes }
        file.writeBytes(bytes)
        img.close()
        bmp.close()
        return file
    }

    private fun allNodes(scene: ImageComposeScene): List<SemanticsNode> {
        val list = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            list.add(node)
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { owner -> walk(owner.rootSemanticsNode) }
        return list
    }

    private fun SemanticsNode.labelText(): String = buildString {
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinTo(this, " ")
        config.getOrNull(SemanticsProperties.Text)?.joinTo(this, " ") { it.text }
    }

    private fun findByExactLabel(scene: ImageComposeScene, target: String): List<SemanticsNode> =
        allNodes(scene).filter { node ->
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val txt = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            val clickLabel = node.config.getOrNull(SemanticsActions.OnClick)?.label
            desc.contains(target) || txt.contains(target) || clickLabel == target
        }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun withSingleThreadScene(
        width: Int,
        height: Int,
        content: @Composable () -> Unit,
        block: suspend (ImageComposeScene) -> Unit,
    ) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val scene = ImageComposeScene(
                    width = width,
                    height = height,
                    coroutineContext = dispatcher,
                    content = content,
                )
                try {
                    block(scene)
                } finally {
                    scene.close()
                }
            }
        }
    }

    private suspend fun pump(scene: ImageComposeScene, count: Int = 12, delayMs: Long = 0L) {
        repeat(count) { idx ->
            if (delayMs > 0L) delay(delayMs) else yield()
            scene.render(idx * 16_000_000L)
        }
    }

    private suspend fun clickNode(scene: ImageComposeScene, label: String, pumpFrames: Int = 12) {
        val node = findByExactLabel(scene, label).firstOrNull()
            ?: error("Semantics node '$label' not found. Available: ${allNodes(scene).mapNotNull { it.labelText().takeIf(String::isNotBlank) }.distinct()}")
        val center: Offset = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, center)
        scene.sendPointerEvent(PointerEventType.Release, center)
        pump(scene, pumpFrames, delayMs = 8L)
    }

    private suspend fun savePng(scene: ImageComposeScene, outFile: File) {
        pump(scene, 6, delayMs = 20L)
        scene.render(250_000_000L).use { image ->
            val bytes = requireNotNull(image.encodeToData()).use { it.bytes }
            assertTrue(bytes.isNotEmpty())
            outFile.writeBytes(bytes)
        }
    }

    private fun assertControlsSingleAndInBounds(scene: ImageComposeScene, width: Int, height: Int, expectDesktopVolume: Boolean) {
        assertEquals(1, findByExactLabel(scene, "Barra de progreso").size, "Expected exactly 1 progress bar at ${width}x${height}")
        val playOrPause = findByExactLabel(scene, "Reproducir").size + findByExactLabel(scene, "Pausar").size
        assertEquals(1, playOrPause, "Expected exactly 1 Play/Pause button at ${width}x${height}")
        assertEquals(1, findByExactLabel(scene, "Canción anterior").size, "Expected exactly 1 Previous button at ${width}x${height}")
        assertEquals(1, findByExactLabel(scene, "Siguiente canción").size, "Expected exactly 1 Next button at ${width}x${height}")
        assertEquals(1, findByExactLabel(scene, "Aleatorio").size, "Expected exactly 1 Shuffle button at ${width}x${height}")
        assertEquals(1, findByExactLabel(scene, "Modo de repetición").size, "Expected exactly 1 Repeat button at ${width}x${height}")
        if (expectDesktopVolume) {
            assertEquals(1, findByExactLabel(scene, "Ajustar volumen").size, "Expected independent volume slider at ${width}x${height}")
        }

        val criticalLabels = listOfNotNull(
            "Barra de progreso",
            "Canción anterior",
            "Siguiente canción",
            "Aleatorio",
            "Modo de repetición",
            if (expectDesktopVolume) "Ajustar volumen" else "Dispositivo",
            if (expectDesktopVolume) "Ecualizador" else "Letras",
            if (!expectDesktopVolume) "Cola" else null,
        )
        for (lbl in criticalLabels) {
            val nodes = findByExactLabel(scene, lbl)
            assertTrue(nodes.isNotEmpty(), "Missing '$lbl' at ${width}x${height}")
            nodes.forEach { n ->
                val b = n.boundsInRoot
                assertTrue(b.left >= -2f, "'$lbl' clipped left (${b.left}) at ${width}x${height}")
                assertTrue(b.top >= -2f, "'$lbl' clipped top (${b.top}) at ${width}x${height}")
                assertTrue(b.right <= width + 2f, "'$lbl' clipped right (${b.right} > $width) at ${width}x${height}")
                assertTrue(b.bottom <= height + 2f, "'$lbl' clipped bottom (${b.bottom} > $height) at ${width}x${height}")
            }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun renderResponsiveShellAndExpandedPlayerAllResolutions() {
        val target = File("build/previews").apply { mkdirs() }
        val coverFile = createSampleCoverFile(target)

        val tracks = listOf(
            MusicItem("t1", "Entra en mi vida", "DesaKTa2", MusicKind.Song, artworkUrl = coverFile.absolutePath, durationSeconds = 197L),
            MusicItem("t2", "Idol", "YOASOBI", MusicKind.Song, artworkUrl = coverFile.absolutePath, durationSeconds = 213L),
            MusicItem("t3", "Show", "Ado", MusicKind.Song, artworkUrl = coverFile.absolutePath, durationSeconds = 190L),
            MusicItem("t4", "Kick Back", "Kenshi Yonezu", MusicKind.Song, artworkUrl = coverFile.absolutePath, durationSeconds = 193L),
        )
        val source = object : MusicSource {
            override suspend fun suggestions(query: String) = SourceResult.Success(emptyList<String>())
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int) =
                SourceResult.Success(MusicPage(tracks))
            override suspend fun discover(page: String?, pageSize: Int) =
                SourceResult.Success(MusicPage(tracks))
            override suspend fun stream(songId: String) =
                stream(songId, AudioQuality.High, AudioFormatPreference.Auto)
            override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference) =
                SourceResult.Success(
                    AudioStream(
                        url = "https://example.com/$songId.opus",
                        mimeType = "audio/flac",
                        bitrateKbps = 160,
                        formatLabel = "FLAC · 24-bit · 48 kHz",
                    )
                )
        }

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val engine = FakeAudioEngine(tracks.first())
            val history = InMemoryHistoryStore()
            val playback = Playback(source, engine, scope, history)
            runBlocking {
                playback.queue.value = tracks
            }
            val model = SearchViewModel(PreviewMusicSource)

            val viewports = listOf(
                1366 to 768,
                1920 to 1080,
                360 to 800,
                390 to 844,
            )

            for ((width, height) in viewports) {
                val isDesktop = width >= 840
                withSingleThreadScene(
                    width = width,
                    height = height,
                    content = { OrbitTheme(ThemeSettings()) { MusicShell(model, playback) {} } },
                ) { scene ->
                    pump(scene, 10)
                    savePng(scene, File(target, "home-${width}x${height}.png"))

                    // Open expanded player via top/dock control without changing public APIs
                    clickNode(scene, if (isDesktop) "Expandir reproductor" else "Abrir reproductor", pumpFrames = 14)

                    // 1) Animated backdrop mode
                    clickNode(scene, "✦ Fondo animado", pumpFrames = 14)
                    assertControlsSingleAndInBounds(scene, width, height, expectDesktopVolume = isDesktop)
                    savePng(scene, File(target, "expanded-${width}x${height}-animado.png"))

                    // 2) HD cover art mode
                    clickNode(scene, "Carátula HD", pumpFrames = 14)
                    assertControlsSingleAndInBounds(scene, width, height, expectDesktopVolume = isDesktop)
                    savePng(scene, File(target, "expanded-${width}x${height}-caratula.png"))

                    // Switch back to animated backdrop for consistency & verify minimize preserves active playback
                    clickNode(scene, "✦ Fondo animado", pumpFrames = 10)
                    if (!isDesktop) {
                        assertEquals(0, findByExactLabel(scene, "Inicio").size, "Bottom navigation must stay hidden when mobile expanded player is open")
                        clickNode(scene, "Minimizar reproductor", pumpFrames = 14)
                        assertTrue(findByExactLabel(scene, "Abrir reproductor").isNotEmpty(), "Mini-player must return after minimizing")
                        assertTrue(findByExactLabel(scene, "Inicio").isNotEmpty(), "Bottom navigation must return after minimizing")
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun renderSearchAndSourcesAllResolutions() {
        val target = File("build/previews").apply { mkdirs() }
        val coverFile = createSampleCoverFile(target)

        val activeTrack = MusicItem(
            id = "https://music.youtube.com/watch?v=active1",
            title = "Báilame despacito",
            subtitle = "CarlosPolo19",
            kind = MusicKind.Song,
            artworkUrl = coverFile.absolutePath,
            durationSeconds = 204L,
            sourceName = "YT Music",
        )
        val searchItems = listOf(
            MusicItem(
                id = "https://music.youtube.com/watch?v=s1",
                title = "Si no es muy tarde",
                subtitle = "Luciano Pereyra",
                kind = MusicKind.Song,
                artworkUrl = coverFile.absolutePath,
                durationSeconds = 222L,
                sourceName = "YT Music",
            ),
            MusicItem(
                id = "https://www.youtube.com/watch?v=s2",
                title = "Si no es muy tarde · En vivo desde el estadio Luna Park edición extendida Hi-Res",
                subtitle = "Luciano Pereyra · Canal Oficial",
                kind = MusicKind.Song,
                artworkUrl = coverFile.absolutePath,
                durationSeconds = 241L,
                sourceName = "YouTube",
            ),
            MusicItem(
                id = "https://soundcloud.com/luciano/si-no-es-muy-tarde-acustico",
                title = "Si no es muy tarde · Versión sin carátula ni portada disponible",
                subtitle = "Luciano Pereyra Acoustic Sessions",
                kind = MusicKind.Song,
                artworkUrl = null,
                durationSeconds = 228L,
                sourceName = "SoundCloud",
            ),
            MusicItem(
                id = "https://bandcamp.com/track/si-no-es-muy-tarde-cover",
                title = "Si no es muy tarde (Guitar Cover)",
                subtitle = "Estudio Sur",
                kind = MusicKind.Song,
                artworkUrl = coverFile.absolutePath,
                durationSeconds = 195L,
                sourceName = "Bandcamp",
            ),
            MusicItem(
                id = "https://music.youtube.com/playlist?list=alb1",
                title = "Si no es muy tarde",
                subtitle = "Luciano Pereyra",
                kind = MusicKind.Album,
                artworkUrl = coverFile.absolutePath,
                sourceName = "YT Music",
            ),
            MusicItem(
                id = "https://www.youtube.com/playlist?list=alb2",
                title = "Luciano Pereyra · En Vivo",
                subtitle = "Luciano Pereyra",
                kind = MusicKind.Album,
                artworkUrl = coverFile.absolutePath,
                sourceName = "YouTube",
            ),
            MusicItem(
                id = "https://www.youtube.com/channel/artist1",
                title = "Luciano Pereyra",
                subtitle = "Artista",
                kind = MusicKind.Artist,
                artworkUrl = coverFile.absolutePath,
                sourceName = "YT Music",
            ),
        )
        val collectionTracks = listOf(
            MusicItem(
                id = "https://music.youtube.com/watch?v=col1",
                title = "Track del álbum 1",
                subtitle = "Luciano Pereyra",
                kind = MusicKind.Song,
                artworkUrl = coverFile.absolutePath,
                durationSeconds = 212L,
                sourceName = "YT Music",
            ),
        )

        val searchSource = object : MusicSource {
            override suspend fun suggestions(query: String) = SourceResult.Success(emptyList<String>())
            override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int): SourceResult<MusicPage> {
                val filtered = if (kind == null) searchItems else searchItems.filter { it.kind == kind }
                return SourceResult.Success(MusicPage(filtered))
            }
            override suspend fun collectionItems(item: MusicItem) = SourceResult.Success(collectionTracks)
            override suspend fun discover(page: String?, pageSize: Int) = SourceResult.Success(MusicPage(searchItems))
            override suspend fun stream(songId: String) =
                stream(songId, AudioQuality.High, AudioFormatPreference.Auto)
            override suspend fun stream(songId: String, quality: AudioQuality, format: AudioFormatPreference) =
                SourceResult.Success(
                    AudioStream(
                        url = "https://example.com/$songId.opus",
                        mimeType = "audio/flac",
                        bitrateKbps = 160,
                        formatLabel = "FLAC · 24-bit · 48 kHz",
                    )
                )
        }

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val engine = FakeAudioEngine(activeTrack)
            val history = InMemoryHistoryStore()
            val playback = Playback(searchSource, engine, scope, history)
            runBlocking {
                playback.queue.value = listOf(activeTrack) + searchItems.take(3)
            }
            val model = SearchViewModel(searchSource, history)
            io.github.jadenyuki4.orbitmusic.search.submitSearchQuery(model, "si no es muy tarde")

            val viewports = listOf(
                1366 to 768,
                1920 to 1080,
                360 to 800,
                390 to 844,
            )

            for ((width, height) in viewports) {
                val isDesktop = width >= 840
                withSingleThreadScene(
                    width = width,
                    height = height,
                    content = { OrbitTheme(ThemeSettings()) { MusicShell(model, playback) {} } },
                ) { scene ->
                    pump(scene, 10)
                    clickNode(scene, if (isDesktop) "Buscar" else "Fuentes", pumpFrames = 14)

                    val expectedLabels = listOfNotNull(
                        "Selector de fuentes",
                        "Todo",
                        "Canciones",
                        "Álbumes",
                        if (!isDesktop) "Abrir reproductor" else "Ajustar volumen",
                    )
                    for (lbl in expectedLabels) {
                        val nodes = findByExactLabel(scene, lbl)
                        assertTrue(nodes.isNotEmpty(), "Expected '$lbl' on SearchScreen at ${width}x${height}")
                        nodes.forEach { n ->
                            assertTrue(n.boundsInRoot.right <= width + 2f, "'$lbl' clipped right at ${width}x${height}")
                            assertTrue(n.boundsInRoot.bottom <= height + 2f, "'$lbl' clipped bottom at ${width}x${height}")
                        }
                    }

                    // Duration stays on a single line (small bounding box height)
                    val durNodes = findByExactLabel(scene, "4:01")
                    assertTrue(durNodes.isNotEmpty(), "Expected single-line duration 4:01 at ${width}x${height}")
                    assertTrue(durNodes.first().boundsInRoot.height <= 36f, "Duration wrapped vertically at ${width}x${height}: ${durNodes.first().boundsInRoot}")

                    savePng(scene, File(target, "search-${width}x${height}.png"))

                    // Verify ⋮ action menu opens without changing active track
                    clickNode(scene, "Acciones de Si no es muy tarde · Versión sin carátula ni portada disponible", pumpFrames = 10)
                    assertEquals(activeTrack.id, playback.engine.state.value.track?.id, "Opening ⋮ menu must not start playback")
                    assertTrue(findByExactLabel(scene, "Reproducir ahora").isNotEmpty(), "Expected ⋮ menu action 'Reproducir ahora'")
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
