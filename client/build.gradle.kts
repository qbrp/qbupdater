plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("edu.sc.seis.launch4j") version "4.0.0"
}

group = "org.lain.qbupdater"
version = "1.0-SNAPSHOT"

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