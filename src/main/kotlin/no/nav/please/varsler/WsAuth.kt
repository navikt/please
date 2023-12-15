package no.nav.please.varsler

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import no.nav.please.plugins.SocketResponse
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException

val logger = LoggerFactory.getLogger("no.nav.please.varsler.WsAuth.kt")

suspend fun DefaultWebSocketServerSession.awaitAuthentication(channel: ReceiveChannel<Frame>, ticketHandler: WsTicketHandler): WsListener {
    val result = channel.receiveAsFlow()
        .map { tryAuthenticateWithMessage(it, ticketHandler) }
        .firstOrNull { it is AuthResult.Success }
    return when (result) {
        is AuthResult.Success -> WsListener(
            wsSession = this,
            subscription = result.subscription
        )
        else -> throw IllegalStateException("Failed to authenticate")
    }
}


sealed class AuthResult {
    class Success(val subscription: Subscription): AuthResult()
    data object Failed: AuthResult()
}

suspend fun DefaultWebSocketServerSession.tryAuthenticateWithMessage(frame: Frame, ticketHandler: WsTicketHandler): AuthResult {
    try {
        logger.info("Received ticket, trying to authenticate $frame")
        if (frame !is Frame.Text) return AuthResult.Failed
        val connectionTicket = ConnectionTicket.of(frame.readText())
            .let { if (it is WellFormedTicket) ticketHandler.consumeTicket(it) else it }

        return when (connectionTicket) {
            is ValidatedTicket -> AuthResult.Success(connectionTicket.subscription)
            else -> {
                send(SocketResponse.INVALID_TOKEN.name)
                AuthResult.Failed
            }
        }
    } catch (e: Throwable) {
        logger.warn("Failed to handle auth ticket", e)
        return AuthResult.Failed
    }
}
