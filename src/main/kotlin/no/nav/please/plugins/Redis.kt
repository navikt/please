package no.nav.please.plugins

import PubSubSubscribeConfigBuilder
import PubsubConfigArgs
import arrow.core.Either
import io.ktor.server.application.*
import io.valkey.DefaultJedisClientConfig
import io.valkey.DefaultRedisCredentials
import io.valkey.DefaultRedisCredentialsProvider
import io.valkey.HostAndPort
import io.valkey.JedisPooled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.please.retry.MaxRetryError
import no.nav.please.retry.Retry
import no.nav.please.varsler.*
import org.slf4j.LoggerFactory

typealias PublishMessage = suspend (NyDialogNotification) -> Either<MaxRetryError, Long>
typealias PingRedis = suspend () -> Either<MaxRetryError, String>
fun Application.configureRedis(): Triple<PublishMessage, PingRedis, TicketStore> {
    val logger = LoggerFactory.getLogger(Application::class.java)

    val config = this.environment.config
    val hostAndPort = config.property("valkey.host").getString().split("://").last()
    val username = config.propertyOrNull("valkey.username")?.getString()
    val password = config.propertyOrNull("valkey.password")?.getString()
    val channel = config.property("valkey.channel").getString()

    val credentials = DefaultRedisCredentials(username, password)
    val credentialsProvider = DefaultRedisCredentialsProvider(credentials)
    val clientConfig: DefaultJedisClientConfig = DefaultJedisClientConfig.builder()
        .ssl(true)
        .credentialsProvider(credentialsProvider)
        .timeoutMillis(0)
        .build()

    val (host, port) = hostAndPort.split(":").also { require(it.size >= 2) { "Malformed redis url" } }
    val redisHostAndPort = HostAndPort(host, port.toInt())
    log.info("Connecting to redis, host: $host port: $port user: $username channel: $channel")

    val jedisPool = when {
        username != null && password != null -> JedisPooled(redisHostAndPort, clientConfig)
        else -> {
            log.info("Fallback to local test connection (localhost) for redis")
            JedisPooled(host, 6379)
        }
    }

    val subscribeConfigBuilder = PubSubSubscribeConfigBuilder(
        PubsubConfigArgs(
            jedisPool = jedisPool,
            channel = channel,
        )
    )

    IncomingDialogMessageFlow.flowOf(subscribeConfigBuilder)
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
