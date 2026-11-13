// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PreferencesSettings
import io.github.jadenyuki4.orbitmusic.db.OrbitDatabase
import io.github.jadenyuki4.orbitmusic.domain.HistoryStore
import io.github.jadenyuki4.orbitmusic.domain.TrackDownloader
import java.io.File
import java.util.prefs.Preferences

/**
 * Opens or initializes the desktop SQLite database in the OS user data directory:
 * - Windows: %LOCALAPPDATA%\OrbitMusic\orbit.db
 * - Linux: $XDG_DATA_HOME/orbitmusic/orbit.db (or ~/.local/share/orbitmusic/orbit.db)
 */
fun createDesktopHistoryStore(dbFile: File = defaultDesktopDbFile()): HistoryStore {
    dbFile.parentFile?.mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    OrbitDatabase(driver).transaction {
        driver.getConnection().createStatement().use { sql ->
            val version = sql.executeQuery("PRAGMA user_version").use { it.next(); it.getLong(1) }
            require(version <= OrbitDatabase.Schema.version) { "Esta biblioteca requiere una versión más reciente de Shinki music." }
            val initialized = sql.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='search_history'").use { it.next() }
            if (!initialized) OrbitDatabase.Schema.create(driver)
            else if (version < OrbitDatabase.Schema.version) {
                // The first desktop release left SQLite user_version at zero.
                OrbitDatabase.Schema.migrate(driver, maxOf(version, 1), OrbitDatabase.Schema.version)
            }
            sql.execute("PRAGMA user_version = ${OrbitDatabase.Schema.version}")
        }
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

fun defaultDesktopDownloadsDir(): File {
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
    return if (home != null) File(home, "Music/Shinki music")
    else File(defaultDesktopDbFile().parentFile, "downloads")
}

fun createDesktopTrackDownloader(downloadsDir: File = defaultDesktopDownloadsDir()): TrackDownloader =
    FileTrackDownloader(
        downloadsDir = downloadsDir,
        onOpenFolder = { dir ->
            runCatching {
                dir.mkdirs()
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(dir)
                    true
                } else false
            }.getOrDefault(false)
        },
    )
