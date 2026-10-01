plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("edu.sc.seis.launch4j") version "4.0.0"
}

group = "org.lain.qbupdater"
version = "3.0"

val getdownVersion = "2.0.1"
val getdownLauncher by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val bootstrapRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.6")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    getdownLauncher(
        "io.github.bekoenig.getdown:getdown-launcher:$getdownVersion:jar-with-dependencies"
    )
    bootstrapRuntime(kotlin("stdlib"))
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

val bootstrapJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Builds the small launcher that starts Getdown"
    dependsOn(tasks.classes)

    archiveFileName.set("bootstrap.jar")
    destinationDirectory.set(layout.buildDirectory.dir("bootstrap"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output) {
        include("org/lain/qbupdater/Bootstrap*.class")
    }
    from(bootstrapRuntime.map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })
    manifest {
        attributes["Main-Class"] = "org.lain.qbupdater.Bootstrap"
    }
}

val getdownDistributionDir = layout.buildDirectory.dir("getdown")

val prepareGetdownDistribution by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Prepares application files that Getdown will download"
    dependsOn(tasks.shadowJar)

    from(tasks.shadowJar.flatMap { it.archiveFile }) {
        rename { "qbupdater.jar" }
    }
    from("src/getdown/getdown.txt")
    into(getdownDistributionDir)
}

val generateGetdownDigests by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Generates digest.txt and digest2.txt for the Getdown release"
    dependsOn(prepareGetdownDistribution)

    classpath = getdownLauncher
    mainClass.set("io.github.bekoenig.getdown.tools.Digester")
    args(getdownDistributionDir.get().asFile.absolutePath)

    inputs.files(
        getdownDistributionDir.map { it.file("getdown.txt") },
        getdownDistributionDir.map { it.file("qbupdater.jar") }
    )
    outputs.files(
        getdownDistributionDir.map { it.file("digest.txt") },
        getdownDistributionDir.map { it.file("digest2.txt") }
    )
}

tasks.register("getdownDistribution") {
    group = "distribution"
    description = "Builds the files to upload to a GitHub release"
    dependsOn(generateGetdownDigests)
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
val runtimeImageDir = layout.buildDirectory.dir("jpackage/runtime-image")
val runtimeModules = listOf(
    "java.base",
    "java.desktop",
    "java.instrument",
    "java.logging",
    "java.naming",
    "java.prefs",
    "java.scripting",
    "jdk.charsets",
    "jdk.crypto.ec",
    "jdk.localedata",
    "jdk.unsupported"
).joinToString(",")

val prepareRuntimeImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a runtime image that keeps the Java launcher required by Getdown"

    inputs.property("modules", runtimeModules)
    outputs.dir(runtimeImageDir)

    doFirst {
        val outputDir = runtimeImageDir.get().asFile
        project.delete(outputDir)

        val jdkHome = packagingJdk.get().metadata.installationPath.asFile
        val jlinkExecutable = jdkHome.resolve(
            if (isWindows) "bin/jlink.exe" else "bin/jlink"
        )
        check(jlinkExecutable.isFile) {
            "jlink was not found in Java 21 toolchain: $jlinkExecutable"
        }

        commandLine(
            jlinkExecutable.absolutePath,
            "--add-modules", runtimeModules,
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--output", outputDir.absolutePath
        )
    }
}

val prepareJpackage by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Prepares Bootstrap and Getdown for jpackage"
    dependsOn(bootstrapJar)
    from(bootstrapJar.flatMap { it.archiveFile })
    from(getdownLauncher) {
        rename { "getdown.jar" }
    }
    into(jpackageInputDir)
}

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Builds a native package (override with -PpackageType=app-image|exe|msi|dmg|pkg|deb|rpm)"
    dependsOn(prepareJpackage, prepareRuntimeImage)

    inputs.file(bootstrapJar.flatMap { it.archiveFile })
    inputs.files(getdownLauncher)
    inputs.dir(runtimeImageDir)
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
            "--main-jar", "bootstrap.jar",
            "--main-class", "org.lain.qbupdater.Bootstrap",
            "--app-version", packageVersion.get(),
            "--description", "Minecraft modpack updater",
            "--vendor", "Lain",
            "--runtime-image", runtimeImageDir.get().asFile.absolutePath,
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
