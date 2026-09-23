package com.elyndra.launcher.masha

import com.elyndra.launcher.data.db.AiCacheDao
import com.elyndra.launcher.data.db.AiCacheEntity
import com.elyndra.launcher.masha.deepseek.DeepSeekMashaAI
import com.elyndra.launcher.masha.deepseek.DeepSeekProtocol
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** El protocolo de DeepSeek y el cliente entero contra un servidor falso. */
class DeepSeekTest {

    /* ── protocolo ────────────────────────────────────────────── */

    @Test
    fun sseLinesAreClassified() {
        assertEquals(DeepSeekProtocol.SseLine.Data("{\"a\":1}"), DeepSeekProtocol.parseLine("data: {\"a\":1}"))
        assertEquals(DeepSeekProtocol.SseLine.Done, DeepSeekProtocol.parseLine("data: [DONE]"))
        assertEquals(DeepSeekProtocol.SseLine.Skip, DeepSeekProtocol.parseLine(": keep-alive"))
        assertEquals(DeepSeekProtocol.SseLine.Skip, DeepSeekProtocol.parseLine(""))
        assertEquals(DeepSeekProtocol.SseLine.Data("{}"), DeepSeekProtocol.parseLine("data:{}\r"))
    }

    @Test
    fun toolCallsArriveInPiecesAndAreReassembled() {
        val acc = DeepSeekProtocol.StreamAccumulator()
        acc.accept("""{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""")
        acc.accept("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"find_games","arguments":""}}]}}]}""")
        acc.accept("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"que"}}]}}]}""")
        acc.accept("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ry\":\"zelda\"}"}}]}}]}""")
        acc.accept("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":64}}""")
        val calls = acc.toolCalls()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("find_games", calls[0].name)
        assertEquals("zelda", calls[0].arguments.str("query"))
        assertEquals("tool_calls", acc.finishReason)
        assertEquals(TokenUsage(100, 20, 64), acc.usage)
    }

    @Test
    fun brokenArgumentsBecomeAnEmptyObject() {
        val acc = DeepSeekProtocol.StreamAccumulator()
        acc.accept("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"get_arcs","arguments":"{nope"}}]}}]}""")
        assertEquals(JsonObject(emptyMap()), acc.toolCalls().single().arguments)
    }

    @Test
    fun requestCarriesToolsAndTheToolRoundTrip() {
        val call = ToolCall("call_9", "launch_game", buildJsonObject { put("title", "Okami") })
        val body = DeepSeekProtocol.requestBody(
            model = "deepseek-chat",
            messages = listOf(
                DeepSeekProtocol.Message.System("sys"),
                DeepSeekProtocol.Message.User("play okami"),
                DeepSeekProtocol.Message.Assistant("", listOf(call)),
                DeepSeekProtocol.Message.Tool("call_9", """{"ok":true}"""),
            ),
            tools = MashaTools.specs,
            stream = true,
            temperature = 1.0,
            maxTokens = 900,
        )
        val root = DeepSeekProtocol.json.parseToJsonElement(body).jsonObject
        assertEquals("deepseek-chat", root["model"]!!.jsonPrimitive.content)
        assertEquals("auto", root["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(MashaTools.specs.size, root["tools"]!!.jsonArray.size)
        val assistant = root["messages"]!!.jsonArray[2].jsonObject
        // Los argumentos viajan como texto JSON, no como objeto.
        val args = assistant["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive
        assertTrue(args.isString)
        assertEquals("call_9", root["messages"]!!.jsonArray[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun httpErrorsMapToMashaErrors() {
        assertEquals(MashaError.Unauthorized, DeepSeekProtocol.errorFor(401, """{"error":{"message":"bad key"}}"""))
        assertEquals(MashaError.InsufficientBalance, DeepSeekProtocol.errorFor(402, ""))
        assertEquals(MashaError.RateLimited, DeepSeekProtocol.errorFor(429, ""))
        assertEquals(MashaError.Unavailable(503), DeepSeekProtocol.errorFor(503, ""))
        assertEquals(MashaError.BadRequest(422, "wrong"), DeepSeekProtocol.errorFor(422, """{"error":{"message":"wrong"}}"""))
    }

    @Test
    fun toolSchemasAreWellFormed() {
        val names = MashaTools.specs.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(MashaTools.MUTATING.all { it in names })
        MashaTools.specs.forEach { spec ->
            assertEquals("object", spec.parameters["type"]!!.jsonPrimitive.content)
            val properties = spec.parameters["properties"]!!.jsonObject.keys
            val required = spec.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertTrue("${spec.name}: $required ⊄ $properties", properties.containsAll(required))
        }
    }

    /* ── cliente contra un servidor falso ─────────────────────── */

    private lateinit var server: MockWebServer
    private lateinit var ai: DeepSeekMashaAI
    private var key = "sk-test"

    private fun sse(vararg chunks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + ": keep-alive\n\ndata: [DONE]\n\n")

    private fun text(piece: String) = """{"choices":[{"index":0,"delta":{"content":${JsonPrimitive(piece)}}}]}"""

    private val stop = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"prompt_cache_hit_tokens":0}}"""

    private fun request(message: String, cacheable: Boolean = false) =
        MashaChatRequest(emptyList(), message, "{}", "fp-1", "en", cacheable)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val endpoint = object : MashaEndpoint {
            override val apiKey get() = key
            override val model = "deepseek-chat"
            override val baseUrl = server.url("/").toString().trimEnd('/')
            override val onlineEnabled = true
        }
        val client = OkHttpClient()
        ai = DeepSeekMashaAI(endpoint, { client }, MashaCache(MemoryCacheDao()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun textStreamsAndCompletes() = runBlocking {
        server.enqueue(sse(text("Okami "), text("is "), text("waiting."), stop))
        val events = ai.chat(request("what now?"), toolbox = null).toList()
        assertEquals(listOf("Okami ", "is ", "waiting."), events.filterIsInstance<MashaEvent.Delta>().map { it.text })
        val done = events.last() as MashaEvent.Completed
        assertEquals("Okami is waiting.", done.text)
        assertEquals(10, done.usage?.promptTokens)

        val sent = server.takeRequest()
        assertEquals("/chat/completions", sent.path)
        assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
    }

    @Test
    fun toolsRunAndTheModelContinues() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_7","type":"function","function":{"name":"find_games","arguments":"{\"list\":\"never_opened\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )
        server.enqueue(sse(text("Three untouched gems."), stop))
        val toolbox = RecordingToolbox()
        val events = ai.chat(request("never opened?"), toolbox).toList()

        assertEquals(listOf("find_games"), toolbox.calls.map { it.name })
        assertEquals("never_opened", toolbox.calls.single().arguments.str("list"))
        assertTrue(events.any { it is MashaEvent.ToolStarted })
        val finished = events.filterIsInstance<MashaEvent.ToolFinished>().single()
        assertEquals(MashaAttachment.Games(null, listOf("r:a")), finished.result.attachment)
        assertEquals("Three untouched gems.", (events.last() as MashaEvent.Completed).text)

        // La segunda petición lleva la llamada y su resultado.
        server.takeRequest()
        val second = DeepSeekProtocol.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = second["messages"]!!.jsonArray
        val tool = messages.last().jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_7", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(messages[messages.size - 2].jsonObject.containsKey("tool_calls"))
    }

    @Test
    fun aRejectedKeyFailsCleanly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Authentication Fails"}}"""))
        val event = ai.chat(request("hi"), null).toList().single()
        assertEquals(MashaEvent.Failed(MashaError.Unauthorized, ""), event)
    }

    @Test
    fun noBalanceIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"message":"Insufficient Balance"}}"""))
        val event = ai.chat(request("hi"), null).toList().single() as MashaEvent.Failed
        assertEquals(MashaError.InsufficientBalance, event.error)
    }

    @Test
    fun withoutKeyNothingIsSent() = runBlocking {
        key = ""
        val event = ai.chat(request("hi"), null).toList().single()
        assertEquals(MashaEvent.Failed(MashaError.NotConfigured, ""), event)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theSameQuestionIsAnsweredFromCache() = runBlocking {
        server.enqueue(sse(text("Try Tetris."), stop))
        val first = ai.chat(request("What should I play?", cacheable = true), null).toList().last() as MashaEvent.Completed
        val second = ai.chat(request("what should   I play?", cacheable = true), null).toList().last() as MashaEvent.Completed
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertEquals(first.text, second.text)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anAnswerThatChangedSomethingIsNeverCached() = runBlocking {
        val launch = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"launch_game","arguments":"{\"title\":\"Okami\"}"}}]}}]}"""
        repeat(2) {
            server.enqueue(sse(launch, """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""))
            server.enqueue(sse(text("Launching."), stop))
        }
        val toolbox = RecordingToolbox()
        ai.chat(request("play okami", cacheable = true), toolbox).toList()
        ai.chat(request("play okami", cacheable = true), toolbox).toList()
        assertEquals(2, toolbox.calls.size)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun pingReadsTheBalance() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"4.20"}]}"""))
        assertEquals("4.20 USD", ai.ping().getOrNull())
        server.enqueue(MockResponse().setBody("""{"is_available":false,"balance_infos":[]}"""))
        assertEquals(MashaError.InsufficientBalance, ai.ping().exceptionOrNull())
    }

    private class RecordingToolbox : MashaToolbox {
        val calls = ArrayList<ToolCall>()
        override val specs: List<ToolSpec> = MashaTools.specs
        override suspend fun execute(call: ToolCall): ToolResult {
            calls += call
            return toolOk(attachment = MashaAttachment.Games(null, listOf("r:a")), mutating = call.name in MashaTools.MUTATING) {
                put("count", 3)
            }
        }
    }

    private class MemoryCacheDao : AiCacheDao {
        private val rows = HashMap<String, AiCacheEntity>()
        override suspend fun get(key: String, now: Long) = rows[key]?.takeIf { it.expiresAt > now }
        override suspend fun put(entry: AiCacheEntity) {
            rows[entry.key] = entry
        }
        override suspend fun evictExpired(now: Long) {
            rows.values.removeIf { it.expiresAt <= now }
        }
        override suspend fun clear() = rows.clear()
    }
}
