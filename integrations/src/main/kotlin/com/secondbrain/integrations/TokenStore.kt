package com.secondbrain.integrations

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * Its own tiny SQLite file, deliberately not `app.db` — see `GoogleConfig`'s
 * doc comment (`:model`) for why: `:integrations` cannot reach `:agent`'s
 * `AgentDb` or `:vault`'s `AppDb` (no such dependency edge in ARCHITECTURE
 * §1), and this is a clean, non-precious sub-domain of its own. Losing this
 * file just means one more OAuth consent screen, not a rebuild — unlike the
 * action ledger, this is not R5-precious.
 *
 * One row per provider. Today only `"google"` (Gmail send + Calendar events
 * share one OAuth client and one token pair — see `GoogleAuth`).
 */
class TokenStore(private val file: Path) : AutoCloseable {

    private val log = LoggerFactory.getLogger(TokenStore::class.java)

    private val connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()).apply {
        createStatement().use { s ->
            s.execute("pragma journal_mode=WAL")
            s.execute("pragma busy_timeout=5000")
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS oauth_tokens (
                  provider    TEXT PRIMARY KEY,
                  access      TEXT NOT NULL,
                  refresh     TEXT NOT NULL,
                  expires_at  TEXT NOT NULL,
                  updated_at  TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    data class Tokens(val access: String, val refresh: String, val expiresAt: Instant)

    fun load(provider: String): Tokens? =
        connection.prepareStatement("SELECT access, refresh, expires_at FROM oauth_tokens WHERE provider = ?").use { ps ->
            ps.setString(1, provider)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else Tokens(rs.getString(1), rs.getString(2), Instant.parse(rs.getString(3)))
            }
        }

    fun save(provider: String, tokens: Tokens, now: Instant = Instant.now()) {
        connection.prepareStatement(
            """
            INSERT INTO oauth_tokens(provider, access, refresh, expires_at, updated_at) VALUES (?,?,?,?,?)
            ON CONFLICT(provider) DO UPDATE SET access=excluded.access, refresh=excluded.refresh,
                                                 expires_at=excluded.expires_at, updated_at=excluded.updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, provider)
            ps.setString(2, tokens.access)
            ps.setString(3, tokens.refresh)
            ps.setString(4, tokens.expiresAt.toString())
            ps.setString(5, now.toString())
            ps.executeUpdate()
        }
        log.debug("Saved OAuth tokens for provider '{}'", provider)
    }

    override fun close() {
        runCatching { connection.close() }
    }
}
