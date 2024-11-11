package no.nav.please.plugins

import arrow.core.Either
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.please.retry.MaxRetryError
import no.nav.please.retry.Retry
import no.nav.please.varsler.*
import org.slf4j.LoggerFactory
import redis.clients.jedis.*
import redis.clients.jedis.exceptions.JedisConnectionException

typealias PublishMessage = suspend (NyDialogNotification) -> Either<MaxRetryError, Long>
typealias PingRedis = suspend () -> Either<MaxRetryError, String>
fun Application.configureRedis(): Triple<PublishMessage, PingRedis, TicketStore> {
    val logger = LoggerFactory.getLogger(Application::class.java)

    val config = this.environment.config
    val hostAndPort = config.property("redis.host").getString().split("://").last()
    val username = config.propertyOrNull("redis.username")?.getString()
    val password = config.propertyOrNull("redis.password")?.getString()
    val channel = config.property("redis.channel").getString()

    val credentials = DefaultRedisCredentials(username, password)
    val credentialsProvider = DefaultRedisCredentialsProvider(credentials)
    val clientConfig: DefaultJedisClientConfig = DefaultJedisClientConfig.builder()
        .ssl(true)
        .credentialsProvider(credentialsProvider)
        .timeoutMillis(0)
        .build()


    val (host, port) = hostAndPort.split(":")
        .also { require(it.size >= 2) { "Malformed redis url" } }
    val redisHostAndPort = HostAndPort(host, port.toInt())
    log.info("Connecting to redis, host: $host port: $port user: $username channel: $channel")

    val jedisPool = when {
        username != null && password != null -> JedisPooled(redisHostAndPort, clientConfig)
        else -> {
            log.info("Fallback to local test connection (localhost) for redis")
            JedisPooled(host, 6379)
        }
    }

    suspend fun subscribeToRedisPubSub(scope: CoroutineScope, onMessage: suspend (message: String) -> Unit, onSubscribe: suspend () -> Unit): Unit {
        val eventHandler = object : JedisPubSub() {
            override fun onMessage(channel: String?, message: String?) {
                if (message == null) return
                scope.launch { onMessage(message) }
            }

            override fun onUnsubscribe(channel: String?, subscribedChannels: Int) {
                super.onUnsubscribe(channel, subscribedChannels)
                log.info("Received unsubscribe")
                jedisPool.subscribe(this, channel)
                log.info("Re-subscribed after unsubscribe")
            }

            override fun onSubscribe(channel: String?, subscribedChannels: Int) {
                super.onSubscribe(channel, subscribedChannels)
                runBlocking { onSubscribe() }
            }
        }
        retryHangingFunction(maxRetries = 3, currentRetry = 0) {
            jedisPool.subscribe(eventHandler, channel)
        }
    }

    IncomingDialogMessageFlow.flowOf(::subscribeToRedisPubSub)
        .onEach { DialogNotifier.notifySubscribers(it) }
        .launchIn(CoroutineScope(Dispatchers.IO))

    val publishMessage: PublishMessage = { message: NyDialogNotification ->
        logger.info("Publishing messages")
        Retry.withRetry {
            jedisPool.publish(channel, Json.encodeToString(message))
        }
            .onLeft { log.error("Failed to publish message to redis", it.latestException) }
            .onRight { numReceivers -> log.info("Published to $numReceivers") }
    }
    val pingRedis: PingRedis = {
        Retry.withRetry {
            jedisPool.ping()
        }
    }

    return Triple(publishMessage, pingRedis, RedisTicketStore(jedisPool))
}

suspend fun retryHangingFunction(maxRetries: Int, currentRetry: Int = 0, exception: Exception? = null, nonReturningFunction: () -> Unit) {
    if (currentRetry >= maxRetries) {
        exception?.let { throw it } ?: throw IllegalStateException("Could not subscribe to redis pubsub after $maxRetries retries")
    }
    try {
        nonReturningFunction()
        // If this line is reached, there are no subscriptions in redis which is wrong, retry
        return retryHangingFunction(maxRetries, currentRetry + 1, null, nonReturningFunction)
    } catch (jedisException: JedisConnectionException) {
        // Try again on jedis exception
        return retryHangingFunction(maxRetries, currentRetry + 1, jedisException, nonReturningFunction)
    }
}
