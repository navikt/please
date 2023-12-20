package no.nav.please.varsler

import io.ktor.websocket.*
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.please.plugins.Metrics
import org.slf4j.LoggerFactory

enum class EventType {
    NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV,
    NY_DIALOGMELDING_FRA_NAV_TIL_BRUKER
}
@Serializable
data class DialogHendelse(
    val eventType: EventType,
    val subscriptionKey: String
)

object DialogNotifier {
    private val logger = LoggerFactory.getLogger(javaClass)
    suspend fun notifySubscribers(messageString: String) {
        runCatching {
            val event = Json.decodeFromString<DialogHendelse>(messageString)
            val websocketMessage = Json.encodeToString(event.eventType)

            WsConnectionHolder.dialogListeners[event.subscriptionKey]
                ?.also { if (it.isNotEmpty()) {
                    logger.info("Delivering message to ${it.size} receivers")
                } }
                ?.forEach {
                    if (it.wsSession.isActive) {
                        it.wsSession.send(websocketMessage)
                        Metrics.registry.counter("websocketevent_delivered", "eventtype", websocketMessage).increment()
                        logger.info("Message delivered")
                    } else {
                        logger.warn("WS session was not active, could not deliver message")
                        WsConnectionHolder.removeListener(it)
                    }
                }
        }.onFailure { error ->
            logger.warn("Failed to notify subscribers", error)
        }
    }
}