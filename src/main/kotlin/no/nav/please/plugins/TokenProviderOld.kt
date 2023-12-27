package no.nav.please.plugins

//typealias GetMachineToMachineToken = (scope: String) -> String
//
//fun Application.configureTokenProvider(): GetMachineToMachineToken {
//    val config = this.environment.config
//    val azureClientId = config.property("azure.client-id").getString()
//    val privateJwk = config.property("azure.jwk").getString()
//    val tokenEndpoint = config.property("azure.token-endpoint").getString()
//
//    val tokenClient = AzureAdTokenClientBuilder.builder()
//        .withClientId(azureClientId)
//        .withPrivateJwk(privateJwk)
//        .withTokenEndpointUrl(tokenEndpoint)
//        .buildMachineToMachineTokenClient()
//
//    return { scope ->
//        tokenClient.createMachineToMachineToken(scope)
//    }
//}
