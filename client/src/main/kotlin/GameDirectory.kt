package org.lain.qbupdater

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
    val logs = gamePath.resolve("logs")
    return !options.exists() || !(logs.exists() && logs.isDirectory)
}

fun fetchVersion(gamePath: File): String {
    val versionFile = File(gamePath, "qbupdater.version")
    return if (!versionFile.exists()) {
        versionFile.createNewFile()
        "none"
    } else {
        versionFile.readText().trim()
    }
        .ifBlank { "none" }
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
