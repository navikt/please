package no.nav.please.varsler

import PubSubSubscribeConfigBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory

object IncomingDialogMessageFlow {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val messageFlow = MutableSharedFlow<String>() // No-replay, hot-flow
    private val isStartedState = MutableStateFlow(false)
    private var shuttingDown = false

    init {
        messageFlow.subscriptionCount
            .map { it != 0 }
            .distinctUntilChanged() // only react to true<->false changes
            .onEach { isActive -> // configure an action
                if (isActive) logger.info("MessageFlow received subscribers")
                else logger.info("MessageFlow has no subscribers") }
            .launchIn(CoroutineScope(Dispatchers.Default))
    }

    fun stop() {
        shuttingDown = true
    }
    fun flowOf(subscribeConfig: PubSubSubscribeConfigBuilder): MutableSharedFlow<String> {
        val coroutineScope = CoroutineScope(Dispatchers.IO)
        val handler = CoroutineExceptionHandler { thread, exception ->
            logger.error("Error in event flow coroutine:", exception)
        }

        logger.info("Setting up flow subscription...")
        coroutineScope.launch(handler) {
            logger.info("Launched coroutine for polling...")
            subscribeConfig
                .withScope(coroutineScope)
                .onMessage { message -> messageFlow.emit(message) }
                .onSubscribe {
                    logger.info("Successfully subscribed to redis")
                    isStartedState.emit(true)
                }

                .onUnsubscribe { isStartedState.emit(false) }
                .startSubscribeLoop()
        }

        runBlocking { isStartedState.first { isStarted -> isStarted } }
        return messageFlow
    }

    fun isSubscribedToRedisPubSub(): Boolean {
        return isStartedState.value
    }
}


