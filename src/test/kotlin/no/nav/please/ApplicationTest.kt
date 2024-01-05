package no.nav.please

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import io.ktor.websocket.*

import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import no.nav.please.plugins.SocketResponse
import no.nav.please.varsler.EventType
import no.nav.please.varsler.IncomingDialogMessageFlow
import no.nav.please.varsler.WsConnectionHolder
import no.nav.security.mock.oauth2.MockOAuth2Server
import org.slf4j.LoggerFactory
import redis.embedded.RedisServer
import java.util.UUID


class ApplicationTest : StringSpec({
    lateinit var redisServer: RedisServer
    lateinit var testApp: TestApplication
    lateinit var client: HttpClient
    beforeSpec {
        testApp = TestApplication {
            environment { doConfig() }
            application { module() }
        }
        client = testApp.createClient {
            install(WebSockets)
        }
        redisServer = RedisServer(6379)
        redisServer.start()
    }
    afterSpec {
        testApp.stop()
        IncomingDialogMessageFlow.stop()
        server.shutdown()
        redisServer.stop()
    }

    "should notify subscribers" {
        val veileder1 = "Z123123"
        val subscriptionKey1 = "12345678910"

        val veileder2 = "Z321321"
        val subscriptionKey2 = "11111178910"

        val veileder1token = client.getWsToken(subscriptionKey1, veileder1)
        val veileder2token = client.getWsToken(subscriptionKey2, veileder2)

        WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe 0

        client.webSocket("/ws") {
            awaitAuthInTest(veileder1token)
            logger.info("Posting to veilarbdialog for test-subscriptionKey 1")
            receiveAfter {
                client.notifySubscribers(subscriptionKey1, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
            } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
            logger.info("Received message, closing websocket for subscriptionKey 1")
            WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe 1
            close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
        }
        client.webSocket("/ws") {
            awaitAuthInTest(veileder2token)
            logger.info("Posting to veilarbdialog for test-subscriptionKey 2")
            receiveAfter {
                client.notifySubscribers(subscriptionKey2, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
            } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
            logger.info("Received message, closing websocket for subscriptionKey 2")
            close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
        }

    }

    "WsConnectionHolder should count correctly" {
        val veileder1 = "Z123123"
        val subscriptionKey1 = "12345678910"
        val countBefore = WsConnectionHolder.dialogListeners.values.sumOf { it.size }
        val veileder1token = client.getWsToken(subscriptionKey1, veileder1)
        client.webSocket("/ws") {
            awaitAuthInTest(veileder1token)
            WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe countBefore + 1
        }
        WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe countBefore

        client.webSocket("/ws") {
            send(Frame.Text("LOL"))
            WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe countBefore
        }
        WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe countBefore
    }

    "should reestablish websocket and reuse subscription" {
        val veileder1 = "Z123123"
        val subscriptionKey1 = "12345678911"
        val veileder1token = client.getWsToken(subscriptionKey1, veileder1)

        client.webSocket("/ws") {
            awaitAuthInTest(veileder1token)
            logger.info("Posting to veilarbdialog for test-subscriptionKey 1")
            receiveAfter {
                client.notifySubscribers(subscriptionKey1, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
            } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
            logger.info("Received message, closing websocket for subscriptionKey 1")
            close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
        }
        client.webSocket("/ws") {
            logger.info("Reestablish session with same token")
            awaitAuthInTest(veileder1token)
            receiveAfter {
                client.notifySubscribers(subscriptionKey1, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
            } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
            logger.info("Posting to veilarbdialog for test-subscriptionKey 1")
            logger.info("Received message, closing websocket for subscriptionKey 1")
            close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
        }
    }

    "should be able to subscribe to selected events" {
        val person = "123123123"
        val veileder = "Z223123"
        val onlyBrukerTilNav = client.getWsToken(person, veileder, listOf(EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV))
        val veileder2 = "Z223124"
        val allEvents = client.getWsToken(person, veileder2)

        client.webSocket("/ws") {
            awaitAuthInTest(allEvents)
            receiveAfter {
                client.webSocket("/ws") {
                    awaitAuthInTest(onlyBrukerTilNav)
                    receiveAfter {
                        client.notifySubscribers(person, EventType.NY_DIALOGMELDING_FRA_NAV_TIL_BRUKER)
                        client.notifySubscribers(person, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
                    } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
                }
            } shouldBe EventType.NY_DIALOGMELDING_FRA_NAV_TIL_BRUKER
        }
    }

    "should fail on invalid subscription" {
        val veileder = "Z123123"
        val subscriptionKey = "12345678911"
        val token = client.getWsToken(subscriptionKey, veileder)
        client.webSocket("/ws") {
            send(Frame.Text("LOL"))
            (incoming.receive() as Frame.Text).readText() shouldBe SocketResponse.INVALID_TOKEN.name
            send(Frame.Text(UUID.randomUUID().toString()))
            (incoming.receive() as Frame.Text).readText() shouldBe SocketResponse.INVALID_TOKEN.name
            send(Frame.Text(token))
            (incoming.receive() as Frame.Text).readText() shouldBe SocketResponse.AUTHENTICATED.name
        }
    }

    }) {

    companion object {
        private val logger = LoggerFactory.getLogger(javaClass)
        val server: MockOAuth2Server by lazy {
            MockOAuth2Server()
                .also { it.start() }
        }

        const val testTopic = "ny-dialog-topic-i-test"

        private fun ApplicationEngineEnvironmentBuilder.doConfig(
            acceptedIssuer: String = "default",
            acceptedAudience: String = "default"
        ) {
            config = MapApplicationConfig(
                "no.nav.security.jwt.issuers.size" to "1",
                "no.nav.security.jwt.issuers.0.issuer_name" to acceptedIssuer,
                "no.nav.security.jwt.issuers.0.discoveryurl" to "${server.wellKnownUrl(acceptedIssuer)}",
                "no.nav.security.jwt.issuers.0.accepted_audience" to acceptedAudience,
                "topic.ny-dialog" to testTopic,
                "redis.host" to "rediss://localhost:6379",
                "redis.channel" to "dab.dialog-events-v1"
            )
        }
    }
}

suspend fun DefaultClientWebSocketSession.awaitAuthInTest(token: String) {
    val logger = LoggerFactory.getLogger(javaClass)
    logger.info("Sending authtoken on websocket")
    send(Frame.Text(token))
    val authAck = (incoming.receive() as? Frame.Text)?.readText() ?: ""
    logger.info("Received auth-ack")
    authAck shouldBe "AUTHENTICATED"
}
suspend fun DefaultClientWebSocketSession.receiveStringWithTimeout(): String {
    return withTimeout(50000) {
        (incoming.receive() as? Frame.Text)?.readText() ?: ""
    }
}

suspend fun DefaultClientWebSocketSession.receiveAfter(block: suspend () -> Unit): EventType {
    val deferredResult = async { receiveStringWithTimeout() }
    block()
    return Json.decodeFromString<EventType>(deferredResult.await())
}

fun getTicketBody(subscriptionKey: String, events: List<EventType>?): String {
    return if (events == null) """{ "subscriptionKey": "$subscriptionKey" }"""
    else """{ "subscriptionKey": "$subscriptionKey", "events": [${events.map { "\"${it}\"" }.joinToString(",")}] }"""
}

suspend fun HttpClient.getWsToken(subscriptionKey: String, sub: String, events: List<EventType>? = null) : String {
    val authToken = this.post("/ws-auth-ticket") {
        bearerAuth(ApplicationTest.server.issueToken(subject = sub).serialize())
        contentType(ContentType.Application.Json)
        setBody(getTicketBody(subscriptionKey, events))
    }.bodyAsText()
    authToken.shouldNotBeEmpty()
    authToken shouldNotBe null
    return authToken
}

suspend fun HttpClient.notifySubscribers(subscriptionKey: String, eventType: EventType) {
    this.post("/notify-subscribers") {
        bearerAuth(ApplicationTest.server.issueToken(subject = "Z123123").serialize())
        contentType(ContentType.Application.Json)
        setBody("""{ "subscriptionKey": "$subscriptionKey", "eventType": "${eventType.name}" }""")
    }.status shouldBe HttpStatusCode.OK
}