package no.nav.please.varsler

import arrow.core.Either
import arrow.core.computations.ResultEffect.bind
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import kotlinx.serialization.Serializable
import no.nav.please.plugins.*
import redis.clients.jedis.exceptions.JedisException
import java.util.*

@Serializable
data class TicketRequest(
    val subscriptionKey: String,
    val events: List<EventType>? = EventType.entries
)

sealed class ConnectionTicket {
    companion object {
        fun of(value: String): Either<InvalidTicket, WellFormedTicket> {
            return runCatching { UUID.fromString(value) }
                .getOrNull()?.let { WellFormedTicket(it.toString()).right() }
                ?: InvalidTicket.left()
        }
    }
}
class WellFormedTicket(val value: String): ConnectionTicket()
class ValidatedTicket(val value: String, val subscription: Subscription): ConnectionTicket()
data object InvalidTicket: ConnectionTicket()

interface TicketStore {
    fun getSubscription(ticket: WellFormedTicket): Either<GetSubscriptionError, Subscription>
    fun addSubscription(token: WellFormedTicket, ticket: Subscription): Either<TicketStoreError, Unit>
    fun removeSubscription(ticket: WellFormedTicket): Either<TicketStoreError, Unit>
}

sealed interface ConsumeTicketError
class TicketHandlerSubscriptionNotFoundError(val ticket: WellFormedTicket) : ConsumeTicketError
class TicketHandlerRedisError(val jedisError: JedisException) : ConsumeTicketError, GenerateTicketError
class TicketHandlerUnknownError(val error: Throwable) : ConsumeTicketError, GenerateTicketError
fun GetSubscriptionError.toConsumeTicketError(): ConsumeTicketError {
    return when (this) {
        is SubscriptionNotFoundError -> TicketHandlerSubscriptionNotFoundError(this.ticket)
        is JedisTicketStoreError -> TicketHandlerRedisError(this.jedisError)
        is UknownTicketStoreError -> TicketHandlerUnknownError(this.error)
    }
}

sealed interface GenerateTicketError
fun TicketStoreError.toGenerateTicketError(): GenerateTicketError {
    return when (this) {
        is JedisTicketStoreError -> TicketHandlerRedisError(this.jedisError)
        is UknownTicketStoreError -> TicketHandlerUnknownError(this.error)
    }
}

class WsTicketHandler(private val ticketStore: TicketStore) {
    // TODO: Only allow 1 ticket per sub
    fun consumeTicket(ticket: WellFormedTicket): Either<ConsumeTicketError, ValidatedTicket> {
        return either {
            val subscription = ticketStore.getSubscription(ticket).bind()
            ValidatedTicket(ticket.value, subscription)
        }.mapLeft { it.toConsumeTicketError() }
    }
    fun generateTicket(subject: String, payload: TicketRequest): Either<GenerateTicketError, WellFormedTicket> {
        return either {
            val ticket = WellFormedTicket(UUID.randomUUID().toString())
            val subscription = Subscription(subject ,ticket.value, payload.subscriptionKey, payload.events ?: EventType.entries)
            ticketStore.addSubscription(ticket, subscription).bind()
            ticket
        }.mapLeft { it.toGenerateTicketError() }
    }
}