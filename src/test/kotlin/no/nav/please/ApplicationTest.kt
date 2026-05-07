package no.nav.please

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import no.nav.please.ApplicationTest.Companion.entraAdHost
import no.nav.please.ApplicationTest.Companion.poaoTilgangHost
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
    lateinit var client: HttpClient

    fun withMocks(block: suspend (client: HttpClient) -> Unit) {
        testApplication {
            client = createClient {
                install(WebSockets)
                install(Logging)
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                    formData()
                }
            }
            environment {
                doConfig()
            }
            application { module(client) }
            mockPoaoTilgangPolicyEval()
            mockAzureAdMachineTokenRequest()

            block(client)
        }
    }


    beforeSpec {
        redisServer = RedisServer(6379)
        redisServer.start()
    }
    afterSpec {
        redisServer.stop()
        IncomingDialogMessageFlow.stop()
        server.shutdown()
    }

    "should notify subscribers on new dialog-message" {
        withMocks { client ->
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
    }

    "WsConnectionHolder should count correctly" {
        withMocks { client ->
            val veileder1 = "Z123123"
            val subscriptionKey1 = "12345678910"
            val countBefore = WsConnectionHolder.dialogListeners.values.sumOf { it.size }
            val veileder1token = client.getWsToken(subscriptionKey1, getAzureToken(veileder1))
            client.webSocket("/ws") {
                awaitAuthInTest(veileder1token)
                WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe countBefore + 1
            }
            awaitListenerCount(countBefore)

            client.webSocket("/ws") {
                send(Frame.Text("LOL"))
                WsConnectionHolder.dialogListeners.values.sumOf { it.size } shouldBe countBefore
            }
            awaitListenerCount(countBefore)
        }
    }

    "should reestablish websocket and reuse subscription" {
        withMocks { client ->
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
    }

    "should be able to subscribe to selected events" {
        withMocks { client ->
            val person = "123123123"

            val veileder = "Z223123"
            val onlyBrukerTilNav = client.getWsToken(
                person,
                getAzureToken(veileder),
                listOf(EventType.NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV)
            )

            val veileder2 = "Z223124"
            val allEvents = client.getWsToken(
                person,
                getAzureToken(veileder2)
            )

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
    }

    "should fail on invalid subscription" {
        withMocks { client ->
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
    }

    }) {

    companion object {
        private val logger = LoggerFactory.getLogger(javaClass)
        val server: MockOAuth2Server by lazy {
            MockOAuth2Server()
                .also { it.start() }
        }

        const val testTopic = "ny-dialog-topic-i-test"

        // TODO: Move somewhere else
        const val jwk = """
              {
                 "p": "2dZeNRmZow7uWKzqpOolNf7FUIr6dP5bThwnqpcDca7sP96fzPGaryZmYAawZj7h1UthpDp9b2D5v0-D0fSrbdp-MisaOz_ZL-2kdwyTSIP0ii-4yPHpFqaZuGTbuLmROwDhklTGMoYC4fN8vb0jgE6cR33bA52JH255qz5R1rc",
                 "kty": "RSA",
                 "q": "pIt7sgMqDPGZDMiksZ19R9iuUZk5ZcsnPeI0yAGIaEp75Nc7IH9F1LQ8mPw-wtV3Yde26mByszjeskVfldlReZmzeCTXq4jgu5WEi2GM7craTZj-ES7SLkuP21uvbgxGCLxEizr4RCdZD8TtkxcSG2-GPkp-N4IX9187kvWbWl8",
                 "d": "R_P82iKNJflwkPnpOr5eGmtekLvTq1cZwJ7M0vbox3LlVmpIP9iRPKVEwuBva0ybRu1pkvM4S3DFgYK6gKjHVzPYl6lHvKZxbFyP8lJoaj1km2NhA3cwqJjqkx4VAJhLlEuG5wDlTSRXNpzqfamdZcH-XMG2rM-nh6yFqbSzyaeO99ZnGMDp5mZvzGuR0VmV6IXPXqelP4uT9cPQD60h1v2DaOKlmd-0ghGfdHa0hzR5S8C55oZ5hF1_bhgx6tA8VzC1jp41mDbKmKAOKvcFG2T9JQRBml2izRVVaCsVN0_ZCR7NhQYrkreqgVN_ZLlgzI6YSA2EN1FWmc9GvNFAbQ",
                 "e": "AQAB",
                 "use": "sig",
                 "kid": "ut-Fle8JH9IdPqo7QDDakblWR1DdvMIijJT9A-lVntk",
                 "qi": "uoncSFVC9_vS652rNSZQO8I7KCk0b2bpt38Sb1iQ8Vha5lYkrTp-AsZLoduj7TscCCqlftm9R-FkfERjEYZLdPKQIaGcCQ-L0RzIG_K3w48Tk2T_EEiMqds4UeBpQxccMjUvX-t_b7pwMjFL1RIEBSWAxg5YShT8C83hv0llh9Y",
                 "dp": "BLMxWSfyPqhl0Bf7AA_lOaMDktdMzBVo1uiYmn-jnWJOypn9DKjx03Gap9u9Fpeou7dipe51ImAPQ2dtyqvivv4F1wNDD6AzCWuxLrhgvSHLtueMrxk5FDoH-wiCDRxD2-gK9eNKW3C0wzdDq7xW9b-8c3ZtsUhG2xzBF0bC8UU",
                 "alg": "RS256",
                 "dq": "R_ji4BhWOlcq9NaGg1I5zEVQ6kw1OPtFbOIW6C0Td1qtGomySSKibslvgBNFeH9auqdaUOZjBVWowx1pE-h8pM3AHJsw4sz6T9K0qSrAM_r4xdxXtThfovRWNkLCV0ZzE7sV2DixA06avDUNHbuHpgyAEZsP3kO_K-qx6jQYAc0",
                 "n": "jAQFAKQ9omNtb_I2iSryCulJnkB56qGf35fA1RrDBLup7ysJCez9dnu-HTZ62SKoe-9Pxu-4WzjjBNQacotUXYTIi7GFWM5Pyb4ha-bBJprbiwhyrYGIVzZw4LIcleexWPcIOI0cTKmpM6qKb9_6CTFa-A6uX_16n-n3fQjWGPKrJBY7mcIalJ4YTmLhavs6yt6efSD67SaJ2FabzjouRa_yeDmsGPq2LA-4FymDvuGCHeeMtPO9ClnA2eWC15L7n3-Pagm5pso5GchORl2Rwr_bhCmNCKsC_Qh6TqTHJyymuJwZIuSOv88cf-5UsSidRSJ9r0dBl0S0KgndCagD6Q"
              }
        """

        val poaoTilgangHost = "http://poao-tilgang-api.test"
        val entraAdHost = "http://entra-ad.test"
        val entraAdTokenEndpoint = "$entraAdHost/tokenEndpoint"
        private fun ApplicationEnvironmentBuilder.doConfig(
            acceptedIssuer: String = "default",
            acceptedAudience: String = "default"
        ) {
            val wellKnownUrl = server.wellKnownUrl(acceptedIssuer)
            config = MapApplicationConfig(
                "no.nav.security.jwt.issuers.size" to "1",
                "no.nav.security.jwt.issuers.0.issuer_name" to acceptedIssuer,
                "no.nav.security.jwt.issuers.0.discoveryurl" to "$wellKnownUrl",
                "no.nav.security.jwt.issuers.0.accepted_audience" to acceptedAudience,
                "topic.ny-dialog" to testTopic,
                "valkey.host" to "rediss://localhost:6379",
                "valkey.channel" to "dab.dialog-events-v1",
                "poao-tilgang.url" to poaoTilgangHost,
                "poao-tilgang.scope" to "api://cluster.namespace.app/.default",
                "azure.client-id" to "clientId",
                "azure.token-endpoint" to entraAdTokenEndpoint,
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

suspend fun awaitListenerCount(expected: Int) {
    withTimeout(5000) {
        while (WsConnectionHolder.dialogListeners.values.sumOf { it.size } != expected) {
            delay(10)
        }
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

fun getAzureToken(navIdent: String) = ApplicationTest.server.issueToken(subject = navIdent, claims = mapOf("NAVident" to navIdent, "oid" to UUID.randomUUID())).serialize()

suspend fun HttpClient.getWsToken(subscriptionKey: String, accessToken: String, events: List<EventType>? = null) : String {
    val (status, body) = this.post("/ws-auth-ticket") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(getTicketBody(subscriptionKey, events))
    }
        .let { it.status to it.bodyAsText() }
    withClue("Failed to get web-socket-token from /ws-auth-ticket in veilarboppfolging-mock, expected status OK but got $status $body") {
        status shouldBe HttpStatusCode.OK
    }
    body.shouldNotBeEmpty()
    body shouldNotBe null
    return body
}

suspend fun HttpClient.notifySubscribers(subscriptionKey: String, eventType: EventType) {
    this.post("/notify-subscribers") {
        bearerAuth(ApplicationTest.server.issueToken(subject = "Z123123").serialize())
        contentType(ContentType.Application.Json)
        setBody("""{ "subscriptionKey": "$subscriptionKey", "eventType": "${eventType.name}" }""")
    }.status shouldBe HttpStatusCode.OK
}

fun TestApplicationBuilder.mockPoaoTilgangPolicyEval() {
    externalServices {
        hosts(poaoTilgangHost) {
            this.install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            this.routing {
                post("/api/v1/policy/evaluate") {
                    call.respondText(
                        """{ "results": [{ "requestId": "4a7ab7c2-80bc-4740-afce-edf0e364929f", "decision": { "type": "PERMIT" } }] }""".trimIndent(),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }
            }
        }
    }
}

fun TestApplicationBuilder.mockAzureAdMachineTokenRequest() {
    externalServices {
        hosts(entraAdHost) {
            this.install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
                formData()
            }
            this.routing {
                post("/tokenEndpoint") {
                    call.respondText(
                        text = """{ "access_token": "shiningToken", "expires_in": 10 }""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }
            }
        }
    }
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
