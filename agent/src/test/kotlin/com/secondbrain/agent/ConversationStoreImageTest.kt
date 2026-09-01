package com.secondbrain.agent

import com.secondbrain.model.AgentConfig
import com.secondbrain.model.Phase
import com.secondbrain.model.TurnUsage
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Step 8: `LlmBlock.Image` through `ConversationStore`'s persistence round-trip.
 *
 * Narrow and separate from the rest of `ConversationStoreTest` (which does not
 * exist as its own file — see the module's own precedent of testing this class
 * indirectly through `AgentLoopTest`/manual runs) because this is exactly the
 * kind of thing D-078 already burned an afternoon on once this session: a
 * block type that serialises to *something* but not back to the *same* thing.
 * See [TestDatabases] for why every test here closes its own `AgentDb`.
 */
class ConversationStoreImageTest {

    private val databases = TestDatabases()

    @AfterEach
    fun closeDatabases() = databases.closeAll()

    @Test
    @DisplayName("an image block round-trips through app.db unchanged")
    fun `image survives persist and replay`(@TempDir dir: Path) = runTest {
        val store = ConversationStore(databases.open(dir), AgentConfig(apiKey = "test"))
        val state = store.startConversation(Phase.CAPTURE)

        val image = LlmBlock.Image(base64 = "c29tZS1mYWtlLWJ5dGVz", mediaType = "image/jpeg")
        val messages = listOf(
            LlmMessage(LlmMessage.Role.USER, listOf(image, LlmBlock.Text("here's my list"))),
            LlmMessage(LlmMessage.Role.ASSISTANT, listOf(LlmBlock.Text("Saved."))),
        )

        store.recordTurn(state, turnIndex = 0, messages = messages, usage = TurnUsage.ZERO)
        val replayed = store.replay(state.conversationId)

        assertEquals(2, replayed.size)
        val userMessage = replayed.first()
        assertEquals(2, userMessage.blocks.size)
        assertEquals(image, userMessage.blocks.first())
    }

    @Test
    @DisplayName("a message with no image round-trips with no image block invented")
    fun `text only message stays text only`(@TempDir dir: Path) = runTest {
        val store = ConversationStore(databases.open(dir), AgentConfig(apiKey = "test"))
        val state = store.startConversation(Phase.CAPTURE)

        store.recordTurn(
            state, turnIndex = 0,
            messages = listOf(LlmMessage(LlmMessage.Role.USER, listOf(LlmBlock.Text("just words")))),
            usage = TurnUsage.ZERO,
        )

        val replayed = store.replay(state.conversationId)
        assertEquals(1, replayed.single().blocks.size)
        assertEquals(LlmBlock.Text("just words"), replayed.single().blocks.single())
    }
}
