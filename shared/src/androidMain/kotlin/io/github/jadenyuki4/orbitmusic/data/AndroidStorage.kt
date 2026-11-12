// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jadenyuki4.orbitmusic.db.OrbitDatabase
import io.github.jadenyuki4.orbitmusic.domain.HistoryStore

/** Opens the app-private SQLite database and SharedPreferences on Android. */
fun createAndroidHistoryStore(context: Context): HistoryStore {
    val appContext = context.applicationContext
    val driver = AndroidSqliteDriver(OrbitDatabase.Schema, appContext, "orbit.db")
    val prefs = appContext.getSharedPreferences("orbit_preferences", Context.MODE_PRIVATE)
    return SqlHistoryStore(driver = driver, settings = SharedPreferencesSettings(prefs))
}
