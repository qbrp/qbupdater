package org.lain.qbupdater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Paths
import kotlin.coroutines.cancellation.CancellationException

data class UpdaterException(override val message: String, override val cause: Throwable) : Exception()

fun writeError(logPath: File, e: Exception, file: String) {
    val logfile = File(logPath, "qbupdater-err-$file.log")
    logfile.parentFile.mkdirs()
    if (!logfile.exists()) logfile.createNewFile()
    logfile.writeText(e.stackTraceToString())
}

suspend fun download(url: String, file: File, progressListener: ProgressListener): InputStream = withContext(Dispatchers.IO) {
    println("Скачивание файла по $url")
    val ftpUrl = handleServerResponse { URI(url).toURL() }
    val connection = ftpUrl.openConnection()
    connection.connectTimeout = 3500
    connection.readTimeout = 3500
    val stream = connection.getInputStream()
    launch {
        stream.use { input ->
            val contentLength = connection.contentLength.toLong()
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    ensureActive()
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    progressListener.use(totalRead, contentLength)
                }
            }
        }
    }
    stream
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

/**
 * @return Возвращает null, если обновлений нет или сервер неактивен
 * @throws UpdaterException при невалидном ответе
 */
fun requestUpdates(version: String): RequiredUpdates? {
    val host = "https://drive.qbrp.online/update?version={}"
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

        statusCode to body
    } catch (e: Throwable) {
        throw UpdaterException("Не удалось подключиться к серверу", e)
    }
    println("Получен ответ от сервера: $statusCode")
    return if (statusCode != 200) {
        println("Невозможно обработать ответ от сервера.")
        null
    } else if (response == "up-to-date") {
        println("Обновления не требуются")
        null
    } else {
        RequiredUpdates.of(response)
    }
}

/**
 * @return есть ли ошибики установка
 */
suspend fun requestDownloadUpdate(
    gamePath: File,
    updates: RequiredUpdates,
    progressListener: ProgressListener
): Boolean {
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
    val sevenZFileExtract = SevenZFile.builder()
        .setPath(downloadFile.toPath())
        .get()
    var processed = 0
    var entry = sevenZFileExtract.getNextEntry()
    val skipped = mutableListOf<String>()
    val errors = mutableListOf<String>()

    while (entry != null) {
        val output = File(gameDirectory, entry.name).canonicalFile
        if (!output.toPath().startsWith(gameDirectory.toPath())) {
            skipped += output.path
            entry = sevenZFileExtract.getNextEntry()
            continue
        }

        if (entry.isDirectory) {
            entry = sevenZFileExtract.getNextEntry()
            continue
        }
        output.parentFile?.mkdirs()

        withContext(Dispatchers.IO) {
            FileOutputStream(output).use { writer ->
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (sevenZFileExtract.read(buffer).also { bytesRead = it } != -1) {
                    writer.write(buffer, 0, bytesRead)
                }
            }
        }

        processed++
        println("[+] ${entry.name}")
        entry = sevenZFileExtract.getNextEntry()
    }

    downloadFile.delete()
    println("Файлы разархивированы.")

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
                println("[-] $line")

                val result = if (fileToDelete.isDirectory) {
                    fileToDelete.deleteRecursively()
                } else {
                    fileToDelete.delete()
                }
                if (!result) {
                    errors += fileToDelete.path
                }
            }
        }
        deletionsFile.delete()
    }

    errors.forEach { println("[!] Не удалось удалить $it") }
    skipped.forEach { println("[!] Пропущен $it") }

    versionFile.writeText(modpackVersion)
    println("Обновление модпака завершено")
    return errors.isNotEmpty() || skipped.isNotEmpty()
}

typealias ProgressListener = (Int) -> Unit

fun ProgressListener.use(current: Long, total: Long) {
    val percent = (current * 100 / total).toInt()
    invoke(percent)
}
