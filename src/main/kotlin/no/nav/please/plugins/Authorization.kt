package no.nav.please.plugins

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import no.nav.please.varsler.logger
import no.nav.poao_tilgang.api.dto.request.TilgangType
import no.nav.poao_tilgang.api.dto.request.policy_input.NavAnsattTilgangTilEksternBrukerPolicyInputV2Dto
import no.nav.poao_tilgang.api.dto.response.DecisionType.PERMIT
import no.nav.poao_tilgang.api.dto.response.EvaluatePoliciesResponse
import java.util.*

typealias NavEmployeeIsAuthorized = suspend (employeeAzureId: UUID, externalUserIdentityNumber: String) -> Boolean // TODO: Få typesatt på annet vis en typealias

fun Application.configureAuthorization(httpClient: HttpClient, getMachineToMachineToken: suspend (String) -> String): NavEmployeeIsAuthorized {

    val poaoTilgangBaseUrl = this.environment.config.property("poao-tilgang.url").getString()
    val poaoTilgangScope = this.environment.config.property("poao-tilgang.scope").getString()

    suspend fun checkAuthorization(employeeAzureId: UUID, externalUserPin: String): Boolean {
        val url = "$poaoTilgangBaseUrl/api/v1/evaluate"
        val accessToken = getMachineToMachineToken(poaoTilgangScope)

        val response: HttpResponse = httpClient.post(url) {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(NavAnsattTilgangTilEksternBrukerPolicyInputV2Dto(
                navAnsattAzureId = employeeAzureId,
                tilgangType = TilgangType.SKRIVE,
                norskIdent = externalUserPin
            ))
        }

        return if (response.status == HttpStatusCode.OK) {
            val evaluationResult: EvaluatePoliciesResponse = response.body()
            require(evaluationResult.results.size == 1) { "More than one evaluation result to one evaluation request" }
            evaluationResult.results.first().decision.type == PERMIT
        } else {
            // TODO: Hvordan håndtere?
            logger.error("Error in authorization evaluation request to poao-tilgang")
            throw RuntimeException()
        }
    }

    return ::checkAuthorization
}
