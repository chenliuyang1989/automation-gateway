package at.rocworks.gateway.core.graphql

import at.rocworks.gateway.core.data.Globals
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.Value
import at.rocworks.gateway.core.service.ServiceHandler

import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.*

import io.reactivex.*
import io.vertx.core.*

import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.graphql.ApolloWSHandler
import io.vertx.ext.web.handler.graphql.GraphQLHandler

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.collections.HashMap

import java.time.format.DateTimeFormatter
import java.util.logging.Level
import java.util.logging.Logger
import io.vertx.ext.web.handler.graphql.GraphiQLHandler

import io.vertx.ext.web.handler.graphql.GraphiQLHandlerOptions




class GraphQLServer(private val config: JsonObject, private val defaultSystem: String) : AbstractVerticle() {
    // TODO: Implement scalar "variant"
    // TODO: Subscribe to multiple nodes

    private val defaultType = Topic.SystemType.Opc.name

    companion object {
        fun create(vertx: Vertx, config: JsonObject, defaultSystem: String) {
            vertx.deployVerticle(GraphQLServer(config, defaultSystem))
        }
    }

    private val id = this.javaClass.simpleName
    private val logger = LoggerFactory.getLogger(id)

    private val schemas: JsonObject = JsonObject()
    private val defaultFieldName: String = "DisplayName"
    private val enableGraphiQL: Boolean = config.getBoolean("GraphiQL", false)
    private val writeSchemaFiles: Boolean = config.getBoolean("WriteSchemaToFile", false)

    init {
        Logger.getLogger(id).level = Level.ALL
    }

    override fun start(startPromise: Promise<Void>) {
        fun build(schema: String, wiring: RuntimeWiring.Builder): GraphQL{
            try {
                val typeDefinitionRegistry = SchemaParser().parse(schema)
                val graphQLSchema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, wiring.build())
                return GraphQL.newGraphQL(graphQLSchema).build()
            } catch (e: Exception) {
                e.printStackTrace()
                throw java.lang.Exception(e.message)
            }
        }
        val schemas = config.getJsonArray("Schemas", JsonArray())
        if (schemas.isEmpty) {
            getGenericSchema().let { (schema, wiring) ->
                startGraphQLServer(build(schema, wiring))
                startPromise.complete()
            }
        } else {
            logger.info("Fetch schemas...")
            val (generic, wiring) = getGenericSchema(withSytems = true)

            val systems = schemas.filterIsInstance<JsonObject>().map { it.getString("System") }

            val results = systems.map { system ->
                val promise = Promise.promise<String>()
                val fieldName = config.getString("FieldName", defaultFieldName) // DisplayName or BrowseName
                fetchSchema(system).onComplete {
                    logger.info("Build GraphQL [{}] ...", system)
                    val result = getSystemSchema(system, fieldName, wiring)
                    logger.info("Build GraphQL [{}] [{}]...complete", system, result.length)
                    promise.complete(result)
                }
                promise.future()
            }

            val systemTypes = "type Systems {\n" + systems.joinToString(separator = "\n") { "  $it: $it" } + "\n}"

            val dataFetcher = getSchemaNode("", "", "", "", "")
            wiring.type(
                TypeRuntimeWiring.newTypeWiring("Query")
                    .dataFetcher("Systems", dataFetcher))

            CompositeFuture.all(results).onComplete {
                val schema = generic + systemTypes + (results.map { it.result() }.joinToString(separator = "\n"))

                if (writeSchemaFiles)
                    File("graphql.gql").writeText(schema)

                logger.info("Generate GraphQL schema...")
                val graphql = build(schema, wiring)
                logger.info("Startup GraphQL server...")
                startGraphQLServer(graphql)
                logger.info("GraphQL ready")
                startPromise.complete()
            }
        }
    }

    private fun fetchSchema(system: String): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val serviceHandler = ServiceHandler(vertx)

        val type = Topic.SystemType.Opc.name
        serviceHandler.observeService(type, system) {
            logger.info("Fetch schema [{}][{}]", type, system)
            vertx.eventBus().request<JsonObject>("${type}/${system}/Schema", JsonObject()) {
                logger.info("Schema response [{}]", it.succeeded())
                schemas.put(system, it.result()?.body() ?: JsonObject())
                promise.complete(it.succeeded())
            }
        }
        return promise.future()
    }

    private fun getSystemSchema(system: String, fieldName: String, wiring: RuntimeWiring.Builder): String {
        val types = mutableListOf<String>()

        class Recursion { // needed, otherwise inline functions cannot be called from each other
            fun addNode(node: JsonObject, path: String, wiring: TypeRuntimeWiring.Builder): String? {
                val browseName = node.getString("BrowseName")
                val displayName = node.getString("DisplayName")
                val nodeId = node.getString("NodeId")
                val nodeClass = node.getString("NodeClass")
                val nodes = node.getJsonArray("Nodes", JsonArray())

                val graphqlName0 = node.getString(fieldName, browseName)
                val graphqlName1 = "[^A-Za-z0-9]".toRegex().replace(graphqlName0, "_")
                val graphqlName2 = if (Character.isDigit(graphqlName1[0])) "_$graphqlName1" else graphqlName1
                val graphqlName = "^__".toRegex().replace(graphqlName2, "_") // __ is reserved GraphQL internal

                // TODO: what happens when the names are not unique anymore (because of substitutions...)
                val dataFetcher = getSchemaNode(system, nodeId, nodeClass, browseName, displayName)
                wiring.dataFetcher(graphqlName, dataFetcher)

                return when (nodeClass) {
                    "Variable" -> {
                        "$graphqlName : Node"
                    }
                    "Object" -> {
                        val newTypeName = "${path}_${graphqlName}"
                        if (addNodes(nodes, newTypeName))
                           "$graphqlName : $newTypeName"
                        else {
                            logger.error("cannot add nodes of $newTypeName")
                            null
                        }
                    }
                    else -> {
                        logger.error("unhandled node class $nodeClass")
                        null
                    }
                }
            }

            fun addNodes(nodes: JsonArray, path: String): Boolean {
                val items = mutableListOf<String>()

                val validNodes = nodes.filterIsInstance<JsonObject>()
                if (validNodes.isEmpty()) return false

                val newTypeWiring = TypeRuntimeWiring.newTypeWiring(path)
                val addedNodes = validNodes.mapNotNull { node -> addNode(node, path, newTypeWiring) }
                if (addedNodes.isEmpty()) return false

                addedNodes.forEach { items.add(it) }
                wiring.type(newTypeWiring)
                types.add("type $path { \n ${items.joinToString(separator = "\n ")} \n}")
                return true;
            }
        }

        try {
            val dataFetcher = getSchemaNode(system, "", "", "", "")
            wiring.type(
                TypeRuntimeWiring.newTypeWiring("Systems")
                    .dataFetcher(system, dataFetcher))
            schemas
                .getJsonObject(system, JsonObject())
                .getJsonArray("Objects", JsonArray())
                .let { Recursion().addNodes(it, system) }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val schema = types.joinToString(separator = "\n")
        if (writeSchemaFiles)
            File("graphql-${system}.gql".toLowerCase()).writeText(schema)

        return schema
    }

    private fun getGenericSchema(withSytems: Boolean = false): Pair<String, RuntimeWiring.Builder> {
        // enum Type must match the Globals.BUS_ROOT_URI_*
        val schema = """
            | enum Type { 
            |   Opc
            |   Plc
            | }  
            | 
            | type Query {
            |   ServerInfo(System: String): ServerInfo
            |   
            |   NodeValue(Type: Type, System: String, NodeId: ID!): Value 
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]): [Value]
            |   BrowseNode(Type: Type, System: String, NodeId: ID, Filter: String): [Node]
            |   FindNodes(Type: Type, System: String, NodeId: ID, Filter: String): [Node]
            |   
            |   ${if (withSytems) "Systems: Systems" else ""}
            | }
            | 
            | type Mutation {
            |   NodeValue(Type: Type, System: String, NodeId: ID!, Value: String!): Boolean
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]!, Values: [String!]!): [Boolean]
            | }
            | 
            | type Subscription {
            |   NodeValue(Type: Type, System: String, NodeId: ID!): Value
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]!): Value
            | }
            | 
            | type Value {
            |   System: String
            |   NodeId: ID
            |   Value: String
            |   DataType: String
            |   DataTypeId: Int
            |   StatusCode: String
            |   SourceTime: String
            |   ServerTime: String
            |   History(Log: ID, From: String, To: String, LastSeconds: Int): [Value]   
            | }
            | 
            | type Node {
            |   System: String
            |   NodeId: ID
            |   BrowseName: String
            |   DisplayName: String
            |   NodeClass: String
            |   Value: Value
            |   Nodes(Filter: String): [Node]
            |   History(Log: ID, From: String, To: String, LastSeconds: Int): [Value]
            |   SetValue(Value: String): Boolean
            | }
            | 
            | type ServerInfo {
            |   Server: [String]
            |   Namespace: [String]
            |   BuildInfo: String
            |   StartTime: String
            |   CurrentTime: String
            |   ServerStatus: String
            | }
            |
            |
            """.trimMargin()

        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
            .type(
                TypeRuntimeWiring.newTypeWiring("Query")
                    .dataFetcher("ServerInfo", getServerInfo())
                    .dataFetcher("NodeValue", getNodeValue())
                    .dataFetcher("NodeValues", getNodeValues())
                    .dataFetcher("BrowseNode", getBrowseNode())
                    .dataFetcher("FindNodes", getFindNodes())
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Mutation")
                    .dataFetcher("NodeValue", setNodeValue())
                    .dataFetcher("NodeValues", setNodeValues())
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Subscription")
                    .dataFetcher("NodeValue", this::subNodeValue)
                    .dataFetcher("NodeValues", this::subNodeValues)
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Node")
                    .dataFetcher("Value", getNodeValue())
                    .dataFetcher("Nodes", getBrowseNode())
                    .dataFetcher("History", getValueHistory())
                    .dataFetcher("SetValue", setNodeValue())
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Value")
                    .dataFetcher("History", getValueHistory())
            )
        return Pair(schema, runtimeWiring)
    }

    private fun startGraphQLServer(graphql: GraphQL) {
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())
        router.route("/graphql").handler(ApolloWSHandler.create(graphql))
        router.route("/graphql").handler(GraphQLHandler.create(graphql))

        if (enableGraphiQL) {
            val options = GraphiQLHandlerOptions().setEnabled(true)
            router.route("/graphiql/*").handler(GraphiQLHandler.create(options))
        }

        val httpServerOptions = HttpServerOptions()
            .setWebSocketSubProtocols(listOf("graphql-ws"))
        val httpServer = vertx.createHttpServer(httpServerOptions)
        val httpPort = config.getInteger("Port", 4000)
        httpServer.requestHandler(router).listen(httpPort)
    }

    private fun getServerInfo(): DataFetcher<CompletableFuture<Map<String, Any?>>> {
        return DataFetcher<CompletableFuture<Map<String, Any?>>> { env ->
            val promise = CompletableFuture<Map<String, Any?>>()
            val (type, system) = getEnvTypeAndSystem(env)
            try {
                vertx.eventBus().request<JsonObject>("$type/$system/ServerInfo", JsonObject()) {
                    logger.debug("getServerInfo read response [{}] [{}]", it.succeeded(), it.result()?.body())
                    if (it.succeeded()) {
                        val result = it.result().body().getJsonObject("Result")
                        val map = HashMap<String, Any>()
                        map["Server"] = result.getJsonArray("Server").toList()
                        map["Namespace"] = result.getJsonArray("Namespace").toList()
                        map["BuildInfo"] = result.getString("BuildInfo")
                        map["StartTime"] = result.getString("StartTime")
                        map["CurrentTime"] = result.getString("CurrentTime")
                        map["ServerStatus"] = result.getString("ServerStatus")
                        promise.complete(map)
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }
            promise
        }
    }

    private fun getEnvArgument(env: DataFetchingEnvironment, name: String): String? {
        val ctx: Map<String, Any>? = env.getSource()
        return env.getArgumentOrDefault(name, ctx?.get(name) as String?)
    }

    private fun getEnvTypeAndSystem(env: DataFetchingEnvironment): Pair<String, String> {
        val ctx: Map<String, Any>? = env.getSource()
        val type: String = env.getArgument("Type")
            ?: ctx?.get("Type") as String?
            ?: defaultType

        val system: String = env.getArgument("System")
            ?: ctx?.get("System") as String?
            ?: defaultSystem

        return Pair(type, system)
    }

    private fun getNodeValue(): DataFetcher<CompletableFuture<Map<String, Any?>>> {
        return DataFetcher<CompletableFuture<Map<String, Any?>>> { env ->
            val promise = CompletableFuture<Map<String, Any?>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId: String = getEnvArgument(env,"NodeId") ?: ""

            val request = JsonObject()
            request.put("NodeId", nodeId)

            try {
                logger.debug("getNodeValue read request...")
                vertx.eventBus().request<JsonObject>("$type/$system/Read", request) {
                    logger.debug("getNodeValue read response [{}] [{}]", it.succeeded(), it.result()?.body())
                    if (it.succeeded()) {
                        try {
                            val data = it.result().body().getJsonObject("Result")
                            if (data!=null) {
                                val input = Value.decodeFromJson(data)
                                val result = valueToGraphQL(type, system, nodeId, input)
                                promise.complete(result)
                            } else {
                                logger.warn("No result in read response!")
                                promise.complete(null)
                            }
                        } catch (e: Exception) {
                            logger.error(e.message)
                            promise.complete(null)
                        }
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }

            promise
        }
    }

    private fun getNodeValues(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeIds = env?.getArgument("NodeIds") ?: listOf<String>()

            val request = JsonObject()
            val nodes = JsonArray()
            nodeIds.forEach { nodes.add(it) }
            request.put("NodeId", nodes)

            try {
                vertx.eventBus().request<JsonObject>("$type/$system/Read", request) { response ->
                    logger.debug("getNodeValues read response [{}] [{}]", response.succeeded(), response.result()?.body())
                    if (response.succeeded()) {
                        val list = response.result().body().getJsonArray("Result")
                        val result = nodeIds.zip(list.filterIsInstance<JsonObject>()).map {
                            valueToGraphQL(type, system, it.first, Value.decodeFromJson(it.second))
                        }
                        promise.complete(result)
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }

            promise
        }
    }

    private fun setNodeValue(): DataFetcher<CompletableFuture<Boolean>> {
        return DataFetcher<CompletableFuture<Boolean>> { env ->
            val promise = CompletableFuture<Boolean>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId: String = getEnvArgument(env, "NodeId") ?: ""
            val value: String = getEnvArgument(env, "Value") ?: ""

            val request = JsonObject()
            request.put("NodeId", nodeId)
            request.put("Value", value)

            logger.info("setNodeValue $type $system $nodeId $value")
            try {
                vertx.eventBus().request<JsonObject>("$type/$system/Write", request) {
                    logger.debug("setNodeValue write response [{}] [{}]", it.succeeded(), it.result()?.body())
                    promise.complete(
                        if (it.succeeded()) {
                            it.result().body().getBoolean("Ok")
                        } else {
                            false
                        })
                }
            } catch (e: Exception){
                e.printStackTrace()
            }

            promise
        }
    }

    private fun setNodeValues(): DataFetcher<CompletableFuture<List<Boolean>>> {
        return DataFetcher<CompletableFuture<List<Boolean>>> { env ->
            val promise = CompletableFuture<List<Boolean>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeIds = env.getArgument("NodeIds") as List<String>
            val values = env.getArgument("Values") as List<String>

            val request = JsonObject()
            request.put("NodeId", nodeIds)
            request.put("Value", values)

            try {
                vertx.eventBus().request<JsonObject>("$type/$system/Write", request) {
                    logger.debug("setNodeValue write response [{}] [{}]", it.succeeded(), it.result()?.body())
                    promise.complete(
                        if (it.succeeded()) {
                            it.result()
                                .body()
                                .getJsonArray("Ok")
                                .map { ok -> ok as? Boolean ?: false }
                        } else {
                            nodeIds.map { false }
                        })
                }
            } catch (e: Exception){
                e.printStackTrace()
            }

            promise
        }
    }

    private fun getBrowseNode(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId = getEnvArgument(env, "NodeId") ?: "i=85"
            val filter: String? = getEnvArgument(env,"Filter")

            val request = JsonObject()
            request.put("NodeId", nodeId)

            try {
                vertx.eventBus().request<JsonObject>("$type/$system/Browse", request) { message ->
                    logger.debug("getNodes browse response [{}] [{}]", message.succeeded(), message.result()?.body())
                    if (message.succeeded()) {
                        try {
                            val list = message.result().body().getJsonArray("Result")
                            val result = list
                                .filterIsInstance<JsonObject>()
                                .filter { filter == null || filter.toRegex().matches(it.getString("BrowseName"))}
                                .map { input ->
                                val item = HashMap<String, Any>()
                                item["System"] = system
                                item["NodeId"] = input.getString("NodeId")
                                item["BrowseName"] = input.getString("BrowseName")
                                item["DisplayName"] = input.getString("DisplayName")
                                item["NodeClass"] = input.getString("NodeClass")
                                item
                            }
                            promise.complete(result)
                        } catch (e: Exception) {
                            promise.completeExceptionally(e)
                        }
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }
            promise
        }
    }

    private fun getFindNodes(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId: String = getEnvArgument(env,"NodeId") ?: "i=85"
            val filter: String? = getEnvArgument(env,"Filter")

            val overallResult =  mutableListOf<HashMap<String, Any>>()

            fun find(nodeId: String): CompletableFuture<Boolean> {
                val promise = CompletableFuture<Boolean>()
                val request = JsonObject()
                request.put("NodeId", nodeId)
                vertx.eventBus()
                    .request<JsonObject>("$type/$system/Browse", request) { message ->
                        logger.debug("getNodes browse response [{}] [{}]", message.succeeded(), message.result()?.body())
                        if (message.succeeded()) {
                            val result = message.result().body().getJsonArray("Result")?.filterIsInstance<JsonObject>()
                            logger.debug("FindNodes result [{}]", result?.size)
                            if (result!=null) {
                                overallResult.addAll(result
                                    .filter { filter == null || filter.toRegex().matches(it.getString("BrowseName"))}
                                    .map { input ->
                                        val item = HashMap<String, Any>()
                                        item["System"] = system
                                        item["NodeId"] = input.getString("NodeId")
                                        item["BrowseName"] = input.getString("BrowseName")
                                        item["DisplayName"] = input.getString("DisplayName")
                                        item["NodeClass"] = input.getString("NodeClass")
                                        item
                                    }
                                )
                                val next = result
                                    .filter { it.getString("NodeClass") == "Object" }
                                    .map { find(it.getString("NodeId")) }

                                if (next.isNotEmpty()) {
                                    CompletableFuture.allOf(*next.toTypedArray()).thenAccept { promise.complete(true) }
                                } else {
                                    promise.complete(true)
                                }
                            } else promise.complete(false)
                        } else promise.complete(false)
                    }
                return promise
            }

            val promise = CompletableFuture<List<Map<String, Any?>>>()
            find(nodeId).thenAccept { promise.complete(overallResult) }
            promise
        }
    }

    private fun subNodeValue(env: DataFetchingEnvironment): Flowable<Map<String, Any?>> {
        val uuid = UUID.randomUUID().toString()

        val (type, system) = getEnvTypeAndSystem(env)
        val nodeId: String = env.getArgument("NodeId") ?: ""

        val topic = Topic.parseTopic("$type/$system/node:json/$nodeId")
        val flowable = Flowable.create(FlowableOnSubscribe<Map<String, Any?>> { emitter ->
            val consumer = vertx.eventBus().consumer<Buffer>(topic.topicName) { message ->
                try {
                    val data = message.body().toJsonObject()
                    val output = Value.decodeFromJson(data.getJsonObject("Value"))
                    if (!emitter.isCancelled) emitter.onNext(valueToGraphQL(type, system, nodeId, output))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            emitter.setCancellable {
                logger.info("Unsubscribe [{}] [{}]", consumer.address(), uuid)
                consumer.unregister()
                val request = JsonObject().put("ClientId", uuid).put("Topics", listOf(topic.encodeToJson()))
                vertx.eventBus().request<JsonObject>("${topic.systemType}/${topic.systemName}/Unsubscribe", request) {
                    logger.info("Unsubscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
                }
            }
        }, BackpressureStrategy.BUFFER)

        val request = JsonObject().put("ClientId", uuid).put("Topic", topic.encodeToJson())
        vertx.eventBus().request<JsonObject>("${topic.systemType}/${topic.systemName}/Subscribe", request) {
            if (it.succeeded()) {
                logger.info("Subscribe response [{}] [{}] [{}]", topic.topicName, it.result().body().getBoolean("Ok"), uuid)
            } else {
                logger.info("Subscribe not succeeded!")
            }
        }

        return flowable
    }

    private fun subNodeValues(env: DataFetchingEnvironment): Flowable<Map<String, Any?>> {
        val uuid = UUID.randomUUID().toString()

        val (type, system) = getEnvTypeAndSystem(env)
        val nodeIds = env.getArgument("NodeIds") ?: listOf<String>()

        val flowable = Flowable.create(FlowableOnSubscribe<Map<String, Any?>> { emitter ->
            val consumers = nodeIds.map { nodeId ->
                val topic = "$type/$system/node:json/$nodeId"
                vertx.eventBus().consumer<Buffer>(topic) { message ->
                    try {
                        val data = message.body().toJsonObject()
                        val output = Value.decodeFromJson(data.getJsonObject("Value"))
                        if (!emitter.isCancelled) emitter.onNext(valueToGraphQL(type, system, nodeId, output))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            emitter.setCancellable {
                consumers.forEach { consumer ->
                    logger.info("Unsubscribe [{}] [{}]", consumer.address(), uuid)
                    consumer.unregister()
                    val topic = Topic.parseTopic(consumer.address())
                    val request = JsonObject().put("ClientId", uuid).put("Topics", listOf(topic.encodeToJson()))
                    vertx.eventBus().request<JsonObject>("${topic.systemType}/${system}/Unsubscribe", request) {
                        logger.info("Unsubscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
                    }
                }
            }
        }, BackpressureStrategy.BUFFER)

        nodeIds.forEach { nodeId ->
            val topic = Topic.parseTopic("$type/$system/node:json/$nodeId")
            val request = JsonObject().put("ClientId", uuid).put("Topic", topic.encodeToJson())
            vertx.eventBus().request<JsonObject>("${topic.systemType}/${topic.systemName}/Subscribe", request) {
                if (it.succeeded()) {
                    logger.info("Subscribe response [{}] [{}] [{}]", topic.topicName, it.result().body().getBoolean("Ok"), uuid)
                } else {
                    logger.info("Subscribe not succeeded!")
                }
            }
        }
        return flowable
    }

    private val timeFormatterISO = DateTimeFormatter.ISO_DATE_TIME


    private fun getValueHistory(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()
            if (env==null) {
                promise.complete(listOf())
            } else {
                val log: String = getEnvArgument(env,"Log") ?: "default"

                val (_, system) = getEnvTypeAndSystem(env)
                val nodeId: String = getEnvArgument(env, "NodeId") ?: ""

                var t1 = Instant.now()
                var t2 = Instant.now()

                val t1arg = env.getArgument<String>("From")
                if (t1arg!=null)
                    t1 = Instant.from(timeFormatterISO.parse(t1arg))

                val t2arg = env.getArgument<String>("To")
                if (t2arg!=null)
                    t2 = Instant.from(timeFormatterISO.parse(t2arg))

                val lastSeconds: Int? = env.getArgument("LastSeconds")
                if (lastSeconds!=null) {
                    t1 = (Instant.now()).minusSeconds(lastSeconds.toLong())
                }

                val request = JsonObject()
                request.put("System", system)
                request.put("NodeId", nodeId)
                request.put("T1", t1.toEpochMilli())
                request.put("T2", t2.toEpochMilli())

                vertx.eventBus()
                    .request<JsonObject>("${Globals.BUS_ROOT_URI_LOG}/$log/QueryHistory", request) { message ->
                        val list =  message.result()?.body()?.getJsonArray("Result") ?: JsonArray()
                        logger.info("Query response [{}] size [{}]", message.succeeded(), list.size())
                        val result = list.filterIsInstance<JsonArray>().map {
                            val item = HashMap<String, Any?>()
                            item["NodeId"] = nodeId
                            item["SourceTime"] = it.getValue(0)
                            item["Value"] = it.getValue(1)
                            item["StatusCode"] = it.getValue(2)
                            item["System"] = it.getValue(3)
                            item
                        }
                        promise.complete(result)
                    }
            }
            promise
        }
    }

    private fun getSchemaNode(system: String, nodeId: String, nodeClass: String, browseName: String, displayName: String): DataFetcher<CompletableFuture<Map<String, Any?>>> {
        return DataFetcher<CompletableFuture<Map<String, Any?>>> {
            val promise = CompletableFuture<Map<String, Any?>>()
            val item = HashMap<String, Any>()
            if (nodeClass=="Variable") {
                item["System"] = system
                item["NodeId"] = nodeId
                item["BrowseName"] = browseName
                item["DisplayName"] = displayName
                item["NodeClass"] = nodeClass
            }
            promise.complete(item)
            promise
        }
    }

    private fun valueToGraphQL(type: String, system: String, nodeId: String, input: Value): HashMap<String, Any?> {
        val item = HashMap<String, Any?>()
        item["Type"] = type
        item["System"] = system
        item["NodeId"] = nodeId
        item["Value"] = input.value?.toString()
        item["DataType"] = input.dataTypeName
        item["DataTypeId"] = input.dataTypeId
        item["StatusCode"] = input.statusCode.toString()
        item["SourceTime"] = input.sourceTimeAsISO()
        item["ServerTime"] = input.serverTimeAsISO()
        return item
    }

}