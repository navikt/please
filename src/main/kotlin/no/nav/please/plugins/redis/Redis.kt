package no.nav.please.plugins.redis

import io.ktor.server.application.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.please.plugins.NyDialogNotification
import no.nav.please.varsler.*
import org.slf4j.LoggerFactory
import redis.clients.jedis.*
import redis.clients.jedis.Protocol.Command

typealias PublishMessage = (NyDialogNotification) -> Long
typealias PingRedis = () -> String

val log = LoggerFactory.getLogger("Redis")

fun Application.configureRedis(): Triple<PublishMessage, PingRedis, TicketStore> {
    val logger = LoggerFactory.getLogger(Application::class.java)

    val redisConfig = getRedisConfig()
    val channel = redisConfig.channel
    // Create separate client for subscription
    RedisSubClient.start(redisConfig.connect(), channel)
    val jedisPool = redisConfig.connect()

    val publishMessage: PublishMessage = { message: NyDialogNotification ->
        logger.info("Publishing messages")
        val numReceivers = jedisPool.publish(channel, Json.encodeToString(message))
        log.info("Published to $numReceivers")
        numReceivers
    }

    val pingRedis: PingRedis = {
        val result = jedisPool.sendCommand(Command.PUBSUB, Protocol.Keyword.NUMSUB.name, channel) as ArrayList<*>
        val numberOfSubscribers = result[1].toString().toIntOrNull()
        log.info("Subscribers to channel: ${numberOfSubscribers}")
        jedisPool.ping()
    }

    return Triple(publishMessage, pingRedis, RedisTicketStore(jedisPool))
}

fun RedisConfig.connect(): JedisPooled {
    return when {
        isLocal -> JedisPooled(hostAndPort, clientConfig)
        else -> {
            log.info("Fallback to local test connection (localhost) for redis")
            JedisPooled(hostAndPort.host, 6379)
        }
    }
}
