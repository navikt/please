package no.nav.please

import io.kotest.assertions.throwables.shouldThrowExactly
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
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import no.nav.please.varsler.EventType
import no.nav.please.varsler.IncomingDialogMessageFlow
import no.nav.please.varsler.WsConnectionHolder
import no.nav.please.varsler.WsListener
import no.nav.security.mock.oauth2.MockOAuth2Server
import org.slf4j.LoggerFactory
import redis.embedded.RedisServer
import java.util.UUID


class ApplicationTest : StringSpec({
    lateinit var redisServer: RedisServer
    beforeSpec {
        redisServer = RedisServer(6379)
        redisServer.start()
    }
    afterSpec {
        redisServer.stop()
        IncomingDialogMessageFlow.stop()
        server.shutdown()
    }

    "should notify subscribers" {
        testApplication {
            environment { doConfig() }
            application { module() }
            val client = createClient {
                install(WebSockets)
            }

            suspend fun postMessage(subscriptionKey: String) {
                client.post("/notify-subscribers") {
                    bearerAuth(server.issueToken(subject = "Z123123").serialize())
                    contentType(ContentType.Application.Json)
                    setBody("""{ "subscriptionKey": "$subscriptionKey", "eventType": "NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV" }""")
                }.status shouldBe HttpStatusCode.OK
            }

            val veileder1 = "Z123123"
            val subscriptionKey1 = "12345678910"

            val veileder2 = "Z321321"
            val subscriptionKey2 = "11111178910"

            val veileder1token = client.getWsToken(subscriptionKey1, veileder1)
            val veileder2token = client.getWsToken(subscriptionKey2, veileder2)

            WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe 0

            client.webSocket("/ws") {
                awaitAuth(veileder1token)
                logger.info("Posting to veilarbdialog for test-subscriptionKey 1")
                client.notifySubscribers(subscriptionKey1, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
                receiveStringWithTimeout().let { Json.decodeFromString<EventType>(it)  } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
                logger.info("Received message, closing websocket for subscriptionKey 1")
                WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe 1
                close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
            }
            client.webSocket("/ws") {
                awaitAuth(veileder2token)
                logger.info("Posting to veilarbdialog for test-subscriptionKey 2")
                client.notifySubscribers(subscriptionKey2, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
                receiveStringWithTimeout().let { Json.decodeFromString<EventType>(it)  } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
                logger.info("Received message, closing websocket for subscriptionKey 2")
                close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
            }
        }
    }

    "WsConnectionHolder should count correctly" {
        testApplication {
            environment { doConfig() }
            application { module() }
            val client = createClient {
                install(WebSockets)
            }
            val veileder1 = "Z123123"
            val subscriptionKey1 = "12345678910"
            val countBefore = WsConnectionHolder.dialogListeners.values.sumOf { it.size }
            val veileder1token = client.getWsToken(subscriptionKey1, veileder1)
            client.webSocket("/ws") {
                awaitAuth(veileder1token)
                WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe countBefore + 1
                close()
            }
            WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe countBefore
        }

    }

    "should reestablish websocket and reuse subscribtion" {
        testApplication {
            environment { doConfig() }
            application { module() }
            val client = createClient {
                install(WebSockets)
            }


            val veileder1 = "Z123123"
            val subscriptionKey1 = "12345678911"

            val veileder1token = client.getWsToken(subscriptionKey1, veileder1)

            client.webSocket("/ws") {
                awaitAuth(veileder1token)
                logger.info("Posting to veilarbdialog for test-subscriptionKey 1")
                client.notifySubscribers(subscriptionKey1, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
                receiveStringWithTimeout().let { Json.decodeFromString<EventType>(it)  } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
                logger.info("Received message, closing websocket for subscriptionKey 1")
                close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
            }
            client.webSocket("/ws") {
                logger.info("Reestablish session with same token")
                awaitAuth(veileder1token)
                logger.info("Posting to veilarbdialog for test-subscriptionKey 1")
                client.notifySubscribers(subscriptionKey1, EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
                receiveStringWithTimeout().let { Json.decodeFromString<EventType>(it)  } shouldBe EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV
                logger.info("Received message, closing websocket for subscriptionKey 1")
                close(CloseReason(CloseReason.Codes.NORMAL, "Bye"))
            }

        }
    }

    "should fail on invalid subscription" {
        testApplication {
            environment { doConfig() }
            application { module() }
            val client = createClient {
                install(WebSockets)
            }


            val veileder1 = "Z123123"
            val subscriptionKey1 = "12345678911"

            val veileder1token = client.getWsToken(subscriptionKey1, veileder1)

            client.webSocket("/ws") {
                val invalidToken = UUID.randomUUID().toString()
                shouldThrowExactly<ClosedReceiveChannelException> { awaitAuth(invalidToken) }
            }

        }
    }

//    "authorization should work" {
//        testApplication {
//            environment { doConfig() }
//            application { module() }
//            client.get("/isAlive").apply {
//                assertEquals(HttpStatusCode.OK, status)
//                assertEquals("", bodyAsText())
//            }
//            client.post("/ws-auth-ticket").apply {
//                assertEquals(HttpStatusCode.Unauthorized, status)
//            }
//            client.post("/ws-auth-ticket") {
//                bearerAuth(server.issueToken().serialize())
//                contentType(ContentType.Application.Json)
//                setBody("""{ "subscriptionKey": "12345678910" }""")
//            }.apply {
//                assertEquals(HttpStatusCode.OK, status)
//                UUID.fromString(this.bodyAsText())
//            }
//        }
//    }
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

suspend fun DefaultClientWebSocketSession.awaitAuth(token: String) {
    val logger = LoggerFactory.getLogger(javaClass)
    logger.info("Sending authtoken on websocket")
    send(Frame.Text(token))
    val authAck = (incoming.receive() as? Frame.Text)?.readText() ?: ""
    logger.info("Received auth-ack")
    authAck shouldBe "AUTHENTICATED"
}
suspend fun DefaultClientWebSocketSession.receiveStringWithTimeout(): String {
    return withTimeout(500) {
        (incoming.receive() as? Frame.Text)?.readText() ?: ""
    }
}

suspend fun HttpClient.getWsToken(subscriptionKey: String, sub: String): String {
    val authToken = this.post("/ws-auth-ticket") {
        bearerAuth(ApplicationTest.server.issueToken(subject = sub).serialize())
        contentType(ContentType.Application.Json)
        setBody("""{ "subscriptionKey": "$subscriptionKey" }""")
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