package no.nav.please.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import no.nav.security.token.support.v3.tokenValidationSupport

fun Application.configureAuthentication() {
    val config = this.environment.config
    install(Authentication) {
        tokenValidationSupport(config = config, name = "AzureOrTokenX")
    }
}