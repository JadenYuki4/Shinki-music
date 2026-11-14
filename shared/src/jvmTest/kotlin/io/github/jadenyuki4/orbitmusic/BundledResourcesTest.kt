// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class BundledResourcesTest {
    @Test
    fun animeBackdropIsAvailableOnTheClasspath() {
        val backdrop = assertNotNull(javaClass.getResourceAsStream("/anime_shrine_bg.jpg"))
        backdrop.use {
            assertContentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte()), it.readNBytes(2))
        }
        val gifBackdrop = assertNotNull(javaClass.getResourceAsStream("/player_anime_gif_bg.jpg"))
        gifBackdrop.use {
            assertContentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte()), it.readNBytes(2))
        }
    }
}
