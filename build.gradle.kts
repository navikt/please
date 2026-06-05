import io.ktor.plugin.features.*

val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val tokensupport_version: String by project
val mockoath_version: String by project
val kotest_version: String by project
val kotest_extensions_version: String by project
val prometheus_version: String by project
val logstash_encoder_version: String by project
val valkey_java_version: String by project
val arrow_version: String by project
val poao_tilgang_version: String by project
val common_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    id("io.ktor.plugin") version "3.5.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}

group = "no.nav.please"
version = "0.0.1"

application {
    mainClass.set("no.nav.please.ApplicationKt")
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment", "-Xmx1024m", "-Xms256m")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

data class GithubImageRegistry(
    override val toImage: Provider<String>,
    override val username: Provider<String>,
    override val password: Provider<String>) : DockerImageRegistry

ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
        localImageName.set("please")
        imageTag.set(providers.environmentVariable("IMAGE_TAG"))
        externalRegistry.set(
            DockerImageRegistry.externalRegistry(
                username = providers.environmentVariable("USERNAME"),
                password = providers.environmentVariable("PASSWORD"),
                project = provider { "please" },
                hostname = provider { "ghcr.io" },
                namespace = provider { "navikt" }
            )
        )
    }
}

dependencies {
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-call-id-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")

    implementation("io.ktor:ktor-client-okhttp:$ktor_version")
    implementation("io.ktor:ktor-client-okhttp-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-auth:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-client-logging:$ktor_version")

    implementation("io.ktor:ktor-server-auth:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor_version")
    implementation("io.ktor:ktor-server-config-yaml:$ktor_version")
    implementation("io.ktor:ktor-server-metrics-micrometer:$ktor_version")
    implementation("io.micrometer:micrometer-registry-prometheus:$prometheus_version")
    implementation("io.arrow-kt:arrow-core:$arrow_version")
    implementation("io.arrow-kt:arrow-fx-coroutines:$arrow_version")
    implementation("io.arrow-kt:arrow-resilience-jvm:$arrow_version")
    implementation("org.slf4j:slf4j-api:2.0.18")

    implementation("net.logstash.logback:logstash-logback-encoder:$logstash_encoder_version")

    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("no.nav.security:token-validation-ktor-v3:$tokensupport_version")

    implementation("io.valkey:valkey-java:$valkey_java_version")
    implementation("no.nav.poao-tilgang:client:$poao_tilgang_version")
    implementation("no.nav.common:token-client:$common_version")
    implementation("no.nav.poao-tilgang:api:$poao_tilgang_version")

    testImplementation("io.kotest:kotest-runner-junit5:$kotest_version")
    testImplementation("io.kotest:kotest-assertions-core:$kotest_version")
    testImplementation("io.kotest:kotest-extensions-wiremock-jvm:$kotest_extensions_version")
    testImplementation("org.signal:embedded-redis:0.9.1")

    testImplementation("no.nav.security:mock-oauth2-server:$mockoath_version")
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("io.ktor:ktor-client-websockets-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}
