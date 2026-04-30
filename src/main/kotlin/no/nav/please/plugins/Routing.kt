package no.nav.please.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import no.nav.please.varsler.*
import no.nav.please.varsler.IncomingDialogMessageFlow.isSubscribedToRedisPubSub
import no.nav.please.varsler.logger
import no.nav.security.token.support.v3.TokenValidationContextPrincipal
import java.util.*

fun Application.configureRouting(
    publishMessage: PublishMessage,
    pingRedis: PingRedis,
    ticketHandler: WsTicketHandler,
    navEmployeeIsAuthorized: NavEmployeeIsAuthorized) {
    routing {
        route("/isAlive") {
            get {
                val redisStatus = pingRedis()
                val ready = isSubscribedToRedisPubSub() and redisStatus.isRight()
                when (ready) {
                    false -> {
                        logger.warn("Failed to ping redis in isAlive")
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                    true -> {
                        require(redisStatus.getOrNull() == "PONG") { "Redis returnerer $redisStatus fra ping()" }
                        call.respond(HttpStatusCode.OK)
                    }
                }
                pingRedis()
                    .fold({
                        logger.warn("Failed to ping redis in isAlive")
                        call.respond(HttpStatusCode.InternalServerError)
                    }, { redisStatus ->
                        require(redisStatus == "PONG") { "Redis returnerer $redisStatus fra ping()" }
                        call.respond(HttpStatusCode.OK)
                    })
            }
        }
        route("/isReady") {
            get {
                val ready = isSubscribedToRedisPubSub() and pingRedis().fold({ false }, { true })
                when (ready) {
                    false -> call.respond(HttpStatusCode.InternalServerError)
                    true -> call.respond(HttpStatusCode.OK)
                }
            }
        }
        authenticate("AzureOrTokenX") {
            post("/notify-subscribers") {
                val dialogNotification = call.receive<NyDialogNotification>()
                call.respond(status = HttpStatusCode.OK, message = "")
                publishMessage(dialogNotification)
            }

            post("/ws-auth-ticket") {
                try {
                    try {
                        val subject = call.getClaim("sub") ?: throw IllegalArgumentException("No subject claim found")
                        val payload = call.receive<TicketRequest>()

                        // TODO: Authorization only necessary when NAV employee sends message to external user - check if subject is NAVident

                        val externalUserPin = payload.subscriptionKey // TODO: Must be obvious that subscriptionKey is always a PIN?
                        val employeeAzureId = call.getClaim("oid") ?: throw RuntimeException("No oid claim found")

                        if (!navEmployeeIsAuthorized(UUID.fromString(employeeAzureId), externalUserPin)) {
                            call.respond(HttpStatusCode.Forbidden, "Not authorized to send message to the external user")
                            return@post
                        }

                        ticketHandler.generateTicket(subject, payload)
                            .fold({ error ->
                                error.log()
                                call.respond(HttpStatusCode.InternalServerError, "Internal error")
                            }, { ticket ->
                                call.respondText(ticket.value)
                            })
                    } catch (e: CannotTransformContentToTypeException) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid payload")
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid auth")
                } catch (e: Throwable) {
                    call.respond(HttpStatusCode.InternalServerError, "Internal error ${e.message}")
                    logger.warn("Internal error", e)
                }
            }
        }
    }
}

@Serializable
data class NyDialogNotification(
    val subscriptionKey: String,
    val eventType: EventType
)

private fun ApplicationCall.getClaim(name: String): String? =
    this.authentication.principal<TokenValidationContextPrincipal>()
        ?.context?.anyValidClaims?.get(name)?.toString()