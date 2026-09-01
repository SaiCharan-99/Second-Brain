package com.secondbrain.agent

import java.nio.file.Path

/**
 * Tracks every [AgentDb] a test opens so it can be closed before JUnit tries to
 * delete the `@TempDir` that holds it.
 *
 * ### Why this is needed at all
 *
 * SQLite keeps `app.db`, `app.db-shm` and `app.db-wal` open for the life of the
 * connection. On POSIX, deleting an open file succeeds — the directory entry
 * goes and the inode lingers until the last handle closes — so a leaked
 * connection is invisible. On Windows the delete *fails*, and JUnit reports it
 * as `TempDirDeletionStrategy$DeletionException` during extension-context
 * teardown, which surfaces as a **failed test whose assertions all passed**.
 *
 * That is a genuinely nasty failure to read: the test name says
 * `EC-Z8: a lost order response is never retried FAILED`, and nothing about
 * `app.db-wal` suggests the test logic is fine. Hence one shared helper with
 * this comment attached, rather than the same three lines copied into five
 * test classes.
 *
 * Usage:
 * ```
 * private val databases = TestDatabases()
 * @AfterEach fun closeDatabases() = databases.closeAll()
 * private fun gate(dir: Path) = ConfirmationGate(ActionLedger(databases.open(dir)))
 * ```
 */
class TestDatabases {

    private val opened = mutableListOf<AgentDb>()

    /** Opens `app.db` under [dir], or [name] if a test needs a second database. */
    fun open(dir: Path, name: String = "app.db"): AgentDb =
        AgentDb(dir.resolve(name)).also { opened += it }

    /** Opens a database at an exact path. For reopen-the-same-file tests. */
    fun openAt(file: Path): AgentDb = AgentDb(file).also { opened += it }

    /**
     * Closes everything, newest first, swallowing failures.
     *
     * A close that throws must not mask the test's own result — by the time
     * this runs the assertions have already had their say.
     */
    fun closeAll() {
        opened.asReversed().forEach { runCatching { it.close() } }
        opened.clear()
    }
}
