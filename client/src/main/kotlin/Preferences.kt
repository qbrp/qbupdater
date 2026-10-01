package org.lain.qbupdater

import java.util.prefs.Preferences

private val PREFERENCES = Preferences.userNodeForPackage(QbUpdater::class.java)

fun restoreGamePath(): String? {
    return PREFERENCES.get("game_path", null)
}

fun restoreBackupCheckbox(): Boolean {
    return PREFERENCES.getBoolean("do_backup", false)
}

fun saveGamePath(path: String) {
    PREFERENCES.put("game_path", path)
}

fun saveBackupCheckbox(value: Boolean) {
    PREFERENCES.putBoolean("do_backup", value)
}