package no.nav.please.plugins

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.please.retry.Retry.Companion.withRetry
import no.nav.please.varsler.Subscription
import no.nav.please.varsler.TicketStore
import no.nav.please.varsler.WellFormedTicket
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.exceptions.JedisException

sealed interface TicketStoreError: GetSubscriptionError
class JedisTicketStoreError(val operationDescription: String, val jedisError: JedisException): TicketStoreError
class UknownTicketStoreError(val operationDescription: String,val error: Throwable): TicketStoreError

// Get subscription errors
sealed interface GetSubscriptionError
class SubscriptionNotFoundError(val ticket: WellFormedTicket): GetSubscriptionError


fun Throwable.toTicketStoreErrorCause(operationDescription: String): TicketStoreError {
    return when (this) {
        is JedisException -> JedisTicketStoreError(operationDescription,this)
        else -> UknownTicketStoreError(operationDescription,this)
    }
}

fun String?.notNullOrSubscriptionNotFoundError(ticket: WellFormedTicket): Either<SubscriptionNotFoundError ,String> {
    return this?.right() ?: SubscriptionNotFoundError(ticket).left()
}
fun String.jsonDecodeWitheEither(): Either<TicketStoreError, Subscription> {
    return Either.catch {
        Json.decodeFromString<Subscription>(this)
    }.mapLeft { it.toTicketStoreErrorCause("get subscription (json decode error)") }
}

val logger = LoggerFactory.getLogger(RedisTicketStore::class.java)
class RedisTicketStore(val jedis: JedisPooled): TicketStore {
    override suspend fun getSubscription(ticket: WellFormedTicket): Either<GetSubscriptionError, Subscription> {
        return either {
            val subscriptionRaw = withRetry { jedis[ticket.value] }
                .mapLeft { it.latestException.toTicketStoreErrorCause("get subscription") }.bind()
            val notNullSubscription = subscriptionRaw.notNullOrSubscriptionNotFoundError(ticket).bind()
            notNullSubscription.jsonDecodeWitheEither().bind()
        }
    }

    override suspend fun addSubscription(ticket: WellFormedTicket, subscription: Subscription): Either<TicketStoreError, Unit> {
        return withRetry {
                jedis.setex(ticket.value, 3600*6, Json.encodeToString(subscription))
                Unit
            }.mapLeft { it.latestException.toTicketStoreErrorCause("add subscription") }
    }

    override suspend fun removeSubscription(ticket: WellFormedTicket): Either<TicketStoreError, Unit> {
        return withRetry {
            jedis.del(ticket.value)
            Unit
        }.mapLeft { it.latestException.toTicketStoreErrorCause("remove subscription") }
    }
}
