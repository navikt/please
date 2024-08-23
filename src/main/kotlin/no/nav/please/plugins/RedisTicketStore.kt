package no.nav.please.plugins

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.please.varsler.Subscription
import no.nav.please.varsler.TicketStore
import no.nav.please.varsler.WellFormedTicket
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.exceptions.JedisException

sealed interface TicketStoreError: GetSubscriptionError
class JedisTicketStoreError(val jedisError: JedisException): TicketStoreError
class UknownTicketStoreError(val error: Throwable): TicketStoreError

// Get subscription errors
sealed interface GetSubscriptionError
class SubscriptionNotFoundError(val ticket: WellFormedTicket): GetSubscriptionError


fun Throwable.toTicketStoreErrorCause(): TicketStoreError {
    return when (this) {
        is JedisException -> JedisTicketStoreError(this)
        else -> UknownTicketStoreError(this)
    }
}

fun <T> catchRedisErrors(operationDescription: String, block: () -> T): Either<TicketStoreError, T> {
    return try {
        block().right()
    } catch (e: Throwable) {
        e.toTicketStoreErrorCause().left()
    }
}

val logger = LoggerFactory.getLogger(RedisTicketStore::class.java)
class RedisTicketStore(val jedis: JedisPooled): TicketStore {
    override fun getSubscription(ticket: WellFormedTicket): Either<GetSubscriptionError, Subscription> {
        return catchRedisErrors("get subscription") {
            jedis[ticket.value]?.let { Json.decodeFromString<Subscription>(it) }
        }
            .fold({ it.left() }) {
                it?.right() ?: SubscriptionNotFoundError(ticket).left()
            }
    }

    override fun addSubscription(ticket: WellFormedTicket, subscription: Subscription): Either<TicketStoreError, Unit> {
        return catchRedisErrors("add subscription") {
            jedis.setex(ticket.value, 3600*6, Json.encodeToString(subscription))
            Unit
        }
    }

    override fun removeSubscription(ticket: WellFormedTicket): Either<TicketStoreError, Unit> {
        return catchRedisErrors("remove subscription") {
            jedis.del(ticket.value)
            Unit
        }
    }
}