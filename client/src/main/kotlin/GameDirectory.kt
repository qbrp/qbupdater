package org.lain.qbupdater

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun fetchEngineVersion(gamePath: File): String? {
    val modsDirectory = gamePath.resolve("mods")
    if (!modsDirectory.exists()) {
        return null
    }
    val engineFileName = modsDirectory.list()
        ?.firstOrNull { it.startsWith("engine") }
        ?: return null
    val parts = engineFileName.split("-")
    val version = parts[1]
    return version.replace("+", "_")
}

fun backupOptions(gamePath: File) {
    val options = gamePath.resolve("options.txt")
    val config = gamePath.resolve("config")
    val backups = gamePath.resolve("backups")
    backups.mkdir()
    val backupName = LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE)
    val backup = backups.resolve(backupName)
    backup.mkdir()
    if (options.exists()) {
        options.copyTo(backup.resolve("options.txt"), true)
    }
    if (config.exists()) {
        config.copyRecursively(backup.resolve("config"), true)
    }
}

fun showGameFolderWarn(gamePath: File): Boolean {
    val options = gamePath.resolve("options.txt")
    val versions = gamePath.resolve("versions")
    return !options.exists() || !(versions.exists() && versions.isDirectory)
}

fun fetchVersion(gamePath: File): String? {
    val versionFile = File(gamePath, "qbupdater.version")
    return if (!versionFile.exists()) {
        versionFile.createNewFile()
        null
    } else {
        versionFile.readText().trim().takeIf { it.isNotEmpty() }
    }
}

fun isMinecraftRunning(): Boolean {
    return ProcessHandle.allProcesses().anyMatch { process: ProcessHandle ->
        val info = process.info()
        val command = info.command().orElse("")
        val arguments = info.arguments().orElse(emptyArray<String>()).joinToString(" ")
        val commandLine = "$command $arguments".lowercase()

        commandLine.contains("minecraft") ||
                commandLine.contains(".minecraft") ||
                commandLine.contains("net.minecraft") ||
                commandLine.contains("fabric-loader")
    }
}
