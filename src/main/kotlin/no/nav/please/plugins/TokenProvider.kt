package no.nav.please.plugins

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import no.nav.please.varsler.logger

typealias MachineToMachineToken = (scope: String) -> String
typealias RefreshMachineToMachineToken = (scope: String) -> String

// TODO: See https://doc.nais.io/security/auth/azure-ad/usage/#oauth-20-client-credentials-grant
fun Application.configureTokenProvider(): Pair<MachineToMachineToken, RefreshMachineToMachineToken>{
    val config = this.environment.config
    val azureClientId = config.property("azure.client-id").getString()
    val clientSecret = config.property("azure.client-secret").toString()
    val tokenEndpoint = config.property("azure.token-endpoint").getString()

    val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
            }
        }
        install(ContentNegotiation) {
            jackson {
                this.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
    }

    val accessTokens: MutableMap<String, AccessToken>  = mutableMapOf()

    fun fetchAccessToken(scope: String): AccessToken {
        val tokenResponse: TokenResponse = try {
            httpClient.post(tokenEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                formData {
                    append("client_id", azureClientId)
                    append("client_secret", clientSecret)
                    append("scope", scope)
                    append("grand_type", "client_credentials")
                }
            }.body<TokenResponse>()
        } catch (e: Exception) {
            logger.error("Failed to fetch token", e)
            throw e
        }

        return AccessToken(
            scope = scope,
            token = tokenResponse.accessToken,
            expiresIn = tokenResponse.expiresIn
        )
    }

    fun storeAccessToken(accessToken: AccessToken) {
        accessTokens[accessToken.scope] = accessToken
    }

    fun getAccessTokenFromStore(scope: String): AccessToken? = accessTokens[scope]

    // TODO: Check expires


    val getToken: MachineToMachineToken = { scope: String ->
        getAccessTokenFromStore(scope)?.token ?: fetchAccessToken(scope).let {
            storeAccessToken(it)
            it.token
        }
    }

    val getRefreshedToken: RefreshMachineToMachineToken = { scope: String ->
        fetchAccessToken(scope).let {
            storeAccessToken(it)
            it.token
        }
    }

    return Pair(getToken, getRefreshedToken)
}

private data class AccessToken(
    val scope: String,
    val token: String,
    val expiresIn: Long
)

private data class TokenResponse(
    @JsonFormat(pattern = "access_token")
    val accessToken: String,
    @JsonFormat(pattern = "expires_in")
    val expiresIn: Long,
)