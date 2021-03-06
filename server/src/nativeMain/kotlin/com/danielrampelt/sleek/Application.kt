package com.danielrampelt.sleek

import co.touchlab.sqliter.DatabaseConfiguration
import com.danielrampelt.sleek.database.Database
import com.squareup.sqldelight.drivers.native.NativeSqliteDriver
import com.squareup.sqldelight.drivers.native.wrapConnection
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.path
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.logging.toLogString
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.createFromCall
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.close
import io.ktor.utils.io.core.readAvailable
import io.ktor.utils.io.core.use
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.posix.SIGINT
import platform.posix.exit
import platform.posix.getenv
import platform.posix.signal
import kotlin.native.concurrent.AtomicReference
import kotlin.native.concurrent.SharedImmutable
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@SharedImmutable
private val engineRef = AtomicReference<ApplicationEngine?>(null)

@OptIn(ExperimentalTime::class)
fun main() {
    val path = getenv("STORAGE_PATH")?.toKString() ?: "."
    val uploadsPath = getenv("UPLOADS_PATH")?.toKString() ?: "$path/uploads"
    FileSystem.SYSTEM.createDirectory(path.toPath())
    FileSystem.SYSTEM.createDirectory(uploadsPath.toPath())

    val apiKey = getenv("API_KEY")?.toKString() ?: throw IllegalStateException("You must set the API_KEY variable")

    val driver = NativeSqliteDriver(
        configuration = DatabaseConfiguration(
            name = "sleek.db",
            Database.Schema.version,
            create = { connection ->
                wrapConnection(connection) { Database.Schema.create(it) }
            },
            upgrade = { connection, oldVersion, newVersion ->
                wrapConnection(connection) { Database.Schema.migrate(it, oldVersion, newVersion) }
            },
            extendedConfig = DatabaseConfiguration.Extended(
                basePath = path,
            ),
        )
    )
    val db = Database(driver)

    class ForbiddenException(message: String? = null) : RuntimeException(message)
    fun PipelineContext<Unit, ApplicationCall>.authorize() {
        val authHeader = call.request.header(HttpHeaders.Authorization) ?: throw ForbiddenException()
        val parts = authHeader.split(" ")
        if (parts.size != 2 || parts[0] != "Bearer" || parts[1] != apiKey) throw ForbiddenException()
    }

    fun ApplicationCall.baseUrl(): URLBuilder = URLBuilder.createFromCall(this).apply {
        pathSegments = emptyList()
        parameters.clear()
    }

    val engine = embeddedServer(CIO, port = 8888) {
        install(StatusPages) {
            exception<NotFoundException> { call, cause ->
                call.respondText("Not Found", status = HttpStatusCode.NotFound)
            }

            exception<ForbiddenException> { call, cause ->
                call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            }
        }

        // Temporary logging until it is supported in native
        intercept(ApplicationCallPipeline.Setup) {
            val time = TimeSource.Monotonic.markNow()
            proceed()
            val duration = time.elapsedNow()
            val message = when (val status = call.response.status() ?: "Unhandled") {
                HttpStatusCode.Found -> "${status as HttpStatusCode}: ${call.request.toLogString()} -> ${call.response.headers[HttpHeaders.Location]} in $duration"
                "Unhandled" -> "$status: ${call.request.toLogString()} in $duration"
                else -> "${status as HttpStatusCode}: ${call.request.toLogString()} in $duration"
            }
            log.info(message)
        }

        routing {
            post("/_/link") {
                authorize()
                val id = call.parameters["id"] ?: randomId()
                val name = call.parameters["name"]
                val path = call.parameters["path"]!!
                require(path.startsWith("http://") || path.startsWith("https://"))
                db.resourceQueries.insert(id, "text/uri-list", name, path)
                val url = call.baseUrl().apply { path(id) }.buildString()
                call.respondText(url)
            }

            post("/_/file") {
                authorize()
                // TODO: multipart doesn't actually work on native yet
                val multipart = call.receiveMultipart()
                var id: String? = call.parameters["id"] ?: randomId()
                var name: String? = call.parameters["name"]
                var type = "application/octet-stream"
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "id" -> id = part.value
                                "name" -> name = part.value
                            }
                        }
                        is PartData.FileItem -> {
                            if (name == null) name = part.originalFileName ?: "file"
                            part.contentType?.let { type = it.toString() }

                            FileSystem.SYSTEM.sink("$uploadsPath/$id".toPath(), mustCreate = true).buffer().use { output ->
                                part.provider().use { input ->
                                    val buffer = ByteArray(4096)
                                    var read = input.readAvailable(buffer, buffer.size)
                                    while (read > 0) {
                                        output.write(buffer, 0, read)
                                        read = input.readAvailable(buffer, buffer.size)
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }

            post("/_/raw") {
                authorize()
                val id = call.parameters["id"] ?: randomId()
                val name = call.parameters["name"]!!
                val type = call.request.header(HttpHeaders.ContentType) ?: "application/octet-stream"
                val channel = call.receiveChannel()
                FileSystem.SYSTEM.sink("$uploadsPath/$id".toPath(), mustCreate = true).buffer().use { output ->
                    val buffer = ByteArray(4096)
                    var read = channel.readAvailable(buffer, 0, buffer.size)
                    while (read > 0) {
                        output.write(buffer, 0, read)
                        read = channel.readAvailable(buffer, 0, buffer.size)
                    }
                }
                db.resourceQueries.insert(id, type, name, id)
                val url = call.baseUrl().apply { path(id) }.buildString()
                call.respondText(url)
            }

            get("{id...}") {
                val id = call.parameters["id"] ?: ""
                val resource = db.resourceQueries.findById(id).executeAsOneOrNull() ?: throw NotFoundException()

                if (resource.type == "text/uri-list" && (resource.path.startsWith("http://") || resource.path.startsWith("https://"))) {
                    call.respondRedirect(resource.path)
                    return@get
                }

                val handle = FileSystem.SYSTEM.openReadOnly("$uploadsPath/$id".toPath())
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Inline
                        .withParameter(ContentDisposition.Parameters.FileName, resource.name ?: "file").toString()
                )
                call.respondBytesWriter(contentType = ContentType.parse(resource.type), contentLength = handle.size()) {
                    handle.source().buffer().use { source ->
                        val buffer = ByteArray(4096)
                        var read = source.read(buffer, 0, buffer.size)
                        while (read > 0) {
                            writeFully(buffer, 0, read)
                            read = source.read(buffer, 0, buffer.size)
                        }
                        flush()
                        close()
                    }
                    handle.close()
                }
            }
        }

    }
    engineRef.value = engine

    signal(SIGINT, staticCFunction(::shutdown))

    engine.start(wait = true)
}

private fun shutdown(signal: Int) {
    val engine = engineRef.value ?: return exit(1)
    engineRef.value = null
    engine.application.log.info("Shutting down...")
    engine.stop(0, 0)
}

@SharedImmutable
private val Alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')

private fun randomId(length: Int = 6): String {
    return (1..length)
        .map { Alphabet[Random.nextInt(Alphabet.size)] }
        .joinToString("")
}
