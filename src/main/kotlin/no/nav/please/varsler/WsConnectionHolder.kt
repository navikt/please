package no.nav.please.varsler

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import no.nav.please.plugins.Metrics
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

typealias SubscriptionKey = String
//typealias ConnectionTicket = String

@Serializable
data class Subscription(
    val sub: String,
    val connectionTicket: String,
    val subscriptionKey: SubscriptionKey
)

data class WsListener(
    val wsSession: DefaultWebSocketServerSession,
    val subscription: Subscription
)

object WsConnectionHolder {
    val dialogListeners = Collections.synchronizedMap(mutableMapOf<SubscriptionKey, List<WsListener>>())
    val numConnectionMetric: AtomicInteger = Metrics.registry.gauge(
        "active_websocket_connections",
        AtomicInteger(0)
    )!!

    fun addListener(wsListener: WsListener) {
        val currentSubscriptions = dialogListeners[wsListener.subscription.subscriptionKey]
        val newWsListeners: List<WsListener> = currentSubscriptions
            ?.let { it + listOf(wsListener) } ?: listOf(wsListener)
        dialogListeners[wsListener.subscription.subscriptionKey] = newWsListeners
        logger.info("Adding new listener, total listeners: ${dialogListeners.values.sumOf { it.size }}")
        numConnectionMetric.incrementAndGet()
    }
    fun removeListener(wsListener: WsListener) {
        val currentSubscriptions = dialogListeners[wsListener.subscription.subscriptionKey]
        val newWsListeners: List<WsListener> = currentSubscriptions
            ?.filter { it.subscription != wsListener.subscription } ?: emptyList()
        dialogListeners[wsListener.subscription.subscriptionKey] = newWsListeners
        runBlocking {
            wsListener.wsSession.close(CloseReason(CloseReason.Codes.GOING_AWAY,"unsubscribing"))
        }
        numConnectionMetric.decrementAndGet()
    }
}