package com.secondbrain.vault

import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.NoteSource
import com.secondbrain.model.VaultConfig
import com.secondbrain.ports.WriteResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The vault end to end: write, index, link, search, move, rebuild. */
class VaultIntegrationTest {

    @TempDir
    lateinit var appRoot: Path

    private lateinit var vault: Vault

    private val config = VaultConfig()

    @BeforeEach
    fun setUp() {
        vault = Vault.open(appRoot, config)
    }

    @AfterEach
    fun tearDown() {
        vault.close()
    }

    private fun draft(
        folder: String = "Inbox",
        title: String,
        body: String = "",
        // Derived from the title rather than a shared constant. One shared
        // summary across every fixture made them all look like duplicates of
        // each other once the D-053 guard existed - which was the guard being
        // right and the fixture being lazy.
        summary: String = "a summary about $title",
        tags: List<String> = listOf("test"),
    ) = NoteDraft(folder, title, tags, summary, body, NoteSource.VOICE)

    /**
     * @param confirmNew set where a test deliberately writes a near-identical note
     *        and the duplicate guard would correctly refuse it.
     */
    private suspend fun write(
        folder: String = "Inbox",
        title: String,
        body: String = "",
        confirmNew: Boolean = false,
    ): WriteResult.Written =
        assertInstanceOf(
            WriteResult.Written::class.java,
            vault.writeNote(draft(folder = folder, title = title, body = body), confirmNew = confirmNew),
        )

    @Nested
    @DisplayName("VaultRoot")
    inner class Root {

        @Test
        fun `creates the vault and seeds Inbox only`() {
            assertTrue(Files.isDirectory(appRoot.resolve("vault")))
            assertTrue(Files.isDirectory(appRoot.resolve("vault/Inbox")))
            // D-032: Projects/ and People/ are NOT pre-created.
            assertFalse(Files.exists(appRoot.resolve("vault/Projects")))
            assertFalse(Files.exists(appRoot.resolve("vault/People")))
        }

        @Test
        fun `both databases exist`() {
            assertTrue(Files.exists(appRoot.resolve("app.db")))
            assertTrue(Files.exists(appRoot.resolve("index.db")))
        }

        @Test
        @DisplayName("F20: orphaned temp files are swept and reported")
        fun `temp sweep`() {
            val orphan = appRoot.resolve("vault/Inbox/half-written.md" + AtomicWriter.TMP_SUFFIX)
            Files.writeString(orphan, "incomplete")

            val swept = vault.root.sweepTempFiles()

            assertEquals(1, swept.size)
            assertFalse(Files.exists(orphan))
        }
    }

    @Nested
    @DisplayName("writing notes")
    inner class Writing {

        @Test
        fun `writes a note and indexes it`() = runTest {
            val result = write(title = "Offline inference is the moat")

            assertEquals("Inbox/offline-inference-is-the-moat.md", result.path)
            assertTrue(Files.exists(appRoot.resolve("vault/" + result.path)))

            val note = vault.read(result.path)
            assertNotNull(note)
            assertEquals("Offline inference is the moat", note!!.title)
            assertEquals("Inbox", note.folder)
        }

        @Test
        @DisplayName("EC-N1 three notes with the same title on the same day")
        fun `slug collisions on disk`() = runTest {
            // Deliberately identical titles; confirmNew because this test is about
            // slug suffixing, not duplicate detection.
            val a = write(title = "Same Title", confirmNew = true)
            val b = write(title = "Same Title", confirmNew = true)
            val c = write(title = "Same Title", confirmNew = true)

            assertEquals("Inbox/same-title.md", a.path)
            assertEquals("Inbox/same-title-2.md", b.path)
            assertEquals("Inbox/same-title-3.md", c.path)
            assertFalse(a.slugSuffixed)
            assertTrue(b.slugSuffixed)

            // All three are real, distinct files, and all three are indexed.
            listOf(a, b, c).forEach { assertTrue(Files.exists(appRoot.resolve("vault/" + it.path))) }
            assertEquals(3, vault.notesInFolder("Inbox").size)

            // Frontmatter titles are identical - only the slug differs.
            listOf(a, b, c).forEach { assertEquals("Same Title", vault.read(it.path)!!.title) }
        }

        @Test
        fun `R4 an unsafe folder is rejected as a result, not a crash`() = runTest {
            val result = vault.writeNote(draft(folder = "../../escape", title = "Nope"))
            val rejected = assertInstanceOf(WriteResult.Rejected::class.java, result)
            assertEquals("unsafe_path", rejected.reason)
        }

        @Test
        fun `a note in a new folder creates the folder and indexes it`() = runTest {
            val result = write(folder = "Projects/Positioning", title = "Nested note")
            assertEquals("Projects/Positioning/nested-note.md", result.path)
            assertTrue(vault.index.folders().contains("Projects/Positioning"))
        }
    }

    @Nested
    @DisplayName("EC-N7 / EC-N8 links")
    inner class Links {

        @Test
        @DisplayName("exact hit resolves")
        fun `exact hit`() = runTest {
            write(title = "BluePrint Lens")
            val citing = write(title = "The moat", body = "Relates to [[BluePrint Lens]].")

            assertEquals(listOf("BluePrint Lens"), citing.resolvedLinks)
            assertTrue(citing.danglingLinks.isEmpty())
        }

        @Test
        @DisplayName("fuzzy hit at ~0.9 resolves")
        fun `fuzzy hit`() = runTest {
            write(title = "Competition Demo Plan")
            // One character different out of 20 -> ~0.95 similarity, above 0.85.
            val citing = write(title = "Cites it", body = "See [[Competition Demo Plans]].")

            assertEquals(listOf("Competition Demo Plans"), citing.resolvedLinks, "expected a fuzzy resolve")
        }

        @Test
        @DisplayName("near-miss at ~0.8 stays dangling - a wrong link is worse than no link")
        fun `near miss dangles`() = runTest {
            write(title = "Positioning")
            val citing = write(title = "Cites it", body = "See [[Posit]].")

            assertEquals(listOf("Posit"), citing.danglingLinks)
            assertTrue(citing.resolvedLinks.isEmpty())
        }

        @Test
        fun `EC-N7 a dangling link is recorded and the text is left intact`() = runTest {
            val citing = write(title = "The moat", body = "Relates to [[Nothing Yet]].")

            assertEquals(listOf("Nothing Yet"), citing.danglingLinks)
            assertTrue(vault.read(citing.path)!!.bodyMarkdown.contains("[[Nothing Yet]]"))
            assertEquals(1, vault.danglingLinks().size)
        }

        @Test
        @DisplayName("F14 alias and heading syntax resolve on the base target")
        fun `alias syntax`() = runTest {
            write(title = "BluePrint Lens")

            val aliased = write(title = "A", body = "See [[BluePrint Lens|the other project]].")
            assertEquals(listOf("BluePrint Lens|the other project"), aliased.resolvedLinks)

            val heading = write(title = "B", body = "See [[BluePrint Lens#Roadmap]].")
            assertEquals(listOf("BluePrint Lens#Roadmap"), heading.resolvedLinks)
        }

        @Test
        @DisplayName("F15 a wikilink inside code is not a link")
        fun `code spans are skipped`() = runTest {
            val note = write(
                title = "About wikilinks",
                body = "Inline `[[not a link]]` and a block:\n\n```\n[[also not a link]]\n```\n\nBut [[Real Target]] is.",
            )
            assertEquals(listOf("Real Target"), note.danglingLinks)
            assertFalse(note.danglingLinks.contains("not a link"))
            assertFalse(note.danglingLinks.contains("also not a link"))
        }

        @Test
        @DisplayName("D-030 an ambiguous target stays dangling with both candidates recorded")
        fun `ambiguous stays dangling`() = runTest {
            write(folder = "Projects", title = "Notes")
            write(folder = "People", title = "Notes", confirmNew = true)

            val citing = write(folder = "Inbox", title = "Cites", body = "See [[Notes]].")

            assertEquals(listOf("Notes"), citing.danglingLinks)
            val dangling = vault.danglingLinks().single { it.fromPath == citing.path }
            assertEquals(2, dangling.ambiguousCandidates.size, "both candidates must be recorded")
            assertTrue(dangling.ambiguousCandidates.any { it.startsWith("Projects/") })
            assertTrue(dangling.ambiguousCandidates.any { it.startsWith("People/") })
        }

        @Test
        fun `a note never links to itself`() = runTest {
            val note = write(title = "Self", body = "See [[Self]].")
            assertTrue(note.resolvedLinks.isEmpty())
            assertEquals(listOf("Self"), note.danglingLinks)
        }

        @Test
        @DisplayName("F12 creating the target un-dangles the reference")
        fun `stub creation resolves the dangling link`() = runTest {
            val citing = write(
                folder = "Projects",
                title = "The moat",
                body = "Relates to [[Competition Demo Plan]].",
            )
            assertEquals(1, vault.danglingLinks().size)

            val stub = vault.createStub(citing.path, "Competition Demo Plan")
            val written = assertInstanceOf(WriteResult.Written::class.java, stub)
            assertEquals("Projects/competition-demo-plan.md", written.path)

            // The badge must clear, otherwise the dashboard shows a permanent lie.
            assertTrue(
                vault.danglingLinks().none { it.fromPath == citing.path },
                "still dangling: " + vault.danglingLinks(),
            )
            assertEquals(1, vault.backlinks(written.path).size)
        }

        @Test
        @DisplayName("F11 backlinks carry surrounding context")
        fun `backlink context`() = runTest {
            val target = write(title = "The moat")
            write(
                title = "Demo narrative",
                body = "We should open on [[The moat]], then move to the demo itself.",
            )

            val backlinks = vault.backlinks(target.path)
            assertEquals(1, backlinks.size)
            assertTrue(backlinks[0].context.contains("open on"), backlinks[0].context)
            assertEquals("Demo narrative", backlinks[0].fromTitle)
        }
    }

    @Nested
    @DisplayName("EC-N6 Folder Guard through the vault")
    inner class Folders {

        @Test
        fun `an accepted folder is created and audited`() = runTest {
            val verdict = vault.createFolder("Projects")
            assertInstanceOf(FolderVerdict.Accepted::class.java, verdict)
            assertTrue(Files.isDirectory(appRoot.resolve("vault/Projects")))

            val decisions = vault.folderDecisions()
            assertEquals(1, decisions.size)
            assertEquals("ACCEPTED", decisions[0].verdict)
            assertEquals("Projects", decisions[0].proposed)
        }

        @Test
        @DisplayName("section 5 rule 6: rejections are audited too")
        fun `rejections are audited`() = runTest {
            vault.createFolder("Projects")
            val verdict = vault.createFolder("Project")

            val rejected = assertInstanceOf(FolderVerdict.Rejected::class.java, verdict)
            assertEquals(FolderVerdict.RejectReason.SIMILAR, rejected.reason)
            assertFalse(Files.exists(appRoot.resolve("vault/Project")), "the folder must not be created")

            val decisions = vault.folderDecisions()
            assertEquals("REJECTED_SIMILAR", decisions[0].verdict)
            assertEquals("Projects", decisions[0].matched)
            assertNotNull(decisions[0].score)
        }

        @Test
        @DisplayName("D-026 the audit trail lives in app.db and is projected into index.db")
        fun `audit projection`() = runTest {
            vault.createFolder("Projects")
            vault.createFolder("Project")

            val fromAppDb = vault.appDb.folderDecisions()
            val fromIndex = vault.index.folderDecisions()

            assertEquals(2, fromAppDb.size)
            assertEquals(fromAppDb.map { it.id }, fromIndex.map { it.id })
            assertEquals(fromAppDb.map { it.verdict }, fromIndex.map { it.verdict })
        }
    }

    @Nested
    @DisplayName("append and move")
    inner class Mutations {

        @Test
        @DisplayName("F3 append preserves created and bumps updated")
        fun `append`() = runTest {
            val note = write(title = "Running notes", body = "First thought.")
            val before = vault.read(note.path)!!

            vault.appendNote(note.path, "Later", "Second thought.")
            val after = vault.read(note.path)!!

            assertEquals(before.created, after.created, "created must never move on append")
            assertTrue(after.updated >= before.updated)
            assertTrue(after.bodyMarkdown.contains("First thought."))
            assertTrue(after.bodyMarkdown.contains("## Later"))
            assertTrue(after.bodyMarkdown.contains("Second thought."))
        }

        @Test
        @DisplayName("D-033 appending under a heading that exists inserts into that section")
        fun `append to existing heading`() = runTest {
            val note = write(
                title = "Structured",
                body = "## Alpha\n\nfirst alpha\n\n## Beta\n\nfirst beta",
            )
            vault.appendNote(note.path, "Alpha", "second alpha")

            val body = vault.read(note.path)!!.bodyMarkdown
            val alphaIndex = body.indexOf("second alpha")
            val betaIndex = body.indexOf("## Beta")
            assertTrue(alphaIndex in 1 until betaIndex, "text landed outside the Alpha section:\n$body")
        }

        @Test
        @DisplayName("appended text cannot inject a frontmatter delimiter")
        fun `append cannot corrupt frontmatter`() = runTest {
            val note = write(title = "Target", body = "body")
            vault.appendNote(note.path, "Injected", "---\ntitle: hijacked\n---\n")

            val reread = vault.read(note.path)!!
            assertEquals("Target", reread.title, "the title must not be hijacked")
            assertFalse(reread.bodyMarkdown.lines().any { it.trim() == "---" })
        }

        @Test
        @DisplayName("EC-N5 move records moved_from and re-indexes")
        fun `move`() = runTest {
            val note = write(folder = "Inbox", title = "Misplaced")
            val moved = assertInstanceOf(WriteResult.Written::class.java, vault.moveNote(note.path, "Projects"))

            assertEquals("Projects/misplaced.md", moved.path)
            assertFalse(Files.exists(appRoot.resolve("vault/" + note.path)), "the old file must be gone")
            assertTrue(Files.exists(appRoot.resolve("vault/" + moved.path)))

            val reread = vault.read(moved.path)!!
            assertEquals(listOf("Inbox/misplaced.md"), reread.movedFrom)

            assertNull(vault.index.note(note.path), "the old path must leave the index")
            assertNotNull(vault.index.note(moved.path))
        }

        @Test
        fun `moving a note updates inbound links`() = runTest {
            val target = write(folder = "Inbox", title = "Target note")
            write(folder = "Inbox", title = "Source", body = "See [[Target note]].")

            val moved = assertInstanceOf(WriteResult.Written::class.java, vault.moveNote(target.path, "Projects"))

            // Rebuild is the honest way to check the whole graph settled.
            vault.rebuildIndex()
            assertEquals(1, vault.backlinks(moved.path).size)
        }
    }

    @Nested
    @DisplayName("search")
    inner class Search {

        @Test
        fun `finds a note by body text and returns a snippet`() = runTest {
            write(title = "The moat", body = "Competitors all need a network round-trip for inference.")

            val hits = vault.search("inference")
            assertEquals(1, hits.size)
            assertEquals("The moat", hits[0].title)
            assertTrue(hits[0].snippet.isNotBlank(), "snippet() must return text, not NULL")
            assertTrue(hits[0].snippet.contains("["), "the match should be delimited: '${hits[0].snippet}'")
        }

        @Test
        fun `finds a note by title and summary`() = runTest {
            write(title = "Pricing thoughts", body = "unrelated body")
            assertEquals(1, vault.search("pricing").size)
        }

        @Test
        @DisplayName("punctuation in a transcript does not become an FTS syntax error")
        fun `query sanitising`() = runTest {
            write(title = "The moat", body = "offline inference wins")

            // Every one of these is FTS5 syntax and would throw if passed through.
            listOf(
                "inference", "inference OR", "\"unbalanced", "off-line inference",
                "NOT inference", "inference*", "a:b", "^start", "(paren",
            ).forEach { query ->
                vault.search(query) // must not throw
            }
            assertTrue(vault.search("offline inference").isNotEmpty())
        }

        @Test
        fun `search reflects an update rather than returning stale text`() = runTest {
            // The contentless schema in section 2 cannot UPDATE, which is why this
            // test exists at all.
            val note = write(title = "Changeable", body = "aardvark")
            assertEquals(1, vault.search("aardvark").size)

            vault.appendNote(note.path, "More", "zebra")
            assertEquals(1, vault.search("zebra").size)
            assertEquals(1, vault.search("aardvark").size)
        }

        @Test
        fun `a deleted note leaves the search index`() = runTest {
            val note = write(title = "Ephemeral", body = "vanishing text")
            assertEquals(1, vault.search("vanishing").size)

            vault.index.deleteNote(note.path)
            assertTrue(vault.search("vanishing").isEmpty(), "FTS rows must be deletable")
        }
    }

    @Nested
    @DisplayName("EC-A5 tree")
    inner class Tree {

        @Test
        @DisplayName("F10 the design board's rollup: Projects 23 = 9 + 7 + 7")
        fun `rollup counts`() = runTest {
            // Titles that differ only by an index; the guard rightly flags them.
            repeat(9) { write(folder = "Projects/BluePrint Lens", title = "bpl $it", confirmNew = true) }
            repeat(7) { write(folder = "Projects/Positioning", title = "pos $it", confirmNew = true) }
            repeat(7) { write(folder = "Projects/Second Brain", title = "sb $it", confirmNew = true) }
            repeat(4) { write(folder = "Inbox", title = "inbox $it", confirmNew = true) }

            val tree = vault.tree()
            val projects = tree.children.single { it.name == "Projects" }

            assertEquals(0, projects.directNoteCount, "Projects holds no notes of its own")
            assertEquals(23, projects.rollupNoteCount, "the rollup is what the tree displays")

            val inbox = tree.children.single { it.name == "Inbox" }
            assertEquals(4, inbox.directNoteCount)
            assertEquals(4, inbox.rollupNoteCount)

            assertEquals(27, tree.rollupNoteCount)
        }

        @Test
        fun `the dangling badge counts links from notes in a folder`() = runTest {
            write(folder = "Projects/Positioning", title = "a", body = "[[Missing One]] and [[Missing Two]]")
            write(folder = "Inbox", title = "b", body = "no links", confirmNew = true)

            val tree = vault.tree()
            val positioning = tree.children
                .single { it.name == "Projects" }.children
                .single { it.name == "Positioning" }

            assertEquals(2, positioning.danglingCount)
            assertEquals(0, tree.children.single { it.name == "Inbox" }.danglingCount)
        }

        @Test
        @DisplayName("EC-A5 depth limiting truncates children but not the counts")
        fun `depth limit`() = runTest {
            write(folder = "Projects/Deep/Deeper", title = "buried")

            val shallow = vault.tree(depth = 1)
            val projects = shallow.children.single { it.name == "Projects" }

            assertTrue(projects.children.isEmpty(), "depth 1 must not descend")
            assertEquals(1, projects.rollupNoteCount, "the count must still include the buried note")

            assertTrue(vault.tree(depth = 3).children.single { it.name == "Projects" }.children.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("EC-N11 index rebuild")
    inner class Rebuild {

        /**
         * Canonical projection of every row the index holds.
         *
         * The exit criterion says "identical contents". Comparing the `.db` file
         * byte for byte would be the wrong test — SQLite page layout, freelists and
         * WAL state all vary between two databases holding identical rows. Comparing
         * every row is the claim that actually matters.
         */
        private fun dump(): String = buildString {
            appendLine("-- folders --")
            vault.index.folders().sorted().forEach { folder ->
                appendLine(folder)
            }
            appendLine("-- notes --")
            vault.index.allNotes().forEach { n ->
                appendLine(
                    listOf(
                        n.path, n.folder, n.title, n.slug, n.summary,
                        n.tags.joinToString("|"), n.source, n.createdAt, n.updatedAt, n.contentHash,
                    ).joinToString("\t")
                )
            }
            appendLine("-- links --")
            vault.index.allLinks().forEach { appendLine(it.fromPath + "\t" + it.toPath + "\t" + it.rawTarget) }
            appendLine("-- dangling --")
            vault.index.allDangling().forEach {
                appendLine(it.fromPath + "\t" + it.rawTarget + "\t" + it.ambiguousCandidates.joinToString("|"))
            }
            appendLine("-- decisions --")
            vault.index.folderDecisions(1000).forEach {
                appendLine(it.id.toString() + "\t" + it.proposed + "\t" + it.verdict + "\t" + it.at)
            }
        }

        private suspend fun populate() {
            vault.createFolder("Projects")
            vault.createFolder("Project") // a rejection, for the audit trail
            write(folder = "Projects", title = "BluePrint Lens", body = "The other project.")
            write(folder = "Projects", title = "The moat", body = "Relates to [[BluePrint Lens]] and [[Nothing]].")
            write(folder = "Inbox", title = "Same Title", confirmNew = true)
            write(folder = "Inbox", title = "Same Title", confirmNew = true)
            write(folder = "Inbox", title = "Pricing: a positioning problem", body = "Colons in titles.")
        }

        @Test
        @DisplayName("Step 2 exit criterion: delete index.db and rebuild with identical contents")
        fun `rebuild is identical`() = runTest {
            populate()
            val before = dump()

            vault.rebuildIndex()
            val after = dump()

            assertEquals(before, after, "a rebuild must reproduce every row")
        }

        @Test
        @DisplayName("the file can be deleted outright and rebuilt")
        fun `delete the file and rebuild`() = runTest {
            populate()
            val before = dump()

            vault.index.deleteFile()
            vault.rebuildIndex()

            assertEquals(before, dump())
        }

        @Test
        @DisplayName("D-027 no timestamp is captured at index time")
        fun `timestamps are derived`() = runTest {
            populate()
            val first = dump()
            Thread.sleep(20)
            vault.rebuildIndex()
            Thread.sleep(20)
            vault.rebuildIndex()

            assertEquals(first, dump(), "a wall-clock value anywhere would make these differ")
        }

        @Test
        @DisplayName("D-026 the audit trail survives an index rebuild because app.db holds it")
        fun `audit survives rebuild`() = runTest {
            vault.createFolder("Projects")
            vault.createFolder("Project")
            assertEquals(2, vault.index.folderDecisions().size)

            vault.index.deleteFile()
            vault.rebuildIndex()

            assertEquals(2, vault.index.folderDecisions().size, "the projection must be restored from app.db")
            assertEquals(2, vault.appDb.folderDecisions().size)
        }

        @Test
        @DisplayName("EC-N11 a schema-version mismatch rebuilds automatically")
        fun `schema drift rebuilds`() = runTest {
            populate()
            val before = dump()
            vault.close()

            // Simulate a database written by a different schema version.
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + appRoot.resolve("index.db")).use { c ->
                c.createStatement().use { it.execute("pragma user_version=999") }
            }

            vault = Vault.open(appRoot, config)
            assertEquals(VaultIndex.SCHEMA_VERSION, vault.index.userVersion())
            assertEquals(before, dump())
        }

        @Test
        fun `a note at the vault root is skipped rather than breaking the foreign key`() = runTest {
            populate()
            // D-032: every note lives in a folder. A stray root-level file must not
            // break the scan.
            Files.writeString(appRoot.resolve("vault/stray.md"), "---\ntitle: \"Stray\"\n---\n\nbody\n")

            val report = vault.rebuildIndex()
            assertTrue(report.skipped.any { it.contains("stray.md") }, report.skipped.toString())
            assertNull(vault.index.note("stray.md"))
        }

        @Test
        @DisplayName("D-036 non-markdown files are ignored")
        fun `non markdown ignored`() = runTest {
            Files.writeString(appRoot.resolve("vault/Inbox/notes.txt"), "not markdown")
            Files.writeString(appRoot.resolve("vault/Inbox/photo.png"), "binary-ish")

            val report = vault.rebuildIndex()
            assertEquals(0, report.notes)
            assertTrue(report.skipped.isEmpty(), "non-markdown should be invisible, not 'skipped'")
        }
    }

    @Nested
    @DisplayName("F13 external deletion")
    inner class Deletion {

        @Test
        fun `deleting a note dangles its inbound links instead of dropping them`() = runTest {
            val target = write(title = "Doomed")
            val citing = write(title = "Cites", body = "See [[Doomed]].")

            assertEquals(1, vault.backlinks(target.path).size)
            assertTrue(vault.danglingLinks().isEmpty())

            vault.index.deleteNote(target.path)

            assertNull(vault.index.note(target.path))
            val dangling = vault.danglingLinks()
            assertEquals(1, dangling.size, "the inbound link must become dangling, not vanish")
            assertEquals(citing.path, dangling[0].fromPath)
            assertEquals("Doomed", dangling[0].rawTarget)
        }
    }
}
