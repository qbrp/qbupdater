package org.lain.qbupdater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

data class UpdaterException(override val message: String, override val cause: Throwable) : Exception()

fun writeError(logPath: File, e: Exception, file: String) {
    val logfile = File(logPath, "qbupdater-err-$file.log")
    logfile.parentFile.mkdirs()
    if (!logfile.exists()) logfile.createNewFile()
    logfile.writeText(e.stackTraceToString())
}

suspend fun download(url: String, file: File, progressListener: ProgressListener) = withContext(Dispatchers.IO) {
    println("Скачивание файла по $url")
    val ftpUrl = handleServerResponse { URI(url).toURL() }
    val connection = ftpUrl.openConnection()
    connection.connectTimeout = 3500
    connection.readTimeout = 3500
    connection.getInputStream().use { input ->
        val progress = ProgressReporter(connection.contentLengthLong, progressListener)
        progress.start()

        FileOutputStream(file).use { output ->
            val buffer = ByteArray(8192)
            var totalRead = 0L
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                ensureActive()
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                progress.update(totalRead)
            }
        }

        progress.complete()
    }
}

fun <T> handleServerResponse(statement: () -> T) = runCatching { statement() }
    .getOrElse { throw UpdaterException("Получен неправильный ответ от сервера. Установлена ли правильная версия qbupdater?", it) }

object QbUpdater {
    @JvmStatic
    fun main(args: Array<String>) {
        setupWindow()
    }
}

data class RequiredUpdates(val modpackVersion: String, val hosts: List<String>) {
    companion object {
        fun of(response: String): RequiredUpdates {
            val responseString = handleServerResponse { response.split("\n") }
            val modpackVersion = handleServerResponse { responseString[0] }
            val hosts = handleServerResponse { responseString.subList(1, responseString.size) }
            return RequiredUpdates(modpackVersion, hosts)
        }
    }
}
/**
 * @return Возвращает null, если обновлений нет или сервер неактивен
 * @throws UpdaterException при невалидном ответе
 */
fun requestUpdates(version: String): RequiredUpdates? {
    val host = "https://drive.qbrp.fun/update?version={}"
    //val host = "http://localhost:8080/update?version={}"
    val (statusCode, response) = try {
        val connection = URL(host.replace("{}", version))
            .openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        val statusCode = connection.responseCode
        val body = try {
            connection.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            connection.errorStream?.bufferedReader()?.readText().orEmpty()
        }

        println("Получен ответ от сервера: $statusCode ($body)")

        statusCode to body
    } catch (e: Throwable) {
        throw UpdaterException("Не удалось подключиться к серверу", e)
    }

    if (statusCode != 200) {
        error("Сервер отправил код ответа: $statusCode")
    }

    if (response == "up-to-date") {
        return null
    }

    return RequiredUpdates.of(response)
}

/**
 * @return есть ли ошибики установка
 */
suspend fun requestDownloadUpdate(
    gamePath: File,
    updates: RequiredUpdates,
    progressListener: ProgressListener,
    conflictDecision: suspend (UpdateConflict) -> ReplacementDecision,
): Boolean = withContext(Dispatchers.IO) {
    val (modpackVersion, hosts) = updates

    val logPath = File(gamePath, "logs")
    val downloadFile = File(gamePath, "temp-modpack.7zip")
    val versionFile = File(gamePath, "qbupdater.version")

    var downloadedSuccess = false
    var hostIndex = 0
    while (!downloadedSuccess && hostIndex < hosts.size) {
        val downloadHost = hosts[hostIndex]
        try {
            download(downloadHost, downloadFile, progressListener)
            downloadedSuccess = true
        } catch (e: CancellationException) {
            println("Загрузка модпака отменена")
            downloadFile.delete()
            throw e
        } catch (e: Exception) {
            val errorMessage = e.message
            println("Не удалось скачать модпак: $errorMessage")
            writeError(logPath, e, URI(downloadHost).host)
            if (hostIndex != hosts.lastIndex) {
                println("Используем резервный хост...")
            } else {
                downloadFile.delete()
                throw UpdaterException("Ни один из серверов не доступен для скачивания модпака, попробуйте позже.", e)
            }
            hostIndex++
        }
    }

    val gameDirectory = Paths.get(gamePath.path).toRealPath().toFile()
    var processed = 0
    val skipped = mutableListOf<String>()
    val errors = mutableListOf<String>()

    val rootOutput = File(gameDirectory, ".update")
    if (rootOutput.exists()) { rootOutput.deleteRecursively() }
    rootOutput.mkdirs()

    println("Распаковка архива")
    SevenZFile.builder()
        .setPath(downloadFile.toPath())
        .get()
        .use { sevenZFileExtract ->
            val totalExtractSize = sevenZFileExtract.entries
                .asSequence()
                .filterNot { it.isDirectory }
                .sumOf { it.size.coerceAtLeast(0L) }
            val extractProgress = ProgressReporter(totalExtractSize, progressListener)
            var extractedSize = 0L
            var entry = sevenZFileExtract.getNextEntry()

            extractProgress.start()

            while (entry != null) {
                ensureActive()

                val output = File(rootOutput, entry.name).canonicalFile
                if (!output.toPath().startsWith(rootOutput.toPath())) {
                    skipped += output.path
                    extractedSize += entry.size.coerceAtLeast(0L)
                    extractProgress.update(extractedSize)
                    entry = sevenZFileExtract.getNextEntry()
                    continue
                }

                if (entry.isDirectory) {
                    println("[+] ${entry.name}")
                    entry = sevenZFileExtract.getNextEntry()
                    continue
                }
                output.parentFile?.mkdirs()

                FileOutputStream(output).use { writer ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (sevenZFileExtract.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        writer.write(buffer, 0, bytesRead)
                        extractedSize += bytesRead
                        extractProgress.update(extractedSize)
                    }
                }

                processed++
                entry = sevenZFileExtract.getNextEntry()
            }

            extractProgress.complete()
        }

    downloadFile.delete()
    println("Файлы распакованы")

    val conflictResolution = resolveUpdateConflicts(
        gameDirectory = gameDirectory,
        stagedDirectory = rootOutput,
        conflictDecision = conflictDecision,
    )
    val skippedUpdateFiles = conflictResolution.skippedUpdateFiles.toMutableSet()
    val protectedGameFiles = conflictResolution.protectedGameFiles.toMutableSet()

    conflictResolution.modReplacements.forEach { replacement ->
        var deletionFailed = false
        replacement.installedFiles.forEach { installedFile ->
            try {
                Files.deleteIfExists(installedFile)
                println("[-] ${gameDirectory.toPath().relativize(installedFile)}")
            } catch (e: Exception) {
                deletionFailed = true
                errors += installedFile.toString()
                println("[!] Не удалось удалить $installedFile: ${e.message}")
            }
        }
        if (deletionFailed) {
            skippedUpdateFiles.addAll(replacement.updateFiles)
            protectedGameFiles.addAll(replacement.installedFiles)
        }
    }

    progressListener(UpdateProgress.ApplyingFiles)
    Files.walk(rootOutput.toPath()).use { paths ->
        paths
            .filter { Files.isRegularFile(it) }
            .filter { it.toAbsolutePath().normalize() !in skippedUpdateFiles }
            .forEach { source ->
                val relative = rootOutput.toPath().relativize(source)
                val target = gameDirectory.toPath().resolve(relative)

                Files.createDirectories(target.parent)

                Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
    }
    rootOutput.deleteRecursively()
    println("Файлы применены")

    val deletionsFile = File(gamePath, ".deleted")
    if (deletionsFile.exists()) {
        deletionsFile.inputStream().bufferedReader().use { reader ->
            reader.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val fileToDelete = File(gameDirectory, line).canonicalFile
                if (!fileToDelete.exists()) return@forEach
                if (!fileToDelete.toPath().startsWith(gameDirectory.toPath())) {
                    println("[!] Файл нельзя удалить по пути: $line")
                    return@forEach
                }
                if (fileToDelete.toPath().toAbsolutePath().normalize() in protectedGameFiles) {
                    println("[=] Сохранён $line")
                    return@forEach
                }
                println("[-] $line")

                try {
                    if (fileToDelete.isDirectory) {
                        if (!fileToDelete.deleteRecursively()) {
                            throw IOException("deleteRecursively() returned false")
                        }
                    } else {
                        Files.delete(fileToDelete.toPath())
                    }
                } catch (e: Exception) {
                    errors += fileToDelete.path
                    println("[!] Не удалось удалить ${fileToDelete.path}: ${e.message}")
                }
            }
        }
        deletionsFile.delete()
    }

    errors.forEach { println("[!] Не удалось удалить $it") }
    skipped.forEach { println("[!] Пропущен $it") }

    val failed = errors.isNotEmpty() || skipped.isNotEmpty()

    if (!failed) {
        versionFile.writeText(modpackVersion)
    }

    println("Обновление модпака завершено")
    progressListener(UpdateProgress.Determinate(100))
    failed
}

sealed interface UpdateProgress {
    data class Determinate(val percent: Int) : UpdateProgress
    data object ApplyingFiles : UpdateProgress
}

typealias ProgressListener = (UpdateProgress) -> Unit

private class ProgressReporter(
    private val total: Long,
    private val listener: ProgressListener,
) {
    private var lastPercent = -1

    fun start() {
        report(0)
    }

    fun update(current: Long) {
        if (total <= 0L) return

        val percent = ((current.coerceIn(0L, total).toDouble() / total) * 100)
            .toInt()
        report(percent)
    }

    fun complete() {
        report(100)
    }

    private fun report(percent: Int) {
        if (percent == lastPercent) return

        lastPercent = percent
        listener(UpdateProgress.Determinate(percent))
    }
}
