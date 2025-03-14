import io.ktor.server.application.log
import io.valkey.JedisPooled
import io.valkey.JedisPubSub
import io.valkey.exceptions.JedisConnectionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

typealias MessageHandler = suspend (message: String) -> Unit
typealias OnSubscribeHandler = suspend () -> Unit
typealias OnUnsubscribeHandler = suspend () -> Unit

data class PubsubConfigArgs(
    val jedisPool: JedisPooled,
    val channel: String
)

class PubSubSubscribeConfigBuilder(val jedisArgs: PubsubConfigArgs) {
    fun withScope(scope: CoroutineScope): RedisConfigWithScope {
        return RedisConfigWithScope(jedisArgs, scope)
    }
}

class RedisConfigWithScope(val jedisArgs: PubsubConfigArgs, val scope: CoroutineScope) {
    fun onMessage(onMessage: MessageHandler): RedisConfigWithMessage {
        return RedisConfigWithMessage(jedisArgs, scope, onMessage)
    }
}

class RedisConfigWithMessage(val jedisArgs: PubsubConfigArgs, val scope: CoroutineScope, val onMessage: MessageHandler) {
    fun onSubscribe(onSubscribe: OnSubscribeHandler): RedisFinishedConfig {
        return RedisFinishedConfig(jedisArgs, scope, onMessage, onSubscribe)
    }
}

class RedisFinishedConfig(val jedisArgs: PubsubConfigArgs, val scope: CoroutineScope, val onMessage: MessageHandler, val onSubscribe: OnSubscribeHandler) {
    fun onUnsubscribe(onUnsubscribeHandler: OnUnsubscribeHandler): JedisPubSubSubscription {
        return JedisPubSubSubscription(jedisArgs, scope, onMessage, onSubscribe, onUnsubscribeHandler)
    }
}

class JedisPubSubSubscription(val jedisArgs: PubsubConfigArgs, val scope: CoroutineScope, val onMessage: MessageHandler, val onSubscribe: OnSubscribeHandler, val onUnsubscribeHandler: OnUnsubscribeHandler) {
    private val log = LoggerFactory.getLogger(JedisPubSubSubscription::class.java)

    suspend fun startSubscribeLoop() {
        val eventHandler = object : JedisPubSub() {
            override fun onMessage(channel: String?, message: String?) {
                if (message == null) return
                scope.launch { onMessage(message) }
            }

            override fun onUnsubscribe(channel: String?, subscribedChannels: Int) {
                super.onUnsubscribe(channel, subscribedChannels)
                log.info("Received unsubscribe")
                runBlocking { onUnsubscribeHandler() }
            }

            override fun onSubscribe(channel: String?, subscribedChannels: Int) {
                super.onSubscribe(channel, subscribedChannels)
                runBlocking { onSubscribe() }
            }
        }
        retryHangingFunction(maxRetries = 3, currentRetry = 0) {
            jedisArgs.jedisPool.subscribe(eventHandler, jedisArgs.channel)
        }
    }
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
