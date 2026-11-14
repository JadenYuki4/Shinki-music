// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
internal expect val isAndroid: Boolean
internal expect fun logRelatedFailure(cause: Throwable)
