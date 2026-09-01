package com.secondbrain.agent

import java.time.Instant
import java.time.ZoneId

/**
 * "When was this utterance spoken" for tools that need it, without changing
 * [ToolSpec.handler]'s fixed `suspend (String) -> ToolOutcome` signature for
 * every tool already registered.
 *
 * EC-C2 requires resolving relative dates ("tomorrow") against the recording's
 * *start* instant, not whenever a tool happens to be dispatched — which, across
 * an `ask_user` round-trip or two, can genuinely be minutes later. [AgentLoop]
 * is the one place that knows the real utterance timestamp; it calls [set] once
 * at the top of [AgentLoop.run]. `CalendarTools` reads [current] when resolving
 * time.
 *
 * Same trick this codebase already uses for other cross-boundary state —
 * `lateinit var voiceController` in `Main.kt`'s forward reference for
 * `ask_user`, the `@Volatile` fields in `VoiceController` — and safe for the
 * same reason: `VoiceController.turnMutex` already guarantees exactly one turn
 * touches this at a time.
 */
class TurnClock {
    data class Moment(val at: Instant, val zone: ZoneId)

    @Volatile
    var current: Moment = Moment(Instant.EPOCH, ZoneId.systemDefault())
        private set

    fun set(at: Instant, zone: ZoneId) {
        current = Moment(at, zone)
    }
}
