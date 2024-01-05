package no.nav.please.plugins.redis

import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.please.plugins.NyDialogNotification
import no.nav.please.varsler.*
import org.slf4j.LoggerFactory
import redis.clients.jedis.*
import redis.clients.jedis.Protocol.Command

typealias PublishMessage = (NyDialogNotification) -> Long
typealias PingRedis = () -> String
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

    val subscribe = { scope: CoroutineScope, onMessage: suspend (message: String) -> Unit ->
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
        }
        jedisPool.subscribe(eventHandler, channel)
    }

    IncomingDialogMessageFlow.flowOf(subscribe)
        .onEach { DialogNotifier.notifySubscribers(it) }
        .launchIn(CoroutineScope(Dispatchers.IO))

    val publishMessage: PublishMessage = { message: NyDialogNotification ->
        logger.info("Publishing messages")
        val numReceivers = jedisPool.publish(channel, Json.encodeToString(message))
        log.info("Published to $numReceivers")
        numReceivers
    }

    val pingRedis: PingRedis = {
        val result = jedisPool.sendCommand(Command.PUBSUB, Protocol.Keyword.NUMSUB.name, channel) as ArrayList<*>
        log.info("Subscribers to channel: ${result[1]}")
        jedisPool.ping()
    }

    return Triple(publishMessage, pingRedis, RedisTicketStore(jedisPool))
}
