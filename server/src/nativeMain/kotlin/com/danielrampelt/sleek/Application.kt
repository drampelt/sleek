package com.danielrampelt.sleek

import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    embeddedServer(CIO, port = 8888) {
        routing {
            get("/") {
                call.respondText("Hello")
            }
        }
    }.start(wait = true)
}
