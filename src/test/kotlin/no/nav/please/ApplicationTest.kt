package no.nav.please

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
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

    val wiremock = WireMockServer(9000)

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
        wiremock.start()
        mockAzureAdMachineTokenRequest(wiremock)
    }
    afterSpec {
        testApp.stop()
        redisServer.stop()
        IncomingDialogMessageFlow.stop()
        server.shutdown()
        wiremock.stop()
    }

    "should notify subscribers" {
        val veileder1 = "Z123123"
        val subscriptionKey1 = "12345678910"

        val veileder2 = "Z321321"
        val subscriptionKey2 = "11111178910"

        val veileder1token = client.getWsToken(subscriptionKey1, getAzureToken(veileder1))
        val veileder2token = client.getWsToken(subscriptionKey2, getAzureToken(veileder2))

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
        val veileder1token = client.getWsToken(subscriptionKey1, getAzureToken(veileder1))
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
        val veileder1token = client.getWsToken(subscriptionKey1, getAzureToken(veileder1))

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
        val veileder = "Z223123"
        val subscriptionKey = "123123123"
        val veiledertoken = client.getWsToken(subscriptionKey, getAzureToken(veileder), listOf(EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV))

        client.webSocket("/ws") {
            awaitAuthInTest(veiledertoken)
            receiveAfter {
                client.notifySubscribers(subscriptionKey, EventType.NY_DIALOGMELDING_FRA_NAV_TIL_BRUKER)
                client.notifySubscribers(subscriptionKey, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
            } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
            close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
        }
    }

    "should fail on invalid subscription" {
        val veileder = "Z123123"
        val subscriptionKey = "12345678911"
        val token = client.getWsToken(subscriptionKey, getAzureToken(veileder))
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
                "redis.host" to "redis://localhost:6379",
                "redis.channel" to "dab.dialog-events-v1",
                "poao-tilgang.url" to "http://app.namespace.svc.cluster.local",
                "poao-tilgang.scope" to "api://cluster.namespace.app/.default",
                "azure.client-id" to "clientId",
                "azure.token-endpoint" to "http://localhost:9000/tokenEndpoint",
                "azure.client-secret" to "clientSecret"
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
    else """{ "subscriptionKey": "$subscriptionKey", "events": [${events.joinToString(",")}] }"""
}

fun getAzureToken(navIdent: String) = ApplicationTest.server.issueToken(subject = navIdent, claims = mapOf("NAVident" to navIdent, "oid" to UUID.randomUUID())).serialize()

suspend fun HttpClient.getWsToken(subscriptionKey: String, accessToken: String, events: List<EventType>? = null) : String {
    val authToken = this.post("/ws-auth-ticket") {
        bearerAuth(accessToken)
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

fun mockAzureAdMachineTokenRequest(wireMockServer: WireMockServer) {
    wireMockServer.stubFor(
        WireMock
            .post(WireMock.urlEqualTo("/tokenEndpoint"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""{ "access_token": "shiningToken", "expires_in": 10 }""")
            )
    )
}
