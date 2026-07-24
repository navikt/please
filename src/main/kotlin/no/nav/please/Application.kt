package no.nav.please

import io.ktor.client.HttpClient
import io.ktor.server.application.*
import io.ktor.server.netty.*
import no.nav.please.plugins.*
import no.nav.please.varsler.WsTicketHandler
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    EngineMain.main(args)
    val logger = LoggerFactory.getLogger(Application::class.java)
    Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
        logger.error("Uncaught exception i thread: ${thread.name}", exception)
    }
}

fun Application.module(
    client: HttpClient? = null,
) {
    configureAuthentication()
    configureMonitoring()
    configureMicrometer()
    configureSerialization()
    val machineTokenProvider = client?.let { MachineToMachineTokenProvider(this.environment.config, it) }
        ?: MachineToMachineTokenProvider(this.environment.config)
    val verifyAuthorization = client?.let { configureAuthorization( machineTokenProvider::getAccessToken, client) }
        ?: configureAuthorization( machineTokenProvider::getAccessToken)
    val (publishMessage, pingRedis, ticketStore) = configureRedis()
    val ticketHandler = WsTicketHandler(ticketStore)
    configureSockets(ticketHandler)
    configureRouting(publishMessage, pingRedis, ticketHandler, verifyAuthorization)
}
