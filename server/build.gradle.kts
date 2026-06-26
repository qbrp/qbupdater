plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.20"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "org.lain.qbupdater"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("io.ktor:ktor-server-netty:3.4.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "org.lain.svetov.Main"
    }
    mergeServiceFiles()
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}