package no.nav.please.varsler

import arrow.core.raise.either
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import no.nav.please.errorUtil.LoggableError
import no.nav.please.plugins.SocketResponse
import org.slf4j.LoggerFactory
import redis.clients.jedis.exceptions.JedisException

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
        else -> throw ClientClosedException("Socket closed before authenticated")
    }
}

class ClientClosedException(message: String): Exception(message)

sealed class AuthResult {
    class Success(val subscription: Subscription): AuthResult()
    data object Failed: AuthResult()
}

sealed interface TicketAuthError
sealed interface AuthFailedTechnicalError: LoggableError
data object AuthFailedInvalidTicketError : TicketAuthError
data class AuthFailedTicketNotFound(val ticket: WellFormedTicket) : TicketAuthError
data class AuthFailedRedisError(val operationDescription: String, val jedisError: JedisException) : TicketAuthError, AuthFailedTechnicalError {
    override fun log() {
        logger.error("Failed to authenticate on websocket: Redis error on:$operationDescription", jedisError)
    }
}

data class AuthFailedUnknownError(val operationDescription: String, val error: Throwable) : TicketAuthError, AuthFailedTechnicalError {
    override fun log() {
        logger.error("Failed to authenticate on websocket: Unknown error on:$operationDescription ", error)
    }
}

fun ConsumeTicketError.toTicketAuthError(): TicketAuthError = when (this) {
    is TicketHandlerRedisError -> AuthFailedRedisError(this.operationDescription, this.jedisError)
    is TicketHandlerSubscriptionNotFoundError -> AuthFailedTicketNotFound(this.ticket)
    is TicketHandlerUnknownError -> AuthFailedUnknownError(this.operationDescription, this.error)
}

suspend fun DefaultWebSocketServerSession.tryAuthenticateWithMessage(frame: Frame, ticketHandler: WsTicketHandler): AuthResult {
    try {
        logger.info("Received ticket, trying to authenticate $frame")
        if (frame !is Frame.Text) return AuthResult.Failed
        val text = frame.readText()
        return either {
            val ticket = ConnectionTicket.of(text)
                .mapLeft { AuthFailedInvalidTicketError }.bind()
            ticketHandler.consumeTicket(ticket)
                .mapLeft { it.toTicketAuthError() }.bind()
        }.fold({
                val response: SocketResponse = when (it) {
                    is AuthFailedTechnicalError -> {
                        it.log()
                        SocketResponse.FAILED_TO_CONSUME_AUTH_TICKET
                    }
                    is AuthFailedTicketNotFound -> SocketResponse.INVALID_TOKEN // Don't tell client if ticket exists or not
                    is AuthFailedInvalidTicketError -> SocketResponse.INVALID_TOKEN
                }
                send(response.name)
                AuthResult.Failed
            },
            {
                AuthResult.Success(it.subscription)
            })
    } catch (e: Throwable) {
        logger.warn("Failed to handle auth ticket", e)
        return AuthResult.Failed
    }
}
