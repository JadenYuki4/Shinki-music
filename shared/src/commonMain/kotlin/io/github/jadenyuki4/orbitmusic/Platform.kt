// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform