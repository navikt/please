package no.nav.please.plugins

import com.nimbusds.oauth2.sdk.token.AccessToken
import io.ktor.client.*
import io.ktor.server.application.*

typealias MachineToMachineToken = (scope: String) -> String
typealias RefreshMachineToMachineToken = (scope: String) -> String

// TODO: See https://doc.nais.io/security/auth/azure-ad/usage/#oauth-20-client-credentials-grant
fun Application.configureTokenProvider(httpClient: HttpClient): Pair<MachineToMachineToken, RefreshMachineToMachineToken>{
    val config = this.environment.config
    val azureClientId = config.property("azure.client-id").getString()
    val privateJwk = config.property("azure.jwk").getString()
    val tokenEndpoint = config.property("azure.token-endpoint").getString()

    var accessToken: AccessToken? = null

    suspend fun fetchAcccessToken(): String {
        return "hentNyttAccessToken"
    }

    val machineToMachineToken = { scope ->
        if (acc)
    }

    val refreshMachineToMachineTOken


    data class AccessToken(
        val scope: String,
        val token: String,
    )

}