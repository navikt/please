package no.nav.please.plugins

import io.ktor.server.application.*
import no.nav.poao_tilgang.client.*
import java.util.*

typealias EmployeeAzureId = UUID
typealias PersonalIdentityNumber = String // TODO: Change so it reflects that this should always represent an external user
typealias VerifyAuthorization = (EmployeeAzureId, PersonalIdentityNumber) -> Boolean

fun Application.configurePoaoTilgangClient(getMachineToMachineToken: GetMachineToMachineToken): VerifyAuthorization {

    val config = this.environment.config
    val poaoTilgangUrl = config.property("poao-tilgang.url").getString()
    val tokenScope = config.property("poao-tilgang.token-scope").getString()

    val client: PoaoTilgangClient = PoaoTilgangCachedClient(
        PoaoTilgangHttpClient(
            baseUrl = poaoTilgangUrl,
            tokenProvider = { getMachineToMachineToken(tokenScope)}
        )
    )

    return { uuid: EmployeeAzureId, externalUserPin: PersonalIdentityNumber ->
        val decision = client.evaluatePolicy(NavAnsattTilgangTilEksternBrukerPolicyInput(
            navAnsattAzureId = uuid,
            tilgangType = TilgangType.SKRIVE, // TODO: Decide on what is the correct permission
            norskIdent = externalUserPin
        )).get()

        decision?.isPermit ?: false
    }
}
