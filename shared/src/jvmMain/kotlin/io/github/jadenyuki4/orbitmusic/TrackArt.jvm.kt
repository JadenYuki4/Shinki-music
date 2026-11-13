package io.github.jadenyuki4.orbitmusic

import androidx.compose.ui.graphics.*
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

private val cachedAnimeBackdrop: ImageBitmap? by lazy {
    runCatching {
        val bytes = animeBackdropBytes() ?: return@runCatching null
        Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
    }.getOrNull()
}

internal actual fun loadAnimeBackdrop(): ImageBitmap? = cachedAnimeBackdrop

internal actual suspend fun loadArt(url: String): ImageBitmap? = withContext(Dispatchers.Default) {
    val bytes = artBytes(url) ?: return@withContext null
    MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@withContext null
        try {
            reader.input = input
            if (maxOf(reader.getWidth(0), reader.getHeight(0)) !in 1..1024) return@withContext null
        } finally { reader.dispose() }
    }
    Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
}
