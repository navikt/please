package no.nav.please.plugins

import arrow.core.raise.catch
import no.nav.please.varsler.WsConnectionHolder.addListener
import no.nav.please.varsler.WsConnectionHolder.removeListener
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import no.nav.please.varsler.ClientClosedException
import no.nav.please.varsler.WsListener
import no.nav.please.varsler.WsTicketHandler
import no.nav.please.varsler.awaitAuthentication
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration

fun Application.configureSockets(ticketHandler: WsTicketHandler) {
    val logger = LoggerFactory.getLogger(javaClass)

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        webSocket("/ws") {
            val wsSocketKey = this.call.request.header("Sec-WebSocket-Key")
            var wsListener: WsListener? = null
            try {
                wsListener = awaitAuthentication(incoming, ticketHandler)
                addListener(wsListener)
                this.send(SocketResponse.AUTHENTICATED.name)
                logger.info("Authenticated, Sec-WebSocket-Key: $wsSocketKey")
                for(frame in incoming) {
                    // Keep open until termination
                    val message = incoming.receive()
                    logger.info("Received unexpected message: ${message}, Sec-WebSocket-Key: $wsSocketKey")
                }
            } catch (e: ClosedReceiveChannelException) {
                logger.info("onClose ${closeReason.await()}")
            } catch (e: ClientClosedException) {
                logger.info("${e.message}, ${closeReason.await()}")
            } catch (e: IOException)  {
                logger.warn("IOException: ${e.message}", e)
                closeExceptionally(e)
            } catch (e: Throwable) {
                logger.warn("onError", e)
                closeExceptionally(e)
            } finally {
                wsListener?.let { removeListener(it) }
            }
        }
    }
}

enum class SocketResponse {
    AUTHENTICATED,
    INVALID_TOKEN,
    FAILED_TO_CONSUME_AUTH_TICKET,
}
