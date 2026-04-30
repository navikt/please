package no.nav.please.plugins

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.please.varsler.logger
import java.time.LocalDateTime

class MachineToMachineTokenProvider(config: ApplicationConfig) {
    private val azureClientId = config.property("azure.client-id").getString()
    private val clientSecret = config.property("azure.client-secret").toString()
    private val tokenEndpoint = config.property("azure.token-endpoint").getString()
    private val grantType = "client_credentials"
    private val accessTokens: MutableMap<String, AccessToken>  = mutableMapOf()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
            }
        }
        install(ContentNegotiation) {
            json()
        }
    }

    private suspend fun fetchAndStoreAccessToken(scope: String): AccessToken {
        val tokenResponse: TokenResponse = try {
            httpClient.post(tokenEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                formData {
                    append("client_id", azureClientId)
                    append("client_secret", clientSecret)
                    append("scope", scope)
                    append("grant_type", grantType)
                }
            }.body<TokenResponse>()
        } catch (e: Exception) {
            logger.error("Failed to fetch token", e)
            throw e
        }

        val accessToken = AccessToken(
            scope = scope,
            token = tokenResponse.accessToken,
            expires = LocalDateTime.now().plusSeconds(tokenResponse.expiresIn)
        )
        accessTokens[scope] = accessToken
        return accessToken
    }

    suspend fun getAccessToken(scope: String): String {
        val existingToken = accessTokens[scope]

        return if (existingToken == null || existingToken.hasExpired()) {
            fetchAndStoreAccessToken(scope).token
        } else {
            existingToken.token
        }
    }
}

private data class AccessToken(
    val scope: String,
    val token: String,
    val expires: LocalDateTime
) {
    fun hasExpired(): Boolean {
        val marginSeconds = 1L
        return LocalDateTime.now().isAfter(expires.plusSeconds(marginSeconds))
    }
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
)
