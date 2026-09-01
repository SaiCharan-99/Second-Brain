package com.secondbrain.agent

import com.secondbrain.model.AgentConfig
import com.secondbrain.model.Phase
import com.secondbrain.model.ToolClass
import com.secondbrain.model.TurnEnd
import com.secondbrain.model.TurnUsage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The agent loop, exercised through a fake [com.secondbrain.ports.LlmPort].
 *
 * CLAUDE.md's `:agent` bar: "Tool dispatch, schema validation, iteration cap, gate
 * suspend/resume, ledger state machine including every crash-recovery path."
 * Gates and the ledger are Step 5; everything else is here.
 */
class AgentLoopTest {

    private val config = AgentConfig(apiKey = "test", maxIterations = 12, maxToolExecutions = 24)
    private val prompts = SystemPrompt()

    /** Records what was dispatched, so tests can assert on tool traffic. */
    private class Recorder {
        val calls = mutableListOf<Pair<String, String>>()
        var failNext = false
        var throwNext = false
    }

    private fun registry(recorder: Recorder): ToolRegistry = ToolRegistry.builder()
        .autonomous(
            "vault_tree", "the tree",
            """{"type":"object","properties":{"depth":{"type":"integer"}},"required":[]}""",
        ) { input ->
            recorder.calls += "vault_tree" to input
            ToolOutcome("""{"tree":"Inbox (0 notes)"}""")
        }
        .autonomous(
            "vault_write_note", "write a note",
            """{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},"required":["title"]}""",
        ) { input ->
            recorder.calls += "vault_write_note" to input
            if (recorder.throwNext) {
                recorder.throwNext = false
                throw IllegalStateException("disk on fire")
            }
            if (recorder.failNext) {
                recorder.failNext = false
                ToolOutcome("""{"rejected":true,"reason":"duplicate"}""", isError = false)
            } else {
                ToolOutcome("""{"path":"Inbox/a-note.md"}""", notePath = "Inbox/a-note.md")
            }
        }
        .autonomous(
            "vault_search", "search",
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
        ) { input ->
            recorder.calls += "vault_search" to input
            ToolOutcome("""{"hits":[]}""")
        }
        .build()

    private fun loop(llm: FakeLlm, recorder: Recorder, cfg: AgentConfig = config): AgentLoop {
        val reg = registry(recorder)
        return AgentLoop(llm, reg, ToolDispatcher(reg), prompts, cfg)
    }

    private suspend fun run(
        llm: FakeLlm,
        recorder: Recorder = Recorder(),
        cfg: AgentConfig = config,
        cancellation: AgentLoop.Cancellation = AgentLoop.Cancellation(),
    ) = loop(llm, recorder, cfg).run(
        utterance = "offline inference is the moat",
        phase = Phase.CAPTURE,
        history = emptyList(),
        conversationId = "conv-1",
        turnIndex = 0,
        cancellation = cancellation,
    )

    @Nested
    @DisplayName("the happy path")
    inner class HappyPath {

        @Test
        fun `tool call then text reply`() = runTest {
            val recorder = Recorder()
            val llm = FakeLlm().enqueue(
                FakeLlm.toolCall("vault_tree", """{"depth":3}"""),
                FakeLlm.toolCall("vault_write_note", """{"title":"The moat","body":"text"}"""),
                FakeLlm.text("Saved to Inbox as 'The moat'."),
            )

            val output = run(llm, recorder)

            assertEquals(TurnEnd.END_TURN, output.result.end)
            assertEquals("Saved to Inbox as 'The moat'.", output.result.spokenText)
            assertEquals(3, output.result.iterations)
            assertEquals(listOf("vault_tree", "vault_write_note"), recorder.calls.map { it.first })
        }

        @Test
        @DisplayName("H27: the note path reaches the caller as structured data, not prose")
        fun `structured result`() = runTest {
            val llm = FakeLlm().enqueue(
                FakeLlm.toolCall("vault_write_note", """{"title":"The moat"}"""),
                FakeLlm.text("Saved."),
            )
            val output = run(llm)

            // The design board's "Open note" and "Move" chips need this, and would
            // otherwise have to regex the spoken sentence.
            assertEquals(listOf("Inbox/a-note.md"), output.result.touchedNotes)
            val event = output.result.toolEvents.single { it.name == "vault_write_note" }
            assertEquals("Inbox/a-note.md", event.notePath)
            assertEquals(ToolClass.AUTONOMOUS, event.toolClass)
            assertFalse(event.isError)
        }

        @Test
        @DisplayName("thinking blocks are kept in the replayed assistant turn")
        fun `thinking round trips`() = runTest {
            val llm = FakeLlm().enqueue(
                FakeLlm.thinkingThenToolCall("vault_tree", "{}"),
                FakeLlm.text("Done."),
            )
            val output = run(llm)

            val assistantBlocks = output.messages
                .filter { it.role.name == "ASSISTANT" }
                .flatMap { it.blocks }
            assertTrue(
                assistantBlocks.any { it is com.secondbrain.ports.LlmBlock.Thinking },
                "the thinking block must survive into the persisted conversation",
            )
        }
    }

    @Nested
    @DisplayName("H9 parallel tool calls")
    inner class Parallel {

        @Test
        @DisplayName("all calls in one response are executed")
        fun `all executed`() = runTest {
            val recorder = Recorder()
            val llm = FakeLlm().enqueue(
                FakeLlm.parallelToolCalls(
                    "vault_tree" to "{}",
                    "vault_search" to """{"query":"moat"}""",
                ),
                FakeLlm.text("Done."),
            )

            run(llm, recorder)
            assertEquals(listOf("vault_tree", "vault_search"), recorder.calls.map { it.first })
        }

        @Test
        @DisplayName("all results go back in ONE user message, or the next request is a 400")
        fun `single results message`() = runTest {
            val llm = FakeLlm().enqueue(
                FakeLlm.parallelToolCalls(
                    "vault_tree" to "{}",
                    "vault_search" to """{"query":"moat"}""",
                    "vault_write_note" to """{"title":"x"}""",
                ),
                FakeLlm.text("Done."),
            )

            val output = run(llm)

            val resultMessages = output.messages.filter { message ->
                message.blocks.any { it is com.secondbrain.ports.LlmBlock.ToolResult }
            }
            assertEquals(1, resultMessages.size, "three results must arrive as one message, not three")
            assertEquals(3, resultMessages.single().blocks.size)
        }

        @Test
        @DisplayName("every tool_use gets a tool_result, even the ones that error")
        fun `every call answered`() = runTest {
            val recorder = Recorder().apply { throwNext = true }
            val llm = FakeLlm().enqueue(
                FakeLlm.parallelToolCalls(
                    "vault_write_note" to """{"title":"x"}""",
                    "vault_tree" to "{}",
                ),
                FakeLlm.text("Done."),
            )

            val output = run(llm, recorder)

            val results = output.messages
                .flatMap { it.blocks }
                .filterIsInstance<com.secondbrain.ports.LlmBlock.ToolResult>()
            assertEquals(2, results.size, "a throwing handler must still produce a result")
            assertTrue(results.any { it.isError }, "the thrown failure must be marked is_error")
        }
    }

    @Nested
    @DisplayName("EC-A1 / H10 the caps")
    inner class Caps {

        @Test
        @DisplayName("twelve round-trips then a forced text reply")
        fun `iteration cap`() = runTest {
            val recorder = Recorder()
            // A model stuck calling vault_tree forever - EC-A1's named example.
            val llm = FakeLlm().apply {
                fallback = FakeLlm.toolCall("vault_tree", "{}")
            }

            val output = run(llm, recorder)

            assertEquals(TurnEnd.ITERATION_CAP, output.result.end)
            // 12 tool round-trips plus the one forced text reply.
            assertEquals(13, output.result.iterations)
            assertEquals(12, recorder.calls.size)
        }

        @Test
        @DisplayName("H10: parallel calls are capped by executions, not just round-trips")
        fun `execution cap`() = runTest {
            val recorder = Recorder()
            val cfg = config.copy(maxIterations = 12, maxToolExecutions = 6)
            val llm = FakeLlm().apply {
                fallback = FakeLlm.parallelToolCalls(
                    "vault_tree" to "{}",
                    "vault_search" to """{"query":"a"}""",
                    "vault_tree" to "{}",
                )
            }

            val output = run(llm, recorder, cfg)

            assertEquals(TurnEnd.ITERATION_CAP, output.result.end)
            // Without a separate execution cap, twelve round-trips of three calls
            // would have run 36 tools.
            assertTrue(recorder.calls.size <= 9, "ran ${recorder.calls.size} tools against a cap of 6")
            assertTrue(output.result.iterations < 12, "the execution cap should bite before the round-trip cap")
        }

        @Test
        @DisplayName("the cap notice is a system message, not a system-prompt edit")
        fun `cap notice channel`() = runTest {
            val llm = FakeLlm().apply { fallback = FakeLlm.toolCall("vault_tree", "{}") }
            val output = run(llm)

            // Editing the system prompt mid-conversation would invalidate the
            // cached prefix for the rest of the turn (H7).
            assertTrue(
                output.messages.any { it.role == com.secondbrain.ports.LlmMessage.Role.SYSTEM },
                "expected a mid-conversation system message carrying the cap notice",
            )
            val systemPrompts = llm.requests.map { it.systemPrompt }.distinct()
            assertEquals(1, systemPrompts.size, "the system prompt must be byte-identical on every request")
        }

        @Test
        @DisplayName("EC-A3 / H14: past the self-correction cap, stop calling tools")
        fun `self correction cap`() = runTest {
            val recorder = Recorder()
            val cfg = config.copy(maxSelfCorrections = 2)
            // The model keeps calling a tool that does not exist.
            val llm = FakeLlm().apply { fallback = FakeLlm.toolCall("no_such_tool", "{}") }

            val output = run(llm, recorder, cfg)

            assertEquals(TurnEnd.ITERATION_CAP, output.result.end)
            assertTrue(
                output.result.iterations <= 5,
                "should give up after ~3 bad attempts, took ${output.result.iterations}",
            )
            assertTrue(recorder.calls.isEmpty(), "no real tool should have run")
        }
    }

    @Nested
    @DisplayName("H12 every stop reason")
    inner class StopReasons {

        @Test
        @DisplayName("refusal arrives as HTTP 200 with empty content and must still speak")
        fun `refusal`() = runTest {
            val output = run(FakeLlm().enqueue(FakeLlm.refusal("cyber")))

            assertEquals(TurnEnd.REFUSAL, output.result.end)
            assertEquals("cyber", output.result.refusalCategory)
            assertTrue(output.result.spokenText.isNotBlank(), "silence would look like a crash")
            // The category is an API-internal label; reading it aloud is noise.
            assertFalse(output.result.spokenText.contains("cyber"))
        }

        @Test
        fun `max_tokens with no text still speaks something`() = runTest {
            val output = run(FakeLlm().enqueue(FakeLlm.maxTokens()))

            assertEquals(TurnEnd.MAX_TOKENS, output.result.end)
            assertTrue(output.result.spokenText.isNotBlank())
        }

        @Test
        fun `max_tokens keeps whatever text arrived`() = runTest {
            val output = run(FakeLlm().enqueue(FakeLlm.maxTokens("Saved to Inbox as")))
            assertEquals("Saved to Inbox as", output.result.spokenText)
        }

        @Test
        @DisplayName("EC-A7: an API failure speaks the error and preserves everything")
        fun `api failure`() = runTest {
            val output = run(FakeLlm().enqueue(FakeLlm.apiFailed("connection reset")))

            assertEquals(TurnEnd.API_FAILED, output.result.end)
            assertNotNull(output.result.error)
            assertTrue(output.result.spokenText.contains("saved", ignoreCase = true))
        }

        @Test
        @DisplayName("tool_use with no tool block does not loop forever")
        fun `empty tool use`() = runTest {
            val output = run(FakeLlm().enqueue(FakeLlm.toolUseWithNoBlocks()))
            assertEquals(TurnEnd.END_TURN, output.result.end)
            assertEquals(1, output.result.iterations)
        }
    }

    @Nested
    @DisplayName("D-048 cancellation")
    inner class Cancel {

        @Test
        @DisplayName("speaking during THINKING abandons the turn")
        fun `cancel before start`() = runTest {
            val cancellation = AgentLoop.Cancellation().apply { cancel() }
            val output = run(FakeLlm().enqueue(FakeLlm.text("never spoken")), cancellation = cancellation)

            assertEquals(TurnEnd.CANCELLED, output.result.end)
            assertEquals("", output.result.spokenText)
            assertEquals(0, output.result.iterations)
        }

        @Test
        @DisplayName("tokens already spent are still recorded - we paid for them")
        fun `cancel mid-turn still bills`() = runTest {
            val recorder = Recorder()
            val cancellation = AgentLoop.Cancellation()
            val reg = registry(recorder)

            // Cancel as soon as the first tool runs.
            val cancellingRegistry = ToolRegistry.builder()
                .autonomous("vault_tree", "the tree", """{"type":"object","properties":{},"required":[]}""") {
                    cancellation.cancel()
                    ToolOutcome("""{"tree":""}""")
                }
                .build()

            val llm = FakeLlm().apply { fallback = FakeLlm.toolCall("vault_tree", "{}") }
            val output = AgentLoop(llm, cancellingRegistry, ToolDispatcher(cancellingRegistry), prompts, config)
                .run("x", Phase.CAPTURE, emptyList(), "conv-1", 0, cancellation)

            assertEquals(TurnEnd.CANCELLED, output.result.end)
            assertTrue(output.result.usd > 0.0, "spend before the cancel must still be accounted for")
        }
    }

    @Nested
    @DisplayName("H1 / H5 cost and cache")
    inner class CostAndCache {

        @Test
        @DisplayName("all four token classes are summed, not just input_tokens")
        fun `usage accumulates`() = runTest {
            val usage = TurnUsage(inputTokens = 100, outputTokens = 50, cacheWriteTokens = 200, cacheReadTokens = 900)
            val llm = FakeLlm().enqueue(
                FakeLlm.toolCall("vault_tree", "{}", usage),
                FakeLlm.text("Done.", usage),
            )

            val output = run(llm)

            assertEquals(200, output.result.usage.inputTokens)
            assertEquals(100, output.result.usage.outputTokens)
            assertEquals(400, output.result.usage.cacheWriteTokens)
            assertEquals(1800, output.result.usage.cacheReadTokens)

            // Reading input_tokens alone would have priced 200 tokens instead of 2400.
            assertEquals(2400, output.result.usage.totalPromptTokens)
        }

        @Test
        @DisplayName("the price of each class is different, and the arithmetic reflects it")
        fun `pricing`() {
            val pricing = com.secondbrain.model.ModelPricing()
            val perMillion = { tokens: Long, usd: Double -> tokens * usd / 1_000_000.0 }

            // 1M of each class, priced separately.
            assertEquals(5.00, pricing.usdFor(TurnUsage(1_000_000, 0)), 1e-9)
            assertEquals(25.00, pricing.usdFor(TurnUsage(0, 1_000_000)), 1e-9)
            assertEquals(6.25, pricing.usdFor(TurnUsage(0, 0, cacheWriteTokens = 1_000_000)), 1e-9)
            assertEquals(0.50, pricing.usdFor(TurnUsage(0, 0, cacheReadTokens = 1_000_000)), 1e-9)

            // A cache read is ten times cheaper than uncached input, which is the
            // entire reason caching is on by default.
            assertTrue(
                pricing.usdFor(TurnUsage(0, 0, cacheReadTokens = 1_000_000)) * 10 ==
                    pricing.usdFor(TurnUsage(1_000_000, 0)),
            )
            assertEquals(perMillion(1_000_000, 5.0), 5.0, 1e-9)
        }

        @Test
        @DisplayName("H6: the system prompt is byte-identical across every request of a turn")
        fun `frozen system prompt`() = runTest {
            val llm = FakeLlm().enqueue(
                FakeLlm.toolCall("vault_tree", "{}"),
                FakeLlm.toolCall("vault_search", """{"query":"a"}"""),
                FakeLlm.text("Done."),
            )
            run(llm)

            assertEquals(1, llm.requests.map { it.systemPrompt }.distinct().size)
            // And nothing volatile leaked in.
            val prompt = llm.requests.first().systemPrompt
            assertFalse(prompt.contains("20"), "a year or date in the system prompt is a guaranteed cache miss")
        }

        @Test
        @DisplayName("H6: tool order and schemas are identical across requests")
        fun `stable tools`() = runTest {
            val llm = FakeLlm().enqueue(
                FakeLlm.toolCall("vault_tree", "{}"),
                FakeLlm.text("Done."),
            )
            run(llm)

            val signatures = llm.requests.map { request ->
                request.tools.joinToString("|") { it.name + ":" + it.inputSchemaJson }
            }.distinct()
            assertEquals(1, signatures.size, "a varying tool list or schema order silently kills the cache")
        }

        @Test
        @DisplayName("H5: breakpoints stay inside the 20-block lookback on a long turn")
        fun `breakpoint spacing`() = runTest {
            val recorder = Recorder()
            val llm = FakeLlm().apply { fallback = FakeLlm.toolCall("vault_tree", "{}") }
            run(llm, recorder)

            // The last request of a 12-call turn carries ~25 blocks. Every gap
            // between consecutive breakpoints must be under 20 blocks or the
            // lookback misses and the whole conversation is rewritten each time.
            val last = llm.requests.last()
            val marks = last.cacheMessageBreakpoints.sorted()
            assertTrue(marks.isNotEmpty(), "a long turn must carry message breakpoints")

            var blocks = 0
            var previousMark = -1
            var maxGap = 0
            last.messages.forEachIndexed { index, message ->
                blocks += message.blocks.size
                if (index in marks) {
                    if (previousMark >= 0) maxGap = maxOf(maxGap, blocks)
                    blocks = 0
                    previousMark = index
                }
            }
            assertTrue(maxGap < 20, "gap of $maxGap blocks between breakpoints exceeds the 20-block lookback")
        }

        @Test
        fun `at most three message breakpoints, leaving room for the system prefix`() = runTest {
            val llm = FakeLlm().apply { fallback = FakeLlm.toolCall("vault_tree", "{}") }
            run(llm)

            // The API allows 4 per request; the system prefix takes one.
            llm.requests.forEach { request ->
                assertTrue(
                    request.cacheMessageBreakpoints.size <= 3,
                    "${request.cacheMessageBreakpoints.size} message breakpoints leaves no slot for the system prefix",
                )
            }
        }

        @Test
        fun `caching off means no breakpoints at all`() = runTest {
            val llm = FakeLlm().enqueue(FakeLlm.text("Done."))
            run(llm, cfg = config.copy(cacheEnabled = false))

            assertFalse(llm.requests.first().cacheSystemPrefix)
            assertTrue(llm.requests.first().cacheMessageBreakpoints.isEmpty())
        }
    }

    @Nested
    @DisplayName("Step 8 / WF-6: photos attached to a turn")
    inner class Photos {

        private val photo = com.secondbrain.ports.LlmBlock.Image("ZmFrZS1iYXNlNjQ=", "image/jpeg")

        @Test
        @DisplayName("an attached image is placed before the text block, in the newest user message")
        fun `image precedes text in the user turn`() = runTest {
            val recorder = Recorder()
            val llm = FakeLlm().enqueue(FakeLlm.text("Saved."))

            AgentLoop(llm, registry(recorder), ToolDispatcher(registry(recorder)), prompts, config).run(
                utterance = "here's my list",
                phase = Phase.COMMERCE,
                history = emptyList(),
                conversationId = "c1",
                turnIndex = 0,
                images = listOf(photo),
            )

            // .messages.last() would be wrong here: FakeLlm stores the request's
            // `messages` list by reference, and AgentLoop mutates that same
            // list afterwards to append the assistant reply - so by the time
            // this test looks, "last()" is the reply, not the turn that was
            // actually sent. The user message is reliably first().
            val sentMessage = llm.requests.single().messages.first()
            assertEquals(com.secondbrain.ports.LlmBlock.Image::class, sentMessage.blocks.first()::class)
            assertEquals(com.secondbrain.ports.LlmBlock.Text::class, sentMessage.blocks.last()::class)
            assertEquals(photo, sentMessage.blocks.first())
        }

        @Test
        @DisplayName("no images is the default, and looks identical to every pre-Step-8 call site")
        fun `omitting images sends a text-only user message`() = runTest {
            val recorder = Recorder()
            val llm = FakeLlm().enqueue(FakeLlm.text("Saved."))

            AgentLoop(llm, registry(recorder), ToolDispatcher(registry(recorder)), prompts, config).run(
                utterance = "a plain thought",
                phase = Phase.CAPTURE,
                history = emptyList(),
                conversationId = "c1",
                turnIndex = 0,
            )

            val sentMessage = llm.requests.single().messages.last()
            assertEquals(1, sentMessage.blocks.size)
            assertEquals(com.secondbrain.ports.LlmBlock.Text::class, sentMessage.blocks.single()::class)
        }

        @Test
        @DisplayName("an image survives a tool-calling round-trip, replayed unchanged on the next request")
        fun `image is replayed in later requests within the same turn`() = runTest {
            val recorder = Recorder()
            val reg = registry(recorder)
            val llm = FakeLlm().enqueue(
                FakeLlm.toolCall("vault_tree", "{}"),
                FakeLlm.text("Done."),
            )

            AgentLoop(llm, reg, ToolDispatcher(reg), prompts, config).run(
                utterance = "photo of my notes",
                phase = Phase.CAPTURE,
                history = emptyList(),
                conversationId = "c1",
                turnIndex = 0,
                images = listOf(photo),
            )

            // Second request's history includes the original user message,
            // image and all - nothing strips it out mid-turn.
            assertEquals(2, llm.requests.size)
            val userMessageInSecondRequest = llm.requests[1].messages.first { it.role == com.secondbrain.ports.LlmMessage.Role.USER }
            assertTrue(userMessageInSecondRequest.blocks.any { it == photo })
        }
    }
}
