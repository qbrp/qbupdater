package org.lain.qbupdater

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Config(
    val hosts: List<String>,
    val modpack: Modpack,
    val port: Int = 8080
)

@Serializable
data class Modpack(
    val version: String,
    val path: String,
    @SerialName("partial_updates") val partialUpdates: Set<String> = emptySet(),
)

private val VERSION_REGEX = Regex("""^[a-zA-Z0-9._-]+$""")

fun modpackResponseOf(config: Config, path: String): String {
    return "${config.modpack.version}\n" + config.hosts.joinToString("\n") { it.replace("{}", path) + ".7z" }
}

fun main() {
    val config = Json.decodeFromString<Config>(File("config.json").readText())
    embeddedServer(Netty, config.port) {
        install(ContentNegotiation) { json() }
        routing {
            get("/update") {
                val version = call.request.queryParameters["version"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                if (!VERSION_REGEX.matches(version)) {
                    return@get call.respond(HttpStatusCode.BadRequest)
                }
                val serverModpack = config.modpack

                if (version == serverModpack.version) {
                    call.respond("up-to-date")
                } else {
                    val path = when(serverModpack.partialUpdates.contains(version)) {
                        true -> version
                        false -> serverModpack.path
                    }
                    call.respond(modpackResponseOf(config, path))
                }
            }
        }
    }.start(wait = true)
}