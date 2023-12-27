package no.nav.please.plugins

import io.ktor.server.application.*
import no.nav.poao_tilgang.client.PoaoTilgangCachedClient
import no.nav.poao_tilgang.client.PoaoTilgangClient
import no.nav.poao_tilgang.client.PoaoTilgangHttpClient
import java.util.*

typealias EmployeeAzureId = UUID
typealias PersonalIdentityNumber = String // TODO: Change so it reflects that this should always represent an external user
typealias VerifyAuthorization = (EmployeeAzureId, PersonalIdentityNumber) -> Boolean

fun Application.configurePoaoTilgangClient(): VerifyAuthorization {

    val config = this.environment.config
    val poaoTilgangUrl = config.property("poao-tilgang.url").getString()
    val tokenScope = config.property("poao-tilgang.token-scope").getString()

    val client: PoaoTilgangClient = PoaoTilgangCachedClient(
        PoaoTilgangHttpClient(
            baseUrl = poaoTilgangUrl,
            tokenProvider = { "machine-to-machine token" } // TODO: Create token-provider
        )
    )

    // TODO: Implement authorization check
    return { uuid: EmployeeAzureId, s: PersonalIdentityNumber ->
        true
    }
}