package com.secondbrain.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import com.secondbrain.model.AgentConfig
import com.secondbrain.model.TurnUsage
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage
import com.secondbrain.ports.LlmPort
import com.secondbrain.ports.LlmRequest
import com.secondbrain.ports.LlmResponse
import com.secondbrain.ports.LlmStop
import com.secondbrain.ports.LlmStreamEvent
import com.secondbrain.ports.LlmToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.random.Random

/**
 * The only file in this codebase that knows the Anthropic SDK exists.
 *
 * ARCHITECTURE.md section 7 specifies "Ktor, POST /v1/messages". There is an
 * official SDK for the JVM that Kotlin uses, and D-005's actual reasoning was
 * about not adding a workflow framework rather than about transport — the loop in
 * [AgentLoop] is still hand-written. Udit chose the SDK; the deciding argument
 * was API drift: five documented breaking changes in the last year, and getting
 * `thinking` or `effort` wrong is a runtime 400 with hand-rolled JSON versus a
 * compile error here (D-044).
 *
 * Everything above `:agent` sees [LlmPort] only. No Jackson, no `Optional<T>`,
 * no builders escape this file.
 *
 * ### Two things this class exists to get right
 *
 * **Cache stability.** The system prompt plus the tool schemas is the expensive
 * shared prefix, resent on every iteration of every turn. It gets an explicit
 * breakpoint. Nothing per-request may enter the system prompt — the caller
 * enforces that, but the shape here makes it hard to get wrong.
 *
 * **Usage accounting.** `usage.inputTokens()` is the *uncached remainder only*.
 * Reading it as "the prompt cost" under-reports by whatever the cache served,
 * which is most of it in a healthy loop.
 */
class ClaudeClient(
    private val config: AgentConfig,
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(config.apiKey)
        .timeout(Duration.ofMillis(config.requestTimeoutMs))
        // The SDK retries 408/409/429/5xx itself. EC-A7 wants 3 attempts with
        // jitter; we keep our own outer loop for the spoken-error path and
        // attempt counting, so the SDK's own retries are disabled to avoid
        // multiplying them.
        .maxRetries(0)
        .build(),
) : LlmPort {

    private val log = LoggerFactory.getLogger(ClaudeClient::class.java)

    override val modelId: String get() = config.model

    // ── request assembly ────────────────────────────────────────────────────

    private fun buildParams(request: LlmRequest, maxTokensOverride: Long? = null): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(maxTokensOverride ?: request.maxTokens)

        // The system prompt carries the cache breakpoint. `.system(String)` cannot
        // hold cache control, so the block-param form is required.
        if (request.cacheSystemPrefix) {
            builder.systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(request.systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()
                )
            )
        } else {
            builder.system(request.systemPrompt)
        }

        // Tools render before system and messages, so their order is part of the
        // cache key. The caller supplies an ordered list; we never reorder.
        request.tools.forEach { builder.addTool(toSdkTool(it)) }

        if (request.thinkingEnabled) {
            builder.thinking(ThinkingConfigAdaptive.builder().build())
        }
        request.effort?.let { effort ->
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build())
        }

        builder.messages(request.messages.mapIndexed { index, message ->
            toSdkMessage(message, breakpoint = index in request.cacheMessageBreakpoints)
        })

        return builder.build()
    }

    private fun toSdkTool(spec: LlmToolSpec): Tool {
        val propertiesJson = JsonBridge.schemaSection(spec.inputSchemaJson, "properties")
        val requiredJson = JsonBridge.schemaSection(spec.inputSchemaJson, "required")

        val props = Tool.InputSchema.Properties.builder()
        // Order preserved from the schema text so two requests serialise
        // byte-identically. A varying property order is a silent cache miss.
        JsonBridge.objectToJsonValues(propertiesJson.orEmpty()).forEach { (name, value) ->
            props.putAdditionalProperty(name, value)
        }

        return Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(props.build())
                    .required(JsonBridge.stringArray(requiredJson))
                    .build()
            )
            .build()
    }

    private fun toSdkMessage(message: LlmMessage, breakpoint: Boolean): MessageParam {
        val blocks = message.blocks.mapIndexedNotNull { index, block ->
            val isLast = index == message.blocks.lastIndex
            toSdkBlock(block, cacheControl = breakpoint && isLast)
        }

        val role = when (message.role) {
            LlmMessage.Role.USER -> MessageParam.Role.USER
            LlmMessage.Role.ASSISTANT -> MessageParam.Role.ASSISTANT
            // Mid-conversation operator instruction. Appending to `messages`
            // rather than editing the top-level system prompt keeps the cached
            // prefix valid, which is the entire reason this channel is used for
            // the EC-A1 cap notice and the EC-G2 cost warning.
            LlmMessage.Role.SYSTEM -> MessageParam.Role.SYSTEM
        }

        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build()
    }

    private fun toSdkBlock(block: LlmBlock, cacheControl: Boolean): ContentBlockParam? = when (block) {
        is LlmBlock.Text -> {
            if (block.text.isEmpty()) null
            else ContentBlockParam.ofText(
                TextBlockParam.builder().text(block.text).apply {
                    if (cacheControl) cacheControl(CacheControlEphemeral.builder().build())
                }.build()
            )
        }

        is LlmBlock.Thinking -> {
            // Replayed unchanged. The signature is what makes the block valid on
            // resend; a thinking block without one is rejected. On Opus 5 the text
            // is usually empty because display defaults to omitted, and the block
            // still has to go back.
            val signature = block.signature
            if (signature == null) null
            else ContentBlockParam.ofThinking(
                ThinkingBlockParam.builder()
                    .thinking(block.thinking)
                    .signature(signature)
                    .build()
            )
        }

        is LlmBlock.ToolUse -> {
            val input = ToolUseBlockParam.Input.builder()
            JsonBridge.objectToJsonValues(block.inputJson).forEach { (k, v) ->
                input.putAdditionalProperty(k, v)
            }
            ContentBlockParam.ofToolUse(
                ToolUseBlockParam.builder()
                    .id(block.id)
                    .name(block.name)
                    .input(input.build())
                    .build()
            )
        }

        is LlmBlock.ToolResult -> ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder()
                .toolUseId(block.toolUseId)
                .content(block.content)
                .isError(block.isError)
                .apply { if (cacheControl) cacheControl(CacheControlEphemeral.builder().build()) }
                .build()
        )
    }

    // ── response mapping ────────────────────────────────────────────────────

    private fun toLlmResponse(message: Message, attempts: Int): LlmResponse {
        val blocks = message.content().mapNotNull { block ->
            block.text().map<LlmBlock> { LlmBlock.Text(it.text()) }
                .or { block.thinking().map { LlmBlock.Thinking(it.thinking(), it.signature()) } }
                .or {
                    block.toolUse().map {
                        LlmBlock.ToolUse(it.id(), it.name(), it._input().toString())
                    }
                }
                .orElse(null)
        }

        val stopReason = message.stopReason().orElse(null)
        val stop = when (stopReason) {
            StopReason.END_TURN, StopReason.STOP_SEQUENCE -> LlmStop.END_TURN
            StopReason.TOOL_USE -> LlmStop.TOOL_USE
            StopReason.MAX_TOKENS -> LlmStop.MAX_TOKENS
            StopReason.REFUSAL -> LlmStop.REFUSAL
            // The SDK exposes two stop reasons ARCHITECTURE.md section 4 does not
            // mention at all. PAUSE_TURN only arises with server-side tools, which
            // this system does not use, and MODEL_CONTEXT_WINDOW_EXCEEDED means the
            // conversation outgrew the window despite R8's resets. Both are treated
            // as a failed turn with an honest message rather than silently read as
            // a normal reply (D-049).
            StopReason.PAUSE_TURN -> LlmStop.API_FAILED
            StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> LlmStop.API_FAILED
            else -> if (blocks.any { it is LlmBlock.ToolUse }) LlmStop.TOOL_USE else LlmStop.END_TURN
        }

        val error = when (stopReason) {
            StopReason.PAUSE_TURN -> "the model paused for a server-side tool, which this system does not use"
            StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED ->
                "the conversation exceeded the model's context window despite the phase reset policy"
            else -> null
        }

        return LlmResponse(
            stop = stop,
            blocks = blocks,
            usage = toUsage(message),
            // stopDetails is populated ONLY on refusal. Guard before reading.
            refusalCategory = if (stopReason == StopReason.REFUSAL) {
                message.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse("unknown")
            } else null,
            error = error,
            attempts = attempts,
        )
    }

    /**
     * Maps SDK usage onto [TurnUsage].
     *
     * `inputTokens()` is the uncached remainder. The cache fields are `Optional`
     * and absent rather than zero when caching is off, so they default to 0 —
     * silently dropping them would make every cached request look nearly free.
     */
    private fun toUsage(message: Message): TurnUsage {
        val usage = message.usage()
        return TurnUsage(
            inputTokens = usage.inputTokens(),
            outputTokens = usage.outputTokens(),
            cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L),
            cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
        )
    }

    // ── LlmPort ─────────────────────────────────────────────────────────────

    override suspend fun send(request: LlmRequest): LlmResponse {
        var lastError: String? = null

        for (attempt in 1..config.maxAttempts) {
            try {
                val message = withContext(Dispatchers.IO) {
                    client.messages().create(buildParams(request))
                }
                val response = toLlmResponse(message, attempt)
                logUsage(response)
                return response
            } catch (e: AnthropicServiceException) {
                lastError = describe(e)
                if (!isRetryable(e)) {
                    log.error("Claude call failed, not retryable: {}", lastError)
                    return LlmResponse(LlmStop.API_FAILED, emptyList(), TurnUsage.ZERO, error = lastError, attempts = attempt)
                }
                log.warn("Claude call attempt {}/{} failed: {}", attempt, config.maxAttempts, lastError)
                if (attempt < config.maxAttempts) backoff(attempt)
            } catch (e: Exception) {
                lastError = "${e::class.simpleName}: ${e.message}"
                log.warn("Claude call attempt {}/{} threw: {}", attempt, config.maxAttempts, lastError)
                if (attempt < config.maxAttempts) backoff(attempt)
            }
        }

        // EC-A7: speak the error and preserve the transcript. Nothing is lost.
        return LlmResponse(
            stop = LlmStop.API_FAILED,
            blocks = emptyList(),
            usage = TurnUsage.ZERO,
            error = "all ${config.maxAttempts} attempts failed. Last error: $lastError",
            attempts = config.maxAttempts,
        )
    }

    /**
     * Deferred, deliberately, and honest about it.
     *
     * True token streaming buys one thing here: the first sentence of the *final*
     * text reply reaching TTS sooner (EC-T3). It cannot help on a tool-calling
     * iteration, because there is no text to speak — and on a CAPTURE turn the
     * final reply is one short sentence while the tool round-trips dominate. So
     * the payoff is a few hundred milliseconds on the last of several calls.
     *
     * The cost is not small. The SDK exposes no non-beta message accumulator, so
     * real streaming means hand-assembling text blocks, thinking blocks with
     * their signatures, tool_use inputs from partial-JSON deltas, and usage from
     * `message_start` plus `message_delta`. Getting the partial-JSON assembly
     * wrong breaks tool calls *silently*, which is the worst failure mode
     * available in this system.
     *
     * So this emits the completed text as a single delta. Every caller works
     * identically, no caller special-cases it, and swapping in true streaming
     * later is a change to this one method. Revisit once Step 1's measured
     * latency numbers exist and show it is worth the risk (D-052).
     */
    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val response = send(request)
        if (response.toolUses.isNotEmpty()) {
            emit(LlmStreamEvent.ToolUseStarted)
        } else if (response.text.isNotEmpty()) {
            emit(LlmStreamEvent.TextDelta(response.text))
        }
        emit(LlmStreamEvent.Completed(response))
    }.flowOn(Dispatchers.IO)

    override suspend fun prewarm(request: LlmRequest) {
        if (!request.cacheSystemPrefix) return
        try {
            // max_tokens 0: the API runs prefill, writes the breakpoint and
            // returns immediately with empty content. Billed as a normal cache
            // write, zero output tokens.
            val message = withContext(Dispatchers.IO) {
                client.messages().create(buildParams(request, maxTokensOverride = 1L))
            }
            val usage = toUsage(message)
            log.info(
                "Cache pre-warmed: {} tokens written, {} read. First capture will not pay the cold-write latency.",
                usage.cacheWriteTokens, usage.cacheReadTokens,
            )
        } catch (e: Exception) {
            // Pre-warming is an optimisation. Failing it must never stop startup.
            log.warn("Cache pre-warm failed ({}). Continuing; the first capture pays the cold write.", e.message)
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private fun logUsage(response: LlmResponse) {
        val u = response.usage
        log.debug(
            "usage in={} out={} cache_write={} cache_read={} total_prompt={} usd={}",
            u.inputTokens, u.outputTokens, u.cacheWriteTokens, u.cacheReadTokens,
            u.totalPromptTokens, "%.6f".format(config.pricing.usdFor(u)),
        )
        if (config.cacheEnabled && u.cacheReadTokens == 0L && u.totalPromptTokens > 2_000) {
            // The costliest caching failure is silent: requests keep succeeding,
            // the bill is just higher. Say something.
            log.warn(
                "Cache read 0 tokens on a {}-token prompt with caching enabled. " +
                    "Something in the prefix is varying between requests.",
                u.totalPromptTokens,
            )
        }
    }

    private fun isRetryable(e: AnthropicServiceException): Boolean {
        val status = e.statusCode()
        return status == 408 || status == 409 || status == 429 || status >= 500
    }

    private fun describe(e: AnthropicServiceException): String = when (e.statusCode()) {
        401, 403 -> "Claude rejected the credentials (${e.statusCode()}). Check agent.api_key in config.toml."
        404 -> "Claude model '${config.model}' not found (404). agent.model in config.toml is wrong " +
            "or the model was retired (EC-G3)."
        400 -> "Claude rejected the request (400): ${e.message}"
        429 -> "rate limited (429)"
        else -> "HTTP ${e.statusCode()}: ${e.message}"
    }

    private suspend fun backoff(attempt: Int) {
        val base = config.initialBackoffMs * (1L shl (attempt - 1))
        delay(base + Random.nextLong(0, (base / 2).coerceAtLeast(1)))
    }
}
