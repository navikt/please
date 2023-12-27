package no.nav.please.plugins

import no.nav.please.varsler.WsTicketHandler
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import no.nav.please.varsler.EventType
import no.nav.please.varsler.TicketRequest
import no.nav.please.varsler.logger
import no.nav.security.token.support.v2.TokenValidationContextPrincipal
import java.util.*

fun Application.configureRouting(publishMessage: (message: NyDialogNotification) -> Long, pingRedis: PingRedis, ticketHandler: WsTicketHandler, isAuthorized: isAuthorizedToContactExternalUser) {
    routing {
        route("/isAlive") {
            get {
                call.respond(HttpStatusCode.OK)
            }
        }
        route("/isReady") {
            get {
                pingRedis()
                call.respond(HttpStatusCode.OK)
            }
        }
        authenticate("AzureOrTokenX") {
            post("/notify-subscribers") {
                val dialogNotification = call.receive<NyDialogNotification>()
                publishMessage(dialogNotification)
                call.respond(status = HttpStatusCode.OK, message = "")
            }

            post("/ws-auth-ticket") {
                try {
                    try {
                        val claims = call.authentication.principal<TokenValidationContextPrincipal>()
                            ?.context?.anyValidClaims?.get()
                        val subject = claims?.get("sub")?.toString() ?: throw IllegalArgumentException("No subject claim found")
                        val payload = call.receive<TicketRequest>()

                        // TODO: Only necessary when NAV employee sends message to external user
                        val externalUserPin = payload.subscriptionKey // TODO: Must be obvious that subscriptionKey is always a PIN?
                        val employeeAzureId = claims.get("oid")?.toString() ?: throw IllegalArgumentException("No oid claim found")

                        val isAuthorized = isAuthorized(UUID.fromString(employeeAzureId), externalUserPin)
                        if (!isAuthorized) {
                            call.respond(HttpStatusCode.Forbidden, "Not authorized to send message to the external user")
                            return@post
                        }

                        val ticket = ticketHandler.generateTicket(subject, payload)
                        call.respondText(ticket.value)
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
