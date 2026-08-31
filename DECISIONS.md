# DECISIONS.md — Second Brain

Append-only. Never edit or delete an entry; supersede it with a new one.

---

## D-001 — Standalone repository, separate from SDLC / BluePrint Lens
**Date:** 2026-09-01

**Decided:** Second Brain is its own repository with its own build, its own vault, and no shared code with BluePrint Lens. Integration, if it ever happens, is a future decision.

**Why:** The two projects now have different runtimes (JVM desktop vs Android), different inference strategies (cloud vs on-device), and different lifecycles. Sharing code would couple two things that are diverging.

**Uncertain:** Nothing.

**Files:** repository root.

---

## D-002 — Kotlin/JVM desktop, Compose Multiplatform, cloud inference
**Date:** 2026-09-01

**Decided:** Prototype targets the laptop as a JVM desktop application with a Compose Multiplatform UI. All orchestration is Kotlin running locally. API keys live in `~/.secondbrain/config.toml`, loaded at startup.

**Why:** Confirmed by Udit. Compose Multiplatform over JavaFX because it keeps an Android path open at near-zero cost and the component model is better. Keys in a local config file are acceptable because nothing is distributed — the moment a binary ships to another machine, this becomes a proxy problem and must be revisited.

**Uncertain:** Whether Compose Desktop's performance is adequate for a live-updating three-pane vault view with a few thousand notes. Unmeasured.

**Files:** `app/`, `~/.secondbrain/config.toml`.

---

## D-003 — Supersedes the all-local inference decision from the prior design
**Date:** 2026-09-01

**Decided:** v1 uses cloud inference throughout — Claude API for reasoning and vision, Gemini API for STT, Kokoro Cloud for TTS. No llama.cpp, no whisper.cpp, no on-device models.

**Why:** Udit's direction. The offline-first constraint belonged to the Android/competition framing; on a laptop prototype it buys nothing and costs a large amount of build time. Ports keep local implementations swappable later.

**Uncertain:** Cost per voice turn is unknown until Step 3. Round-trip latency across three cloud services is unmeasured. Either could force a partial reversal.

**Files:** `ports/`, `voice/`, `agent/`.

---

## D-004 — App-private vault storage, no Obsidian interop
**Date:** 2026-09-01

**Decided:** The vault lives at `~/.secondbrain/vault/` and is read only by this application's dashboard. Files remain Obsidian-*shaped* (YAML frontmatter, `[[wikilinks]]`) because that costs nothing, but no interop, sync, or external-editor guarantee is offered.

**Why:** Confirmed by Udit. Removes an entire class of sync, permission, and concurrent-write problems from v1.

**Uncertain:** Nothing for v1. If external editing is wanted later, `FileWatcher` already handles it; the missing piece would be conflict resolution.

**Files:** `vault/`.

---

## D-005 — Claude native tool-use is the orchestrator; no workflow framework
**Date:** 2026-09-01

**Decided:** The agent loop is a hand-written Kotlin loop over the Anthropic `/v1/messages` tool-use protocol. No LangGraph equivalent, no state-machine DSL.

**Why:** Same conclusion as the prior project reached for different reasons. The orchestration here is: call model, dispatch tool, feed result back, cap iterations. A framework would be a wrapper around forty lines of code and would make the confirmation-gate suspend/resume harder, not easier.

**Uncertain:** Nothing.

**Files:** `agent/AgentLoop.kt`.

---

## D-006 — Two tool classes with a hard gate; no direct-execution tools for irreversible actions
**Date:** 2026-09-01

**Decided:** Tools are `AUTONOMOUS` or `GATED`, declared at registration. Gated tools create a `Proposal` and suspend the loop; a human resolution causes execution. No `email_send` / `calendar_create` / `place_order` tool exists in the registry.

**Why:** This is the entire safety model for a voice-driven system with an ASR error rate above zero and three irreversible action types. Making "send" something the model cannot express is stronger than any prompt instruction.

**Uncertain:** Whether one-gate-at-a-time is too restrictive in practice — if a user routinely says "email X and block time with Y" in one breath, the `gate_busy` queue-and-re-propose behaviour needs testing.

**Files:** `agent/ToolRegistry.kt`, `agent/ConfirmationGate.kt`.

---

## D-007 — Folder Guard is deterministic Kotlin, not prompt instruction
**Date:** 2026-09-01

**Decided:** New-folder creation is intercepted by a scoring function in `:vault`. Similarity ≥ 0.72 against an existing folder, depth > 3, or a 13th top-level folder all reject with a structured reason the model must respond to.

**Why:** The predictable failure mode of giving an LLM a `create_folder` tool is a vault with ninety top-level folders. A prompt saying "reuse existing folders where possible" does not survive contact with two hundred captures. Thresholds are config so they can be tuned against real data.

**Uncertain:** The 0.72 threshold and the 12-folder cap are guesses. Step 3's exit criteria measure them against 20 real captures; expect to tune both.

**Files:** `vault/FolderGuard.kt`, `vault/VaultConfig.kt`.

---

## D-008 — Verbatim fields are the only sanctioned typing path
**Date:** 2026-09-01

**Decided:** Email addresses, phone numbers, and ambiguous quantities are `VERBATIM` fields: read back by TTS character by character, corrected by keyboard via `request_typed_input`. Everything else is voice.

**Why:** Generalises the email-correction flow Udit described into a reusable pattern. ASR on an email address is unreliable and the cost of an error is a message sent to a stranger.

**Uncertain:** Nothing.

**Files:** `agent/tools/RequestTypedInput.kt`, `app/ProposalWindow.kt`.

---

## D-009 — Zepto is behind a `CommerceAdapter` with a working fake, and it is built last
**Date:** 2026-09-01

**Decided:** All commerce goes through the `CommerceAdapter` port. Two implementations: `McpCommerceAdapter` (dynamic bridge over the Zepto MCP server) and `FakeCommerceAdapter` (deterministic catalogue with seeded stock-outs and price changes). WF-4 is Step 7, last in the build order.

**Why:** The endpoint exists but is untested. Building the highest-blast-radius workflow on the least-validated dependency is the wrong order. The fake also makes every commerce edge case testable offline, which the real endpoint would not.

**Uncertain:** Whether `tools/list` responds at all, what transport and auth it uses, and whether the tool set actually covers search → add → read cart → place COD order. Blocking spikes S7.1 and S7.2.

**Files:** `ports/CommerceAdapter.kt`, `integrations/`.

---

## D-010 — Time resolution is deterministic Kotlin, not the model
**Date:** 2026-09-01

**Decided:** Claude extracts temporal *intent* ("tomorrow", "12", "1 hour"). `TimeResolver` converts it to an absolute `ZonedDateTime` using `java.time`, resolved against the utterance timestamp, and reports ambiguities that force a clarifying question.

**Why:** Models emit wrong absolute timestamps, particularly around relative dates, DST, and year rollover. This is a solved problem in the standard library and should not be delegated.

**Uncertain:** Nothing, but it needs thorough tests — 30+ cases including "tomorrow" at 23:58 and both DST transitions.

**Files:** `vault/TimeResolver.kt`.

---

## D-011 — Graph view, wake word, and offline inference explicitly out of scope for v1
**Date:** 2026-09-01

**Decided:** See `ARCHITECTURE.md` §8 for the full list.

**Why:** Written down so it does not creep back in. Each of these is a plausible-sounding addition that would push the walking skeleton out by a week.

**Uncertain:** Nothing.

**Files:** `ARCHITECTURE.md`.

---

## D-012 — `ConfigLoader` lives in `:model`, with a hand-rolled TOML reader
**Date:** 2026-09-01

**Decided:** `AppConfig` and `ConfigLoader` both live in `:model`. TOML is parsed by a ~70-line reader inside `ConfigLoader` rather than a third-party library. Precedence is env var (`SECONDBRAIN_<SECTION>_<KEY>`) over file over `AppConfig` default.

**Why:** Config has to be readable from `:voice` at Step 1 and from `:agent` at Step 3, so it cannot live in `:app` — Step 1's exit criterion is `./gradlew :voice:run`, and `:voice` may not depend on `:app`. The alternatives were a ninth `:config` module (rejected: a whole module for 80 lines, and a dependency edge not in §1) or a TOML library in `:model` (rejected: CLAUDE.md restricts `:model` to kotlinx-serialization; `tomlkt` was added, then removed once the reader existed). `config.toml` is a handful of scalars under single-level sections; a targeted reader also gives `file:line` error messages, which a generic parser does not. Nested sections and non-scalar values are rejected loudly rather than half-understood.

**Uncertain:** If config grows to need arrays or nested tables, the reader needs replacing rather than extending. That is a deliberate cliff, not an oversight.

**Files:** `model/src/main/kotlin/com/secondbrain/model/ConfigLoader.kt`, `model/build.gradle.kts`, `config.example.toml`.

---

## D-013 — Spike S1.3 result: JVM audio on this laptop
**Date:** 2026-09-01

**Decided:** `javax.sound.sampled` is adequate. 16 kHz mono PCM16 signed little-endian is supported for both `TargetDataLine` and `SourceDataLine` on the target machine. Device enumeration is `AudioSystem.getMixerInfo()` filtered by `Mixer.isLineSupported`. Lines are opened per utterance and closed after, never cached across utterances.

**Why:** Measured, not assumed. Probe output on the target laptop (Windows 11, Temurin 17.0.16):

```
TargetDataLine 16k/16/mono/LE: true
SourceDataLine 16k/16/mono/LE: true
SourceDataLine 24k/16/mono/LE: true
capture  : Primary Sound Capture Driver | Microphone (Realtek(R) Audio) | Headset (EarPods)
playback : Primary Sound Driver | Headset (EarPods) | Speakers (Realtek(R) Audio)
```

Three capture and three playback devices, including a hot-pluggable headset — which is the EC-V9 test rig. Lines are not cached because `getMixerInfo()` re-reads the OS device list on every call, so re-enumerating is the recovery path; a cached line whose device was unplugged throws on its next read.

**Uncertain:** 24 kHz playback is supported, which matters because Kokoro's native rate is 24 kHz — but the rate Kokoro actually returns is still S1.2. Windows SAPI was observed returning 22050 Hz, and the playback line opens from the chunk's declared format, so neither is hardcoded.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/AudioDevices.kt`, `JvmAudioCapture.kt`, `JvmAudioPlayback.kt`.

---

## D-014 — Session audio is retained for 30 days by default, and retention is configurable
**Date:** 2026-09-01

**Decided:** `sessions/<ts>/` holds `audio.wav`, `transcript.txt` and `meta.json`. Audio is kept for `sessions.retention_days` (default 30), swept at startup. `sessions.delete_wav_on_commit` deletes the WAV as soon as the transcript commits, for anyone who wants the stricter reading. `FAILED` and `EMPTY` audio is retained regardless of that flag.

**Why:** `ARCHITECTURE.md` §2 says sessions are "rotated at 30 days" while §7 Step 1 says "audio is deleted only after the transcript commits" — two different policies for the same files. Udit chose configurable, default retain. The substantive argument for retaining: Step 1's whole purpose is measuring Gemini's accuracy on real Indian-English and code-switched speech, and that measurement is impossible without the audio to re-run. R10 is a floor ("never before the transcript commits"), not a ceiling.

**Uncertain:** Nothing about the mechanism. Whether 30 days is the right number is unmeasured — it is one config key away from changing.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/SessionStore.kt`, `model/src/main/kotlin/com/secondbrain/model/AppConfig.kt`, `config.example.toml`.

---

## D-015 — A failed or empty transcript still commits; `meta.json` is the commit record
**Date:** 2026-09-01

**Decided:** Every utterance writes `meta.json` with an `sttStatus` of `OK` / `EMPTY` / `FAILED`. Writing that file **is** the commit R10 refers to. Gate rejections (EC-V1) delete their session directory outright; interrupted recordings get `INCOMPLETE.txt` and are never swept.

**Why:** R10 permits deleting a WAV only after "its transcript commits". Under a literal reading, a transcript that never arrives means the audio can never be cleaned up and — worse — the failure leaves no trace, so nobody can count how often STT fails. Neither `ARCHITECTURE.md` nor the edge-case catalogue covers a 200-with-empty-body or an exhausted-retry outcome. A status field makes both terminal and countable. Gate rejections are deleted because a 200 ms accidental keypress is not a thought, and keeping them would bury the real recordings.

**Uncertain:** Nothing.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/SessionStore.kt`, `model/src/main/kotlin/com/secondbrain/model/Transcript.kt`.

---

## D-016 — EC-T4 fallback is Windows SAPI via PowerShell, not FreeTTS
**Date:** 2026-09-01

**Decided:** When Kokoro is unreachable, speak through `System.Speech.Synthesis.SpeechSynthesizer` driven by a PowerShell subprocess, rendering to a temp WAV that plays through the same `JvmAudioPlayback` path. On a non-Windows host, degrade to on-screen text. FreeTTS is not used. This supersedes the "FreeTTS/system" suggestion in `ARCHITECTURE.md` EC-T4.

**Why:** FreeTTS' last release was 2009, its JSAPI licensing is awkward, and it sounds worse than the voice already installed on the machine. SAPI costs zero dependencies and zero licence questions. Routing through the same playback port matters more than voice quality: barge-in, device selection and the 100 ms cut all keep working in the degraded state, which they would not if the fallback shelled out to a media player. Verified: 128,070 bytes at 22,050 Hz, synthesised and played through the normal path.

**Uncertain:** Nothing on Windows. The macOS/Linux path is on-screen text only; `say` / `espeak` would be easy to add if the project ever leaves Windows.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/SystemTtsFallback.kt`.

---

## D-017 — Barge-in is a keypress, not energy detection
**Date:** 2026-09-01

**Decided:** `gate.barge_in` defaults to `KEYPRESS`: any key during playback cuts audio. `ENERGY` exists but additionally requires a 300 ms grace window after playback starts and capture/playback on physically different devices, unless `allow_energy_barge_in_same_device` is explicitly set true.

**Why:** EC-V3 says "mic stays hot during playback", but there is no acoustic echo cancellation anywhere in the JVM. With mic and speaker on one headset — the normal case — energy VAD detects our own TTS and the assistant interrupts itself in a loop. The design does not address this. Push-to-talk is the default gate mode anyway, so a keypress barge-in costs nothing and has zero false positives. Udit confirmed. Measured cut latency: 1–2 ms against a 100 ms budget.

**Uncertain:** Whether hands-free barge-in is wanted badly enough to justify a real threshold-calibration exercise. Untested until someone asks for it.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/JvmAudioPlayback.kt`, `voice/src/main/kotlin/com/secondbrain/voice/harness/PttWindow.kt`, `model/src/main/kotlin/com/secondbrain/model/AppConfig.kt`.

---

## D-018 — Noise floor is measured at startup and cached outside `config.toml`
**Date:** 2026-09-01

**Decided:** Sample `gate.calibration_ms` (500 ms) of room audio at startup, take the **median** chunk RMS as the floor, and set the gate threshold to floor + `gate.energy_margin_db` (12 dB). Cache to `~/.secondbrain/calibration.json`, keyed by device name; re-measure when the device changes.

**Why:** EC-V1 requires "RMS energy above a calibrated floor" and defines none of: how it is derived, when, or where it lives. Median rather than mean, so one cough during calibration does not deafen the gate for the whole session. dBFS rather than raw amplitude, because a 12 dB margin means the same thing in a quiet room and a noisy one. Not written back into `config.toml`, because that file is hand-edited and holds API keys — the app must never rewrite it. `app.db` was rejected because it does not exist until Step 3. Measured on this laptop: floor −61 dBFS, threshold −49 dBFS, and room noise at −59 dBFS is correctly discarded.

**Uncertain:** The 12 dB margin is a guess. It will need tuning against quiet speech, and 500 ms may be too short a sample in a variable room.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/NoiseFloorCalibrator.kt`, `Rms.kt`, `VoiceGate.kt`.

---

## D-019 — Step 1's push-to-talk harness is Swing, not Compose and not a console loop
**Date:** 2026-09-01

**Decided:** `voice/harness/PttWindow.kt` is a bare JDK Swing frame that exists only to deliver key-down / key-up events. Compose stays in Step 4. `:app` remains a stub with no Compose plugin applied.

**Why:** Step 1's exit criterion is "hold space" in a `:voice` CLI harness, which is not expressible: a JVM console gets line-buffered stdin with no key-release event at all. Swing ships with the JDK, so this adds no dependency, does not pull the Compose tree into Step 1, and does not violate a module edge. Udit confirmed, over the alternative of building design-board screen 1a now. Key repeat is handled with a `down` flag — without it, holding a key on Windows restarts capture roughly thirty times a second.

**Uncertain:** Nothing. The harness is disposable; Step 4 replaces it.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/harness/PttWindow.kt`, `VoiceHarness.kt`, `voice/build.gradle.kts`.

---

## D-020 — Retrying STT and TTS is correct and is not an R5 violation
**Date:** 2026-09-01

**Decided:** `GeminiStt` and `KokoroTts` retry three times with exponential backoff plus jitter, configurable per service. R5's "never auto-retry" applies to irreversible actions only.

**Why:** Worth writing down explicitly, because the rules read as if they conflict. R5 governs the action ledger: sending an email, creating an event, placing an order. Transcription and synthesis are read-only and idempotent, and EC-V7 positively *requires* retrying STT so a dropped connection does not lose a thought. Without this entry, someone reads R5 in six weeks and deletes the retry loop.

**Uncertain:** Nothing.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/GeminiStt.kt`, `KokoroTts.kt`, `config.example.toml`.

---

## D-021 — Secret redaction is a configuration concern, not a code-review concern
**Date:** 2026-09-01

**Decided:** `SecretRedactor` wraps Ktor's logger and masks key-shaped strings by pattern, plus exact-match on secrets registered from the loaded config. Ktor's own internal loggers (`io.ktor`, `io.netty`) are pinned to WARN in `logback.xml`. The Gemini key is sent as the `x-goog-api-key` header, never as a `?key=` query parameter.

**Why:** CLAUDE.md forbids writing an API key into a log. Ktor's `Logging` plugin prints request headers by default, and Gemini's documented auth is a query parameter — so the *default* configuration of the tools in use violates the prohibition on the very first call. Ktor's internal loggers are a second channel the redactor never sees, which is why they are pinned rather than left to the root level. Using the header form means the key never enters a URL, which is what ends up in stack traces and access logs.

**Uncertain:** Pattern matching cannot cover a shape nobody predicted, which is why exact-match registration exists as a second layer. Ten unit tests cover the known shapes.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/SecretRedactor.kt`, `HttpClients.kt`, `voice/src/main/resources/logback.xml`.

---

## D-022 — `verifyModuleGraph` makes §1 an executable rule
**Date:** 2026-09-01

**Decided:** A root-build task walks every subproject's `ProjectDependency` set against the §1 allowlist and fails `check` on any edge outside it. Wired into `build`.

**Why:** "`:agent` must not depend on `:vault`, `:voice`, or `:integrations`" is the constraint the whole hexagonal design rests on, and Gradle has no native way to express "must not depend on". `ARCHITECTURE.md` §7 Step 1 asks for "the dependency edges from §1 enforced" without naming a mechanism. Thirty lines in the root build is cheap, and the failure message points at the missing port rather than just refusing. Verified by deliberately adding `:agent -> :vault`, confirming the build failed with that exact message, then reverting.

**Uncertain:** Nothing.

**Files:** `build.gradle.kts`.

---

## D-023 — Gradle 8.14.5 with a pinned distribution checksum, bootstrapped from the verified distribution
**Date:** 2026-09-01

**Decided:** Gradle 8.14.5, Kotlin 2.4.10, JDK 17 toolchain, Ktor 3.5.2, JUnit 5. `gradle-wrapper.properties` carries `distributionSha256Sum`. The wrapper was generated by downloading the official distribution, verifying its published SHA-256, and running `gradle wrapper` once.

**Why:** No Gradle on PATH and no wrapper in the repo is a chicken-and-egg problem, and hand-placing a `gradle-wrapper.jar` fetched from a mirror is a supply-chain risk taken for no reason. Gradle 8.14.x rather than 9.x because Compose Multiplatform arrives in Step 4 and its plugin support for Gradle 9 is a fight not worth having now; 8.14.5 specifically because Kotlin 2.4 warns below 8.14.4. The pinned checksum means a future `./gradlew` on another machine verifies what it downloads.

**Uncertain:** Whether Compose Multiplatform at Step 4 will want a newer Gradle. If so, that is a one-line bump and a re-pinned checksum.

**Files:** `gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `build.gradle.kts`.

---

## D-024 — Two defects the smoke check found in interruptible playback
**Date:** 2026-09-01

**Decided:** Barge-in unwinds `Flow.collect` by throwing a private `AbortPlayback`, and `logback.xml` must never contain a double hyphen inside a comment.

**Why:** Both were live defects that compiled, passed unit tests, and were caught only by running against real hardware — which is why `:voice:smokeCheck` exists.

1. **Playback cancellation.** Two wrong versions preceded the right one. `return@collect` on a flag silences audio but lets the flow run to completion, and `KokoroTts` issues one HTTP request *per sentence* — barge-in on a six-sentence reply kept paying for five sentences nobody hears. Replacing it with `job.cancel()` did nothing either: cancellation is cooperative, and the collect lambda is pure blocking `SourceDataLine.write` with no suspension point for the cancel to land on. Instrumented output showed all five chunks emitted 1 ms apart after the cut. Throwing terminates the collection and, with it, the upstream flow: one of five chunks now.
2. **logback.xml.** XML forbids `--` inside a comment. A comment explaining the key-leak risk contained one; logback rejected the entire config and every logger silently fell back to its default level — re-enabling the exact `io.ktor` DEBUG path the comment was warning about.

**Uncertain:** Nothing. Both are covered by `:voice:smokeCheck`, which asserts the cut latency and the dropped-chunk count against real devices.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/JvmAudioPlayback.kt`, `voice/src/main/resources/logback.xml`, `voice/src/main/kotlin/com/secondbrain/voice/harness/SmokeCheck.kt`.

---

## D-025 — Artifacts moved to the repository root
**Date:** 2026-09-01

**Decided:** `CLAUDE.md`, `ARCHITECTURE.md` (renamed from `ARCHITECTURE (1).md`) and `DECISIONS.md` now live at the repository root. `artifacts/` keeps only `Second Brain UI.html` as the design reference.

**Why:** `ARCHITECTURE.md` §1 shows all three at the root, and CLAUDE.md's first line tells the reader to open `ARCHITECTURE.md` — a file that did not exist under that name. It also means Claude Code auto-loads CLAUDE.md every session, which it did not while the file was nested two directories down.

**Uncertain:** Nothing.

**Files:** repository root, `artifacts/`.

---

## D-026 — Plain FTS5, not `content=''`; `folder_decisions` in `app.db` with an `index.db` projection
**Date:** 2026-09-01

**Decided:** `notes_fts` is a plain (non-contentless) FTS5 table. `folder_decisions` is written to `app.db`, which is the sole source of truth, and mirrored into `index.db` as a strictly one-way projection rebuilt from `app.db`. Both supersede the `index.db` schema in `ARCHITECTURE.md` §2.

**Why:** The §2 schema specifies `notes_fts USING fts5(..., content='')`. Measured against SQLite 3.53.4 via sqlite-jdbc 3.53.4.0, before any code was written:

```
DELETE from contentless   ->  "cannot DELETE from contentless fts5 table: notes_fts"
UPDATE contentless        ->  "cannot UPDATE contentless fts5 table: notes_fts"
snippet() on contentless  ->  NULL
plain fts5 INSERT/UPDATE/DELETE  ->  all succeed
snippet() on plain fts5   ->  "offline [inference] wins"
```

EC-N10 (re-index an externally edited note), EC-N11 (rebuild), every note edit, and the dashboard's "Search the vault" all need what that schema forbids. `contentless_delete=1` was tested and fixes `DELETE` only — still no `UPDATE`, still no snippets. Body duplication in a plain table is irrelevant at a few thousand notes.

For `folder_decisions`: it is an audit trail of *runtime* verdicts, and the vault has no record of a folder Claude proposed and had rejected, so a rebuild loses it permanently — exactly what R10 forbids storing in `index.db`. Udit chose to write it to `app.db` **and** cache it in `index.db`. Built as a one-way projection specifically so there is no reconciliation path to get wrong: `AppDb` is the only writer, `VaultIndex.syncFolderDecisions` overwrites the projection wholesale, and a deleted `index.db` re-derives the panel from `app.db`.

**Uncertain:** Nothing measured. Whether the dashboard ever needs to join `folder_decisions` against `notes` is unproven — if it never does, the projection could be dropped and the panel read straight from `app.db`.

**Files:** `vault/src/main/kotlin/com/secondbrain/vault/VaultIndex.kt`, `AppDb.kt`, `VaultWriter.kt`.

---

## D-027 — Every `index.db` value is derived from the vault, so a rebuild is reproducible
**Date:** 2026-09-01

**Decided:** No column in `index.db` holds a wall-clock value captured at index time. `folders.created_at` is the earliest `created` among the folder's notes; `dangling_links.seen_at` is the referencing note's `updated`. `folders.note_count` stores the **direct** count and the tree query computes the rollup. Two extra columns exist that §2 does not list: `folders.parent` and `notes.path_lower`.

**Why:** The Step 2 exit criterion is "`index.db` can be deleted and fully rebuilt from `vault/` with identical contents", and R10's claim that the file is disposable rests entirely on that. As specified it was unachievable: `created_at` and `seen_at` were index-time timestamps that would differ on every rebuild. Deriving both makes the rebuild reproducible and testable.

Two counts because the index and the UI want different things. The design board shows `Projects 23` where its children hold 9 + 7 + 7 — the tree displays a rollup — but only a direct count can be maintained incrementally without touching every ancestor on every write. `parent` exists so the tree can be assembled in one pass instead of by string manipulation; `path_lower` carries a unique index for the reason in D-035.

**Correction to an earlier claim:** the rebuild is verified by comparing every row through a canonical dump, not by comparing the `.db` file byte for byte. Two SQLite databases holding identical rows differ in page layout, freelists and WAL state, so a byte comparison of the file would fail for reasons that have nothing to do with correctness. Rows are the claim that matters.

**Uncertain:** Deriving `folders.created_at` from note timestamps means an empty folder's date comes from the filesystem, which is not reproducible across a machine transfer. Accepted: an empty folder has no data to lose.

**Files:** `vault/.../VaultIndex.kt`, `VaultScanner.kt`, `VaultWriter.kt`, `model/.../VaultConfig.kt`.

---

## D-028 — `NoteParser` added; frontmatter scalars are quoted
**Date:** 2026-09-01

**Decided:** `:vault` gains `NoteParser`, the inverse of `NoteRenderer`, with a round-trip property test. `NoteRenderer` emits every string scalar as a double-quoted, escaped YAML value and sanitises tags to lowercase hyphenated tokens. This deviates from the unquoted example in §3.

**Why:** Two separate problems in the specified design.

*The parser.* `NoteRenderer` is specified as write-only, but four specified features need to read a rendered note back into structure: `vault_move_note` must add `moved_from` to frontmatter (EC-N5), `vault_append_note` must bump `updated`, re-indexing an externally edited note must recover its title and tags (EC-N10), and rebuilding the index must read every note (EC-N11). Without a parser, `created` is silently reset to now on the first append — nothing looks broken, the note just forgets when it was written. This does not violate R1: the parser reads, `NoteRenderer` is still the only thing that writes `.md` bytes.

*The quoting.* §3 shows `title: Offline inference is the moat`. A title of *"Pricing: a positioning problem"* — one of the note titles in the design board itself — emits `title: Pricing: a positioning problem`, which is not valid YAML. Titles beginning `-`, `#`, `[`, `&`, `*`, `?`, `|`, `>`, `@` or `%` break or change meaning the same way, `summary` has identical exposure, and `tags: [a, b]` splits one tag into two on any tag containing a comma. All are things a transcript will produce. Quoting is deterministic, testable and costs nothing.

**Uncertain:** Nothing. The round-trip is a property test, and `render -> parse -> rerender` is asserted to be a fixed point so `content_hash` does not churn.

**Files:** `vault/.../NoteParser.kt`, `NoteRenderer.kt`, `vault/src/test/.../NoteRoundTripTest.kt`.

---

## D-029 — Slugs are transliterated to ASCII via ICU4J, with an empty-result fallback
**Date:** 2026-09-01

**Decided:** Titles are transliterated to ASCII with ICU4J's `Any-Latn; Latin-ASCII`. Collision suffixes are `-2`, `-3` (not `" 2"`), applied to an already-truncated base. An empty transliteration result falls back to `note-<yyyy-MM-dd-HHmm>`. The frontmatter `title` keeps the original script untouched.

**Why:** Udit's choice, over keeping native script in filenames. It needs ICU: `java.text.Normalizer` decomposes Latin diacritics only and produces an **empty string** for every non-Latin script. Measured before the dependency was accepted:

```
                        icu4j Any-Latn; Latin-ASCII    Normalizer alone
బ్లూప్రింట్ లెన్స్        bluprint-lens                  <EMPTY>
ఇది చాలా బాగుంది          idi-cala-bagundi               <EMPTY>
इदि बहुत अछ्छा है          idi-bahuta-achcha-hai          <EMPTY>
中文测试                  zhong-wen-ce-shi               <EMPTY>
Café résumé naïve       cafe-resume-naive              cafe-resume-naive
🚀🚀🚀 / "??? !!! ..."   <EMPTY>                        <EMPTY>
```

Cost is a 14 MB dependency in `:vault`; there is no smaller transliteration-only artifact. On an undistributed desktop app that is irrelevant, and a hand-rolled Telugu + Devanagari table would be ~200 lines, lower quality, and cover nothing else.

The fallback is **not** a second slug policy — emoji-only and punctuation-only titles reduce to zero characters, which without it is a file with no name. It fires only on an empty result.

EC-N1 says append `" 2"` with a space, but the design board filename is `offline-inference-is-the-moat.md`, fully hyphenated; a space would be the only whitespace in any filename the app produces. Truncation happens **before** the suffix is appended — the other order silently exceeds the cap, and there is a test at the boundary.

**Uncertain:** Devanagari picks up schwa artifacts (`ब्लूप्रिंट लेंस` → `bluprinta-lensa`). Recognisable, imperfect, not worth solving. Transliteration is also not injective, so two different titles can collide — the existing suffix mechanism handles that.

**Files:** `vault/.../Slugifier.kt`, `vault/build.gradle.kts`, `gradle/libs.versions.toml`.

---

## D-030 — Folder Guard scores against every folder; ambiguous wikilinks stay dangling
**Date:** 2026-09-01

**Decided:** A proposed folder is scored against **every** existing folder in the vault, not just its siblings, exactly as §5 specifies. A `[[wikilink]]` whose top score is tied between two or more notes is left dangling, with all tied candidates recorded.

**Why:** Both Udit's calls. Scoring vault-wide is maximum anti-sprawl: one folder per concept anywhere, and `Projects/Reading` gets redirected to an existing top-level `Reading`. The accepted cost is that two folders with the same name under different parents are impossible — `Projects/Notes` and `People/Notes` cannot coexist. Scoring compares the **leaf** segment, because diluting the score with a differing path prefix is how sprawl gets through.

Ambiguity is not covered anywhere in the artifacts. Leaving a tie dangling extends EC-N8's own reasoning — "a wrong link is worse than no link" — from weak matches to ties. The alternatives both fail quietly: nearest-by-path is usually right and invisible when wrong, and most-recently-updated makes the link graph non-reproducible over time.

**Uncertain:** Whether vault-wide scoring fights the user in practice. `VaultConfig` makes the threshold tunable but not the scope; if 20 real captures in Step 3 show it being obstructive, scoping becomes a config flag and a new entry here.

**Files:** `vault/.../FolderGuard.kt`, `LinkResolver.kt`.

---

## D-031 — Conservative singulariser, and the 0.72 threshold cannot move single-word verdicts
**Date:** 2026-09-01

**Decided:** §5's "singularise" step strips a trailing `s` only when the word is longer than three characters and does not end `ss`, `us` or `is`, plus `-ies`/`-ches`/`-shes`/`-xes` rules and an eleven-entry irregular map. It affects scoring only; the stored folder name is never changed.

**Why:** §5 says "singularise" without saying how, and the obvious implementation is wrong. A Porter stemmer turns *People* into *Peopl*, *Analysis* into *Analysi* and *Status* into *Statu*, so `People` would stop matching itself in a way nobody would ever think to debug.

Recorded alongside it, because it will save someone a wasted afternoon: **the 0.72 threshold does almost nothing for single-word folder names.** Jaccard over a single token is binary, so every single-word comparison lands at ~0.95 (same concept) or below ~0.40 (different concept) and the threshold sits in a wide empty gap. Measured: `Project` vs `Projects` = 0.95, `Recipes` vs `Architecture` = 0.07. There is a test asserting that no threshold between 0.45 and 0.90 changes either verdict. It only bites on multi-word names — `Second Brain UI` vs `Second Brain` scores ~0.74, which is why that pair is the one used to test tunability. D-007 expects to tune this against 20 captures in Step 3; that tuning will only affect multi-word cases.

**Uncertain:** The irregular map is short and will miss words. A miss degrades gracefully — the pair simply scores lower and the folder is allowed.

**Files:** `vault/.../FolderGuard.kt`, `model/.../VaultConfig.kt`, `config.example.toml`.

---

## D-032 — Notes always live in a folder; only `Inbox/` is seeded; external deletion dangles inbound links
**Date:** 2026-09-01

**Decided:** Every note is at depth 1 or deeper. A note found at the vault root is skipped by the scanner and logged. `VaultRoot` seeds `Inbox/` and nothing else. When a note's file disappears, the index drops the note and its outbound links, and converts **inbound** links to dangling.

**Why:** `notes.folder` has a foreign key to `folders(path)`, and the vault root has no natural path — representing it as `""` means a folders row for something that is not a folder. Requiring depth ≥ 1 makes the key trivially satisfiable and the dashboard tree always meaningful, with `Inbox/` as the fallback placement.

§2's storage layout shows `Inbox/`, `Projects/`, `People/` and `...`, but Step 2's build list says "seeds `Inbox/`", and pre-creating folders the user may never want is precisely the sprawl the Folder Guard exists to prevent. Every other folder gets created because a capture needed it.

Deletion is absent from the artifacts entirely. There is no delete tool and there should not be, but EC-N10 admits external editing, which includes deleting a file. Dangling the inbound links rather than dropping them means the dashboard shows that something now points at nothing, which is the honest state.

**Uncertain:** Nothing.

**Files:** `vault/.../VaultRoot.kt`, `VaultScanner.kt`, `VaultIndex.kt`.

---

## D-033 — Append creates a missing heading, and cannot inject frontmatter
**Date:** 2026-09-01

**Decided:** `vault_append_note` inserts at the end of the named heading's section. If the heading does not exist it is created at the end of the body. Any line in the appended text matching `^-{3,}$` is rewritten to `***`.

**Why:** §4 lists the tool as "append to an existing note under a heading" and says nothing about a heading that is not there; creating it is the least surprising outcome and the alternative is a tool call that silently does nothing. The `---` rewrite matters because the appended markdown comes from the model: a line of three hyphens at column 0 reads as a frontmatter delimiter, and the next parse would split the note in two and take the injected keys as real. R3's fail-closed instinct applies to text from a model, not just to tool classification. There is a test that tries to hijack a note's title through an append.

**Uncertain:** Nothing.

**Files:** `vault/.../VaultWriter.kt`.

---

## D-034 — Accepted risk: the rename in an atomic write cannot be made durable on Windows
**Date:** 2026-09-01

**Decided:** `AtomicWriter` writes to a `.sbtmp` file in the same directory, `fsync`s the **file**, then `ATOMIC_MOVE`s it over the target with bounded retry. Directory `fsync` is not attempted. Orphaned temp files are swept at startup and logged.

**Why:** EC-N4's recipe is "write to `.tmp`, `fsync`, then atomic `Files.move`". Two thirds of that is available here, both measured:

```
fsync a FILE      via FileChannel.force(true)   ->  OK
fsync a DIRECTORY via FileChannel.force(true)   ->  AccessDeniedException
ATOMIC_MOVE, target held open by another handle ->  AccessDeniedException
```

There is no JVM API to fsync a directory on Windows, so the rename's durability is at the OS's discretion. The exposure is narrow: file contents are durable, and the only thing a power cut in that window can leave behind is a temp file, which the startup sweep removes. Accepted rather than worked around, because there is nothing to work around it with.

The second failure is not exceptional — Defender, the Search Indexer and the Step 4 dashboard reader all hold files open routinely — so it is handled as a normal condition: retry with backoff, then fall back to a non-atomic replace with a warning. The other half of that fix is a discipline: every read in `:vault` goes through `AtomicWriter.read`, which reads fully and closes immediately, so this module never causes the failure for itself.

**Uncertain:** Whether 5 attempts at 40 ms is enough under a full antivirus scan. Both values are config. There is a test that holds the target open and asserts the write still lands.

**Files:** `vault/.../AtomicWriter.kt`, `VaultRoot.kt`, `model/.../VaultConfig.kt`.

---

## D-035 — Paths are compared case-insensitively
**Date:** 2026-09-01

**Decided:** `notes` carries a `path_lower` column with a unique index, and slug collision detection lowercases before comparing. The as-created casing is what gets stored and displayed.

**Why:** Measured on this filesystem:

```
write TheMoat.md, then themoat.md  ->  1 file on disk
TheMoat.md now contains            ->  "second"
```

One file, and the first note's content is gone. `notes.path` as a case-sensitive TEXT primary key cannot see that, so the index would hold two rows for one file and the drift would be permanent. In the module whose entire job is not losing data, that is not acceptable at any probability.

Our own slugs are always lowercase after D-029, so the app cannot self-collide; the exposure is an externally created file (EC-N10) differing from an existing note only in case. Folder names keep their casing, and the Folder Guard already returns the existing casing when a proposal matches case-insensitively, so index and disk stay in agreement there too.

**Uncertain:** On a case-sensitive filesystem this is stricter than necessary — two notes legitimately differing only in case would get a `-2` suffix. Acceptable; the runtime target is Windows.

**Files:** `vault/.../VaultIndex.kt`, `Slugifier.kt`, `FolderGuard.kt`.

---

## D-036 — Only `.md` is indexed, watched, or acknowledged
**Date:** 2026-09-01

**Decided:** The scanner indexes `*.md` only. The watcher emits events for `*.md` only. Everything else in the vault is ignored silently, not reported as skipped.

**Why:** The vault is a directory the user can open in Explorer, so a PDF or a screenshot will end up in it eventually. Indexing a binary as a note produces garbage in search results; reporting it as "skipped" on every scan trains the user to ignore the warnings that matter. Genuine problems — a path `PathSafety` rejects, an unreadable file, a note at the vault root — are still reported.

**Uncertain:** Nothing.

**Files:** `vault/.../VaultRoot.kt`, `VaultScanner.kt`, `FileWatcher.kt`.

---

## D-037 — `app.db` migrates, `index.db` rebuilds
**Date:** 2026-09-01

**Decided:** `app.db` has a forward-only versioned migration runner, transactional per migration, refusing outright to open a database from a newer schema version. `index.db` has a `user_version` check that discards and rebuilds on any mismatch. `app.db` runs `synchronous=FULL`, `index.db` runs `NORMAL`. Both use WAL.

**Why:** This asymmetry is R10 expressed in code. `index.db` can be recreated from the vault, so migration logic for it would be pure cost — EC-N11 already specifies rebuild-on-mismatch. `app.db` has nothing to rebuild itself from, so a schema change must migrate or the data is gone.

The runner exists at Step 2 rather than Step 5 because `folder_decisions` moved into `app.db` (D-026), so the file now exists from Step 2 and Steps 3, 5 and 6 will each add tables to it. Writing the runner now costs an hour; discovering at Step 5 that the action ledger's database has no migration path is the worst possible table to learn that on. Refusing a future-versioned database matters for the same reason: an older build silently writing to a newer `app.db` would corrupt data it does not understand.

`synchronous=FULL` on `app.db` is chosen for Step 5 — with R5, ledger durability is the difference between "sent once" and "sent twice".

**Uncertain:** Nothing.

**Files:** `vault/.../AppDb.kt`, `VaultIndex.kt`.

---

## D-038 — WAL plus `busy_timeout` for writer-versus-watcher contention
**Date:** 2026-09-01

**Decided:** Both databases run in WAL mode with a 5-second `busy_timeout`. All app-initiated mutations funnel through `VaultWriter`'s single lock, so the only concurrency SQLite has to survive is our writer against the FileWatcher's re-index.

**Why:** EC-N3 covers "concurrent write while the dashboard reads" and answers it with a single-writer actor, but there are genuinely two writers to `index.db`: the vault writer after a mutation, and the watcher after an external edit. Serialising our own path reduces that to one axis of contention, which WAL plus a timeout handles without any application-level coordination.

**Uncertain:** Nothing at this scale.

**Files:** `vault/.../AppDb.kt`, `VaultIndex.kt`, `VaultWriter.kt`.

---

## D-039 — One serialised writer, not an actor plus per-file mutexes
**Date:** 2026-09-01

**Decided:** `VaultWriter` uses a single `Mutex`. No per-path locks.

**Why:** Step 2's build list asks for "a single-writer actor, per-file mutex", which is two mechanisms for one job — a single serialised writer already provides per-file exclusion. Per-path locks would only buy parallelism across different notes, and the workload is one person speaking one thought at a time. Simpler is also easier to reason about for read-modify-write sequences like append and move, which span several awaits. If a bulk import ever appears, per-path locks can be layered on then.

**Uncertain:** Nothing for v1.

**Files:** `vault/.../VaultWriter.kt`.

---

## D-040 — Link escapes are detected with `toRealPath`, and a junction is the real Windows threat
**Date:** 2026-09-01

**Decided:** `PathSafety` walks from the candidate up to the vault root, resolving every existing ancestor with `toRealPath()` and comparing against the real root. It never calls `Files.isSymbolicLink`. It also rejects trailing dots, leading and trailing whitespace, `:`, and reserved device names. The whole input is deliberately **not** trimmed.

**Why:** Measured, and it changed the implementation. A **directory junction** created by an unprivileged user:

```
created without elevation             ->  yes (mklink /J)
reads through to outside the vault    ->  yes
lexically inside the vault root       ->  yes
Files.isSymbolicLink()                ->  FALSE
toRealPath() resolves outside root    ->  yes
```

An implementation built on `isSymbolicLink` misses the escape completely, and a junction needs no privilege — so it is the escape a real unprivileged process would actually use, and it is more reachable than the symlink case the exit criteria name. The `toRealPath` comparison catches it. There is a test that creates a junction, asserts the escape is genuinely reachable, asserts `isSymbolicLink` returns false, and then asserts we reject it. The symlink test remains but skips on this machine, since Windows refuses symlink creation without elevation — the junction test covers the same property and does run.

Three smaller findings, all measured. Writing `dot.md.` produces a file named `dot.md` on disk, so the index would hold a path no directory scan can reproduce — rejected. Trailing whitespace and `:` are rejected by Java with `InvalidPathException`, an exception type no caller expects, so they are rejected here first with a clear reason; that is also why the input is not trimmed, since trimming silently strips a trailing space off the final segment and hands the problem to Java. Reserved device names (`con.md`, `nul.md`, `aux.md`, `com1.md`) **round-tripped cleanly** on this machine and are rejected purely for portability, not because of an observed failure.

**Uncertain:** The symlink-specific path is unverified on this machine. The junction test exercises the same code, so the risk is that symlinks behave differently in some way not covered — unlikely, and `toRealPath` is agnostic to which kind of reparse point it resolved.

**Files:** `vault/.../PathSafety.kt`, `vault/src/test/.../PathSafetyTest.kt`.

---

## D-041 — `WatchService` is registered recursively, and OVERFLOW forces a rescan
**Date:** 2026-09-01

**Decided:** `FileWatcher` registers every directory under the vault individually, registers new directories on `ENTRY_CREATE`, ignores `*.sbtmp` and every non-`.md` file, debounces 300 ms, and escalates `OVERFLOW` to a full rescan.

**Why:** Step 2 says "`WatchService` on the vault root". Measured: registering only the root produced **zero** events for a file written into `Projects/`. `java.nio.file.WatchService` is not recursive on any platform. Since every note lives in a folder (D-032), the watcher as specified would never have fired at all — the feature would have looked implemented and done nothing.

The other three are consequences of how the rest of the module works. One `AtomicWriter` write emits a temp CREATE, a MODIFY, a rename CREATE and a temp DELETE, so temp files are filtered and the debounce window collapses the remainder into one re-index. Our own writes trigger the watcher, so `VaultWriter.reindexFromDisk` compares `content_hash` first and returns early — EC-N10 names that guard and this is where it earns its place, because without it every write triggers a re-index which triggers another event. `OVERFLOW` means the OS dropped events and the index is provably behind by an unknowable amount, so ignoring it silently loses edits.

**Uncertain:** The debounce window is a guess and is config. A very large vault may make full-rescan-on-overflow expensive; unmeasured, and no vault is large yet.

**Files:** `vault/.../FileWatcher.kt`, `VaultWriter.kt`, `Vault.kt`.

---

## D-042 — Backlink context is extracted on read, not stored
**Date:** 2026-09-01

**Decided:** No snippet column. `Vault.backlinks` reads the source note, finds the occurrence that targets this note, and extracts ±60 characters around it.

**Why:** The design board shows a context line under each backlink ("…open on the moat, then the demo…"), and there is no such column in the §2 schema. A stored snippet goes stale the moment the source note is edited, and would need invalidating on every write. Extracting on read is always correct, needs no schema change, and costs one file read per backlink — the panel shows three.

**Uncertain:** On a note with a hundred inbound links this is a hundred file reads. Unmeasured, and the design board shows three.

**Files:** `vault/.../Vault.kt`, `LinkResolver.kt`.

---

## D-043 — FTS5 queries are tokenised and quoted, never passed through
**Date:** 2026-09-01

**Decided:** `VaultIndex.search` strips every non-alphanumeric character from each token and wraps each in double quotes before handing the query to FTS5.

**Why:** FTS5 treats `-`, `*`, `:`, `^`, `(`, `)`, `"` and the bare words `AND`, `OR`, `NOT` as query syntax. Search text here comes from a voice transcript via `vault_search`, which is exactly where stray punctuation and the word "not" come from, so passing it through turns an ordinary question into a SQL error instead of a search. The cost is that deliberate FTS operators are unavailable, which no voice interface can express anyway. There is a test that fires nine syntactically hostile queries and asserts none of them throws.

**Uncertain:** Nothing.

**Files:** `vault/.../VaultIndex.kt`.
