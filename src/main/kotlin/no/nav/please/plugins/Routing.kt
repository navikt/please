package no.nav.please.plugins

import arrow.core.Either
import no.nav.please.varsler.WsTicketHandler
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import no.nav.please.retry.MaxRetryError
import no.nav.please.varsler.EventType
import no.nav.please.varsler.TicketRequest
import no.nav.please.varsler.logger
import no.nav.security.token.support.v2.TokenValidationContextPrincipal

fun Application.configureRouting(publishMessage: suspend (message: NyDialogNotification) -> Either<MaxRetryError, Long>, pingRedis: PingRedis, ticketHandler: WsTicketHandler) {
    routing {
        route("/isAlive") {
            get {
                val redisStatus = pingRedis()
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
                pingRedis()
                    .fold({
                        call.respond(HttpStatusCode.InternalServerError)
                    }, {
                        call.respond(HttpStatusCode.OK)
                    })
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
                    // TODO: Add authorization(a2) (POAO-tilgang)
                    try {
                        val subject = call.authentication.principal<TokenValidationContextPrincipal>()
                            ?.context?.anyValidClaims?.get()?.get("sub")?.toString() ?: throw IllegalArgumentException(
                            "No subject claim found")
                        val payload = call.receive<TicketRequest>()
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
                    call.respond(HttpStatusCode.InternalServerError, "Internal error")
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
