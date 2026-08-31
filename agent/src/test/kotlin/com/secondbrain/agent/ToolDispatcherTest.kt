package com.secondbrain.agent

import com.secondbrain.model.ToolClass
import com.secondbrain.ports.LlmBlock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** EC-A3, EC-A4, R2, R3, and H13 (a handler that throws). */
class ToolDispatcherTest {

    private val schema = """
        {"type":"object","properties":{
          "title":{"type":"string"},
          "depth":{"type":"integer"},
          "confirm":{"type":"boolean"},
          "tags":{"type":"array"}
        },"required":["title"]}
    """.trimIndent()

    private val registry = ToolRegistry.builder()
        .autonomous("vault_write_note", "writes a note", schema) { input ->
            ToolOutcome("""{"path":"Inbox/x.md","echo":$input}""", notePath = "Inbox/x.md")
        }
        .autonomous("boom", "always throws", """{"type":"object","properties":{},"required":[]}""") {
            throw IllegalStateException("disk on fire")
        }
        .build()

    private val dispatcher = ToolDispatcher(registry)

    private fun call(name: String, input: String) = LlmBlock.ToolUse("tu_1", name, input)

    @Nested
    @DisplayName("EC-A3 unknown tool")
    inner class UnknownTool {

        @Test
        fun `returns a structured error listing the valid tools`() = runTest {
            val result = dispatcher.dispatch(call("vault_delete_everything", "{}"))

            assertTrue(result.result.isError)
            assertTrue(result.needsSelfCorrection)
            assertTrue(result.result.content.contains("unknown_tool"))
            // "Return a structured unknown_tool error as tool_result with the
            // valid tool list" - the model cannot correct itself without it.
            assertTrue(result.result.content.contains("vault_write_note"))
            assertTrue(result.result.content.contains("available_tools"))
        }

        @Test
        fun `still produces a tool_result with the right id`() = runTest {
            val result = dispatcher.dispatch(call("nope", "{}"))
            assertEquals("tu_1", result.result.toolUseId)
        }
    }

    @Nested
    @DisplayName("EC-A4 schema validation before dispatch")
    inner class Validation {

        @Test
        fun `missing required field is reported by name`() = runTest {
            val result = dispatcher.dispatch(call("vault_write_note", """{"depth":3}"""))

            assertTrue(result.result.isError)
            assertTrue(result.needsSelfCorrection)
            assertTrue(result.result.content.contains("missing required field 'title'"), result.result.content)
        }

        @Test
        fun `wrong types are reported per field`() = runTest {
            val cases = mapOf(
                """{"title":123}""" to "must be a string",
                """{"title":"x","depth":"three"}""" to "must be an integer",
                """{"title":"x","confirm":"yes"}""" to "must be a boolean",
                """{"title":"x","tags":"one"}""" to "must be an array",
            )
            cases.forEach { (input, expected) ->
                val result = dispatcher.dispatch(call("vault_write_note", input))
                assertTrue(result.result.isError, "expected rejection for $input")
                assertTrue(result.result.content.contains(expected), "for $input got ${result.result.content}")
            }
        }

        @Test
        fun `an unknown field is reported rather than silently dropped`() = runTest {
            val result = dispatcher.dispatch(call("vault_write_note", """{"title":"x","colour":"blue"}"""))

            // A model that invents a field has usually misread the schema; dropping
            // it silently produces a note missing whatever it meant to set.
            assertTrue(result.result.isError)
            assertTrue(result.result.content.contains("unknown field 'colour'"), result.result.content)
        }

        @Test
        fun `malformed JSON is a validation error, not a crash`() = runTest {
            val result = dispatcher.dispatch(call("vault_write_note", """{"title": """))
            assertTrue(result.result.isError)
            assertTrue(result.result.content.contains("not valid JSON"), result.result.content)
        }

        @Test
        fun `valid input dispatches and the handler sees it verbatim`() = runTest {
            val result = dispatcher.dispatch(call("vault_write_note", """{"title":"The moat","depth":2}"""))

            assertFalse(result.result.isError)
            assertFalse(result.needsSelfCorrection)
            assertTrue(result.result.content.contains("The moat"))
            assertEquals("Inbox/x.md", result.event.notePath)
        }

        @Test
        fun `empty input is treated as an empty object`() = runTest {
            val reg = ToolRegistry.builder()
                .autonomous("noargs", "takes nothing", """{"type":"object","properties":{},"required":[]}""") {
                    ToolOutcome("""{"ok":true}""")
                }
                .build()
            val result = ToolDispatcher(reg).dispatch(call("noargs", ""))
            assertFalse(result.result.isError)
        }
    }

    @Nested
    @DisplayName("H13 a handler that throws")
    inner class Throwing {

        @Test
        fun `becomes is_error rather than killing the loop`() = runTest {
            val result = dispatcher.dispatch(call("boom", "{}"))

            assertTrue(result.result.isError)
            assertTrue(result.result.content.contains("tool_failed"))
            assertTrue(result.result.content.contains("disk on fire"))
            // Not a self-correction: the input was fine, the tool broke. Telling
            // the model to fix its arguments would send it in circles.
            assertFalse(result.needsSelfCorrection)
        }

        @Test
        fun `the result still carries the tool_use_id`() = runTest {
            // A tool_use with no matching tool_result is a 400 on the next request.
            assertEquals("tu_1", dispatcher.dispatch(call("boom", "{}")).result.toolUseId)
        }
    }

    @Nested
    @DisplayName("R2 / R3 tool classes")
    inner class Classes {

        @Test
        fun `a gated tool never executes from a model call`() = runTest {
            var executed = false
            val reg = ToolRegistry.builder()
                .gated("email_draft", "drafts an email", """{"type":"object","properties":{},"required":[]}""") {
                    executed = true
                    ToolOutcome("""{"sent":true}""")
                }
                .build()

            val result = ToolDispatcher(reg).dispatch(call("email_draft", "{}"))

            assertFalse(executed, "R2: a gated handler must not run because the model asked")
            assertEquals("email_draft", result.gatedToolName)
            assertTrue(result.result.content.contains("awaiting_user_confirmation"))
            assertEquals(ToolClass.GATED, result.event.toolClass)
        }

        @Test
        @DisplayName("R2: a direct-execution tool for an irreversible action cannot be registered")
        fun `forbidden names`() {
            listOf("email_send", "calendar_create", "place_order", "send_email").forEach { name ->
                val e = assertThrows(IllegalArgumentException::class.java) {
                    ToolRegistry.builder()
                        .autonomous(name, "d", """{"type":"object","properties":{},"required":[]}""") {
                            ToolOutcome("{}")
                        }
                        .build()
                }
                assertTrue(e.message!!.contains("R2"), "for '$name': ${e.message}")
            }
        }

        @Test
        fun `duplicate registration is refused`() {
            assertThrows(IllegalArgumentException::class.java) {
                ToolRegistry.builder()
                    .autonomous("t", "d", """{"type":"object","properties":{},"required":[]}""") { ToolOutcome("{}") }
                    .autonomous("t", "d", """{"type":"object","properties":{},"required":[]}""") { ToolOutcome("{}") }
                    .build()
            }
        }

        @Test
        fun `a tool needs a description, because the model reads it`() {
            assertThrows(IllegalArgumentException::class.java) {
                ToolSpec("t", "", "{}", ToolClass.AUTONOMOUS) { ToolOutcome("{}") }
            }
        }

        @Test
        fun `an invalid tool name is refused at registration`() {
            listOf("has space", "has.dot", "", "a".repeat(65)).forEach { name ->
                assertThrows(IllegalArgumentException::class.java, {
                    ToolSpec(name, "d", "{}", ToolClass.AUTONOMOUS) { ToolOutcome("{}") }
                }, "'$name' should be refused")
            }
        }

        @Test
        @DisplayName("H6: registry order is stable, because it is part of the cache key")
        fun `stable order`() {
            fun build() = ToolRegistry.builder()
                .autonomous("c", "d", """{"type":"object","properties":{},"required":[]}""") { ToolOutcome("{}") }
                .autonomous("a", "d", """{"type":"object","properties":{},"required":[]}""") { ToolOutcome("{}") }
                .autonomous("b", "d", """{"type":"object","properties":{},"required":[]}""") { ToolOutcome("{}") }
                .build()

            // Registration order, not sorted - and identical across builds.
            assertEquals(listOf("c", "a", "b"), build().names)
            assertEquals(build().names, build().names)
        }
    }
}
