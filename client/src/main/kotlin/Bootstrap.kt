package org.lain.qbupdater

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import javax.swing.JOptionPane

object Bootstrap {
    private const val DEFAULT_APP_BASE =
        "https://github.com/qbrp/qbupdater/releases/latest/download/"

    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        try {
            launchGetdown()
        } catch (error: Exception) {
            error.printStackTrace()
            JOptionPane.showMessageDialog(
                null,
                "Не удалось запустить обновление qbupdater:\n${error.message}",
                "qbupdater",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun launchGetdown() {
        val appDir = applicationDirectory()
        Files.createDirectories(appDir)

        val appBase = System.getProperty("qbupdater.appbase", DEFAULT_APP_BASE)
            .let { if (it.endsWith('/')) it else "$it/" }
        val getdownJar = System.getProperty("qbupdater.getdown.jar")
            ?.let(Paths::get)
            ?: bootstrapDirectory().resolve("getdown.jar")

        require(Files.isRegularFile(getdownJar)) {
            "Getdown не найден: ${getdownJar.toAbsolutePath()}"
        }

        ProcessBuilder(
            javaExecutable().toString(),
            "-Dfile.encoding=UTF-8",
            "-Dappbase=$appBase",
            "-Dappbase_override=$appBase",
            "-jar",
            getdownJar.toAbsolutePath().normalize().toString(),
            appDir.toAbsolutePath().normalize().toString()
        )
            .directory(appDir.toFile())
            .inheritIO()
            .start()
    }

    private fun applicationDirectory(): Path {
        System.getProperty("qbupdater.appdir")?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }

        val userHome = Paths.get(System.getProperty("user.home"))
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        val baseDirectory = when {
            osName.contains("win") -> System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?: userHome.resolve("AppData/Local")

            osName.contains("mac") -> userHome.resolve("Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?: userHome.resolve(".local/share")
        }

        return baseDirectory.resolve("qbupdater").toAbsolutePath().normalize()
    }

    private fun bootstrapDirectory(): Path {
        val location = Paths.get(
            Bootstrap::class.java.protectionDomain.codeSource.location.toURI()
        ).toAbsolutePath().normalize()

        return if (Files.isDirectory(location)) location else location.parent
    }

    private fun javaExecutable(): Path {
        val binDirectory = Paths.get(System.getProperty("java.home"), "bin")
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        val executableNames = if (osName.contains("win")) {
            arrayOf("javaw.exe", "java.exe")
        } else {
            arrayOf("java")
        }

        return executableNames
            .asSequence()
            .map(binDirectory::resolve)
            .firstOrNull(Files::isRegularFile)
            ?: error("Java не найдена в $binDirectory")
    }
}
