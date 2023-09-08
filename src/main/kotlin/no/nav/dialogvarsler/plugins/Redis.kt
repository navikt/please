package no.nav.dialogvarsler.plugins

import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.dialogvarsler.NyDialogFlow
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub

fun Application.configureRedis(): (NyDialogNotification) -> Long {
    val logger = LoggerFactory.getLogger(Application::class.java)

    val config = this.environment.config
    val host = config.property("redis.host").getString()
    val username = config.propertyOrNull("redis.username")?.getString()
    val password = config.propertyOrNull("redis.password")?.getString()
    val channel = config.property("redis.channel")?.getString()

    val poolConfig = JedisPoolConfig()
    val jedisPool = when {
        username != null && password != null -> JedisPool(poolConfig, host, 6379, username, password)
        else -> JedisPool(poolConfig, host, 6379)
    }

    val subscribe = { scope: CoroutineScope, onMessage: suspend (message: String) -> Unit ->
        val eventHandler = object : JedisPubSub() {
            override fun onMessage(channel: String?, message: String?) {
                if (message == null) return
                scope.launch { onMessage(message) }
            }
        }
        jedisPool.resource.subscribe(eventHandler, channel)
    }

    NyDialogFlow.flowOf(subscribe)
    return { message: NyDialogNotification -> jedisPool.resource.publish(channel, Json.encodeToString(message))
        .also { receivers -> logger.info("Message delivered to $receivers receivers") }
    }
}