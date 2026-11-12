// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PreferencesSettings
import io.github.jadenyuki4.orbitmusic.db.OrbitDatabase
import io.github.jadenyuki4.orbitmusic.domain.HistoryStore
import java.io.File
import java.util.prefs.Preferences

/**
 * Opens or initializes the desktop SQLite database in the OS user data directory:
 * - Windows: %LOCALAPPDATA%\OrbitMusic\orbit.db
 * - Linux: $XDG_DATA_HOME/orbitmusic/orbit.db (or ~/.local/share/orbitmusic/orbit.db)
 */
fun createDesktopHistoryStore(dbFile: File = defaultDesktopDbFile()): HistoryStore {
    dbFile.parentFile?.mkdirs()
    val isNew = !dbFile.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    if (isNew) {
        OrbitDatabase.Schema.create(driver)
    } else {
        // Ensure tables exist if an empty file was created previously.
        runCatching { OrbitDatabase.Schema.create(driver) }
    }
    val prefs = Preferences.userNodeForPackage(SqlHistoryStore::class.java)
    return SqlHistoryStore(driver = driver, settings = PreferencesSettings(prefs))
}

private fun defaultDesktopDbFile(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val baseDir = if (os.contains("win")) {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?: File(System.getProperty("user.home", "."), "AppData/Local").absolutePath
        File(localAppData, "OrbitMusic")
    } else {
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home", "."), ".local/share").absolutePath
        File(xdg, "orbitmusic")
    }
    return File(baseDir, "orbit.db")
}
