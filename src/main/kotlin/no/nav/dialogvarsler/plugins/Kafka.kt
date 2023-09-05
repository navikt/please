//package no.nav.dialogvarsler.plugins
//
//import no.nav.dialogvarsler.NyDialogFlow
//import io.ktor.server.application.*
//import org.apache.kafka.clients.consumer.ConsumerConfig
//import org.apache.kafka.clients.consumer.KafkaConsumer
//import org.apache.kafka.common.TopicPartition
//import org.apache.kafka.common.config.SslConfigs
//import org.apache.kafka.common.serialization.StringDeserializer
//import java.time.Duration
//import java.util.*
//
//
//fun Application.configureKafka() {
//    val config = this.environment.config
//    val properties = Properties().also {
//        it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = config.property("kafka.brokers").getString()
//        it[ConsumerConfig.GROUP_ID_CONFIG] = "dialogvarsler"
//        it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
//        it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
//        it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "latest"
//
////        // Keystore config
//        if (config.propertyOrNull("kafka.keystore.path") != null) {
//            val keystoreConfig = config.config("kafka.keystore")
//            it[SslConfigs.SSL_KEYSTORE_TYPE_CONFIG] = "PKCS12"
//            it[SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG] = keystoreConfig.property("path").getString()
//            it[SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG] = keystoreConfig.property("password").getString()
//        }
//        // Truststore config
//        if (config.propertyOrNull("kafka.keystore.path") != null) {
//            val truststoreConfig = config.config("kafka.truststore")
//            it[SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG] = "JKS"
//            it[SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG] = truststoreConfig.property("path").getString()
//            it[SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG] = truststoreConfig.property("password").getString()
//        }
//    }
//
//    val consumer = KafkaConsumer<String, String>(properties)
//    val topic = config.property("topic.ny-dialog").getString()
//    consumer.assign(listOf(TopicPartition(topic, 0)))
//
//    // When in tests mode make sure kafka is up and runnning before
//    if (config.propertyOrNull("kafka.localTest")?.getString()?.toBoolean() == true) {
//        val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
//        consumer.seekToEnd(partitions)
//        partitions.forEach { partition -> consumer.position(partition, Duration.ofSeconds(10)) }
//        consumer.commitSync()
//    }
//
//    NyDialogFlow.subscribe(consumer)
//}