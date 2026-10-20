package no.nav.please.varsler

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import no.nav.please.plugins.Metrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

typealias SubscriptionKey = String

@Serializable
data class Subscription(
    val sub: String,
    val connectionTicket: String,
    val subscriptionKey: SubscriptionKey,
    val events: List<EventType>
)

data class WsListener(
    val wsSession: DefaultWebSocketServerSession,
    val subscription: Subscription
)

object WsConnectionHolder {
    val dialogListeners = ConcurrentHashMap<SubscriptionKey, List<WsListener>>()
    val numConnectionMetric: AtomicInteger = Metrics.registry.gauge(
        "active_websocket_connections",
        AtomicInteger(0)
    )!!

    fun addListener(wsListener: WsListener) {
        val currentSubscriptions = dialogListeners[wsListener.subscription.subscriptionKey]
        val newWsListeners: List<WsListener> = currentSubscriptions
            ?.let { it + listOf(wsListener) } ?: listOf(wsListener)
        dialogListeners[wsListener.subscription.subscriptionKey] = newWsListeners
        logger.info("Adding new listener")
        numConnectionMetric.incrementAndGet()
    }
    suspend fun removeListener(wsListener: WsListener) {
        val currentSubscriptions = dialogListeners[wsListener.subscription.subscriptionKey]
        val newWsListeners: List<WsListener> = currentSubscriptions
            ?.filter { it.subscription != wsListener.subscription } ?: emptyList()
        if (newWsListeners.isEmpty()) {
            dialogListeners.remove(wsListener.subscription.subscriptionKey)
        } else {
            dialogListeners[wsListener.subscription.subscriptionKey] = newWsListeners
        }
        wsListener.wsSession.close(CloseReason(CloseReason.Codes.GOING_AWAY,"unsubscribing"))
        if (currentSubscriptions != null && newWsListeners.size < currentSubscriptions.size) {
            numConnectionMetric.decrementAndGet()
        }
    }
}
