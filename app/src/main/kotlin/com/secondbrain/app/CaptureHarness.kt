package com.secondbrain.app

import com.secondbrain.agent.AgentDb
import com.secondbrain.agent.AgentLoop
import com.secondbrain.agent.ClaudeClient
import com.secondbrain.agent.ConversationStore
import com.secondbrain.agent.CostMeter
import com.secondbrain.agent.SystemPrompt
import com.secondbrain.agent.ToolDispatcher
import com.secondbrain.agent.ToolRegistry
import com.secondbrain.agent.VaultTools
import com.secondbrain.model.AgentConfig
import com.secondbrain.model.ConfigException
import com.secondbrain.model.ConfigLoader
import com.secondbrain.model.Phase
import com.secondbrain.model.TurnEnd
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage
import com.secondbrain.ports.LlmRequest
import com.secondbrain.vault.Vault
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Step 3's quality gate, driven by typed utterances.
 *
 * The exit criteria are voice-first — "dictate 20 varied real thoughts" — but the
 * things being measured are not properties of the voice path:
 *
 *  - total folder count after 20 captures (must be <= 8)
 *  - whether each note landed where a person would have put it
 *  - per-capture USD, which becomes the budget for Steps 4 through 7
 *
 * All three are properties of the *agent loop*. Driving them from typed input
 * removes Gemini from the measurement, costs nothing in STT, and makes the run
 * repeatable from a file. R9 is not in play: this is a developer harness, not a
 * user-facing typing path (D-056).
 *
 * ```
 * ./gradlew :app:capture --args="thoughts.txt"
 * ./gradlew :app:capture                        # reads stdin
 * ```
 *
 * The voice path is the real one and arrives with the UI in Step 4.
 */
object CaptureHarness

private val SEPARATOR = "-".repeat(76)

fun main(args: Array<String>) = runBlocking {
    val appConfig = try {
        ConfigLoader.load()
    } catch (e: ConfigException) {
        System.err.println()
        System.err.println("─── Second Brain cannot start ───")
        System.err.println(e.message)
        exitProcess(2)
    }

    val agentConfig = appConfig.agent
    if (agentConfig.apiKey.isBlank()) {
        System.err.println("agent.api_key is not set in config.toml. The capture harness needs Claude.")
        exitProcess(2)
    }

    val root: Path = ConfigLoader.expandHome(appConfig.paths.root)
    Files.createDirectories(root)

    val utterances = readUtterances(args)
    if (utterances.isEmpty()) {
        System.err.println("No utterances. Pass a file, or pipe one thought per line on stdin.")
        exitProcess(2)
    }

    println()
    println("Second Brain — capture harness")
    println(SEPARATOR)
    println("vault      ${root.resolve("vault")}")
    println("model      ${agentConfig.model}  (effort ${agentConfig.effort}, thinking ${agentConfig.thinkingEnabled})")
    println("caching    ${if (agentConfig.cacheEnabled) "on" else "OFF - the cost figure will be inflated"}")
    println("utterances ${utterances.size}")
    println(SEPARATOR)

    val vault = Vault.open(root, appConfig.vault)
    val agentDb = AgentDb(root.resolve("app.db"))

    try {
        val llm = ClaudeClient(agentConfig)
        val prompts = SystemPrompt()
        val costMeter = CostMeter(agentDb, agentConfig)
        val store = ConversationStore(agentDb, agentConfig)

        // ask_user cannot work from a file, so it reports no answer rather than
        // pretending. The loop already handles that honestly (H11), and a harness
        // that silently invented answers would corrupt the placement measurement.
        val tools = VaultTools(vault, appConfig.vault, askUser = { question ->
            println("   [ask_user] $question")
            println("   [ask_user] no answer available in the harness")
            VaultTools.AskResult.NoAnswer("running from a file; nobody is listening")
        })

        val registry = tools.register(ToolRegistry.builder()).build()
        val loop = AgentLoop(llm, registry, ToolDispatcher(registry), prompts, agentConfig)

        if (agentConfig.prewarmCache) {
            llm.prewarm(
                LlmRequest(
                    model = agentConfig.model,
                    systemPrompt = prompts.system(),
                    messages = listOf(LlmMessage(LlmMessage.Role.USER, listOf(LlmBlock.Text("ready")))),
                    tools = registry.specs(),
                    maxTokens = 1,
                    thinkingEnabled = false,
                    effort = null,
                    cacheSystemPrefix = true,
                )
            )
        }

        var state = store.startConversation(Phase.CAPTURE)
        val outcomes = mutableListOf<Outcome>()

        utterances.forEachIndexed { index, utterance ->
            // EC-G2: the ceiling is checked between utterances, never mid-turn.
            when (val verdict = costMeter.check()) {
                is CostMeter.Verdict.Blocked -> {
                    println()
                    println("Stopped: session spend $%.4f reached the $%.2f ceiling after $index capture(s)."
                        .format(verdict.spentUsd, verdict.ceilingUsd))
                    println("Raise agent.session_usd_ceiling in config.toml to continue.")
                    return@forEachIndexed
                }
                is CostMeter.Verdict.Warn ->
                    println("   [cost] $%.4f of $%.2f spent".format(verdict.spentUsd, verdict.ceilingUsd))
                CostMeter.Verdict.Proceed -> {}
            }

            println()
            println("[${index + 1}/${utterances.size}] $utterance")

            val output = loop.run(
                utterance = utterance,
                phase = state.phase,
                history = store.historyFor(state),
                conversationId = state.conversationId,
                turnIndex = index,
                cancellation = AgentLoop.Cancellation(),
            )
            val result = output.result

            store.recordTurn(state, index, output.messages, result.usage)
            costMeter.record(
                CostMeter.Service.CLAUDE, result.usage,
                conversationId = state.conversationId, turnIndex = index, model = agentConfig.model,
            )

            state = store.advance(state, index, output.messages) { dropped, previous ->
                // A real summariser is a Claude call (D-047). Kept out of the
                // harness so summary spend does not contaminate the per-capture
                // figure this run exists to measure.
                (previous.orEmpty() + " " + dropped.size + " earlier turn(s) omitted.").trim()
            }

            result.toolEvents.forEach { event ->
                val marker = if (event.isError) "!" else " "
                println("   $marker ${event.name}  ${event.resultJson.take(110)}")
            }
            println("   -> ${result.spokenText}")
            println("   [%s] %d iteration(s), %dms, $%.5f".format(
                result.end, result.iterations, result.latencyMs, result.usd,
            ))

            outcomes += Outcome(utterance, result.touchedNotes, result.end, result.usd, result.iterations)
        }

        store.endConversation(state.conversationId)
        report(vault, costMeter, state.conversationId, outcomes)
    } finally {
        agentDb.close()
        vault.close()
    }
}

private data class Outcome(
    val utterance: String,
    val notePaths: List<String>,
    val end: TurnEnd,
    val usd: Double,
    val iterations: Int,
)

private fun readUtterances(args: Array<String>): List<String> {
    val text = if (args.isNotEmpty()) {
        val path = Path.of(args[0])
        if (Files.notExists(path)) {
            System.err.println("No such file: $path")
            exitProcess(2)
        }
        Files.readString(path)
    } else {
        System.`in`.bufferedReader().readText()
    }
    // Blank lines separate thoughts; a '#' line is a comment.
    return text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
}

/**
 * The Step 3 exit criteria, measured rather than eyeballed.
 *
 * Placement quality ("at least 17 of 20 in a folder you'd have chosen yourself")
 * is a human judgement and is printed for review rather than asserted — claiming
 * to have measured it automatically would be a lie.
 */
private fun report(
    vault: Vault,
    costMeter: CostMeter,
    conversationId: String,
    outcomes: List<Outcome>,
) = runBlocking {
    val tree = vault.tree(depth = 3)
    val allFolders = vault.index.folders()
    val topLevel = tree.children.map { it.name }

    println()
    println(SEPARATOR)
    println("Step 3 exit criteria")
    println(SEPARATOR)

    val folderCount = allFolders.size
    val folderVerdict = if (folderCount <= 8) "PASS" else "FAIL"
    println("[$folderVerdict] folder count after ${outcomes.size} captures: $folderCount (must be <= 8)")
    println("         top level: ${topLevel.joinToString(", ")}")
    if (folderCount > 8) {
        println("         Tune vault.folder_similarity_threshold and re-run. Do not proceed with a sprawling vault.")
    }

    val crashes = outcomes.count { it.end == TurnEnd.API_FAILED }
    val capped = outcomes.count { it.end == TurnEnd.ITERATION_CAP }
    println("[%s] outcomes: %d ok, %d hit the iteration cap, %d API failures".format(
        if (crashes == 0) "PASS" else "FAIL",
        outcomes.count { it.end == TurnEnd.END_TURN }, capped, crashes,
    ))

    val notesWritten = outcomes.flatMap { it.notePaths }.distinct()
    println("[%s] notes written: %d from %d utterances".format(
        if (notesWritten.isNotEmpty()) "PASS" else "FAIL", notesWritten.size, outcomes.size,
    ))

    val meanUsd = costMeter.meanUsdPerTurn(conversationId)
    println("[INFO] cost per capture: $%.5f mean, $%.4f session total".format(meanUsd, costMeter.sessionUsd()))
    costMeter.breakdown().forEach { (service, usd) ->
        println("         %-16s $%.4f".format(service, usd))
    }
    println("         Record the per-capture figure in DECISIONS.md - it sets the budget for Steps 4 to 7.")

    println()
    println("Placement, for manual review (>= 17 of 20 should be where you would have put them):")
    outcomes.forEach { outcome ->
        val where = outcome.notePaths.ifEmpty { listOf("(no note written)") }.joinToString(", ")
        println("   %-52s -> %s".format(outcome.utterance.take(52), where))
    }

    println()
    println("Folder Guard decisions:")
    vault.folderDecisions(30).reversed().forEach { decision ->
        val score = decision.score?.let { " %.2f".format(it) } ?: ""
        println("   %-22s %-20s %s%s".format(
            decision.proposed.take(22), decision.verdict, decision.matched.orEmpty(), score,
        ))
    }

    val dangling = vault.danglingLinks()
    if (dangling.isNotEmpty()) {
        println()
        println("Dangling links (${dangling.size}):")
        dangling.take(10).forEach { println("   ${it.fromPath} -> [[${it.rawTarget}]]") }
    }
    println(SEPARATOR)
}
