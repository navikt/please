package no.nav.please.plugins

import io.ktor.server.application.*
import no.nav.common.token_client.builder.AzureAdTokenClientBuilder

typealias GetMachineToMachineToken = (scope: String) -> String

fun Application.configureTokenProvider(): GetMachineToMachineToken {
    val tokenClient = AzureAdTokenClientBuilder.builder()
        .withNaisDefaults()
        .buildMachineToMachineTokenClient()

    return { scope ->
        tokenClient.createMachineToMachineToken(scope)
    }
}
