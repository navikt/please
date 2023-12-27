package no.nav.please.plugins

import io.ktor.server.application.*
import no.nav.poao_tilgang.client.*
import java.util.*

typealias isAuthorizedToContactExternalUser = (employeeAzureId: UUID, externalUserIdentityNumber: String) -> Boolean

fun Application.configurePoaoTilgangClient(getMachineToMachineToken: GetMachineToMachineToken): isAuthorizedToContactExternalUser {

    val config = this.environment.config
    val poaoTilgangUrl = config.property("poao-tilgang.url").getString()
    val tokenScope = config.property("poao-tilgang.token-scope").getString()

    val client: PoaoTilgangClient = PoaoTilgangCachedClient(
        PoaoTilgangHttpClient(
            baseUrl = poaoTilgangUrl,
            tokenProvider = { getMachineToMachineToken(tokenScope)}
        )
    )

    return { employeeAzureId: UUID, externalUserIdentityNumber: String ->
        val decision = client.evaluatePolicy(NavAnsattTilgangTilEksternBrukerPolicyInput(
            navAnsattAzureId = employeeAzureId,
            tilgangType = TilgangType.SKRIVE, // TODO: Decide on what is the correct permission
            norskIdent = externalUserIdentityNumber
        )).get()

        decision?.isPermit ?: false
    }
}