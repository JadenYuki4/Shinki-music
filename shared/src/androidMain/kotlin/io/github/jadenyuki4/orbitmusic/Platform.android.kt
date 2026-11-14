// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()
internal actual val isAndroid: Boolean = true
internal actual fun logRelatedFailure(cause: Throwable) {
    android.util.Log.w("ShinkiRelated", "Error al obtener canciones relacionadas", cause)
}
