package com.secondbrain.vault

import com.secondbrain.model.Backlink
import com.secondbrain.model.DanglingLink
import com.secondbrain.model.FolderDecision
import com.secondbrain.model.LinkRef
import com.secondbrain.model.Note
import com.secondbrain.model.SearchHit
import com.secondbrain.model.TreeNode
import com.secondbrain.model.VaultConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * `index.db` — the disposable one.
 *
 * R10: nothing here may be unrecoverable. Delete the file and [rebuildFrom]
 * reconstructs every row by re-walking the vault, so the Step 2 exit criterion
 * ("`index.db` can be deleted and fully rebuilt from `vault/` with identical
 * contents") is literally true rather than approximately true. Two changes were
 * needed to make it so:
 *
 * **The FTS5 schema in section 2 cannot be updated or deleted from.** It specifies
 * `content=''`, a contentless table. Measured against SQLite 3.53.4:
 *
 * ```
 * DELETE   ->  cannot DELETE from contentless fts5 table
 * UPDATE   ->  cannot UPDATE contentless fts5 table
 * snippet()->  NULL
 * ```
 *
 * EC-N10 (re-index an externally edited note), EC-N11 (rebuild), every note edit,
 * and the dashboard's "Search the vault" all need what that schema forbids. So
 * this uses a plain FTS5 table. Body duplication is irrelevant at a few thousand
 * notes and it buys working UPDATE/DELETE plus real snippets (D-026).
 *
 * **Every timestamp is derived, never captured at index time.** Section 2 has
 * `folders.created_at` and `dangling_links.seen_at`, both of which would differ on
 * every rebuild and make "identical contents" impossible. Here `created_at` is the
 * earliest `created` among a folder's notes and `seen_at` is the referencing
 * note's `updated`, so a rebuild is byte-identical (D-027).
 */
class VaultIndex(
    private val file: Path,
    private val config: VaultConfig = VaultConfig(),
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(VaultIndex::class.java)
    private val json = Json

    private var connection: Connection = open(file)

    companion object {
        /** Bumped on any schema change. A mismatch rebuilds; it never migrates. */
        const val SCHEMA_VERSION: Int = 1

        private fun open(file: Path): Connection =
            DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()).apply {
                createStatement().use { s ->
                    s.execute("pragma journal_mode=WAL")
                    s.execute("pragma busy_timeout=5000")
                    // NORMAL, not FULL: this database is disposable by design, so
                    // trading durability for speed is the correct call here and
                    // the opposite of the call in AppDb.
                    s.execute("pragma synchronous=NORMAL")
                }
            }
    }

    /**
     * True when the schema was wrong or missing and the caller must rebuild.
     *
     * EC-N11: a schema-version mismatch deletes and recreates rather than
     * migrating. That is only safe because every row is derivable from the vault.
     */
    fun openOrRecreate(): Boolean {
        val version = userVersion()
        val hasTables = tableExists("notes")

        if (version == SCHEMA_VERSION && hasTables) {
            log.debug("index.db schema version {} - up to date", version)
            return false
        }

        if (hasTables || version != 0) {
            log.info(
                "index.db schema is version {} (expected {}) - discarding and rebuilding from the vault.",
                version, SCHEMA_VERSION,
            )
        }
        recreate()
        return true
    }

    /** Drops everything and recreates the schema. */
    fun recreate() {
        connection.createStatement().use { s ->
            s.execute("drop table if exists notes_fts")
            s.execute("drop table if exists dangling_links")
            s.execute("drop table if exists links")
            s.execute("drop table if exists notes")
            s.execute("drop table if exists folders")
            s.execute("drop table if exists folder_decisions")
        }
        createSchema()
        setUserVersion(SCHEMA_VERSION)
    }

    private fun createSchema() {
        connection.createStatement().use { s ->
            s.execute(
                """
                CREATE TABLE folders (
                  path        TEXT PRIMARY KEY,
                  name        TEXT NOT NULL,
                  depth       INTEGER NOT NULL,
                  parent      TEXT,
                  note_count  INTEGER NOT NULL DEFAULT 0,
                  created_at  TEXT NOT NULL
                )
                """.trimIndent()
            )
            s.execute(
                """
                CREATE TABLE notes (
                  path         TEXT PRIMARY KEY,
                  path_lower   TEXT NOT NULL,
                  folder       TEXT NOT NULL REFERENCES folders(path),
                  title        TEXT NOT NULL,
                  slug         TEXT NOT NULL,
                  summary      TEXT,
                  tags         TEXT,
                  source       TEXT NOT NULL,
                  created_at   TEXT NOT NULL,
                  updated_at   TEXT NOT NULL,
                  content_hash TEXT NOT NULL
                )
                """.trimIndent()
            )
            // F7: writing TheMoat.md then themoat.md leaves ONE file on this
            // filesystem and silently destroys the first note's content. A
            // case-sensitive TEXT primary key cannot see that, so the collision is
            // caught here instead.
            s.execute("CREATE UNIQUE INDEX idx_notes_path_lower ON notes(path_lower)")
            s.execute("CREATE INDEX idx_notes_folder ON notes(folder)")
            s.execute("CREATE INDEX idx_notes_updated ON notes(updated_at DESC)")

            // Plain FTS5, not content=''. See the class comment.
            s.execute(
                """
                CREATE VIRTUAL TABLE notes_fts USING fts5(
                  path UNINDEXED, title, summary, body, tokenize='porter unicode61'
                )
                """.trimIndent()
            )

            s.execute(
                """
                CREATE TABLE links (
                  from_path   TEXT NOT NULL,
                  to_path     TEXT NOT NULL,
                  raw_target  TEXT NOT NULL,
                  score       REAL NOT NULL,
                  PRIMARY KEY (from_path, raw_target)
                )
                """.trimIndent()
            )
            s.execute("CREATE INDEX idx_links_to ON links(to_path)")

            s.execute(
                """
                CREATE TABLE dangling_links (
                  from_path   TEXT NOT NULL,
                  raw_target  TEXT NOT NULL,
                  candidates  TEXT NOT NULL DEFAULT '[]',
                  seen_at     TEXT NOT NULL,
                  PRIMARY KEY (from_path, raw_target)
                )
                """.trimIndent()
            )
            s.execute("CREATE INDEX idx_dangling_target ON dangling_links(raw_target)")

            // One-way projection of app.db. Never written except by syncFolderDecisions.
            s.execute(
                """
                CREATE TABLE folder_decisions (
                  id          INTEGER PRIMARY KEY,
                  proposed    TEXT NOT NULL,
                  verdict     TEXT NOT NULL,
                  matched     TEXT,
                  score       REAL,
                  at          TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    // ── folders ─────────────────────────────────────────────────────────────

    /**
     * Inserts [path] and every ancestor above it.
     *
     * The invariant is "a folders row implies rows for all of its ancestors", and
     * it has to be enforced here rather than at each call site. Writing a note to
     * `Projects/Positioning` creates both directories on disk in one call, so
     * inserting only the leaf leaves `Projects` with no row — which breaks the
     * `notes.folder` foreign key and, more visibly, makes the dashboard tree
     * render with no top level at all because nothing has `parent = ''`.
     */
    fun upsertFolder(path: String, createdAt: Instant) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        segments.indices.forEach { i ->
            upsertSingleFolder(segments.take(i + 1).joinToString("/"), createdAt)
        }
    }

    private fun upsertSingleFolder(path: String, createdAt: Instant) {
        val name = path.substringAfterLast('/')
        val depth = path.count { it == '/' } + 1
        val parent = path.substringBeforeLast('/', "").ifEmpty { null }

        connection.prepareStatement(
            """
            INSERT INTO folders(path, name, depth, parent, note_count, created_at)
            VALUES (?,?,?,?,0,?)
            ON CONFLICT(path) DO UPDATE SET
              name=excluded.name, depth=excluded.depth, parent=excluded.parent,
              created_at=MIN(folders.created_at, excluded.created_at)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, path)
            ps.setString(2, name)
            ps.setInt(3, depth)
            if (parent == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, parent)
            ps.setString(5, createdAt.toString())
            ps.executeUpdate()
        }
    }

    fun folders(): Set<String> {
        val out = mutableSetOf<String>()
        connection.createStatement().use { s ->
            s.executeQuery("SELECT path FROM folders").use { rs ->
                while (rs.next()) out += rs.getString(1)
            }
        }
        return out
    }

    /** Recomputes `note_count` for every folder. Direct counts only (D-027). */
    fun recomputeFolderCounts() {
        connection.createStatement().use { s ->
            s.execute(
                """
                UPDATE folders SET note_count = (
                  SELECT COUNT(*) FROM notes WHERE notes.folder = folders.path
                )
                """.trimIndent()
            )
        }
    }

    // ── notes ───────────────────────────────────────────────────────────────

    fun upsertNote(note: Note, body: String) {
        connection.prepareStatement(
            """
            INSERT INTO notes(path, path_lower, folder, title, slug, summary, tags, source,
                              created_at, updated_at, content_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(path) DO UPDATE SET
              folder=excluded.folder, title=excluded.title, slug=excluded.slug,
              summary=excluded.summary, tags=excluded.tags, source=excluded.source,
              created_at=excluded.created_at, updated_at=excluded.updated_at,
              content_hash=excluded.content_hash
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, note.path)
            ps.setString(2, note.path.lowercase())
            ps.setString(3, note.folder)
            ps.setString(4, note.title)
            ps.setString(5, note.slug)
            ps.setString(6, note.summary)
            ps.setString(7, json.encodeToString(ListSerializer(String.serializer()), note.tags))
            ps.setString(8, note.source.name)
            ps.setString(9, note.created.toString())
            ps.setString(10, note.updated.toString())
            ps.setString(11, note.contentHash)
            ps.executeUpdate()
        }

        // Plain FTS5 supports delete-then-insert, which the section 2 schema did not.
        connection.prepareStatement("DELETE FROM notes_fts WHERE path = ?").use { ps ->
            ps.setString(1, note.path)
            ps.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO notes_fts(path, title, summary, body) VALUES (?,?,?,?)"
        ).use { ps ->
            ps.setString(1, note.path)
            ps.setString(2, note.title)
            ps.setString(3, note.summary)
            ps.setString(4, body)
            ps.executeUpdate()
        }
    }

    /**
     * Removes a note and reconciles its links.
     *
     * Note deletion is absent from the artifacts entirely (F13). EC-N10 admits
     * external editing, which includes deleting a file, so this has to exist.
     * Outbound links go away with the note; **inbound** links become dangling
     * rather than vanishing, so the dashboard shows that something now points at
     * nothing (D-032).
     */
    fun deleteNote(path: String) {
        val inbound = backlinkSources(path)

        connection.prepareStatement("DELETE FROM notes WHERE path = ?").use { ps ->
            ps.setString(1, path); ps.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM notes_fts WHERE path = ?").use { ps ->
            ps.setString(1, path); ps.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM links WHERE from_path = ?").use { ps ->
            ps.setString(1, path); ps.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM dangling_links WHERE from_path = ?").use { ps ->
            ps.setString(1, path); ps.executeUpdate()
        }

        inbound.forEach { (fromPath, rawTarget, updatedAt) ->
            connection.prepareStatement("DELETE FROM links WHERE from_path = ? AND raw_target = ?").use { ps ->
                ps.setString(1, fromPath); ps.setString(2, rawTarget); ps.executeUpdate()
            }
            insertDangling(DanglingLink(fromPath, rawTarget), updatedAt)
        }
    }

    private fun backlinkSources(toPath: String): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        connection.prepareStatement(
            """
            SELECT l.from_path, l.raw_target, n.updated_at
            FROM links l JOIN notes n ON n.path = l.from_path
            WHERE l.to_path = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, toPath)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                }
            }
        }
        return out
    }

    fun note(path: String): IndexedNote? {
        connection.prepareStatement(
            "SELECT path, folder, title, slug, summary, tags, source, created_at, updated_at, content_hash " +
                "FROM notes WHERE path_lower = ?"
        ).use { ps ->
            ps.setString(1, path.lowercase())
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return IndexedNote(
                    path = rs.getString("path"),
                    folder = rs.getString("folder"),
                    title = rs.getString("title"),
                    slug = rs.getString("slug"),
                    summary = rs.getString("summary") ?: "",
                    tags = runCatching {
                        json.decodeFromString(ListSerializer(String.serializer()), rs.getString("tags") ?: "[]")
                    }.getOrDefault(emptyList()),
                    source = rs.getString("source"),
                    createdAt = rs.getString("created_at"),
                    updatedAt = rs.getString("updated_at"),
                    contentHash = rs.getString("content_hash"),
                )
            }
        }
    }

    data class IndexedNote(
        val path: String,
        val folder: String,
        val title: String,
        val slug: String,
        val summary: String,
        val tags: List<String>,
        val source: String,
        val createdAt: String,
        val updatedAt: String,
        val contentHash: String,
    )

    /** Every note, for link resolution and rebuild comparison. */
    fun allNotes(): List<IndexedNote> {
        val out = mutableListOf<IndexedNote>()
        connection.createStatement().use { s ->
            s.executeQuery("SELECT path FROM notes ORDER BY path").use { rs ->
                val paths = mutableListOf<String>()
                while (rs.next()) paths += rs.getString(1)
                paths.forEach { p -> note(p)?.let { out += it } }
            }
        }
        return out
    }

    /** Hash of the indexed copy, or null if unknown. EC-N10's redundancy guard. */
    fun contentHash(path: String): String? {
        connection.prepareStatement("SELECT content_hash FROM notes WHERE path_lower = ?").use { ps ->
            ps.setString(1, path.lowercase())
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    fun notesInFolder(folder: String): List<IndexedNote> {
        val paths = mutableListOf<String>()
        connection.prepareStatement(
            "SELECT path FROM notes WHERE folder = ? ORDER BY updated_at DESC"
        ).use { ps ->
            ps.setString(1, folder)
            ps.executeQuery().use { rs -> while (rs.next()) paths += rs.getString(1) }
        }
        return paths.mapNotNull { note(it) }
    }

    // ── links ───────────────────────────────────────────────────────────────

    fun replaceLinks(fromPath: String, resolved: List<LinkRef>, dangling: List<DanglingLink>, updatedAt: String) {
        connection.prepareStatement("DELETE FROM links WHERE from_path = ?").use { ps ->
            ps.setString(1, fromPath); ps.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM dangling_links WHERE from_path = ?").use { ps ->
            ps.setString(1, fromPath); ps.executeUpdate()
        }

        resolved.forEach { link ->
            connection.prepareStatement(
                "INSERT OR REPLACE INTO links(from_path, to_path, raw_target, score) VALUES (?,?,?,?)"
            ).use { ps ->
                ps.setString(1, link.fromPath)
                ps.setString(2, link.toPath)
                ps.setString(3, link.rawTarget)
                ps.setDouble(4, link.score)
                ps.executeUpdate()
            }
        }
        dangling.forEach { insertDangling(it, updatedAt) }
    }

    private fun insertDangling(link: DanglingLink, seenAt: String) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO dangling_links(from_path, raw_target, candidates, seen_at) VALUES (?,?,?,?)"
        ).use { ps ->
            ps.setString(1, link.fromPath)
            ps.setString(2, link.rawTarget)
            ps.setString(3, json.encodeToString(ListSerializer(String.serializer()), link.ambiguousCandidates))
            // Derived from the referencing note, never Instant.now(), so a rebuild
            // reproduces it exactly (D-027).
            ps.setString(4, seenAt)
            ps.executeUpdate()
        }
    }

    /**
     * Dangling links whose target could now be satisfied by a note.
     *
     * F12: without this, clicking "Create stub in Positioning" leaves the dangling
     * badge on screen forever, which looks exactly like a bug.
     */
    fun danglingTargeting(title: String, slug: String): List<DanglingLink> {
        val out = mutableListOf<DanglingLink>()
        connection.prepareStatement(
            "SELECT from_path, raw_target, candidates FROM dangling_links " +
                "WHERE lower(raw_target) = lower(?) OR lower(raw_target) = lower(?)"
        ).use { ps ->
            ps.setString(1, title)
            ps.setString(2, slug)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += DanglingLink(
                        fromPath = rs.getString(1),
                        rawTarget = rs.getString(2),
                        ambiguousCandidates = runCatching {
                            json.decodeFromString(ListSerializer(String.serializer()), rs.getString(3))
                        }.getOrDefault(emptyList()),
                    )
                }
            }
        }
        return out
    }

    fun allDangling(): List<DanglingLink> {
        val out = mutableListOf<DanglingLink>()
        connection.createStatement().use { s ->
            s.executeQuery("SELECT from_path, raw_target, candidates FROM dangling_links ORDER BY from_path, raw_target")
                .use { rs ->
                    while (rs.next()) {
                        out += DanglingLink(
                            rs.getString(1), rs.getString(2),
                            runCatching {
                                json.decodeFromString(ListSerializer(String.serializer()), rs.getString(3))
                            }.getOrDefault(emptyList()),
                        )
                    }
                }
        }
        return out
    }

    fun allLinks(): List<LinkRef> {
        val out = mutableListOf<LinkRef>()
        connection.createStatement().use { s ->
            s.executeQuery("SELECT from_path, to_path, raw_target, score FROM links ORDER BY from_path, raw_target")
                .use { rs ->
                    while (rs.next()) {
                        out += LinkRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4))
                    }
                }
        }
        return out
    }

    /** Inbound links. [Backlink.context] is filled in by the caller from the file. */
    fun backlinks(toPath: String): List<Backlink> {
        val out = mutableListOf<Backlink>()
        connection.prepareStatement(
            """
            SELECT l.from_path, n.title, l.raw_target
            FROM links l JOIN notes n ON n.path = l.from_path
            WHERE l.to_path = ? ORDER BY n.updated_at DESC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, toPath)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += Backlink(rs.getString(1), rs.getString(2), rs.getString(3), context = "")
                }
            }
        }
        return out
    }

    // ── search ──────────────────────────────────────────────────────────────

    /**
     * FTS5 search with a real snippet.
     *
     * The `snippet()` call is the reason the schema had to change: on the
     * contentless table specified in section 2 it returns NULL.
     */
    fun search(query: String, limit: Int): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val ftsQuery = toFtsQuery(query)
        if (ftsQuery.isBlank()) return emptyList()

        connection.prepareStatement(
            """
            SELECT f.path, f.title, f.summary,
                   snippet(notes_fts, 3, '[', ']', '…', 12) AS snip,
                   rank
            FROM notes_fts f
            WHERE notes_fts MATCH ?
            ORDER BY rank
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ftsQuery)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += SearchHit(
                        path = rs.getString("path"),
                        title = rs.getString("title") ?: "",
                        summary = rs.getString("summary") ?: "",
                        snippet = rs.getString("snip") ?: "",
                        rank = rs.getDouble("rank"),
                    )
                }
            }
        }
        return out
    }

    /**
     * Turns user text into an FTS5 query.
     *
     * Every token is quoted. FTS5 treats `-`, `*`, `:`, `^`, `(`, `)`, `"` and the
     * words AND/OR/NOT as syntax, so an unquoted transcript containing any of them
     * is a syntax error rather than a search — and transcripts are exactly where
     * stray punctuation comes from.
     */
    internal fun toFtsQuery(raw: String): String =
        raw.split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"" + it + "\"" }

    // ── tree ────────────────────────────────────────────────────────────────

    /**
     * The dashboard tree.
     *
     * Both counts are present because the index and the UI want different things:
     * the design board shows `Projects 23` where its children hold 9 + 7 + 7, so
     * the display is a rollup, while only the direct count can be maintained
     * incrementally (D-027).
     *
     * EC-A5: `maxDepth` limits how deep this goes, so `vault_tree` on a 2000-note
     * vault cannot blow the context.
     */
    fun tree(maxDepth: Int = config.treeDefaultDepth): TreeNode {
        val rows = folderRows()
        val danglingByFolder = danglingCountsByFolder()
        val childrenOf = rows.groupBy { it.parent ?: "" }

        // Rollups are computed bottom-up in one pass over the whole forest, so a
        // deep tree costs one traversal rather than one per node.
        val noteRollup = HashMap<String, Int>(rows.size)
        val danglingRollup = HashMap<String, Int>(rows.size)

        fun accumulate(row: FolderRow): Pair<Int, Int> {
            val kids = childrenOf[row.path] ?: emptyList()
            var notes = row.noteCount
            var dangs = danglingByFolder[row.path] ?: 0
            kids.forEach { kid ->
                val (kn, kd) = accumulate(kid)
                notes += kn
                dangs += kd
            }
            noteRollup[row.path] = notes
            danglingRollup[row.path] = dangs
            return notes to dangs
        }
        (childrenOf[""] ?: emptyList()).forEach { accumulate(it) }

        fun build(row: FolderRow): TreeNode = TreeNode(
            path = row.path,
            name = row.name,
            depth = row.depth,
            directNoteCount = row.noteCount,
            rollupNoteCount = noteRollup[row.path] ?: row.noteCount,
            danglingCount = danglingRollup[row.path] ?: 0,
            // EC-A5: stop descending at maxDepth so vault_tree on a 2000-note
            // vault cannot blow the context. The counts above still reflect
            // everything underneath, so a truncated branch is never misleading.
            children = if (row.depth >= maxDepth) emptyList()
            else (childrenOf[row.path] ?: emptyList())
                .map { build(it) }
                .sortedBy { it.name.lowercase() },
        )

        val topLevel = (childrenOf[""] ?: emptyList()).map { build(it) }

        return TreeNode(
            path = "",
            name = "vault",
            depth = 0,
            directNoteCount = 0,
            rollupNoteCount = topLevel.sumOf { it.rollupNoteCount },
            danglingCount = topLevel.sumOf { it.danglingCount },
            children = topLevel.sortedBy { it.name.lowercase() },
        )
    }

    private data class FolderRow(
        val path: String,
        val name: String,
        val depth: Int,
        val parent: String?,
        val noteCount: Int,
    )

    private fun folderRows(): List<FolderRow> {
        val rows = mutableListOf<FolderRow>()
        connection.createStatement().use { s ->
            s.executeQuery("SELECT path, name, depth, parent, note_count FROM folders ORDER BY path").use { rs ->
                while (rs.next()) {
                    rows += FolderRow(
                        path = rs.getString("path"),
                        name = rs.getString("name"),
                        depth = rs.getInt("depth"),
                        parent = rs.getString("parent"),
                        noteCount = rs.getInt("note_count"),
                    )
                }
            }
        }
        return rows
    }

    /** Dangling links grouped by the folder of the note that contains them. */
    private fun danglingCountsByFolder(): Map<String, Int> {
        val out = HashMap<String, Int>()
        connection.createStatement().use { s ->
            s.executeQuery(
                """
                SELECT n.folder AS folder, COUNT(*) AS c
                FROM dangling_links d JOIN notes n ON n.path = d.from_path
                GROUP BY n.folder
                """.trimIndent()
            ).use { rs -> while (rs.next()) out[rs.getString("folder")] = rs.getInt("c") }
        }
        return out
    }

    // ── folder_decisions projection ─────────────────────────────────────────

    /**
     * Mirrors `app.db`'s audit trail. Strictly one-way: this is a projection, not
     * a second copy that could diverge (D-026).
     */
    fun syncFolderDecisions(decisions: List<FolderDecision>) {
        connection.createStatement().use { it.execute("DELETE FROM folder_decisions") }
        decisions.forEach { d ->
            connection.prepareStatement(
                "INSERT INTO folder_decisions(id, proposed, verdict, matched, score, at) VALUES (?,?,?,?,?,?)"
            ).use { ps ->
                ps.setLong(1, d.id)
                ps.setString(2, d.proposed)
                ps.setString(3, d.verdict)
                val matched = d.matched
                if (matched == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, matched)
                val score = d.score
                if (score == null) ps.setNull(5, java.sql.Types.REAL) else ps.setDouble(5, score)
                ps.setString(6, d.at)
                ps.executeUpdate()
            }
        }
    }

    fun folderDecisions(limit: Int = 50): List<FolderDecision> {
        val out = mutableListOf<FolderDecision>()
        connection.prepareStatement(
            "SELECT id, proposed, verdict, matched, score, at FROM folder_decisions ORDER BY id DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += FolderDecision(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5)?.let { rs.getDouble(5) }, rs.getString(6),
                    )
                }
            }
        }
        return out
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    fun <T> transaction(body: () -> T): T {
        connection.autoCommit = false
        try {
            val result = body()
            connection.commit()
            return result
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun userVersion(): Int =
        connection.createStatement().use { s ->
            s.executeQuery("pragma user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun setUserVersion(v: Int) {
        connection.createStatement().use { it.execute("pragma user_version=" + v) }
    }

    private fun tableExists(name: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { it.next() }
        }

    /** Deletes the database file entirely. Safe, by R10 — every row is derivable. */
    fun deleteFile() {
        close()
        listOf(file, Path.of(file.toString() + "-wal"), Path.of(file.toString() + "-shm"))
            .forEach { runCatching { Files.deleteIfExists(it) } }
        connection = open(file)
    }

    override fun close() {
        runCatching { connection.close() }
    }
}
