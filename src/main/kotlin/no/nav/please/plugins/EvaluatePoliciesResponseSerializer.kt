package no.nav.please.plugins

import kotlinx.serialization.Serializable
import no.nav.poao_tilgang.api.dto.response.DecisionDto
import no.nav.poao_tilgang.api.dto.response.DecisionType
import no.nav.poao_tilgang.api.dto.response.EvaluatePoliciesResponse
import no.nav.poao_tilgang.api.dto.response.PolicyEvaluationResultDto
import java.util.UUID

@Serializable
enum class DecisionTypeSurrogate { PERMIT, DENY }

@Serializable
data class DecisionDtoSurrogate(
    val type: DecisionTypeSurrogate,
    val message: String? = null,
    val reason: String? = null
)

@Serializable
data class PolicyEvaluationResultDtoSurrogate(
    @Serializable(with = UUIDSerializer::class) val requestId: UUID,
    val decision: DecisionDtoSurrogate
)

@Serializable
data class EvaluatePoliciesResponseSurrogate(
    val results: List<PolicyEvaluationResultDtoSurrogate>
)

fun EvaluatePoliciesResponseSurrogate.toDomain() = EvaluatePoliciesResponse(
    results = results.map { result ->
        PolicyEvaluationResultDto(
            requestId = result.requestId,
            decision = DecisionDto(
                type = when (result.decision.type) {
                    DecisionTypeSurrogate.PERMIT -> DecisionType.PERMIT
                    DecisionTypeSurrogate.DENY -> DecisionType.DENY
                },
                message = result.decision.message,
                reason = result.decision.reason
            )
        )
    }
)
