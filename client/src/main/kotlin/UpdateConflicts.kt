package org.lain.qbupdater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile

sealed interface UpdateConflict {
    data class Mod(
        val identifier: String,
        val displayName: String,
        val installedFiles: List<String>,
        val installedVersions: List<String?>,
        val updateFiles: List<String>,
        val updateVersion: String?,
    ) : UpdateConflict
}

enum class ReplacementDecision {
    REPLACE,
    KEEP,
    REPLACE_ALL_MODS,
}

internal data class ModReplacement(
    val installedFiles: List<Path>,
    val updateFiles: List<Path>,
)

internal data class ConflictResolution(
    val skippedUpdateFiles: Set<Path>,
    val protectedGameFiles: Set<Path>,
    val modReplacements: List<ModReplacement>,
)

internal data class ModMetadata(
    val identifier: String,
    val displayName: String,
    val version: String?,
)

private data class ModFile(
    val path: Path,
    val metadata: ModMetadata,
)

private val json = Json { ignoreUnknownKeys = true }

internal suspend fun resolveUpdateConflicts(
    gameDirectory: File,
    stagedDirectory: File,
    conflictDecision: suspend (UpdateConflict) -> ReplacementDecision,
): ConflictResolution {
    val skippedUpdateFiles = mutableSetOf<Path>()
    val protectedGameFiles = mutableSetOf<Path>()
    val modReplacements = mutableListOf<ModReplacement>()

    val installedOptions = gameDirectory.resolve("options.txt")
    val stagedOptions = stagedDirectory.resolve("options.txt")
    if (stagedOptions.isFile) {
        protectedGameFiles.add(installedOptions.normalizedPath())
        if (!mergeMissingOptions(installedOptions, stagedOptions)) {
            skippedUpdateFiles.add(stagedOptions.normalizedPath())
        }
    }

    val installedModsDirectory = gameDirectory.resolve("mods")
    val stagedModsDirectory = stagedDirectory.resolve("mods")
    if (!stagedModsDirectory.isDirectory) {
        return ConflictResolution(skippedUpdateFiles, protectedGameFiles, modReplacements)
    }

    val scheduledDeletions = readScheduledDeletions(gameDirectory, stagedDirectory)
    val installedMods = scanMods(installedModsDirectory)
        .filterNot { it.path in scheduledDeletions }
    val updateMods = scanMods(stagedModsDirectory)
    val duplicateUpdateIds = updateMods
        .groupBy { it.metadata.identifier }
        .filterValues { it.size > 1 }

    if (duplicateUpdateIds.isNotEmpty()) {
        val duplicates = duplicateUpdateIds.entries.joinToString { (id, files) ->
            "$id (${files.joinToString { it.path.fileName.toString() }})"
        }
        throw IOException("В обновлении найдено несколько файлов с одинаковым идентификатором мода: $duplicates")
    }

    val installedById = installedMods.groupBy { it.metadata.identifier }
    val keptModReplacements = mutableListOf<ModReplacement>()
    var replaceAllMods = false
    updateMods.sortedBy { it.metadata.identifier }.forEach { updateMod ->
        val installed = installedById[updateMod.metadata.identifier].orEmpty()
        if (installed.isEmpty()) return@forEach

        val sortedInstalled = installed.sortedBy { it.path.fileName.toString() }
        val conflict = UpdateConflict.Mod(
            identifier = updateMod.metadata.identifier,
            displayName = updateMod.metadata.displayName,
            installedFiles = sortedInstalled.map { it.path.fileName.toString() },
            installedVersions = sortedInstalled.map { it.metadata.version },
            updateFiles = listOf(updateMod.path.fileName.toString()),
            updateVersion = updateMod.metadata.version,
        )

        val versionsMatch = updateMod.metadata.version != null &&
            installed.all { it.metadata.version == updateMod.metadata.version }
        val replacement = ModReplacement(
            installedFiles = installed.map { it.path },
            updateFiles = listOf(updateMod.path),
        )
        val decision = if (versionsMatch || replaceAllMods) {
            ReplacementDecision.REPLACE
        } else {
            conflictDecision(conflict)
        }

        when (decision) {
            ReplacementDecision.REPLACE -> modReplacements.add(replacement)
            ReplacementDecision.KEEP -> keptModReplacements.add(replacement)
            ReplacementDecision.REPLACE_ALL_MODS -> {
                replaceAllMods = true
                modReplacements.addAll(keptModReplacements)
                keptModReplacements.clear()
                modReplacements.add(replacement)
            }
        }
    }

    keptModReplacements.forEach { replacement ->
        skippedUpdateFiles.addAll(replacement.updateFiles)
        protectedGameFiles.addAll(replacement.installedFiles)
    }

    return ConflictResolution(skippedUpdateFiles, protectedGameFiles, modReplacements)
}

private fun mergeMissingOptions(installedOptions: File, stagedOptions: File): Boolean {
    val installedText = installedOptions.takeIf { it.isFile }?.readText().orEmpty()
    val existingKeys = installedText.lineSequence()
        .mapNotNull(::optionKey)
        .toMutableSet()
    val missingLines = stagedOptions.readLines().filter { line ->
        optionKey(line)?.let(existingKeys::add) == true
    }

    if (missingLines.isEmpty()) return false

    val lineSeparator = if ("\r\n" in installedText) "\r\n" else "\n"
    val mergedText = buildString {
        append(installedText)
        if (installedText.isNotEmpty() && !installedText.endsWith("\n") && !installedText.endsWith("\r")) {
            append(lineSeparator)
        }
        append(missingLines.joinToString(lineSeparator))
    }
    stagedOptions.writeText(mergedText)
    return true
}

private fun optionKey(line: String): String? {
    val separatorIndex = line.indexOf(':')
    if (separatorIndex <= 0) return null
    return line.substring(0, separatorIndex).trim().takeIf { it.isNotEmpty() }
}

private fun readScheduledDeletions(gameDirectory: File, stagedDirectory: File): Set<Path> {
    val deletionsFile = stagedDirectory.resolve(".deleted")
    if (!deletionsFile.isFile) return emptySet()

    val gamePath = gameDirectory.canonicalFile.normalizedPath()
    return deletionsFile.readLines()
        .asSequence()
        .filter(String::isNotBlank)
        .map { gameDirectory.resolve(it).canonicalFile.normalizedPath() }
        .filter { it.startsWith(gamePath) }
        .toSet()
}

private fun scanMods(directory: File): List<ModFile> {
    if (!directory.isDirectory) return emptyList()

    return directory.walkTopDown()
        .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
        .map { file ->
            val metadata = readModMetadata(file)
                ?: throw IOException("Не удалось определить идентификатор мода: ${file.path}")
            ModFile(file.normalizedPath(), metadata)
        }
        .toList()
}

internal fun readModMetadata(file: File): ModMetadata? = runCatching {
    ZipFile(file).use { archive ->
        readJsonMetadata(archive, "fabric.mod.json") { root ->
            val identifier = root["id"]?.jsonPrimitive?.contentOrNull
                ?: return@readJsonMetadata null
            val name = root["name"]?.jsonPrimitive?.contentOrNull
            val version = root["version"]?.jsonPrimitive?.contentOrNull
            ModMetadata(identifier.normalizedId(), name.orEmpty().ifBlank { identifier }, version.normalizedVersion())
        } ?: readJsonMetadata(archive, "quilt.mod.json") { root ->
            val loader = root["quilt_loader"]?.jsonObject ?: return@readJsonMetadata null
            val identifier = loader["id"]?.jsonPrimitive?.contentOrNull
                ?: return@readJsonMetadata null
            val name = loader["metadata"]?.jsonObject
                ?.get("name")?.jsonPrimitive?.contentOrNull
            val version = loader["version"]?.jsonPrimitive?.contentOrNull
            ModMetadata(identifier.normalizedId(), name.orEmpty().ifBlank { identifier }, version.normalizedVersion())
        } ?: readForgeMetadata(archive, "META-INF/neoforge.mods.toml")
            ?: readForgeMetadata(archive, "META-INF/mods.toml")
            ?: readLegacyForgeMetadata(archive)
    }
}.getOrNull()

private inline fun readJsonMetadata(
    archive: ZipFile,
    entryName: String,
    parse: (kotlinx.serialization.json.JsonObject) -> ModMetadata?,
): ModMetadata? {
    val entry = archive.getEntry(entryName) ?: return null
    val root = archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
        json.parseToJsonElement(reader.readText()).jsonObject
    }
    return parse(root)
}

private fun readForgeMetadata(archive: ZipFile, entryName: String): ModMetadata? {
    val entry = archive.getEntry(entryName) ?: return null
    val contents = archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    val modsSection = contents.substringAfter("[[mods]]", missingDelimiterValue = "")
        .substringBefore("[[", missingDelimiterValue = contents)
    if (modsSection.isBlank()) return null

    val identifier = tomlValue("modId", modsSection) ?: return null
    val name = tomlValue("displayName", modsSection)
    val version = resolveForgeVersion(archive, tomlValue("version", modsSection))
    return ModMetadata(identifier.normalizedId(), name.orEmpty().ifBlank { identifier }, version)
}

private fun readLegacyForgeMetadata(archive: ZipFile): ModMetadata? {
    val entry = archive.getEntry("mcmod.info") ?: return null
    val element = archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
        json.parseToJsonElement(reader.readText())
    }
    val root = element.jsonArray.firstOrNull()?.jsonObject ?: return null
    val identifier = root["modid"]?.jsonPrimitive?.contentOrNull ?: return null
    val name = root["name"]?.jsonPrimitive?.contentOrNull
    val version = root["version"]?.jsonPrimitive?.contentOrNull
    return ModMetadata(identifier.normalizedId(), name.orEmpty().ifBlank { identifier }, version.normalizedVersion())
}

private fun resolveForgeVersion(archive: ZipFile, declaredVersion: String?): String? {
    val normalized = declaredVersion.normalizedVersion()
    if (normalized != null && !normalized.startsWith("${'$'}{")) return normalized

    val manifestEntry = archive.getEntry("META-INF/MANIFEST.MF") ?: return normalized
    val manifest = archive.getInputStream(manifestEntry).use { java.util.jar.Manifest(it) }
    return manifest.mainAttributes.getValue("Implementation-Version").normalizedVersion()
        ?: manifest.mainAttributes.getValue("Specification-Version").normalizedVersion()
}

private fun tomlValue(key: String, contents: String): String? =
    Regex("(?m)^\\s*${Regex.escape(key)}\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
        .find(contents)
        ?.groupValues
        ?.get(1)

private fun String.normalizedId(): String = trim().lowercase(Locale.ROOT)

private fun String?.normalizedVersion(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun File.normalizedPath(): Path = toPath().toAbsolutePath().normalize()
