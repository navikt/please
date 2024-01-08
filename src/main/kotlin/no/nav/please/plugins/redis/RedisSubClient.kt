package no.nav.please.plugins.redis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nav.please.varsler.DialogNotifier
import no.nav.please.varsler.IncomingDialogMessageFlow
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub

/*
* https://redis.io/docs/interact/pubsub/
* A client subscribed to one or more channels shouldn't issue commands, although it can SUBSCRIBE and UNSUBSCRIBE to and from other channels.
* */
object RedisSubClient {
    fun start(jedis: JedisPooled, channel: String) {
        val subscribe = { scope: CoroutineScope, onMessage: suspend (message: String) -> Unit ->
            val eventHandler = object : JedisPubSub() {
                override fun onMessage(channel: String?, message: String?) {
                    if (message == null) return
                    scope.launch { onMessage(message) }
                }

                override fun onUnsubscribe(channel: String?, subscribedChannels: Int) {
                    super.onUnsubscribe(channel, subscribedChannels)
                    log.info("Received unsubscribe")
                    jedis.subscribe(this, channel)
                    log.info("Re-subscribed after unsubscribe")
                }
            }
            jedis.subscribe(eventHandler, channel)
        }
        IncomingDialogMessageFlow.flowOf(subscribe)
            .onEach { DialogNotifier.notifySubscribers(it) }
            .launchIn(CoroutineScope(Dispatchers.IO))
    }
}
