package io.github.jadenyuki4.orbitmusic

import android.app.Application
import io.github.jadenyuki4.orbitmusic.data.*
import kotlinx.coroutines.*

/** Queue, sleep timer and the Android controller survive Activity recreation and background playback. */
class OrbitApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val source by lazy { NewPipeMusicSource() }
    val history by lazy { createAndroidHistoryStore(this) }
    val downloader by lazy { createAndroidTrackDownloader(this) }
    val playback by lazy { Playback(source, AndroidEngine(this, scope), scope, history, downloader).also { it.persistSession(scope, SessionStore()) } }
}
