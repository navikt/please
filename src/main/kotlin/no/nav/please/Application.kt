package no.nav.please

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

fun Application.module() {
    configureAuthentication()
    configureMonitoring()
    configureMicrometer()
    configureSerialization()
    val getMachineToMachineToken = configureTokenProvider()
    val verifyAuthorization = configureAuthorization(getMachineToMachineToken)
    val (publishMessage, pingRedis, ticketStore) = configureRedis()
    val ticketHandler = WsTicketHandler(ticketStore)
    configureSockets(ticketHandler)
    configureRouting(publishMessage, pingRedis, ticketHandler, verifyAuthorization)
}
