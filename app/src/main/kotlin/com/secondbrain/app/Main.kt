package com.secondbrain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.secondbrain.agent.ActionLedger
import com.secondbrain.agent.AgentDb
import com.secondbrain.agent.AgentLoop
import com.secondbrain.agent.CalendarTools
import com.secondbrain.agent.ClaudeClient
import com.secondbrain.agent.ConfirmationGate
import com.secondbrain.agent.ConversationStore
import com.secondbrain.agent.CostMeter
import com.secondbrain.agent.EmailTools
import com.secondbrain.agent.SystemPrompt
import com.secondbrain.agent.ToolDispatcher
import com.secondbrain.agent.ToolRegistry
import com.secondbrain.agent.TurnClock
import com.secondbrain.agent.VaultTools
import com.secondbrain.app.vault.VaultBrowserController
import com.secondbrain.app.voice.VoiceController
import com.secondbrain.integrations.CalendarAdapter
import com.secondbrain.integrations.GmailAdapter
import com.secondbrain.integrations.GoogleAuth
import com.secondbrain.integrations.TokenStore
import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.ConfigException
import com.secondbrain.model.ConfigLoader
import com.secondbrain.ports.CalendarPort
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage
import com.secondbrain.ports.LlmRequest
import com.secondbrain.ports.MailPort
import com.secondbrain.vault.Vault
import com.secondbrain.voice.AudioDevices
import com.secondbrain.voice.GeminiStt
import com.secondbrain.voice.GeminiTts
import com.secondbrain.voice.HttpClients
import com.secondbrain.voice.JvmAudioCapture
import com.secondbrain.voice.JvmAudioPlayback
import com.secondbrain.voice.NoiseFloorCalibrator
import com.secondbrain.voice.SecretRedactor
import com.secondbrain.voice.SessionStore
import com.secondbrain.voice.SystemTtsFallback
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * The real entry point — ARCHITECTURE.md §1: ":app owns Compose UI,
 * ProposalWindow, composition root, main()." Step 5 adds `ProposalWindow`;
 * this is everything else.
 *
 * All the blocking setup — config, HTTP clients, the vault, noise-floor
 * calibration — happens here, synchronously, before a single Compose frame is
 * drawn. That is deliberate: [androidx.compose.ui.window.application] runs its
 * own event loop once entered, so this is the one place in the app where
 * "finish setup, then decide what to show" can be a plain top-to-bottom
 * function rather than a race between composition and initialisation.
 */
private val log = LoggerFactory.getLogger("com.secondbrain.app.Main")

fun main() {
    val session = try {
        buildSession()
    } catch (e: ConfigException) {
        log.error("Cannot start: {}", e.message)
        showStartupError(e.message ?: "Unknown configuration error.")
        return
    } catch (e: Exception) {
        log.error("Cannot start", e)
        showStartupError("${e::class.simpleName}: ${e.message}")
        return
    }

    application {
        val windowState = rememberWindowState(width = 1180.dp, height = 760.dp)
        Window(
            onCloseRequest = {
                session.shutdown()
                exitApplication()
            },
            title = "Second Brain",
            state = windowState,
        ) {
            App(session.voiceController, session.vaultController, session.confirmationGate, session.calendarPort)
        }
    }
}

/** Everything with a lifecycle. One place to unwind it all on window close. */
private class AppSession(
    val voiceController: VoiceController,
    val vaultController: VaultBrowserController,
    val confirmationGate: ConfirmationGate,
    /** Null when [com.secondbrain.model.GoogleConfig] is unset (decision 16). */
    val calendarPort: CalendarPort?,
    private val vault: Vault,
    private val agentDb: AgentDb,
    private val sttHttp: HttpClient,
    private val ttsHttp: HttpClient,
    private val tokenStore: TokenStore?,
    private val scope: CoroutineScope,
) {
    fun shutdown() {
        log.info("Shutting down.")
        voiceController.shutdown()
        scope.cancel()
        runCatching { sttHttp.close() }
        runCatching { ttsHttp.close() }
        runCatching { vault.close() }
        runCatching { agentDb.close() }
        runCatching { tokenStore?.close() }
    }
}

private fun buildSession(): AppSession {
    val appConfig = ConfigLoader.load()
    SecretRedactor.register(appConfig.stt.apiKey, appConfig.tts.apiKey)

    // EC-G1: fail fast and by name. config.example.toml's required list does
    // not include agent.api_key — :voice's own Step 1 harness genuinely does
    // not need Claude — but nothing in the live app works without it, so the
    // same check CaptureHarness makes for Step 3's harness applies here too.
    if (appConfig.agent.apiKey.isBlank()) {
        throw ConfigException(
            "agent.api_key is not set in config.toml. The Second Brain app needs Claude for everything beyond raw speech.",
        )
    }

    val root = ConfigLoader.expandHome(appConfig.paths.root)
    Files.createDirectories(root)
    val sessionsRoot = root.resolve("sessions")
    Files.createDirectories(sessionsRoot)

    val format = AudioFormatSpec(sampleRateHz = appConfig.audio.sampleRateHz)
    val capture = JvmAudioCapture(appConfig.audio, format)
    val playback = JvmAudioPlayback(appConfig.audio)
    val sessions = SessionStore(sessionsRoot, appConfig.sessions)

    val sttHttp = HttpClients.create(appConfig.stt.requestTimeoutMs, "gemini-stt")
    val ttsHttp = HttpClients.create(appConfig.tts.requestTimeoutMs, "gemini-tts")
    val stt = GeminiStt(sttHttp, appConfig.stt)
    // D-065: Gemini TTS, not Kokoro — KokoroTts still exists in :voice and
    // reads this same TtsConfig shape if tts.base_url is ever pointed at a
    // self-hosted endpoint instead.
    val primaryTts = GeminiTts(ttsHttp, appConfig.tts)
    val fallbackTts = SystemTtsFallback()

    AudioDevices.logInventory()
    val swept = sessions.sweep()
    if (swept > 0) log.info("Startup sweep removed {} expired session(s).", swept)

    // E4: measured once at startup, synchronously — the window has no reason
    // to appear before the gate has a real threshold to check against.
    val probeDevice = appConfig.audio.preferredCaptureDevice
        ?: AudioDevices.captureDevices(format).firstOrNull { it.isDefault }?.name
        ?: AudioDevices.captureDevices(format).firstOrNull()?.name
        ?: "unknown"
    val calibrator = NoiseFloorCalibrator(capture, appConfig.gate, root.resolve("calibration.json"))
    val calibration = runBlocking {
        try {
            calibrator.calibrate(probeDevice, format)
        } catch (e: Exception) {
            log.error("Noise-floor calibration failed ({}). Falling back to a fixed -45 dBFS threshold.", e.message)
            NoiseFloorCalibrator.Calibration(probeDevice, -57.0, -45.0, "fallback", 0)
        }
    }

    val vault = Vault.open(root, appConfig.vault)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    vault.startWatching(scope) // Step 4: FileWatcher -> UI state flow (Vault.changes)

    val agentDb = AgentDb(root.resolve("app.db"))
    val llm = ClaudeClient(appConfig.agent)
    val prompts = SystemPrompt()
    val costMeter = CostMeter(agentDb, appConfig.agent)
    val store = ConversationStore(agentDb, appConfig.agent)

    // ── Step 5/6: the confirmation gate and its ledger ──────────────────────
    // Always built, even with no Google account configured (decision 16) -
    // ActionLedger/ConfirmationGate are cheap and having no gated tool ever
    // call submit() is a perfectly normal, fully supported state.
    val actionLedger = ActionLedger(agentDb)
    val reconciliation = actionLedger.reconcileOnStartup() // EC-A9
    if (reconciliation.cancelledCount > 0 || reconciliation.unknownCount > 0) {
        log.warn(
            "Startup reconciliation: {} stale proposal(s) cancelled, {} left UNKNOWN from an interrupted send/create.",
            reconciliation.cancelledCount, reconciliation.unknownCount,
        )
    }
    val confirmationGate = ConfirmationGate(actionLedger)

    // ── Google: optional (decision 16) ──────────────────────────────────────
    val googleConfigured = appConfig.google.clientId.isNotBlank() && appConfig.google.clientSecret.isNotBlank()
    var tokenStore: TokenStore? = null
    var mailPort: MailPort? = null
    var calendarPort: CalendarPort? = null
    if (googleConfigured) {
        tokenStore = TokenStore(root.resolve(appConfig.google.tokenStorePath))
        val auth = GoogleAuth(appConfig.google.clientId, appConfig.google.clientSecret, appConfig.google.redirectPort, tokenStore)
        mailPort = GmailAdapter(auth)
        calendarPort = CalendarAdapter(auth)
        log.info("Google configured: email_draft and the calendar tools will be registered.")
    } else {
        log.warn("google.client_id/client_secret not set - email_draft and the calendar tools will NOT be registered. Voice capture works normally without them.")
    }

    // ask_user needs to call back into VoiceController, and VoiceController
    // needs the fully-built AgentLoop below — the same forward-reference shape
    // VoiceHarness already uses for its own `window` callbacks (`lateinit var
    // window: PttWindow`). Safe here for the same reason: the lambda is only
    // ever invoked from inside a running turn, which cannot happen before
    // `voiceController` is assigned.
    lateinit var voiceController: VoiceController
    val askUser: suspend (String) -> VaultTools.AskResult = { question -> voiceController.handleAskUser(question) }
    val tools = VaultTools(vault, appConfig.vault, askUser)
    var builder = tools.register(ToolRegistry.builder())
    if (mailPort != null) {
        val emailTools = EmailTools(mailPort, confirmationGate) { prompt, kind -> voiceController.handleTypedInput(prompt, kind) }
        builder = emailTools.register(builder)
    }
    val turnClock = TurnClock()
    if (calendarPort != null) {
        val calendarTools = CalendarTools(calendarPort, confirmationGate, vault, turnClock, askUser)
        builder = calendarTools.register(builder)
    }
    val registry = builder.build()
    val agentLoop = AgentLoop(llm, registry, ToolDispatcher(registry), prompts, appConfig.agent, turnClock)

    if (appConfig.agent.prewarmCache) {
        // Backgrounded, unlike CaptureHarness's synchronous prewarm: a person
        // needs at least a few seconds to notice the window and decide what to
        // say, so there is no reason to hold the window itself back for this.
        scope.launch {
            runCatching {
                llm.prewarm(
                    LlmRequest(
                        model = appConfig.agent.model,
                        systemPrompt = prompts.system(),
                        messages = listOf(LlmMessage(LlmMessage.Role.USER, listOf(LlmBlock.Text("ready")))),
                        tools = registry.specs(),
                        maxTokens = 1,
                        thinkingEnabled = false,
                        effort = null,
                        cacheSystemPrefix = true,
                    ),
                )
            }.onFailure { log.warn("Cache prewarm failed (non-fatal): {}", it.message) }
        }
    }

    voiceController = VoiceController(
        scope = scope,
        appConfig = appConfig,
        capture = capture,
        playback = playback,
        sessions = sessions,
        stt = stt,
        primaryTts = primaryTts,
        fallbackTts = fallbackTts,
        thresholdDbfs = calibration.thresholdDbfs,
        micDeviceLabel = probeDevice,
        llm = llm,
        agentLoop = agentLoop,
        store = store,
        costMeter = costMeter,
        prompts = prompts,
        confirmationGate = confirmationGate,
    )

    val vaultController = VaultBrowserController(scope, vault)

    log.info(
        "Second Brain ready. vault={} model={} caching={}",
        root.resolve("vault"), appConfig.agent.model, if (appConfig.agent.cacheEnabled) "on" else "OFF",
    )

    return AppSession(voiceController, vaultController, confirmationGate, calendarPort, vault, agentDb, sttHttp, ttsHttp, tokenStore, scope)
}

/**
 * EC-G1 for a GUI app: a config failure must be seen, not just logged to a
 * console the user is not looking at. Deliberately minimal — this is not the
 * product, it is the one screen that exists so "cannot start" is never a
 * silent exit.
 */
private fun showStartupError(message: String) = application {
    Window(onCloseRequest = ::exitApplication, title = "Second Brain — cannot start", state = WindowState(width = 640.dp, height = 420.dp)) {
        StartupErrorContent(message)
    }
}

@Composable
private fun StartupErrorContent(message: String) {
    SecondBrainTheme {
        Box(Modifier.fillMaxSize().background(AppColors.Canvas).padding(28.dp)) {
            Column {
                Text("Second Brain cannot start", style = MaterialTheme.typography.headlineSmall, color = AppColors.Ink)
                Box(Modifier.padding(top = 16.dp)) {
                    Text(message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AppColors.Ink)
                }
            }
        }
    }
}
