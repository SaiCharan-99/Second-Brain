package com.secondbrain.agent

import com.secondbrain.model.Backlink
import com.secondbrain.model.CalendarProposal
import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.Note
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.NoteSource
import com.secondbrain.model.SearchHit
import com.secondbrain.model.TreeNode
import com.secondbrain.ports.BusyBlock
import com.secondbrain.ports.CalendarPort
import com.secondbrain.ports.InsertOutcome
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.VaultStore
import com.secondbrain.ports.WriteResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * `calendar_resolve_time` / `calendar_find_conflicts` / `calendar_propose_event`
 * through the real [ToolDispatcher] + [ConfirmationGate], per WF-3.
 */
class CalendarToolsTest {

    private class FakeVaultStore(private val people: Map<String, String> = emptyMap()) : VaultStore {
        override suspend fun tree(depth: Int?) = TreeNode("", "", 0, 0, 0, 0)
        override suspend fun read(path: String): Note? {
            val name = path.removePrefix("People/").removeSuffix(".md")
            val email = people[name] ?: return null
            return Note(
                path = path, folder = "People", title = name, slug = name, tags = emptyList(),
                summary = "", bodyMarkdown = "Email: $email", created = Instant.EPOCH, updated = Instant.EPOCH,
                source = NoteSource.TEXT,
            )
        }
        override suspend fun search(query: String, limit: Int): List<SearchHit> =
            people.keys.filter { it.equals(query, ignoreCase = true) }.map { SearchHit("People/$it.md", it, "", "", 0.0) }
        override suspend fun createFolder(path: String): FolderVerdict = FolderVerdict.Accepted(path)
        override suspend fun writeNote(draft: NoteDraft, confirmNew: Boolean): WriteResult = WriteResult.Written(path = "x")
        override suspend fun appendNote(path: String, heading: String, markdown: String): WriteResult = WriteResult.Written(path = path)
        override suspend fun moveNote(path: String, toFolder: String): WriteResult = WriteResult.Written(path = path)
        override suspend fun backlinks(path: String): List<Backlink> = emptyList()
    }

    private class FakeCalendarPort(
        private val insertOutcome: InsertOutcome = InsertOutcome.Created("evt-1"),
        private val busy: List<BusyBlock> = emptyList(),
    ) : CalendarPort {
        var inserted: CalendarProposal? = null
        override suspend fun findBusy(start: Instant, end: Instant): List<BusyBlock> = busy
        override suspend fun insert(proposal: CalendarProposal, idempotencyKey: String): InsertOutcome {
            inserted = proposal
            return insertOutcome
        }
    }

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val tuesdayNoon = Instant.parse("2026-09-01T10:15:00Z")

    private fun turnClock(): TurnClock = TurnClock().apply { set(tuesdayNoon, kolkata) }

    private fun gate(dir: Path) = ConfirmationGate(ActionLedger(AgentDb(dir.resolve("app.db"))))

    private fun dispatcherFor(
        dir: Path,
        calendarPort: CalendarPort = FakeCalendarPort(),
        vault: VaultStore = FakeVaultStore(),
        askUser: suspend (String) -> VaultTools.AskResult = { _ -> VaultTools.AskResult.NoAnswer("not exercised") },
    ): Triple<ToolDispatcher, ConfirmationGate, TurnClock> {
        val g = gate(dir)
        val clock = turnClock()
        val tools = CalendarTools(calendarPort, g, vault, clock, askUser)
        val registry = tools.register(ToolRegistry.builder()).build()
        return Triple(ToolDispatcher(registry), g, clock)
    }

    @Test
    fun `calendar_resolve_time resolves a clean expression`(@TempDir dir: Path) = runTest {
        val (dispatcher, _, _) = dispatcherFor(dir)
        val result = dispatcher.dispatch(
            LlmBlock.ToolUse(
                "tu_1", "calendar_resolve_time",
                """{"date_phrase":"tomorrow","start_hour":14,"duration_minutes":60}""",
            )
        )
        assertFalse(result.result.isError)
        assertTrue(result.result.content.contains("\"resolved\":true"))
    }

    @Test
    @DisplayName("EC-C1: bare 12 with no meridiem comes back ambiguous, never guessed")
    fun `calendar_resolve_time reports ambiguity`(@TempDir dir: Path) = runTest {
        val (dispatcher, _, _) = dispatcherFor(dir)
        val result = dispatcher.dispatch(
            LlmBlock.ToolUse(
                "tu_1", "calendar_resolve_time",
                """{"date_phrase":"tomorrow","start_hour":12,"start_hour_ambiguous":true,"duration_minutes":60}""",
            )
        )
        assertTrue(result.result.content.contains("\"resolved\":false"))
        assertTrue(result.result.content.contains("HOUR_12_OR_24"))
    }

    @Test
    @DisplayName("EC-C4: conflicts are reported as informational, never as a block")
    fun `calendar_find_conflicts reports without blocking`(@TempDir dir: Path) = runTest {
        val busy = listOf(BusyBlock(Instant.parse("2026-09-02T06:00:00Z"), Instant.parse("2026-09-02T07:00:00Z"), "Standup"))
        val (dispatcher, _, _) = dispatcherFor(dir, calendarPort = FakeCalendarPort(busy = busy))
        val result = dispatcher.dispatch(
            LlmBlock.ToolUse(
                "tu_1", "calendar_find_conflicts",
                """{"start":"2026-09-02T06:30:00Z","end":"2026-09-02T07:30:00Z"}""",
            )
        )
        assertTrue(result.result.content.contains("\"has_conflict\":true"))
        assertTrue(result.result.content.contains("Standup"))
    }

    @Test
    @DisplayName("EC-C7: an inverted range is rejected before any gate opens")
    fun `end before start is rejected up front`(@TempDir dir: Path) = runTest {
        val (dispatcher, gate, _) = dispatcherFor(dir)
        val result = dispatcher.dispatch(
            LlmBlock.ToolUse(
                "tu_1", "calendar_propose_event",
                """{"title":"x","start":"2026-09-02T07:00:00Z","end":"2026-09-02T06:00:00Z","zone":"Asia/Kolkata","all_day":false}""",
            )
        )
        assertTrue(result.result.isError)
        assertNull(gate.state.value)
    }

    @Test
    @DisplayName("EC-C5: an unresolvable attendee name stops before any gate opens and never fabricates an address")
    fun `unknown attendee with no answer is rejected, not guessed`(@TempDir dir: Path) = runTest {
        val (dispatcher, gate, _) = dispatcherFor(dir, askUser = { VaultTools.AskResult.NoAnswer("silence") })
        val result = dispatcher.dispatch(
            LlmBlock.ToolUse(
                "tu_1", "calendar_propose_event",
                """{"title":"Lunch","start":"2026-09-02T06:30:00Z","end":"2026-09-02T07:30:00Z","zone":"Asia/Kolkata","all_day":false,"attendees":["Charan"]}""",
            )
        )
        assertTrue(result.result.content.contains("unknown_attendee"))
        assertNull(gate.state.value)
    }

    @Test
    @DisplayName("EC-C5: a name found under People/ resolves without asking")
    fun `known attendee resolves from the vault`(@TempDir dir: Path) = runTest {
        val vault = FakeVaultStore(mapOf("Charan" to "charan@example.com"))
        val calendarPort = FakeCalendarPort()
        val (dispatcher, gate, _) = dispatcherFor(dir, calendarPort = calendarPort, vault = vault)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.dispatch(
                LlmBlock.ToolUse(
                    "tu_1", "calendar_propose_event",
                    """{"title":"Lunch","start":"2026-09-02T06:30:00Z","end":"2026-09-02T07:30:00Z","zone":"Asia/Kolkata","all_day":false,"attendees":["Charan"]}""",
                )
            )
        }

        val proposalId = gate.state.value!!.proposalId
        assertEquals(listOf("charan@example.com"), (gate.state.value!!.proposal as CalendarProposal).attendees)
        gate.confirmContent(proposalId)
        gate.confirmVerbatim(proposalId, "attendees")
        gate.confirmExecute(proposalId)

        val content = dispatched.await().result.content
        assertTrue(content.contains("external_id"))
        assertEquals("charan@example.com", calendarPort.inserted?.attendees?.first())
    }

    @Test
    @DisplayName("happy path with no attendees skips straight to READY after content approval")
    fun `no-attendee event skips verbatim verify`(@TempDir dir: Path) = runTest {
        val calendarPort = FakeCalendarPort()
        val (dispatcher, gate, _) = dispatcherFor(dir, calendarPort = calendarPort)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.dispatch(
                LlmBlock.ToolUse(
                    "tu_1", "calendar_propose_event",
                    """{"title":"Focus block","start":"2026-09-02T06:30:00Z","end":"2026-09-02T07:30:00Z","zone":"Asia/Kolkata","all_day":false}""",
                )
            )
        }
        val proposalId = gate.state.value!!.proposalId
        gate.confirmContent(proposalId)
        assertEquals(ConfirmationGate.Stage.READY, gate.state.value!!.stage)
        gate.confirmExecute(proposalId)
        dispatched.await()
        assertEquals("Focus block", calendarPort.inserted?.title)
    }
}
