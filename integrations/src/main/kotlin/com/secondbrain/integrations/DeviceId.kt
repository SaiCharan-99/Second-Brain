package com.secondbrain.integrations

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A stable, per-install identifier `update_cart` requires as a fallback cart
 * key (D-089): *"Unique device identifier used as a fallback cart key when a
 * transportSessionId is not available."* Zepto's own MCP session apparently
 * does not always carry enough context to key the cart by itself, so this
 * exists purely to give `update_cart` calls a consistent identity across a
 * session and across restarts — the point is stability, not the value.
 *
 * One line, one file. Not app.db (that is `:agent`'s and this is
 * `:integrations` — no dependency edge there per ARCHITECTURE §1), not the
 * token store (a device id is not a credential and outlives any one sign-in).
 * Its own file, same reasoning as `TokenStore`'s: losing it costs nothing
 * more than a new device identity Zepto has never seen, not a rebuild.
 */
object DeviceId {

    /** Reads [path] if it holds one, otherwise generates and persists a new UUID. */
    fun stable(path: Path): String {
        if (Files.exists(path)) {
            val existing = Files.readString(path).trim()
            if (existing.isNotBlank()) return existing
        }
        val fresh = UUID.randomUUID().toString()
        Files.createDirectories(path.parent)
        Files.writeString(path, fresh)
        return fresh
    }
}
