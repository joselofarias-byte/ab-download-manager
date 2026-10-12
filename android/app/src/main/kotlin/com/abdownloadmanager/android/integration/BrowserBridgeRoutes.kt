package com.abdownloadmanager.android.integration

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

fun Application.browserBridgeRoutes(key: String, accept: suspend (String) -> Unit) {
    routing {
        post("/ping") {
            if (!BrowserRequest.authorized(key, call.request.headers["X-Api-Key"])) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@post
            }
            call.respondText("pong")
        }
        post("/add") {
            if (!BrowserRequest.authorized(key, call.request.headers["X-Api-Key"])) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@post
            }
            try {
                // Bound the actual stream, including chunked bodies, before decoding JSON.
                val body = withTimeout(2000) {
                    val channel = call.receiveChannel()
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break
                        require(output.size() + read <= BrowserRequest.MAX_BYTES)
                        output.write(buffer, 0, read)
                    }
                    output.toString("UTF-8")
                }
                BrowserRequest.decode(body)
                accept(body)
                call.respondText("OK")
            } catch (_: IllegalArgumentException) {
                call.respondText("Invalid browser request", status = HttpStatusCode.BadRequest)
            } catch (_: kotlinx.serialization.SerializationException) {
                call.respondText("Invalid browser request", status = HttpStatusCode.BadRequest)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                call.respondText("Request timeout", status = HttpStatusCode.RequestTimeout)
            } catch (_: java.io.IOException) {
                call.respondText("Browser inbox unavailable", status = HttpStatusCode.ServiceUnavailable)
            } catch (_: IllegalStateException) {
                call.respondText("Browser inbox unavailable", status = HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
