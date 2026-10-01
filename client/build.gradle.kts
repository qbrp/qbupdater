plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("edu.sc.seis.launch4j") version "4.0.0"
}

group = "org.lain.qbupdater"
version = "3.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.6")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

launch4j {
    mainClassName = "org.lain.qbupdater.QbUpdater"
    icon = "${projectDir}/lain.ico"
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveVersion.set("")
    archiveBaseName.set("qbupdater")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "org.lain.qbupdater.QbUpdater"
    }
    mergeServiceFiles()
}

kotlin {
    jvmToolchain(21)
}

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("windows")
val isMacOs = osName.contains("mac")
val defaultPackageType = when {
    isWindows -> "exe"
    isMacOs -> "dmg"
    else -> "deb"
}
val packageType = providers.gradleProperty("packageType").orElse(defaultPackageType)
val packageVersion = providers.gradleProperty("packageVersion")
    .orElse(version.toString().substringBefore('-'))
val jpackageInputDir = layout.buildDirectory.dir("jpackage/input")
val jpackageOutputDir = packageType.flatMap { type ->
    layout.buildDirectory.dir("jpackage/$type")
}
val javaToolchains = extensions.getByType<JavaToolchainService>()
val packagingJdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val prepareJpackage by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Prepares the fat JAR for jpackage"
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(jpackageInputDir)
}

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Builds a native package (override with -PpackageType=app-image|exe|msi|dmg|pkg|deb|rpm)"
    dependsOn(prepareJpackage)

    inputs.file(tasks.shadowJar.flatMap { it.archiveFile })
    inputs.property("packageType", packageType)
    inputs.property("packageVersion", packageVersion)
    outputs.dir(jpackageOutputDir)
    outputs.upToDateWhen { false }

    doFirst {
        val currentPackageType = packageType.get()
        val outputDir = jpackageOutputDir.get().asFile
        project.delete(outputDir)
        outputDir.mkdirs()

        val jdkHome = packagingJdk.get().metadata.installationPath.asFile
        val jpackageExecutable = jdkHome.resolve(
            if (isWindows) "bin/jpackage.exe" else "bin/jpackage"
        )
        check(jpackageExecutable.isFile) {
            "jpackage was not found in Java 21 toolchain: $jpackageExecutable"
        }

        if (isWindows) {
            val wixBinCandidates = listOf(
                file("C:/Program Files (x86)/WiX Toolset v3.14/bin"),
                file("C:/Program Files (x86)/WiX Toolset v3.11/bin"),
                file("C:/Program Files/WiX Toolset v3.14/bin"),
                file("C:/Program Files/WiX Toolset v3.11/bin")
            )
            wixBinCandidates.firstOrNull { it.resolve("candle.exe").isFile }?.let { wixBin ->
                environment("PATH", wixBin.absolutePath + File.pathSeparator + System.getenv("PATH"))
            }
        }

        val arguments = mutableListOf(
            "--type", currentPackageType,
            "--input", jpackageInputDir.get().asFile.absolutePath,
            "--dest", outputDir.absolutePath,
            "--name", "qbupdater",
            "--main-jar", tasks.shadowJar.get().archiveFileName.get(),
            "--main-class", "org.lain.qbupdater.QbUpdater",
            "--app-version", packageVersion.get(),
            "--description", "Minecraft modpack updater",
            "--vendor", "Lain",
            "--java-options", "-Dfile.encoding=UTF-8"
        )

        val icon = when {
            isWindows -> file("lain.ico")
            isMacOs -> file("src/jpackage/icon.icns")
            else -> file("src/jpackage/icon.png")
        }
        if (icon.isFile) {
            arguments += listOf("--icon", icon.absolutePath)
        }

        if (isWindows && currentPackageType in setOf("exe", "msi")) {
            arguments += listOf(
                "--win-per-user-install",
                "--win-menu",
                "--win-shortcut",
                "--win-dir-chooser",
                "--win-upgrade-uuid", "2319ec6f-e8fd-47c3-82d0-f079c111485b"
            )
        } else if (isMacOs && currentPackageType in setOf("dmg", "pkg")) {
            arguments += listOf(
                "--mac-package-identifier", "org.lain.qbupdater",
                "--mac-package-name", "QBUpdater"
            )
        } else if (!isWindows && !isMacOs && currentPackageType in setOf("deb", "rpm")) {
            arguments += listOf(
                "--linux-shortcut",
                "--linux-app-category", "Utility"
            )
        }

        commandLine(listOf(jpackageExecutable.absolutePath) + arguments)
    }
}
