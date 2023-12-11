package no.nav.please.plugins

import no.nav.please.varsler.WsConnectionHolder.addListener
import no.nav.please.varsler.WsConnectionHolder.removeListener
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import no.nav.please.varsler.WsListener
import no.nav.please.varsler.WsTicketHandler
import no.nav.please.varsler.awaitAuthentication
import org.slf4j.LoggerFactory
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
            logger.info("Opening websocket connection")
            var wsListener: WsListener? = null
            try {
                wsListener = awaitAuthentication(incoming, ticketHandler)
                addListener(wsListener)
                this.send("AUTHENTICATED")
                val wsSocketKey = this.call.request.header("Sec-WebSocket-Key")
                logger.info("Authenticated, Sec-WebSocket-Key $wsSocketKey")
                // Keep open until termination
                incoming.receive()
            } catch (e: ClosedReceiveChannelException) {
                val wsSocketKey = this.call.request.header("Sec-WebSocket-Key")
                logger.warn("onClose, Sec-WebSocket-Key $wsSocketKey, ${closeReason.await()}")
                wsListener?.let { removeListener(it) }
            } catch (e: Throwable) {
                val wsSocketKey = this.call.request.header("Sec-WebSocket-Key")
                logger.warn("onError, Sec-WebSocket-Key $wsSocketKey ${closeReason.await()}", e)
                wsListener?.let { removeListener(it) }
            } finally {
                logger.info("Closing websocket connection")
            }
        }
    }
}
