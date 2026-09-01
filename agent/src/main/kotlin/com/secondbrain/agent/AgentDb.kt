package com.secondbrain.agent

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * `:agent`'s tables in `app.db`.
 *
 * ### Why this exists instead of reusing `:vault`'s AppDb
 *
 * `app.db` is genuinely shared: `folder_decisions` belongs to `:vault` (D-026),
 * while `conversations`, `messages`, `cost_meter` and — from Step 5 — the action
 * ledger belong to `:agent`. Neither module may see the other (§1), so the file
 * has two owners and there is no shared place to put one migration runner:
 * `:model` is data classes plus kotlinx-serialization, and `:ports` is
 * interfaces.
 *
 * Three options were considered. A ninth `:store` module adds an edge §1 does not
 * have. A port exposing `java.sql.Connection` would let every future port
 * implementation write arbitrary SQL to the precious database — a bigger hole
 * than the problem. So: each module owns its own tables and its own migration
 * lineage in one file, coordinating through a `schema_migrations(module, version)`
 * table rather than the single `user_version` pragma, which cannot represent two
 * independent lineages. The cost is ~40 lines of migration mechanics duplicated
 * between here and `:vault`; that is cheaper than either alternative (D-045).
 *
 * R10 still applies: this database is precious and migrates forward. It never
 * recreates itself — there is nothing to rebuild it from.
 */
class AgentDb(
    private val file: Path,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(AgentDb::class.java)

    val connection: Connection = DriverManager
        .getConnection("jdbc:sqlite:" + file.toAbsolutePath())
        .apply {
            createStatement().use { s ->
                s.execute("pragma journal_mode=WAL")
                s.execute("pragma busy_timeout=5000")
                s.execute("pragma foreign_keys=ON")
                // FULL, matching :vault's AppDb. From Step 5 this file holds the
                // action ledger, where durability is the difference between "sent
                // once" and "sent twice" (R5).
                s.execute("pragma synchronous=FULL")
            }
        }

    companion object {
        const val MODULE: String = "agent"

        /** Bump when adding a migration. Never renumber an existing one. */
        const val SCHEMA_VERSION: Int = 2
    }

    init {
        ensureMigrationsTable()
        migrate()
    }

    /**
     * The shared coordination point between `:vault` and `:agent`.
     *
     * `CREATE TABLE IF NOT EXISTS` rather than a migration, because whichever
     * module opens the file first has to be able to create it.
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

    fun currentVersion(): Int =
        connection.prepareStatement("SELECT version FROM schema_migrations WHERE module = ?").use { ps ->
            ps.setString(1, MODULE)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun migrate() {
        val current = currentVersion()

        if (current > SCHEMA_VERSION) {
            throw IllegalStateException(
                "app.db holds '$MODULE' schema version $current but this build understands " +
                    "$SCHEMA_VERSION. This database was written by a newer version of Second Brain. " +
                    "app.db is not rebuildable - do not delete it."
            )
        }
        if (current == SCHEMA_VERSION) {
            log.debug("app.db module '{}' at schema version {} - up to date", MODULE, current)
            return
        }

        log.info("Migrating app.db module '{}' from version {} to {}", MODULE, current, SCHEMA_VERSION)
        connection.autoCommit = false
        try {
            if (current < 1) migrateTo1()
            if (current < 2) migrateTo2()
            recordVersion(SCHEMA_VERSION)
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw IllegalStateException("app.db '$MODULE' migration failed and was rolled back: ${e.message}", e)
        } finally {
            connection.autoCommit = true
        }
    }

    /** Migration 1 — conversations, messages, cost_meter. Schema from section 2. */
    private fun migrateTo1() {
        connection.createStatement().use { s ->
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS conversations (
                  id          TEXT PRIMARY KEY,
                  started_at  TEXT NOT NULL,
                  ended_at    TEXT,
                  phase       TEXT NOT NULL
                )
                """.trimIndent()
            )

            s.execute(
                """
                CREATE TABLE IF NOT EXISTS messages (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  conv_id     TEXT NOT NULL REFERENCES conversations(id),
                  turn_index  INTEGER NOT NULL,
                  phase       TEXT NOT NULL,
                  role        TEXT NOT NULL,
                  content     TEXT NOT NULL,
                  tokens_in   INTEGER,
                  tokens_out  INTEGER,
                  cache_write_tokens INTEGER,
                  cache_read_tokens  INTEGER,
                  at          TEXT NOT NULL
                )
                """.trimIndent()
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conv_id, turn_index, id)")

            // Section 2 gives cost_meter a single `units REAL`. There are four
            // token classes at four different prices - uncached input, cache
            // write at 1.25x, cache read at 0.1x, and output at 5x input - and one
            // column cannot represent them. Since the Step 3 exit criterion
            // records a per-capture USD figure that sets the budget for every
            // later step, a schema that cannot express the arithmetic would
            // corrupt Steps 4 through 7 (H1 / D-046).
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS cost_meter (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  conv_id     TEXT,
                  turn_index  INTEGER,
                  service     TEXT NOT NULL,
                  model       TEXT,
                  tokens_in   INTEGER NOT NULL DEFAULT 0,
                  tokens_out  INTEGER NOT NULL DEFAULT 0,
                  cache_write_tokens INTEGER NOT NULL DEFAULT 0,
                  cache_read_tokens  INTEGER NOT NULL DEFAULT 0,
                  units       REAL NOT NULL DEFAULT 0,
                  usd         REAL NOT NULL,
                  at          TEXT NOT NULL
                )
                """.trimIndent()
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_cost_meter_conv ON cost_meter(conv_id)")
            s.execute("CREATE INDEX IF NOT EXISTS idx_cost_meter_at ON cost_meter(at)")
        }
    }

    /**
     * Migration 2 — the action ledger, Step 5/6's `ActionLedger`. Schema from
     * ARCHITECTURE §2's `action_ledger`, unchanged, except `oauth_tokens` is
     * NOT here — see `GoogleConfig`'s doc comment: `:integrations` cannot reach
     * this database (no such dependency edge in §1), so OAuth tokens get their
     * own small file instead. Supersedes §2 on that one table only.
     */
    private fun migrateTo2() {
        connection.createStatement().use { s ->
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS action_ledger (
                  proposal_id TEXT PRIMARY KEY,
                  kind        TEXT NOT NULL,
                  payload     TEXT NOT NULL,
                  state       TEXT NOT NULL,
                  external_id TEXT,
                  error       TEXT,
                  created_at  TEXT NOT NULL,
                  updated_at  TEXT NOT NULL
                )
                """.trimIndent()
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_action_ledger_state ON action_ledger(state)")
        }
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
            ps.setString(3, java.time.Instant.now().toString())
            ps.executeUpdate()
        }
    }

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

    override fun close() {
        runCatching { connection.close() }
    }
}
