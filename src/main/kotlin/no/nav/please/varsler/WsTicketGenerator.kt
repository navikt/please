package no.nav.please.varsler

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.valkey.exceptions.JedisException
import kotlinx.serialization.Serializable
import no.nav.please.errorUtil.LoggableError
import no.nav.please.plugins.*
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
    suspend fun getSubscription(ticket: WellFormedTicket): Either<GetSubscriptionError, Subscription>
    suspend fun addSubscription(token: WellFormedTicket, ticket: Subscription): Either<TicketStoreError, Unit>
    suspend fun removeSubscription(ticket: WellFormedTicket): Either<TicketStoreError, Unit>
}

sealed interface ConsumeTicketError
class TicketHandlerSubscriptionNotFoundError(val ticket: WellFormedTicket) : ConsumeTicketError
class TicketHandlerRedisError(val operationDescription: String, val jedisError: JedisException) : ConsumeTicketError, GenerateTicketError {
    override fun log() {
        logger.error("Failed to generateTicket ($operationDescription) because of redis error", jedisError)
    }
}

class TicketHandlerUnknownError(val operationDescription: String, val error: Throwable) : ConsumeTicketError, GenerateTicketError {
    override fun log() {
        logger.error("Failed to generateTicket ($operationDescription) because of unknown error", error)
    }
}

fun GetSubscriptionError.toConsumeTicketError(): ConsumeTicketError {
    return when (this) {
        is SubscriptionNotFoundError -> TicketHandlerSubscriptionNotFoundError(this.ticket)
        is JedisTicketStoreError -> TicketHandlerRedisError(this.operationDescription, this.jedisError)
        is UknownTicketStoreError -> TicketHandlerUnknownError(this.operationDescription, this.error)
    }
}

sealed interface GenerateTicketError: LoggableError
fun TicketStoreError.toGenerateTicketError(): GenerateTicketError {
    return when (this) {
        is JedisTicketStoreError -> TicketHandlerRedisError(this.operationDescription, this.jedisError)
        is UknownTicketStoreError -> TicketHandlerUnknownError(this.operationDescription, this.error)
    }
}

class WsTicketHandler(private val ticketStore: TicketStore) {
    // TODO: Only allow 1 ticket per sub
    suspend fun consumeTicket(ticket: WellFormedTicket): Either<ConsumeTicketError, ValidatedTicket> {
        return either {
            val subscription = ticketStore.getSubscription(ticket).bind()
            ValidatedTicket(ticket.value, subscription)
        }.mapLeft { it.toConsumeTicketError() }
    }
    suspend fun generateTicket(subject: String, payload: TicketRequest): Either<GenerateTicketError, WellFormedTicket> {
        return either {
            val ticket = WellFormedTicket(UUID.randomUUID().toString())
            val subscription = Subscription(subject ,ticket.value, payload.subscriptionKey, payload.events ?: EventType.entries)
            ticketStore.addSubscription(ticket, subscription).bind()
            ticket
        }.mapLeft { it.toGenerateTicketError() }
    }
}