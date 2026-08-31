package com.secondbrain.vault

import com.secondbrain.model.FolderDecision
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * `app.db` — the precious one.
 *
 * R10: "`index.db` is disposable, `app.db` is precious." That asymmetry drives the
 * whole design of this class. `index.db` recovers from schema drift by deleting
 * itself and re-walking the vault (EC-N11); `app.db` has nothing to rebuild
 * itself *from*, so a schema change must **migrate** rather than recreate. There
 * is no second chance.
 *
 * Step 2 creates it earlier than section 2 anticipated, holding one table:
 * `folder_decisions`. Section 2 puts that table in `index.db`, but it is an audit
 * trail of *runtime* verdicts — the vault has no record of a folder Claude
 * proposed and had rejected, so a rebuild loses it permanently, which is exactly
 * what R10 forbids. Udit chose to write it here and cache a copy in `index.db`
 * (D-026).
 *
 * The cache is a strict one-way projection: this class is the only writer, and
 * `index.db`'s copy is rebuilt from here. There is no reconciliation path to get
 * wrong.
 *
 * `:agent` owns the other tables in this same file - `conversations`,
 * `messages`, `cost_meter`, and the Step 5 action ledger - and neither module may
 * see the other, so the two coordinate through a `schema_migrations(module,
 * version)` table rather than the single `user_version` pragma (D-045). The
 * migration runner exists now rather than at Step 5 because discovering that the
 * action ledger's database has no migration path is the worst possible table to
 * learn that on (F24 / D-037).
 */
class AppDb(
    private val file: Path,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(AppDb::class.java)

    private val connection: Connection = DriverManager
        .getConnection("jdbc:sqlite:" + file.toAbsolutePath())
        .apply {
            createStatement().use { s ->
                // WAL so the watcher's index writes and our writes do not block
                // each other into a busy error (F21 / D-038).
                s.execute("pragma journal_mode=WAL")
                s.execute("pragma busy_timeout=5000")
                s.execute("pragma foreign_keys=ON")
                // FULL rather than NORMAL: this database holds the action ledger
                // from Step 5, and R5 makes its durability the difference between
                // "sent once" and "sent twice".
                s.execute("pragma synchronous=FULL")
            }
        }

    companion object {
        const val MODULE: String = "vault"

        /** Bump when adding a migration. Never renumber an existing one. */
        const val SCHEMA_VERSION: Int = 1
    }

    init {
        ensureMigrationsTable()
        migrate()
    }

    /**
     * Shared coordination point with `:agent`, which owns its own tables in this
     * same file.
     *
     * The `user_version` pragma holds one integer, so it cannot represent two
     * independent migration lineages - and `app.db` has two owners because
     * `folder_decisions` is ours while `conversations`, `messages`, `cost_meter`
     * and the Step 5 action ledger are `:agent`'s. Neither module may see the
     * other, so they coordinate through this table instead. `IF NOT EXISTS`
     * because whichever module opens the file first has to create it (D-045).
     */
    private fun ensureMigrationsTable() {
        connection.createStatement().use { s ->
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                  module   TEXT PRIMARY KEY,
                  version  INTEGER NOT NULL,
                  applied_at TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Applies migrations from this module's recorded version up to [SCHEMA_VERSION].
     *
     * Forward-only and idempotent. A database from the future is a hard failure
     * rather than a silent downgrade: running an older build against a newer
     * `app.db` would corrupt data it does not understand.
     */
    private fun migrate() {
        val current = currentVersion()

        if (current > SCHEMA_VERSION) {
            throw IllegalStateException(
                "app.db at " + file + " holds '" + MODULE + "' schema version " + current +
                    " but this build only understands " + SCHEMA_VERSION + ". This database was " +
                    "written by a newer version of Second Brain. app.db is not rebuildable - do " +
                    "not delete it. Use a matching build, or migrate deliberately."
            )
        }

        if (current == SCHEMA_VERSION) {
            log.debug("app.db module '{}' at schema version {} - up to date", MODULE, current)
            return
        }

        log.info("Migrating app.db module '{}' from schema version {} to {}", MODULE, current, SCHEMA_VERSION)
        connection.autoCommit = false
        try {
            if (current < 1) migrateTo1()
            recordVersion(SCHEMA_VERSION)
            connection.commit()
            log.info("app.db module '{}' migrated to schema version {}", MODULE, SCHEMA_VERSION)
        } catch (e: Exception) {
            connection.rollback()
            throw IllegalStateException("app.db '" + MODULE + "' migration failed and was rolled back: " + e.message, e)
        } finally {
            connection.autoCommit = true
        }
    }

    /** Migration 1 — the Folder Guard audit trail. */
    private fun migrateTo1() {
        connection.createStatement().use { s ->
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS folder_decisions (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  proposed    TEXT NOT NULL,
                  verdict     TEXT NOT NULL,
                  matched     TEXT,
                  score       REAL,
                  at          TEXT NOT NULL
                )
                """.trimIndent()
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_folder_decisions_at ON folder_decisions(at DESC)")
        }
    }

    fun currentVersion(): Int =
        connection.prepareStatement("SELECT version FROM schema_migrations WHERE module = ?").use { ps ->
            ps.setString(1, MODULE)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun recordVersion(version: Int) {
        connection.prepareStatement(
            """
            INSERT INTO schema_migrations(module, version, applied_at) VALUES (?,?,?)
            ON CONFLICT(module) DO UPDATE SET version=excluded.version, applied_at=excluded.applied_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, MODULE)
            ps.setInt(2, version)
            ps.setString(3, Instant.now().toString())
            ps.executeUpdate()
        }
    }

    /**
     * Records a Folder Guard verdict. Section 5 rule 6: every verdict is written.
     *
     * @return the row id, so the index cache can mirror it.
     */
    fun recordFolderDecision(
        proposed: String,
        verdict: String,
        matched: String?,
        score: Double?,
        at: Instant = Instant.now(),
    ): Long {
        connection.prepareStatement(
            "INSERT INTO folder_decisions(proposed, verdict, matched, score, at) VALUES (?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, proposed)
            ps.setString(2, verdict)
            if (matched == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, matched)
            if (score == null) ps.setNull(4, java.sql.Types.REAL) else ps.setDouble(4, score)
            ps.setString(5, at.toString())
            ps.executeUpdate()
        }
        connection.createStatement().use { s ->
            s.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    /** Most recent verdicts first. Feeds the dashboard's Folder Guard panel. */
    fun folderDecisions(limit: Int = 50): List<FolderDecision> {
        val out = mutableListOf<FolderDecision>()
        connection.prepareStatement(
            "SELECT id, proposed, verdict, matched, score, at FROM folder_decisions ORDER BY id DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += FolderDecision(
                        id = rs.getLong("id"),
                        proposed = rs.getString("proposed"),
                        verdict = rs.getString("verdict"),
                        matched = rs.getString("matched"),
                        score = rs.getObject("score")?.let { rs.getDouble("score") },
                        at = rs.getString("at"),
                    )
                }
            }
        }
        return out
    }

    /** Every verdict, oldest first. Used to rebuild the `index.db` projection. */
    fun allFolderDecisions(): List<FolderDecision> {
        val out = mutableListOf<FolderDecision>()
        connection.createStatement().use { s ->
            s.executeQuery("SELECT id, proposed, verdict, matched, score, at FROM folder_decisions ORDER BY id ASC")
                .use { rs ->
                    while (rs.next()) {
                        out += FolderDecision(
                            id = rs.getLong("id"),
                            proposed = rs.getString("proposed"),
                            verdict = rs.getString("verdict"),
                            matched = rs.getString("matched"),
                            score = rs.getObject("score")?.let { rs.getDouble("score") },
                            at = rs.getString("at"),
                        )
                    }
                }
        }
        return out
    }

    override fun close() {
        runCatching { connection.close() }
    }
}
