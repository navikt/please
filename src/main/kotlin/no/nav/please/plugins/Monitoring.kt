package no.nav.please.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.*
import org.slf4j.event.*

val excludedPaths = listOf("/isAlive", "/isReady", "/metrics")

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            path.startsWith("/") && !excludedPaths.contains(path)
        }
        callIdMdc("nav-call-id")
        format { call ->
            val responseTime = call.processingTimeMillis()
            val status = call.response.status()?.value
            val method = call.request.httpMethod.value
            val path = call.request.path()
            "$status $method - $path in ${responseTime}ms"
        }
    }
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { callId: String ->
            callId.isNotEmpty()
        }
    }
}
