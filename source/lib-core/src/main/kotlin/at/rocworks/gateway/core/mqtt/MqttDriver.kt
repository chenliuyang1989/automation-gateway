package at.rocworks.gateway.core.mqtt
/*
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem
import groovy.lang.Binding
import groovy.lang.GroovyShell
import groovy.lang.Script
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import io.vertx.mqtt.messages.MqttPublishMessage

import java.nio.charset.Charset

class MqttDriver(val config: JsonObject) : DriverBase(config) {
    override fun getType() = Topic.SystemType.Mqtt

    var client: MqttClient? = null

    private val port: Int = config.getInteger("Port", 1883)
    private val host: String = config.getString("Host", "localhost")
    private val username: String? = config.getString("Username")
    private val password: String? = config.getString("Password")
    private val ssl: Boolean = config.getBoolean("Ssl", false)
    private val qos: Int = config.getInteger("Qos", 0)
    private val maxMessageSizeKb = config.getInteger("MaxMessageSizeKb", 8) * 1024

    private val readerScript: String
    private val writerScript: String

    private val sharedReaderData = Binding()
    private val sharedWriterData = Binding()
    private val groovyReaderShell = GroovyShell(sharedReaderData)
    private val groovyWriterShell = GroovyShell(sharedWriterData)
    private val groovyReaderScript: Script?
    private val groovyWriterScript: Script?

    private val subscribedTopics = HashSet<Topic>() // Subscribed topic name can have wildcard
    private val receivedTopics = HashMap<String, List<Topic>>()

    init {
        val value = config.getJsonObject("Value", JsonObject())

        val defaultReaderScript = """
            return [
              className: "TopicValueOpc",
              sourceTime: Instant.now().toString(),
              serverTime: Instant.now().toString(),
              value: value,
              statusCode: 0
            ]                        
        """.trimIndent()

        val defaultWriterScript = """
            return value
        """.trimIndent()

        readerScript = value.getString("Reader", defaultReaderScript)
        writerScript = value.getString("Writer", defaultWriterScript)

        groovyReaderScript = parseReaderGroovyScript()
        groovyWriterScript = parseWriterGroovyScript()
    }

    override fun connect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val options = MqttClientOptions()
        options.isCleanSession = true
        username?.let { options.username = it }
        password?.let { options.password = it }
        options.isSsl = ssl
        options.maxMessageSize = maxMessageSizeKb

        client = MqttClient.create(vertx, options)
        client?.publishHandler(::valueConsumer)
        client?.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.result().code()}]")
            promise.complete(it.succeeded())
        } ?: promise.fail("Client is null!")

        return promise.future()
    }

    private fun parseReaderGroovyScript(): Script? {
        return if (readerScript.isNotEmpty()) {
            val script =
                """
                import java.time.*                     
                import groovy.json.*
                def output(value) { $readerScript }
                return JsonOutput.toJson(output(value))
                """.trimIndent()
            groovyReaderShell.parse(script)
        } else null
    }

    private fun parseWriterGroovyScript(): Script? {
        return if (writerScript.isNotEmpty()) {
            val script =
                """
                import java.time.*                     
                import groovy.json.*
                def output(value) { $writerScript }
                return output(new JsonBuilder(value))
                """.trimIndent()
            groovyWriterShell.parse(script)
        } else null
    }

    private fun transformReaderValue(value: Buffer): String {
        return if (groovyReaderScript != null) {
            sharedReaderData.setProperty("value", value.toString(Charset.defaultCharset()))
            try {
                groovyReaderScript.run().toString()
            } catch (e: Exception) {
                e.toString()
            }
        } else {
            value.toString(Charset.defaultCharset())
        }
    }

    private fun transformWriterValue(value: String): Buffer {
        return if (groovyWriterScript != null) {
            sharedWriterData.setProperty("value", value)
            try {
                Buffer.buffer(groovyWriterScript.run().toString())
            } catch (e: Exception) {
                Buffer.buffer(e.toString())
            }
        } else {
            Buffer.buffer(value)
        }
    }

    override fun disconnect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        client?.disconnect {
            logger.info("Mqtt client disconnect [${it.succeeded()}]")
            promise.complete(it.succeeded())
        } ?: promise.fail("Client is null!")
        return promise.future()
    }

    override fun shutdown() {
        disconnect()
    }

    private fun compareTopic(actualTopic: String, subscribedNode: String): Boolean {
        val regex = subscribedNode.replace("+", "[^/]+").replace("#", ".+")
        return actualTopic.matches(regex.toRegex())
    }

    override fun subscribeTopics(topics: List<Topic>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        if (topics.isEmpty()) promise.complete(true)
        else {
            logger.info("Subscribe to [${topics.size}] topics")
            topics.forEach { topic ->
                logger.info("Subscribe topic [${topic.topicName}] node [${topic.node}]")
                client?.subscribe(topic.node, qos)
                registry.addMonitoredItem(MqttMonitoredItem(topic.node), topic)
                subscribedTopics.add(topic)
                resetReceivedTopics(topic.node)
            }
            promise.complete(true)
        }
        return promise.future()
    }

    override fun unsubscribeTopics(topics: List<Topic>, items: List<MonitoredItem>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()

        subscribedTopics.removeIf { topic ->
            topics.filter { it.topicName == topic.topicName }.count() > 0
        }

        items.map { (it as MqttMonitoredItem).item }
            .filter { node ->
                subscribedTopics.filter { it.node == node }.count() == 0 }
            .forEach { node ->
                logger.info("Unsubscribe node [${node}]", )
                client?.unsubscribe(node)?.onComplete {
                    logger.info("Unsubscribe node [${node}] result [${it.result()}]")
                }
                resetReceivedTopics(node)
            }
        promise.complete(true)
        return promise.future()
    }

    private fun resetReceivedTopics(node: String) {
        receivedTopics.filter { receivedTopic ->
            compareTopic(receivedTopic.key, node)
        }.forEach {
            receivedTopics.remove(it.key)
        }
    }

    private fun valueConsumer(message: MqttPublishMessage) {
        logger.finest { "Got value [${message.topicName()}] [${ message.payload()}]" }
        try {
            val receivedTopic = message.topicName()
            val payload : Buffer = message.payload()

            fun json(topic: Topic) = JsonObject()
                .put("Topic", topic.encodeToJson())
                .put("Value", Json.decodeValue(transformReaderValue(payload)))

            fun publish(topic: Topic) {
                try {
                    topic.browsePath = receivedTopic
                    val buffer: Buffer? = when (topic.format) {
                        Topic.Format.Value -> payload
                        Topic.Format.Json -> Buffer.buffer(json(topic).encode())
                        Topic.Format.Pretty -> Buffer.buffer(json(topic).encodePrettily())
                    }
                    vertx.eventBus().publish(topic.topicName, buffer)
                } catch (e: Exception) {
                    logger.warning("Exception on publish value [${e.message}]", )
                }
            }

            receivedTopics[receivedTopic]?.let {
                it.forEach(::publish)
            } ?: run {
                val topics = subscribedTopics.filter { compareTopic(receivedTopic, it.node) }
                receivedTopics[receivedTopic] = topics
                topics.forEach(::publish)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        promise.fail("publishTopic([$topic], [$value]) Not yet implemented")
        return promise.future()
    }

    override fun readServerInfo(): JsonObject {
        logger.severe("readServerInfo() Not yet implemented")
        return JsonObject()
    }

    override fun readHandler(message: Message<JsonObject>) {
        logger.severe("readHandler() Not yet implemented")
        message.fail(-1, "Not yet implemented")
    }

    override fun writeHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")

        fun publish(client: MqttClient, topic: String, value: String): Future<Int> =
            client.publish(topic, transformWriterValue(value), MqttQoS.AT_LEAST_ONCE, false, false) // TODO: configurable QoS...

        when {
            node != null && node is String -> {
                val value = message.body().getString("Value", "")
                client?.let { client ->
                    publish(client, node, value).onComplete {
                        message.reply(JsonObject().put("Ok", true))
                    }
                } ?: run {
                    message.reply(JsonObject().put("Ok", false))
                }
            }
            node != null && node is JsonArray -> {
                val values = message.body().getJsonArray("Value", JsonArray())
                client?.let { client ->
                    CompositeFuture.all(node.zip(values).mapNotNull {
                        if (it.first is String && it.second is String) {
                            publish(client, it.first as String, it.second as String)
                        } else null
                    }).onComplete { result ->
                      message.reply(JsonObject().put("Ok", JsonArray(node.map { result.succeeded() })))
                    }
                } ?: run {
                    message.reply(JsonObject().put("Ok", JsonArray(node.map { false })))
                }
            }
            else -> {
                val err = String.format("Invalid format in write request!")
                message.reply(JsonObject().put("Ok", false))
                logger.severe(err)
            }
        }
    }

    override fun browseHandler(message: Message<JsonObject>) {
        logger.severe("browseHandler() Not yet implemented")
        message.reply(JsonObject().put("Ok", false))
    }

    override fun schemaHandler(message: Message<JsonObject>) {
        logger.severe("schemaHandler() Not yet implemented")
        message.reply(JsonObject().put("Ok", false))
    }
}
 */