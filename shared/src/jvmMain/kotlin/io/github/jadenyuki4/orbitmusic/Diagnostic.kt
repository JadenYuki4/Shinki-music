package io.github.jadenyuki4.orbitmusic

internal actual fun platformDiagnostic(): String = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}) · libmpv"
