package no.nav.please.varsler

import kotlinx.serialization.Serializable
import java.lang.IllegalArgumentException
import java.util.*

@Serializable
data class TicketRequest(
    val subscriptionKey: String,
)

sealed class ConnectionTicket {
    companion object {
        fun of(value: String): ConnectionTicket {
            return runCatching { UUID.fromString(value) }
                .getOrNull()?.let { WellFormedTicket(it.toString()) }
                ?: InvalidTicket
        }
    }
}
class WellFormedTicket(val value: String): ConnectionTicket()
data object InvalidTicket: ConnectionTicket()

interface TicketStore {
    fun getSubscription(ticket: WellFormedTicket): Subscription?
    fun addSubscription(token: WellFormedTicket, ticket: Subscription)
    fun removeSubscription(ticket: WellFormedTicket)
}

class WsTicketHandler(private val ticketStore: TicketStore) {
    // TODO: Only allow 1 ticket per sub
    fun consumeTicket(ticket: WellFormedTicket): Subscription {
        return ticketStore.getSubscription(ticket)
            ?: throw IllegalArgumentException("Invalid connection ticket")
    }
    fun generateTicket(subject: String, payload: TicketRequest): WellFormedTicket {
        return WellFormedTicket(UUID.randomUUID().toString())
            .also { ticketStore.addSubscription(it, Subscription(subject ,it.value, payload.subscriptionKey)) }
    }
}