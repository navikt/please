package no.nav.please.plugins

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.context.either
import arrow.core.right
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.nav.please.varsler.logger
import no.nav.poao_tilgang.api.dto.request.TilgangType
import java.util.*

typealias NavEmployeeIsAuthorized = suspend (employeeAzureId: UUID, externalUserIdentityNumber: String) -> Either<Throwable, Boolean>

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }
    override fun deserialize(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }
}


@Serializable
class EvaluatePoliciesRequest(
    val requests: List<PolicyEvaluationRequestDto>
)

@Serializable
enum class PolicyId {
    NAV_ANSATT_TILGANG_TIL_EKSTERN_BRUKER_V2
}

@Serializable
data class PolicyEvaluationRequestDto(
    @Serializable(with = UUIDSerializer::class)
    val requestId: UUID,
    val policyInput: NavAnsattTilgangTilEksternBrukerPolicyInputV2Dto,
    val policyId: PolicyId
)

@Serializable
class NavAnsattTilgangTilEksternBrukerPolicyInputV2Dto(
    @Serializable(with = UUIDSerializer::class)
    val navAnsattAzureId: UUID,
    val tilgangType: TilgangType,
    val norskIdent: String
)

class AuthCheckFailedException(message: String) : RuntimeException(message)

fun Application.configureAuthorization(
    getMachineToMachineToken: suspend (String) -> String,
    /*  Injected only in tests */
    httpClient: HttpClient = machineToMachineClient(),
): NavEmployeeIsAuthorized {

    val poaoTilgangBaseUrl = this.environment.config.property("poao-tilgang.url").getString()
    val poaoTilgangScope = this.environment.config.property("poao-tilgang.scope").getString()

    suspend fun checkAuthorization(employeeAzureId: UUID, externalUserPin: String): Either<Throwable, Boolean> {
        val url = "$poaoTilgangBaseUrl/api/v1/policy/evaluate"

        return either {
            val accessToken = getMachineToMachineToken(poaoTilgangScope)

            val response: HttpResponse = httpClient.post(url) {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(
                    EvaluatePoliciesRequest(
                        listOf(
                            PolicyEvaluationRequestDto(
                                UUID.randomUUID(),
                                NavAnsattTilgangTilEksternBrukerPolicyInputV2Dto(
                                    navAnsattAzureId = employeeAzureId,
                                    tilgangType = TilgangType.LESE,
                                    norskIdent = externalUserPin
                                ),
                                PolicyId.NAV_ANSATT_TILGANG_TIL_EKSTERN_BRUKER_V2
                            )

                        )

                    )
                )
            }

            return if (response.status == HttpStatusCode.OK) {
                val evaluationResult = response.body<EvaluatePoliciesResponseSurrogate>()
                if (evaluationResult.results.size > 1) {
                    AuthCheckFailedException("More than one evaluation result to one evaluation request").left()
                } else {
                    (evaluationResult.results.first().decision.type == DecisionTypeSurrogate.PERMIT).right()
                }
            } else {
                val message = "Could not evaluate authorization, unsuccessful response-code from poao-tilgang, status: ${response.status.value}"
                logger.error(message)
                AuthCheckFailedException(message).left()
            }
        }
    }

    return ::checkAuthorization
}
