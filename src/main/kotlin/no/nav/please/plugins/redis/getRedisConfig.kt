package no.nav.please.plugins.redis

import io.ktor.server.application.*
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.DefaultRedisCredentials
import redis.clients.jedis.DefaultRedisCredentialsProvider
import redis.clients.jedis.HostAndPort

fun Application.getRedisConfig(): RedisConfig {
    val config = this.environment.config
    val hostAndPort = config.property("redis.host").getString().split("://").last()
    val username = config.propertyOrNull("redis.username")?.getString()
    val password = config.propertyOrNull("redis.password")?.getString()
    val channel = config.property("redis.channel").getString()

    val credentials = DefaultRedisCredentials(username, password)
    val credentialsProvider = DefaultRedisCredentialsProvider(credentials)
    val clientConfig: DefaultJedisClientConfig = DefaultJedisClientConfig.builder()
        .ssl(true)
        .credentialsProvider(credentialsProvider)
        .timeoutMillis(0)
        .build()

    val (host, port) = hostAndPort.split(":")
        .also { require(it.size >= 2) { "Malformed redis url" } }
    val redisHostAndPort = HostAndPort(host, port.toInt())
    log.info("Connecting to redis, host: $host port: $port user: $username channel: $channel")
    return RedisConfig( redisHostAndPort, clientConfig, channel, username != null && password != null)
}
data class RedisConfig(
    val hostAndPort: HostAndPort,
    val clientConfig: DefaultJedisClientConfig,
    val channel: String,
    val isLocal: Boolean
)
