package io.github.jadenyuki4.orbitmusic

internal actual fun platformDiagnostic(): String = "Android API ${android.os.Build.VERSION.SDK_INT} · Media3/ExoPlayer"
