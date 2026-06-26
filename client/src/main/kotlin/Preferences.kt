package org.lain.qbupdater

import java.util.prefs.Preferences

private val PREFERENCES = Preferences.userNodeForPackage(QbUpdater::class.java)

fun restoreGamePath(): String? {
    return PREFERENCES.get("game_path", null)
}

fun saveGamePath(path: String) {
    PREFERENCES.put("game_path", path)
}