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

---

## D-044 — The official Anthropic SDK is the Claude transport, behind `LlmPort`
**Date:** 2026-09-01

**Decided:** `ClaudeClient` uses `com.anthropic:anthropic-java` 2.59.0, wrapped behind a new `LlmPort` in `:ports`. The agent loop remains hand-written. Supersedes `ARCHITECTURE.md` §7 Step 3's "`ClaudeClient` — Ktor, `POST /v1/messages`".

**Why:** Udit's call. D-005 is not in conflict — re-read, its reasoning is about not adding a workflow framework ("no LangGraph equivalent, no state-machine DSL"), which says nothing about transport; a hand-written loop over the SDK satisfies it exactly as a hand-written loop over Ktor would.

The deciding argument was API drift. Five breaking changes are documented in the last year alone: `thinking: {type: "adaptive"}` replacing `budget_tokens` (which now returns a **400** on Opus 5), `effort` moving inside `output_config`, assistant prefill being removed, the web-tool type strings changing, and the Files/Skills namespaces leaving beta. With hand-rolled JSON each of those is a runtime 400 discovered mid-conversation; with the SDK they are compile errors. That is not hypothetical — writing this file produced four compile errors from exactly that class of mistake (`JsonValue.fromJsonString` does not exist, `ToolUseBlockParam.input` takes a typed `Input`, not a `JsonValue`), each of which would have been a runtime failure against a hand-rolled client.

Accepted cost: Jackson and OkHttp now sit alongside Ktor and kotlinx-serialization, so the app carries two HTTP stacks and two JSON libraries. The Jackson boundary is confined to one file, `JsonBridge.kt`.

`LlmPort` is what makes this reversible and testable. Nothing above `:agent` sees Jackson, `Optional<T>` or a builder; if the SDK ever proves wrong, only `ClaudeClient` changes. It is also what makes CLAUDE.md's "Fake `LlmPort`" bar reachable — the whole loop is tested with zero API calls and zero dollars.

**Uncertain:** The reference documents SDK 2.34.0 and 2.59.0 is pinned, so a documented builder name may have moved. That surfaces as a compile error, which is the point.

**Files:** `agent/src/main/kotlin/com/secondbrain/agent/ClaudeClient.kt`, `JsonBridge.kt`, `ports/.../LlmPort.kt`, `gradle/libs.versions.toml`.

---

## D-058 — Compose Multiplatform Desktop wiring: `google()` is required, the `application` plugin is gone, no icon-font dependency
**Date:** 2026-09-01

**Decided:** `:app` applies `org.jetbrains.compose` 1.11.1 and `org.jetbrains.kotlin.plugin.compose` (pinned to the Kotlin version, 2.4.10) instead of the plain `application` plugin. `settings.gradle.kts`'s `dependencyResolutionManagement` gains `google()` alongside `mavenCentral()`. There is no Material icon dependency; the handful of glyphs the UI needs (a tree chevron, a nav-rail dot) are hand-drawn.

**Why:** Measured, not assumed, against the real Compose Multiplatform 1.11.1 artifacts. `:app:compileKotlin` failed to resolve `androidx.collection:collection`, `androidx.compose.runtime:runtime`, `androidx.annotation:annotation`, `androidx.lifecycle:*` and `androidx.savedstate:*` — all real transitive dependencies of `compose.desktop.currentOs`/`compose.material3` that are published only to Google's Maven repository, never Maven Central, even though nothing here targets Android. `mavenCentral()` alone is not enough for a "desktop-only" Compose Multiplatform app.

The plain `application` plugin and `compose.desktop.application {}` each register a task literally named `run`; applying both fails immediately. Compose Desktop's own block supplies `mainClass` and an equivalent `:app:run`, so `SETUP_GUIDE.md`'s `./gradlew.bat :app:run` keeps working, and native packaging (`packageMsi`) is the right distribution shape for a GUI app — the old plugin's `distTar`/`distZip`/`startScripts` were dead weight the moment there was a real window instead of a stub `println`. `capture` (the Step 3 harness task) does not depend on `application` at all — `sourceSets` comes from the Kotlin plugin — so it is unaffected.

`compose.materialIconsExtended` prints its own warning on first resolve: it is pinned to a 1.7.3 snapshot that "will not receive updates." Given the design board's own brief — "light, quiet, desktop-native: system type, hairline rules, no brand color beyond one system blue" — a few Canvas-drawn glyphs cost nothing and owe no version anyone has to track later. `compose.material3` is used via its deprecated-but-functional accessor (`'material3: String' is deprecated. Specify dependency directly`); accepted rather than chased, since the warning does not fail the build and pinning the artifact coordinate by hand trades one uncertainty for another for no behavioural gain.

The composition root also needs `io.ktor.client.HttpClient` itself, to own closing the two clients `:voice`'s `HttpClients.create` returns — `:voice` declares Ktor as `implementation`, which does not leak the type to `:app`'s compile classpath, so `:app` takes the same `libs.bundles.ktor.client` dependency directly.

**Uncertain:** Whether `compose.material3`'s deprecation warning becomes a hard removal in a later Compose Multiplatform release, at which point the dependency declaration needs the explicit coordinate the warning names. Native packaging (`packageMsi`) is wired but unexercised — nobody has run it yet.

**Files:** `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`.

---

## D-059 — `Vault.changes`: the FileWatcher → UI signal ARCHITECTURE.md's Step 4 asks for
**Date:** 2026-09-01

**Decided:** `Vault` exposes `changes: SharedFlow<Unit>`, ticking once after every mutation — our own writes (from `runWrite`, covering `writeNote`/`appendNote`/`moveNote`/`createStub`) and every change the `FileWatcher` collector picks up (`Upserted`, `Deleted`, and a post-`Overflowed`-rescan). Content-free by design: no payload describing *what* changed.

**Why:** ARCHITECTURE.md §7 Step 4 names this exact wiring — "FileWatcher → UI state flow, so a capture on screen 1 appears on screen 2 with no restart" — without saying how. `:app` already learns about its own agent-driven writes synchronously, from `AgentTurnResult.touchedNotes`, so the gap this actually closes is EC-N10: a note edited in an external editor while the dashboard is open, which has no other signal reaching `:app` at all. A payload would have to be kept in lock-step with every call site touching the index, and a stale one is worse than none; the UI already knows how to re-query whatever it currently has selected, so a bare tick is the whole contract. `extraBufferCapacity` plus `DROP_OLDEST` means a burst — a multi-file external edit, an `OVERFLOW` rescan — coalesces into "something changed, look again" instead of the vault's own write path ever blocking on a slow UI collector.

**Uncertain:** Nothing about the mechanism. Whether ticking on *every* mutation (including our own, which `:app` already knows about by other means) causes a visibly redundant extra query at real vault sizes is unmeasured — cheap at the "few thousand notes" scale D-042 already accepted, and simpler than trying to distinguish "a change the caller already knows about" from "a change it doesn't."

**Files:** `vault/src/main/kotlin/com/secondbrain/vault/Vault.kt`.

---

## D-060 — Speech normalisation happens in `:app`, never in `:agent`; `AgentTurnResult.spokenText`'s doc comment was wrong
**Date:** 2026-09-01

**Decided:** `AgentTurnResult.spokenText` is the model's raw text reply — not normalised, not capped for speech. `VoiceController.speak()` runs it through `SpeechNormalizer.normalize` and `capForSpeech` before every synthesis call, the same way `VoiceHarness.speak()` already did for its own text in Step 1.

**Why:** Step 3's `AgentTurnResult` KDoc claimed the field was "already normalised and capped for speech (EC-T1, EC-T2)" — which cannot be true: `SpeechNormalizer` lives in `:voice`, and `:agent` is not permitted to depend on `:voice` (§1). The claim was aspirational or simply wrong; either way, Step 4 is the first place both modules are visible to the same caller, so it is also the first place the mistake was reachable. Left uncorrected, the natural reading would have been "skip normalising, it's already done," which would have shipped raw Markdown straight to Kokoro.

**Uncertain:** Nothing.

**Files:** `model/src/main/kotlin/com/secondbrain/model/Agent.kt`, `app/src/main/kotlin/com/secondbrain/app/voice/VoiceController.kt`.

---

## D-061 — The rolling summariser is a real Claude call now; a text digest, never a replay of raw blocks
**Date:** 2026-09-01

**Decided:** `VoiceController.summarise` — the `summariser` `ConversationStore.advance` has always accepted — is a one-shot `LlmPort.send` call, `thinkingEnabled = false`, logged to `CostMeter.Service.CLAUDE_SUMMARY`. Its input is `ConversationDigest.render(dropped)`: plain `"User: ..."` / `"Assistant: ..."` / `"[used tool: X]"` lines, never the dropped `LlmMessage`s themselves.

**Why:** D-047 named this ("a real summariser is a Claude call") and Step 3's harness deliberately supplied a no-op instead, "kept out of the harness so summary spend does not contaminate the per-capture figure" — a harness reason that does not apply to the live app, where a session genuinely exceeding `context_window_turns` (8) is an expected, not exceptional, outcome of normal use. Replaying the dropped messages verbatim into a fresh one-shot request is unsafe regardless: a `tool_result` without the `tool_use` it answers, in the *same* request, is a 400 from the API — `AgentLoop`'s own documented finding — and the turns falling out of the window have no reason to land on a clean user/assistant boundary. Rendering to text sidesteps the pairing requirement entirely and, as a side effect, is legible if it ever needs debugging.

**Uncertain:** Whether 300 `maxTokens` is enough headroom for "one short paragraph" in practice — unmeasured, no summary call has run against the real API yet (Step 3's own credential gap, D-056, is still open). The system prompt for the summary call ("You summarise conversation excerpts...") is a fixed one-liner rather than anything from `SystemPrompt`'s frozen-prefix machinery, since a one-shot request has its own prefix and will not see a cache hit regardless — deliberately not cached, so a future reader does not mistake the absence of a cache breakpoint here for an oversight.

**Files:** `app/src/main/kotlin/com/secondbrain/app/voice/VoiceController.kt`, `ConversationDigest.kt`, `agent/src/main/kotlin/com/secondbrain/agent/SystemPrompt.kt`.

---

## D-062 — `ask_user` actually speaks and listens now; no timeout; a mutex keeps it from deadlocking itself
**Date:** 2026-09-01

**Decided:** The `askUser` lambda `VaultTools` is built with, in `Main.kt`, calls `VoiceController.handleAskUser`, which speaks the question, then suspends on a `CompletableDeferred<VaultTools.AskResult>` until the user's *next* full talk-press-and-release cycle resolves it — with no timeout. `VoiceController.turnMutex` serialises top-level turn processing (STT through the agent loop and its bookkeeping) but `handleAskUser`'s answer-delivery path deliberately never takes it, since the turn holding the lock is the one waiting on this call.

**Why:** D-055 built `AskResult.Answered` / `NoAnswer` in Step 3 but left the wait itself to "the caller supplying the handler," explicitly because "what the right wait is has not been measured." Step 4 is that caller. Inventing a timeout number now would be exactly the kind of silent guess CLAUDE.md's working style says not to make — waiting indefinitely for a person to answer a question the machine itself just asked is the honest version of "unmeasured," not a gap.

The mutex exists because of D-048: talking again while THINKING cancels the in-flight turn *and* immediately starts a new recording, so a cancelled turn's tail (`ConversationStore.recordTurn`/`advance`, `CostMeter.record`) can genuinely still be running when the replacement utterance's own processing begins — both would otherwise be free to mutate `conversationState`/`turnIndex` at once. Taking that same lock inside `handleAskUser` would deadlock: the mutex is only ever released by the turn that is, at that moment, suspended waiting for `handleAskUser` to return. EC-V1's gate check runs before either branch and never resolves a pending answer on a discard, so a fumbled talk-press while answering is retried, not silently counted as `NoAnswer`.

**Uncertain:** Whether a genuinely unbounded wait is the right call once this sees real usage — nothing here has been exercised against the live API (same credential gap as D-061). If it turns out wrong, the fix is additive (a configured timeout in `AgentConfig`), not a redesign.

**Files:** `app/src/main/kotlin/com/secondbrain/app/voice/VoiceController.kt`, `app/src/main/kotlin/com/secondbrain/app/Main.kt`.

---

## D-063 — EC-G2's spoken cost-ceiling confirmation is a narrow keyword gate, and each "yes" buys one more ceiling's worth
**Date:** 2026-09-01

**Decided:** `CostConfirmation.parse` matches a fixed allow-list of yes/no phrases against one utterance. On `CostMeter.Verdict.Blocked`, `VoiceController` speaks the balance, sets `awaitingCostConfirmation`, and does *not* process that utterance further — the request itself is not retried automatically; the user repeats it after confirming. A "yes" calls `CostMeter.raiseCeiling(appConfig.agent.sessionUsdCeiling)` — the ceiling amount itself, so each confirmation extends the session by one more ceiling's worth.

**Why:** D-047 requires this to be spoken, not a click — "continuing to spend money is neither [irreversible nor a verbatim field], so a spoken yes keeps the count at two rather than inventing a third" R9 exception. That leaves EC-V8's "never route on keyword matching, Claude classifies intent" looking like it forbids exactly this, until you notice what EC-V8 is actually about: telling *different* actions apart ("send" / "spend"), which needs a classifier because guessing wrong is expensive and unbounded. Here there is no Claude turn to ask *through* — the whole point of the gate is that the loop must not resume without spending more money, so routing "should I keep spending money?" through another paid model call is circular. A closed allow-list for one bounded binary question is the same shape as a "press 1 to confirm" voice menu, not the open-ended routing EC-V8 is about.

The extend-by-one-ceiling rule is a guess, and a documented one rather than a silent one. The alternative — lifting the ceiling once and never re-checking — defeats the ceiling's purpose after a single confirmation; re-extending by the same increment each time keeps the number meaningful without inventing a second config key nobody has asked for yet.

**Uncertain:** The extend-by-one-ceiling increment is unvalidated against real usage, same footing as D-007's Folder Guard threshold — a guess to be tuned once someone actually hits it. The default `session_usd_ceiling` ($2.00) makes this rare enough that it may simply never fire in normal use before the number gets revisited.

**Files:** `app/src/main/kotlin/com/secondbrain/app/voice/CostConfirmation.kt`, `VoiceController.kt`.

---

## D-064 — The vault dashboard: a hand-rolled Markdown renderer, `LinkAnnotation` over the deprecated `ClickableText`, and "All notes" as the default view
**Date:** 2026-09-01

**Decided:** `NoteMarkdown` renders a note body to an `AnnotatedString` with a small hand-written inline scanner (headings, bold, italic, inline code, `[[wikilinks]]`) rather than a Markdown parser dependency. `[[wikilinks]]` carry a `LinkAnnotation.Clickable` with an embedded `LinkInteractionListener`, not the offset-lookup `ClickableText` API. A dangling link is distinguished from a resolved one by colour (`AppColors.Dangling` vs `AppColors.Blue`), not the design board's literal dashed underline. The list pane defaults to a pinned "All notes" pseudo-folder (`selectedFolder == null`), sorted by `Instant.parse(updatedAt)` descending, rather than nothing selected.

**Why:** A note body is `NoteRenderer` output or a person typing by hand — the same small, closed set of Markdown a transcript could plausibly contain — so this is the same call `SpeechNormalizer` already made for itself: "a parser would be correct and slow to write... a sequence of targeted rewrites is what the job actually needs." `ClickableText` prints its own deprecation warning on first use, steering explicitly at `LinkAnnotation` — worth taking on a fresh Step 4 UI rather than shipping a known-deprecated API on day one, especially with a real compiler available to verify the replacement immediately. Compose's `TextDecoration` has no dashed variant for inline spans; colour carries the "visually distinct" requirement WF-5 actually asks for, even though it is not the exact stroke the mockup shows.

"All notes" as the default, rather than an empty selection, is what makes the Step 4 exit criterion — "capture a note by voice; it appears in the dashboard within 2 seconds" — actually *visible* without the reviewer first having to click into the right folder: a new or updated note sorts to the top of the unfiltered view the moment `Vault.changes` ticks. Sorting parses to `Instant` rather than comparing `updatedAt` strings lexicographically: `Instant.toString()` omits trailing zero fractional digits, so two timestamps in the same second can compare out of order as plain strings — a real, if narrow, correctness gap, not a hypothetical one, and the fix costs nothing.

**Uncertain:** Whether the hand-rolled renderer's bold/italic overlap handling (`**bold**` next to `*italic*` in one line) is fully correct on adversarial input is not exhaustively tested — reasonable for model-generated and hand-typed prose, untested against deliberately hostile Markdown, which nothing in this vault has a reason to contain.

**Files:** `app/src/main/kotlin/com/secondbrain/app/vault/NoteMarkdown.kt`, `VaultBrowserController.kt`, `VaultScreen.kt`, `TreeFlatten.kt`.

---

## D-065 — STT moves to `gemini-3.5-transcribe`, TTS moves to Gemini native audio; both measured live before being wired in
**Date:** 2026-09-01

**Decided:** `stt.model` becomes `gemini-3.5-transcribe`, a dedicated speech-to-text model, superseding the general-chat-model assumption (`gemini-2.5-flash`) §7 Step 1 shipped with. `GeminiStt.extractText` now reads `parts[].audioTranscription.text` (falling back to `parts[].text`). TTS moves off Kokoro to `gemini-3.1-flash-tts-preview` — a new `GeminiTts` (`:voice`) implementing `TtsPort` against `generateContent` with `responseModalities: ["AUDIO"]`. `KokoroTts` is untouched and still compiles; `TtsConfig.model`/`voice`/`baseUrl` all gained Gemini defaults, so a config with no `[tts]` section at all now loads, and `tts.api_key` joined `ConfigLoader`'s required list — Gemini genuinely needs one, where a self-hosted Kokoro deployment often didn't.

**Why:** Udit supplied a live Gemini key and named both models by name. Rather than trust a training-cutoff memory of what those model IDs' APIs look like — both post-date this session's knowledge — every shape below was measured against the real endpoint first, the same discipline D-013's noise-floor probe and S1.2's Kokoro-contract framing already establish for this codebase:

```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-tts-preview:generateContent
  -> 200, candidates[0].content.parts[0].inlineData: {"mimeType":"audio/l16; rate=24000; channels=1","data":"<base64 PCM>"}

POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent
  (inline_data audio, the SAME request shape GeminiStt already used)
  -> 200, candidates[0].content.parts[0].audioTranscription.text: "Saved to inbox as a new note."
  (round-tripped: that WAV was the TTS call's own output, decoded from its base64 PCM)
```

Two findings neither vendor doc page surfaced on its own. First, the transcription model's response nests the text under a structured `audioTranscription` part, not the `text` field every other Gemini response uses — `GeminiStt.extractText` would have silently returned `null` (read as an empty transcript) against a model that had, in fact, transcribed correctly. Second, official docs describe `gemini-3.5-transcribe` as needing the Files API and a different `/v1beta/interactions` endpoint; measured against the actual API, the existing inline-base64 `generateContent` shape GeminiStt already used works unmodified — simpler than the documented path, and confirmed rather than assumed to be a safe simplification specifically because a real 200 came back with a correct transcript.

`TtsConfig` gaining defaults (rather than requiring the fields again for a second provider) mirrors `stt.base_url`'s own existing pattern once Gemini is the primary TTS provider too. `KokoroTts` is deliberately not deleted — ports exist so a provider is swappable, and D-065 is exactly the swap that machinery was built for; someone pointing `tts.base_url` at a self-hosted endpoint still has a working implementation to fall back to.

**Uncertain:** Whether `gemini-3.5-transcribe`'s "smart" cleanup mode (disfluency/filler-word removal, per its own product docs) can be disabled from the `generateContent` path this file uses — only the `/v1beta/interactions` endpoint's documented `transcription_config.mode: "verbatim"` was confirmed to exist, and that endpoint was not the one wired in. EC-V5 requires verbatim output; the existing prompt's explicit anti-cleanup instructions are kept as the defence, unverified against a genuinely disfluent, code-switched utterance (only a short, clean English test phrase was tested end-to-end). Cost tracking for STT/TTS calls was never wired up even in Step 1 — `CostMeter.Service.GEMINI`/`KOKORO` exist but nothing calls `record()` with them, a pre-existing gap this entry does not close. Gemini TTS's `speed` and multi-language/multi-speaker behaviour are unexercised. `KokoroTts` itself remains exactly as unconfirmed as D-016 already recorded it.

**Files:** `voice/src/main/kotlin/com/secondbrain/voice/GeminiStt.kt`, `GeminiTts.kt` (new), `voice/src/test/kotlin/com/secondbrain/voice/GeminiTtsTest.kt` (new), `model/src/main/kotlin/com/secondbrain/model/AppConfig.kt`, `ConfigLoader.kt`, `model/src/test/kotlin/com/secondbrain/model/ConfigLoaderTest.kt`, `app/src/main/kotlin/com/secondbrain/app/Main.kt`, `config.example.toml`.

---

## D-066 — `agent.workspace_id`, and `agent.effort` becomes provider-omittable
**Date:** 2026-09-01

**Decided:** `AgentConfig` gains `workspaceId: String? = null`; when set, `ClaudeClient` sends it as the `anthropic-workspace-id` header. `AgentLoop.request()` now omits `output_config.effort` entirely when `config.effort` is blank, rather than sending `LlmRequest.effort` unconditionally.

**Why:** Measured live, in order. First attempt at wiring Udit's Anthropic key failed every request with `anthropic-workspace-id is required when authenticating with an identity-linked API key` — a personal/service-account key scoped to more than one workspace, distinct from the plain single-workspace keys this client was built against. No endpoint reachable with that key could list workspace IDs to self-discover one (the Admin API workspaces list returned a 403 `permission_error` for this key specifically), so it has to come from the Console and be config, not something the app can infer.

Separately, switching the target model toward Claude Haiku 4.5 (see the "Uncertain" note below — never completed) surfaced that `effort` is Opus/Sonnet/Fable-tier only and is rejected outright on Haiku. `AgentConfig.effort` had no way to express "omit this" — it was a plain non-null `String` always forwarded. Blank-means-omit reuses the type rather than making the field nullable, since `ConfigLoader`'s flat TOML reader has no natural way to distinguish "key absent" from "key present with an empty value" once a Kotlin default exists either way, and "absent" already means "use the default," which is the opposite of what an omit signal needs to mean here.

**Uncertain:** The Haiku migration itself was never finished — mid-troubleshooting, Anthropic stopped accepting payment on the account entirely (see D-067), and the effort session moved to DeepSeek instead. The blank-omits-effort mechanism is real and now in the codebase, but nothing has exercised it against a live Haiku response — only against DeepSeek's endpoint, where `effort` support is itself unconfirmed (also left blank there, for the same reason).

**Files:** `model/src/main/kotlin/com/secondbrain/model/Agent.kt`, `agent/src/main/kotlin/com/secondbrain/agent/AgentLoop.kt`, `ClaudeClient.kt`.

---

## D-067 — Reasoning moves to DeepSeek, via `ClaudeClient` pointed at a different `base_url`; no new `LlmPort` implementation
**Date:** 2026-09-01

**Decided:** `AgentConfig.baseUrl: String? = null` — blank keeps the Anthropic SDK's own default; set to `https://api.deepseek.com/anthropic` and `ClaudeClient` runs unmodified against DeepSeek's Anthropic-wire-compatible endpoint instead of writing a second `LlmPort` implementation. `model = "deepseek-v4-pro"` (DeepSeek's own model ID, not the Claude-name auto-mapping DeepSeek also offers — explicit beats an implicit table that could change, per EC-G3's reasoning applied to a second provider). `cache_enabled` and `prewarm_cache` set to `false` in Udit's config, and pricing updated to DeepSeek's rates, none of which the code enforces automatically.

**Why:** Udit asked first for "Gemini 3.5" reasoning; checked before promising anything — Gemini 3.5 Pro does not exist yet as of this session (delayed since May, no model ID, no public API, per live search). Real options were `gemini-3.1-pro-preview`, or building nothing and fixing Anthropic's billing instead — recommended the latter, since the blocker at that point (an identity-linked key needing a workspace header) was already solved and the remaining problem was a credit-balance top-up, not an integration problem. Udit then reported Anthropic outright refusing the payment method — a harder blocker than credits — and supplied a DeepSeek key instead.

Before writing a new client: DeepSeek documents an Anthropic-compatible endpoint (`api-docs.deepseek.com/guides/anthropic_api/`) built specifically so "existing Anthropic client code" works against it unmodified. Measured against the real endpoint before trusting the docs: a request with the wrong URL, key shape, or model name fails before it reaches billing; this request reached `HTTP 402 Insufficient Balance` — meaning the URL, the `x-api-key` auth (same header name Anthropic uses), and the model string were all accepted, and only account funding is blocking a full response. Ran the actual `:app:capture` harness against the real `config.toml` immediately after — vault opens, tool registry builds, the agent loop dispatches, and the request reaches DeepSeek and fails with the identical 402, end to end, not just via a bare curl call.

Documented, not measured directly (the account has no balance to complete a real turn with): DeepSeek ignores `cache_control` rather than rejecting it (prompt caching is a silent no-op, not an error — `cache_enabled` set false in config so `CostMeter`'s "cache read 0 tokens" warning doesn't fire constantly and misleadingly on every real turn) and ignores `budget_tokens` (irrelevant — this client never sends one, per D-049). Tool use and parallel tool calls are documented as supported; `output_config.effort` support is not documented either way, so it stays blank (D-066) rather than guessed.

Not a new `LlmPort` implementation, on purpose: the entire cost of building `GeminiClient` or `DeepSeekClient` from scratch — new tool-use mapping, new stop-reason mapping, new thinking-block handling, all newly unverified — is exactly what D-044 chose the official SDK to avoid in the first place. `ClaudeClient`'s own doc comment already says the class depends on the *SDK*, not literally on Claude; this proves that was true, not just a phrasing choice.

**Uncertain:** No real turn has completed against DeepSeek — the account has zero balance, same class of blocker as Anthropic's, just further along (past auth and request validation, not past billing). `output_config.effort` support is genuinely unknown. Pricing is off-peak only; DeepSeek's peak-hours multiplier (roughly 3x, 01:00-04:00 and 06:00-10:00 UTC) has no representation in `AgentConfig`'s single-price-per-token-class model, so a capture made during peak hours will under-report cost by that factor — accepted rather than modelled, since `:agent`'s pricing shape has no time-of-day axis anywhere and adding one for a single provider's promotional pricing structure is more machinery than this is worth right now. Whether adaptive thinking specifically (versus thinking in general) is what DeepSeek's "supported" claim covers is documented, not independently confirmed.

**Files:** `model/src/main/kotlin/com/secondbrain/model/Agent.kt`, `agent/src/main/kotlin/com/secondbrain/agent/ClaudeClient.kt`, `config.example.toml`.

---

## D-068 — Reasoning lands back on Claude Opus 5, direct; D-066/D-067's machinery kept, not reverted
**Date:** 2026-09-01

**Decided:** `agent.model` is `claude-opus-5` again, `base_url` unset (Anthropic directly, not DeepSeek), `thinking_enabled = true`, `effort = "high"`, `cache_enabled = true`, `prewarm_cache = true`, and pricing back to Opus 5's $5.00 / $25.00. `agent.workspace_id` stays set — the same identity-linked key from D-066 needs it regardless of which model sits behind it.

**Why:** Udit funded the Anthropic account. Measured live before trusting it: a plain message returned a real `HTTP 200` with genuine usage accounting — the first fully successful reasoning-model call of this entire session, across four attempted providers (Anthropic without credits, Claude Haiku's `effort` incompatibility, DeepSeek's unfunded account, an Azure AI Foundry deployment that 500'd on every request regardless of shape). A second test — `thinking: adaptive` + `effort: high` + a tool, the actual shape `AgentConfig`'s defaults produce — hit `529 overloaded_error` twice in a row, Anthropic's own documented transient capacity signal and exactly what `ClaudeClient.isRetryable()` already treats as retryable; not re-litigated further, since the first plain call already confirmed auth, workspace resolution, and billing all work, and `529` says nothing about request correctness.

None of D-066 or D-067's changes were undone. `workspace_id` and blank-means-omit `effort` are real, general capabilities now, independent of which provider is configured — D-066 exists because of this exact key regardless of model, and D-067's `base_url` override stays available (DeepSeek's own wiring is fully built and tested, just unfunded) if Anthropic's account ever needs a fallback again. This is config, not code, reverting.

One live mistake caught and fixed in the process: reverting the config by hand missed that D-067's DeepSeek pass had **overwritten** the `workspace_id` line with a comment (correct at the time — DeepSeek ignores it) rather than merely setting it blank. Restoring Opus 5 without restoring that value would have silently reintroduced D-066's original failure. Caught by re-reading the file after the edit rather than trusting the edit script, which is exactly why every config change this session has ended with a structural re-read.

**Uncertain:** Whether Opus 5 capacity (`529`) is a persistent issue or a momentary blip — worth noting if it recurs often, since `EC-A7`'s 3-attempt retry has a ceiling. The real end-to-end `:app:capture` run against this config (not just a raw curl call) was still in flight when this entry was written; a follow-up note belongs here once it completes if the outcome says anything this entry doesn't already cover.

**Files:** `~/.secondbrain/config.toml` only — no source changes.

---

## D-045 — `app.db` has two module owners, coordinating through `schema_migrations`
**Date:** 2026-09-01

**Decided:** `app.db` tracks migrations per module in a `schema_migrations(module, version, applied_at)` table rather than the `user_version` pragma. `:vault` owns `folder_decisions`; `:agent` owns `conversations`, `messages`, `cost_meter`, and from Step 5 the action ledger. Each opens its own connection and migrates only its own tables. Supersedes D-037's use of `user_version`.

**Why:** The file genuinely has two owners after D-026 moved `folder_decisions` into it, and §1 forbids `:vault` and `:agent` from seeing each other. There is no shared home for a single migration runner: `:model` is data classes plus kotlinx-serialization, and `:ports` is interfaces. `user_version` holds one integer and cannot represent two independent lineages.

Two alternatives were rejected. A ninth `:store` module adds a dependency edge §1 does not have. A port exposing `java.sql.Connection` would let every future port implementation write arbitrary SQL to the *precious* database — a larger hole than the problem being solved.

Accepted cost: roughly forty lines of migration mechanics duplicated between `AppDb` and `AgentDb`. That is cheaper than either alternative, and it keeps `:agent` testable against a bare temp file with no `:vault` present at all.

**Uncertain:** Two connections to one SQLite file rely on WAL and `busy_timeout`, which is fine at this scale and untested under contention.

**Files:** `agent/.../AgentDb.kt`, `vault/.../AppDb.kt`.

---

## D-046 — Four token classes, four prices, and prices live in config
**Date:** 2026-09-01

**Decided:** `cost_meter` and `messages` carry `tokens_in`, `tokens_out`, `cache_write_tokens` and `cache_read_tokens` as separate columns. The four per-million prices live in `config.toml` under `[agent]`. Supersedes §2's `cost_meter` schema, which has a single `units REAL`.

**Why:** `usage.input_tokens` from the API is the **uncached remainder only**. Total prompt is `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`, and the three are priced differently — 1×, 1.25× at the 5-minute TTL, and 0.1× — with output at 5× base input on Opus 5 ($25 against $5). One `units` column cannot express that, and costing a turn from `input_tokens` alone under-reports by whatever the cache served, which in a healthy loop is most of it.

This matters more than a schema tidy-up. The Step 3 exit criterion records a per-capture USD figure and says it "sets the budget for everything after", so an under-reporting cost meter would corrupt Steps 4 through 7 with a number nobody would think to re-check.

Prices are config for the same reason model IDs are (EC-G3): switching models forces you to look at the price, and a stale price is visible rather than buried in a constant. They are flat fields under `[agent]` rather than a nested `pricing` object — `config.toml` uses single-level sections, so a nested field would be unreachable from the file and `ignoreUnknownKeys` would silently fall back to the defaults. That near-miss is now covered by a test.

**Uncertain:** Prices are correct as of 2026-09-01 and will drift. Nothing checks them against the live API.

**Files:** `agent/.../AgentDb.kt`, `CostMeter.kt`, `model/.../Agent.kt`, `config.example.toml`.

---

## D-047 — Caching is on from the start; summaries are a Claude call; the ceiling is checked between turns
**Date:** 2026-09-01

**Decided:** An explicit cache breakpoint on the system-plus-tools prefix, intermediate breakpoints every 15 content blocks, and startup pre-warming — all on by default. Rolling summaries and phase carry-over are a Claude call on the same model, logged to `cost_meter` as a separate `CLAUDE_SUMMARY` service. The EC-G2 ceiling is evaluated before a new utterance starts, never mid-turn, and continuing past it is confirmed by **speech**.

**Why:** Caching is absent from the artifacts entirely, and it is the dominant cost lever for this workload: the system prompt plus eight tool schemas is a large stable prefix resent on *every* iteration, EC-A1 allows twelve, and cache reads cost 0.1×. Measuring the per-capture figure without it would record a number roughly ten times the prefix cost too high — and that number becomes the project's budget. Voice also meets every criterion for pre-warming (user-visible first-request latency, a large shared prefix, a quiet moment at startup), which serves §9's ~4 s concern directly.

Summaries are model work — §4 asks for "a one-paragraph carry-over summary", which a template cannot write. Same model keeps one cache namespace, though a summary call is a one-shot with its own prefix and will not see hits either way; the separate `cost_meter` service row is so summary spend does not contaminate the per-capture figure.

The ceiling timing is the EC-V7 argument: tripping at iteration seven of a capture would abandon a thought halfway through. A turn in flight always finishes. Confirmation is spoken because R9 permits exactly two typing exceptions — verbatim fields and confirmation clicks for irreversible actions — and continuing to spend money is neither, so a spoken yes keeps the count at two rather than inventing a third.

**Uncertain:** Whether 15 blocks is the right breakpoint spacing, and whether pre-warming's one cache write pays for itself, are both unmeasured until the keys exist.

**Files:** `agent/.../AgentLoop.kt`, `ClaudeClient.kt`, `CostMeter.kt`, `ConversationStore.kt`, `model/.../Agent.kt`.

---

## D-048 — Speaking during THINKING cancels the in-flight turn
**Date:** 2026-09-01

**Decided:** A new utterance while the agent loop is running cancels the current turn. Cancellation is a cooperative flag checked between iterations and after each tool, not coroutine cancellation. Tokens already spent are still recorded.

**Why:** Udit's call, and it matches the barge-in instinct already established for playback: the user changed their mind, and making them wait through up to twelve round-trips for a reply they no longer want is the worse failure. Anything already written stays written.

Cooperative rather than structured cancellation for the same reason Step 1's playback needed it (D-024): cancellation only lands where something checks for it, and a tool handler doing blocking file I/O is not a suspension point.

Safe in Step 3 specifically — every vault write is atomic and serialised, so a cancel between tools leaves no partial state, and no irreversible action exists yet. Spend is still logged because we paid for it whether or not the answer was wanted.

**Uncertain:** **This needs re-examining at Step 5.** Cancelling with a proposal in flight is a ledger question rather than a UX one, and the R5 state machine has opinions this decision does not cover.

**Files:** `agent/.../AgentLoop.kt`.

---

## D-049 — Five stop reasons, two caps, and thinking is on
**Date:** 2026-09-01

**Decided:** The loop branches on `END_TURN`, `TOOL_USE`, `MAX_TOKENS`, `REFUSAL` and `API_FAILED`, and treats the SDK's `PAUSE_TURN` and `MODEL_CONTEXT_WINDOW_EXCEEDED` as failed turns with an honest message. Round-trips and tool executions are capped separately. Adaptive thinking is on, with depth controlled by `effort`.

**Why:** §4's flowchart has a `stop_reason?` diamond with two branches, and the API has more. Two of the missing ones fail silently: `refusal` arrives as HTTP 200 with possibly-empty content, so an unguarded loop speaks nothing and looks broken; `max_tokens` can truncate a response mid-`tool_use`, leaving incomplete input JSON that must not be executed. Inspecting the SDK turned up two further stop reasons the artifacts never mention at all — `PAUSE_TURN` and `MODEL_CONTEXT_WINDOW_EXCEEDED`.

EC-A1's "12 tool calls per turn" is ambiguous once parallel tool use is on by default, because one response can carry several calls. Twelve round-trips of N parallel calls each is unbounded, so both are capped: 12 round-trips, 24 executions.

Thinking is on by default on Opus 5, bills as output, and `budget_tokens` returns a 400 there — depth is `effort`, which defaults to `high`. §4 has no notion of thinking at all, and it is both a cost and a latency factor on the critical path.

**Uncertain:** `effort = high` is the API default rather than a measured choice. It should be tuned once per-capture cost is real.

**Files:** `agent/.../AgentLoop.kt`, `ClaudeClient.kt`, `model/.../Agent.kt`.

---

## D-050 — Parallel tool results go back as one message; only two phases are reachable
**Date:** 2026-09-01

**Decided:** All `tool_result` blocks from one assistant turn are returned in a single user message, including for calls that errored, were refused as gated, or arrived after a cancel. A "turn" is one utterance plus everything the assistant did about it. Phases are built in full, and only `CAPTURE` and `QUERY` are reachable in Step 3.

**Why:** §4's flowchart is strictly sequential — one `tool_use`, one `tool_result`, loop — and parallel tool use is on by default. Splitting results across messages trains the model out of parallel calls, and a *missing* result for any `tool_use` id is a 400 from the API on the next request, not a soft degradation. That makes "every call gets a result, always" a correctness requirement rather than tidiness, which is why the throwing-handler and cancelled-mid-batch paths both still emit one.

"Turn" needed defining because §4's eight-turn window is ambiguous against a twelve-iteration cap: eight turns of *messages* would be roughly 200 content blocks, while eight turns of *utterances* is what a person means. The UI's "TURN 3 / 8" chip counts utterances.

Phases are honest about their coverage: no email, calendar or commerce tool exists yet, so three of the five cannot be entered. The machinery and the gated hook are built so Step 5 is plumbing, but nothing claims they are exercised.

**Uncertain:** Phase *detection* remains unresolved. EC-V8 says Claude classifies intent and never keyword matching, but a phase transition resets the context the model would classify from. Step 3 sidesteps it because only one phase is entered; Step 5 cannot.

**Files:** `agent/.../AgentLoop.kt`, `ConversationStore.kt`, `model/.../Agent.kt`.

---

## D-051 — The loop returns structured results, not just a spoken sentence
**Date:** 2026-09-01

**Decided:** `AgentLoop.run` returns `AgentTurnResult` carrying the spoken text plus `toolEvents`, phase, turn index, usage, USD, iteration count, latency and — per tool — the note path it touched.

**Why:** The design board's assistant turn offers "Open note" and "Move" chips beneath the reply. Those need the note *path*, which is not in the spoken sentence. A loop returning only a string would force Step 4's UI to regex prose for a filename, which is the kind of coupling that works until a phrasing changes. The same object carries what the status bar needs (`session $0.0416`) and what the DECISIONS entry needs (per-capture USD).

**Uncertain:** Nothing.

**Files:** `model/.../Agent.kt`, `agent/.../AgentLoop.kt`, `ToolRegistry.kt`.

---

## D-052 — True token streaming is deferred, and `stream()` is honest about it
**Date:** 2026-09-01

**Decided:** `LlmPort.stream()` exists and every caller uses it, but `ClaudeClient` implements it by completing the request and emitting the text as a single delta. Real token streaming is deferred.

**Why:** Streaming buys exactly one thing here: the first sentence of the *final* text reply reaching TTS sooner (EC-T3, and §9 calls it "the main lever" on latency). It cannot help on a tool-calling iteration, because there is no text to speak — and on a CAPTURE turn the final reply is one short sentence while several tool round-trips dominate the wall clock. The payoff is a few hundred milliseconds on the last of several calls.

The cost is not small. The SDK exposes no non-beta message accumulator, so real streaming means hand-assembling text blocks, thinking blocks with their signatures, `tool_use` inputs from partial-JSON deltas, and usage from `message_start` plus `message_delta`. Getting the partial-JSON assembly wrong breaks tool calls *silently*, which is the worst failure mode this system has.

Deferring behind a working port rather than a `TODO` means no caller special-cases it and the upgrade is one method.

**Uncertain:** Whether the saving is worth it at all. Revisit once Step 1's measured latency numbers exist — which needs the Gemini and Kokoro credentials.

**Files:** `agent/.../ClaudeClient.kt`, `ports/.../LlmPort.kt`.

---

## D-053 — Duplicate detection is a deterministic gate in `:vault`, overridable
**Date:** 2026-09-01

**Decided:** `DuplicateGuard` in `:vault` compares a proposed note's title and summary against existing notes and refuses the write above `vault.duplicate_similarity_threshold` (0.78), naming the match. The model overrides with `confirm_new`. Supersedes EC-N9's prompt-instruction formulation.

**Why:** Udit's call. EC-N9 says "before `vault_write_note`, search for near-duplicates and offer append", which relies on the model choosing to search first — the exact mechanism D-007 argues at length does not survive contact: *"a prompt saying 'reuse existing folders where possible' does not survive two hundred captures."* If that reasoning holds for folders it holds for duplicates.

EC-N9's stated measure also does not exist. It says "cosine/FTS similarity is very high"; there is no cosine anywhere in this system, and FTS5 `rank` is an unbounded negative bm25 score that is not comparable across queries, so "very high" is not expressible with what Step 2 built. This reuses the Folder Guard's normalised-Levenshtein and Jaccard machinery instead: bounded 0..1, deterministic, already tested.

It lives in `:vault` rather than `:agent` because `:agent` cannot see `:vault` and this is a write-path guard exactly like the Folder Guard.

Title and summary are **combined**, weighted 0.65 to the title, rather than taking whichever scores higher. Taking the max was the first implementation and it was wrong: summaries are model-generated and often generically phrased, so a single shared summary made entirely unrelated notes look identical. The test suite caught it immediately — ten integration tests failed, all of them notes with different titles and one shared fixture summary.

`confirm_new` matters as much as the guard. Two genuinely distinct thoughts about one subject must stay writable, so a rejection is a question rather than a wall. `createStub` sets it, because the user clicking "Create stub" for a named dangling target has already decided.

**Uncertain:** 0.78 and the 0.65 title weight are guesses on the same footing as the Folder Guard's 0.72, to be tuned against the 20 real captures.

**Files:** `vault/.../DuplicateGuard.kt`, `VaultWriter.kt`, `Vault.kt`, `ports/.../VaultStore.kt`, `model/.../VaultConfig.kt`.

---

## D-054 — `vault_tree` is indented text with an estimated token cap; `request_typed_input` is not registered
**Date:** 2026-09-01

**Decided:** `vault_tree` returns indented text rather than nested JSON, truncated at ~2000 tokens estimated at 4 characters per token. `request_typed_input` is not registered in Step 3.

**Why:** EC-A5 caps the `vault_tree` response at "~2000 tokens", which Step 2 did not implement — it built the depth limit and the rollup counts only. A real `count_tokens` call per tool result would double the number of API requests per turn to measure something needed only approximately, so it is estimated and truncated on a line boundary with an explicit marker. Indented text rather than JSON because a tree is what the model reads to choose a folder, and the text encoding costs roughly half the tokens for the same information — which is most of how it fits under the cap at all.

`request_typed_input` is listed as autonomous in §4 but belongs to Step 5: nothing in Step 3 has a verbatim field. An unregistered tool costs nothing; a registered unused one costs bytes in the cached prefix on every request and invites the model to reach for it wrongly.

**Uncertain:** 4 characters per token is a rule of thumb and will be wrong for code-switched text, which tokenises worse than English.

**Files:** `agent/.../VaultTools.kt`.

---

## D-055 — `ask_user` distinguishes "no answer" from "no"
**Date:** 2026-09-01

**Decided:** `ask_user` returns either `Answered(text)` or `NoAnswer(reason)`. The tool result for `NoAnswer` sets `no_answer: true` with a null answer and tells the model to decide without the user or stop and say so.

**Why:** `ask_user` suspends the entire agent loop on a voice round-trip — TTS, then microphone, then Gemini — and the artifacts do not reach any of the consequences. The one that matters most for correctness: the user may not answer at all. They stay silent, or the EC-V1 gate discards a 200 ms noise, or transcription fails. Collapsing that into an empty string would let the model read silence as a negative, which is how you file a note nobody confirmed.

The others are noted rather than solved here: two clarifying questions burn two of the twelve iterations, because the cap now bounds *conversational* turns; a barge-in cutting the question mid-sentence must not read as failure; and a user thinking for six minutes exceeds the 5-minute cache TTL, which is exactly the 5-to-60-minute band where the 1-hour TTL's doubled write price would pay off.

**Uncertain:** No timeout is implemented — the caller supplying the handler owns that, and what the right wait is has not been measured with a real person.

**Files:** `agent/.../VaultTools.kt`.

---

## D-056 — Step 3's quality gate runs from typed utterances
**Date:** 2026-09-01

**Decided:** `:app:capture` drives the agent loop over a file of thoughts (or stdin) against the real vault and the real model, reporting folder count, per-capture USD, Folder Guard decisions and placement for manual review.

**Why:** Step 3's exit criteria are voice-first, but the three things being measured are not properties of the voice path: folder count after 20 captures, whether each note landed where a person would have put it, and per-capture cost. All three are properties of the agent loop. Driving them from typed input removes Gemini from the measurement, spends nothing on STT, and makes a run repeatable from a file — which matters because the criterion says to tune the Folder Guard threshold and *re-run*.

R9 is not in play: this is a developer harness, the same category as Step 1's `:voice:run`, not a third user-facing typing exception.

The harness's `ask_user` reports no answer rather than inventing one. A harness that silently fabricated answers would corrupt the placement measurement it exists to produce.

Placement quality is printed for human review rather than asserted. Claiming to have measured "17 of 20 in a folder you'd have chosen yourself" automatically would be a lie.

**Uncertain:** **Step 3's exit criteria are blocked on two credentials.** The loop and the cost figure need an Anthropic key; the voice path additionally needs Gemini (S1.1, still outstanding). Everything is built and unit-tested against a fake `LlmPort`, but the 20-capture gate cannot run yet.

**Files:** `app/src/main/kotlin/com/secondbrain/app/CaptureHarness.kt`, `app/build.gradle.kts`.

---

## D-057 — System prompt v1 explains rejections; it never states a cap
**Date:** 2026-09-01

**Decided:** The system prompt covers how to speak, one-note-per-thought, placement, wikilink conventions, "ask rather than guess", and — at length — how to read a structured rejection. It contains no numbers: no iteration cap, no folder cap, no similarity threshold. It is a pure constant, byte-identical on every request.

**Why:** R7, read strictly. "A prompt asking a model to keep it short is not a cap." Every limit in this system is enforced by code that returns a structured rejection, so what the prompt usefully supplies is not the limit but the *response* to it — use the folder named in `use_instead` rather than trying a variant spelling; read the note named in a duplicate rejection before deciding to append or confirm.

The constancy is a cache requirement, not style. Any per-request byte in the system prompt — today's date, a note count, the vault tree — invalidates the cached prefix on every single call, which on a twelve-iteration turn means paying full price twelve times for bytes that never change. The date the model genuinely needs goes in the user turn, behind the last breakpoint, where it costs nothing. There is a test asserting the system prompt is identical across every request of a turn.

**Uncertain:** Placement quality is entirely unvalidated. The 20-capture gate exists to measure whether these instructions actually produce ≤ 8 folders and sensible folders, and it has not run.

**Files:** `agent/src/main/kotlin/com/secondbrain/agent/SystemPrompt.kt`.

---

## D-068 — Step 5/6 built together: `ConfirmationGate` is the one mechanism for email and calendar, and R2 now holds structurally rather than by dispatcher interception
**Date:** 2026-09-01

**Decided:** `ToolDispatcher`'s Step-3 placeholder — intercept every `GATED` call and return a canned `awaiting_user_confirmation` result without calling the handler — is removed. A gated handler (`EmailTools.draftEmail`, `CalendarTools.proposeEvent`) now runs exactly like an autonomous one. What makes R2 ("gated tools never execute from a model call") still true is not dispatcher interception any more, but what the handler is *written* to do: build a `Proposal` and call `ConfirmationGate.submit(...)`, which suspends the coroutine — and with it, the whole turn — until a human clicks through every stage, and only then invokes a caller-supplied `executor` lambda that performs the real side effect. There is no code path from a `tool_use` block to `gmail.send`/`events.insert` that does not pass through a resolved gate.

**Why:** Step 3's stub was explicitly a placeholder — its own comment said "this is unreachable today" and "ConfirmationGate resolves these in Step 5." Building the real thing meant choosing where the safety property lives, and dispatcher interception was the wrong place for it: interception can only ever produce a *canned* response, never the real multi-stage content-review / verbatim-verify / execute flow WF-2 and WF-3 describe, which has to live somewhere that can suspend indefinitely, hold per-proposal state, and be driven by UI clicks over an arbitrary amount of real time — exactly the shape `ask_user`'s `CompletableDeferred` pattern (D-055/D-062) already proved out for a different kind of suspend. `ConfirmationGate` reuses that pattern.

`ActionLedger` (new, `AgentDb` migration 2) is the R5 state machine verbatim from ARCHITECTURE §2's `action_ledger`, with one exception: `LedgerKind` is `EMAIL_SEND | CALENDAR_CREATE` only — no `ORDER_PLACE` yet, and correspondingly no `OrderProposal` in the new `Proposal` sealed hierarchy (`EmailProposal`, `CalendarProposal`). Zepto's actual shape is unknown until Step 7's blocking spikes run (D-009), and CLAUDE.md's working style is explicit that designing a third variant now, before anything is validated, is exactly the mistake to avoid.

`Proposal.kind` reuses `LedgerKind` rather than introducing a parallel `ProposalKind` enum with the same two values — a proposal and its ledger row describe the same action, and a second enum would only ever need mapping back and forth to this one.

**Uncertain:** Whether `ConfirmationGate`'s multi-stage design (content review → verbatim verify → ready → executing) generalises cleanly to Step 7's commerce flow, which has a materially different shape (a whole cart, partial failures per item, re-reading server state before proposing). Likely needs its own machinery layered on top rather than a third caller of `submit`.

**Files:** `agent/src/main/kotlin/com/secondbrain/agent/ConfirmationGate.kt`, `ActionLedger.kt`, `agent/.../AgentDb.kt`, `agent/.../ToolDispatcher.kt`, `model/.../Proposal.kt`, `model/.../Ledger.kt`.

---

## D-069 — `TimeResolver` lives in `:model`, not `:vault` — corrects CLAUDE.md's module map
**Date:** 2026-09-01

**Decided:** `TimeResolver`, `TimeExpression`, `ResolvedTimeRange` and `Ambiguity` are in `model/src/main/kotlin/com/secondbrain/model/TimeResolver.kt`. CLAUDE.md's module map lists `TimeResolver` under `:vault`'s "owns" column; that line is now wrong and this entry corrects it.

**Why:** CalendarTools — the code that actually needs to resolve "tomorrow" against an absolute instant — lives in `:agent` alongside `VaultTools`, in the `ToolRegistry`. `:agent` may not depend on `:vault` (§1); that constraint predates Step 6 and nothing about it changed. Putting `TimeResolver` in `:vault` as CLAUDE.md's table says would have made it unreachable from the one place that needs it, discovered only once `CalendarTools.kt` failed to compile. `:model` costs nothing extra to receive it: `java.time` is JDK-standard, and `:model`'s "zero dependencies beyond kotlinx-serialization" rule is about libraries, not about which module owns pure logic with no I/O.

ARCHITECTURE §7 Step 6 itself hedges — "`:vault` or `:model` — `TimeResolver`" — so this is resolving an ambiguity the architecture document left open, not overriding a firm decision.

**Uncertain:** Nothing about the placement. The resolver's actual date-phrase vocabulary (today/tomorrow/yesterday/ISO date/weekday names, with "next X" and bare "X" both meaning the closest occurrence strictly after today) is a reasonable v1, not exhaustively validated against real speech — same footing as every other threshold in this system awaiting real usage.

**Files:** `model/src/main/kotlin/com/secondbrain/model/TimeResolver.kt`, `model/src/test/.../TimeResolverTest.kt`, `CLAUDE.md`.

---

## D-070 — EC-A8's "one gate at a time" is a structural property of sequential dispatch plus `turnMutex`; `ConfirmationGate`'s busy-check is a defensive backstop, not (yet) a reachable path
**Date:** 2026-09-01

**Decided:** `ConfirmationGate` holds a single `AtomicReference<Pending?>`; a second `submit()` while one is pending returns `GateOutcome.Busy` synchronously, rolling back the ledger row it had just created. Built and unit-tested directly (two concurrent `submit()` calls in `ConfirmationGateTest`), but honestly documented as **currently unreachable via the real app**: `AgentLoop`'s `for (call in calls)` loop dispatches tool calls sequentially, not concurrently, so a second gated call in the same parallel batch cannot even begin dispatching — and therefore cannot call `submit()` — until the first's `dispatch()` returns, which does not happen until its gate resolves. And `VoiceController.turnMutex` prevents a *second turn* from starting while the first's gate is open at all (decision in D-071 below).

**Why:** ARCHITECTURE's WF description of EC-A8 — "the second gated tool call returns `gate_busy`; Claude queues it and re-proposes after the first resolves" — describes a *rejection-and-retry* mechanism. Given the app's actual concurrency shape, that mechanism's trigger condition (two gate opens racing) cannot occur, so the visible behaviour is different but arguably better: the model's *second* gated tool call, in the same response, simply doesn't run until the first one's entire human-approval round trip completes — sequential resolution rather than a rejected-and-requeued one, with no wasted round trip. D-006 already flagged this exact scenario as unresolved ("whether one-gate-at-a-time is too restrictive in practice... needs testing") without committing to a mechanism; this entry is that decision, made honestly rather than by claiming an untested rejection path is exercised. The `Busy` branch stays in the code — R3's fail-closed instinct says a future caller (a second UI surface, a background job) should not be able to violate "one gate at a time" by construction, and it is real, tested code, just not reachable from today's call graph.

**Uncertain:** Whether a future change (e.g., dispatching parallel tool calls concurrently, for latency) would make `Busy` reachable and whether the resulting UX — a `gate_busy` tool_result telling the model to "wait and re-propose" — is actually good then. Revisit if `AgentLoop`'s dispatch loop ever stops being sequential.

**Files:** `agent/.../ConfirmationGate.kt`, `agent/src/test/.../ConfirmationGateTest.kt`, `agent/.../AgentLoop.kt` (unchanged dispatch loop — see its own updated doc comment).

---

## D-071 — Confirmation is a click, never a spoken "yes"; talking during an open gate queues rather than cancels it — resolves D-048's flagged gap
**Date:** 2026-09-01

**Decided:** Every button in `ProposalWindow` — Approve, Sounds right / Let me retype it, Cancel, and the final red Send/Create — is a click. Nothing anywhere accepts a spoken "yes" as an irreversible-action confirmation. `VoiceController.MicState` gains `AWAITING_CONFIRMATION`; `onTalkDown()` during that state begins a normal recording (no `cancellation.cancel()`), which then queues on the existing `turnMutex` behind the in-flight turn until its open gate resolves.

**Why:** R9 permits exactly two typing/click exceptions. D-063 already had to argue at length that a *spoken* "yes" for the EC-G2 cost ceiling doesn't count as a third exception — the argument there was specifically that continuing to spend money is "neither irreversible nor a verbatim field." Sending an email or creating an event is irreversible by definition, so that argument is unavailable here; treating a spoken "yes" as a send/create confirmation would be a genuine, unjustified third exception. WF-2/WF-3's own diagram language ("TTS: ... {User action}") is ambiguous about the channel; this entry resolves it in the only direction R9 supports.

`AgentLoop.Cancellation`'s doc has said "re-examine at Step 5" since Step 3, flagging that cancelling with a proposal in flight was a ledger question. The answer: don't cancel it. `cancellation.cancel()` sets a flag `AgentLoop.run` only checks *between* calls, never during one — so even if something did call it while a call was suspended inside `ConfirmationGate.submit`, the open gate would be unaffected; the risk was never a crash, it was a *UX* one — discarding a real "sent" outcome the instant it resolves, because the top-of-loop check would immediately read `end = CANCELLED` and blank `spokenText` (see `AgentLoop.run`'s cancellation branch). Not calling `cancel()` at all during `AWAITING_CONFIRMATION` sidesteps that risk entirely, at the cost of the new recording's *turn* not starting until the open one finishes — which is the same "one gate at a time" property D-070 already establishes by other means, so nothing new is lost.

**Uncertain:** Whether waiting behind an open gate (rather than, say, some other explicit "later" affordance) feels right in practice for someone who wanted to say something unrelated while a proposal sits open. Untested against a real user.

**Files:** `app/src/main/kotlin/com/secondbrain/app/voice/VoiceController.kt`, `ProposalWindow.kt`, `agent/.../AgentLoop.kt` (doc only), `agent/.../ConfirmationGate.kt` (cancel()/withPending()'s EXECUTING guards).

---

## D-072 — EC-C2 is now actually wired end to end: `AgentLoop.run` takes the utterance's own instant and zone; `TurnClock` carries it to `CalendarTools`
**Date:** 2026-09-01

**Decided:** `AgentLoop.run` gains `utteranceAt: Instant` and `zone: ZoneId` parameters (defaulted to call-time for source compatibility with `AgentLoopTest` and `CaptureHarness`, neither of which has a real recording to time-stamp). `VoiceController.runTurn` passes `utterance.startedAt`/`utterance.zoneId`. `SystemPrompt.userTurn` takes the instant as a parameter instead of defaulting to `Instant.now()` at call time. A new `TurnClock` (tiny, `:agent`) holds the current turn's `(Instant, ZoneId)`, set once at the top of `run`; `CalendarTools` reads it when resolving relative time.

**Why:** `Utterance.startedAt`'s own doc comment has said since Step 1 — "EC-C2: 'tomorrow' spoken at 23:58 must resolve against the timestamp of the *utterance*, not of the API call that eventually processes it... The calendar workflow is Step 6, but the field is free today and a painful retrofit later." That retrofit is now due, and checking confirmed it actually was needed: `AgentLoop.run` never took a timestamp parameter at all, and `SystemPrompt.userTurn`'s `now` parameter defaulted to `Instant.now()` evaluated inside `run()` — close to "STT finished," not "recording started," and on a turn with one or two `ask_user` round trips, that gap is minutes, which is exactly the window EC-C2 exists to get right.

`TurnClock` exists because `ToolSpec.handler`'s signature (`suspend (String) -> ToolOutcome`, fixed since Step 3) has no room for per-turn context, and changing it would touch every one of the seven already-registered vault tools for a need only the two new calendar tools have. A small mutable holder set once per turn, read by the one tool group that needs it, is the same trick this codebase already uses for other cross-boundary state (`lateinit var voiceController` in `Main.kt`, the `@Volatile` fields in `VoiceController`) — and `VoiceController.turnMutex` already guarantees exactly one turn touches it at a time, so there is no new synchronisation to get wrong.

**Uncertain:** Nothing about the mechanism. Whether resolving the system prompt's "Current date and time" line against recording-start rather than call-time changes anything observable outside of calendar (it's a few seconds' difference in the common case) is unmeasured and probably immaterial.

**Files:** `agent/.../AgentLoop.kt`, `agent/.../TurnClock.kt`, `agent/.../SystemPrompt.kt`, `app/.../VoiceController.kt`.

---

## D-073 — Ambiguity resolution is its own autonomous tool, called before the gated propose; ledger idempotency is our own state machine, not a server-side key
**Date:** 2026-09-01

**Decided:** `calendar_resolve_time` (AUTONOMOUS) takes the pieces of a spoken time expression and returns either a resolved absolute range or a spoken question; the model calls `ask_user` with that question and calls `calendar_resolve_time` again. `calendar_propose_event` (GATED) only ever accepts an already-resolved absolute start/end/zone — it never lets Claude supply relative language or compute a timestamp. `calendar_find_conflicts` (AUTONOMOUS) is available separately but `calendar_propose_event` always re-checks conflicts internally regardless of whether the model called it, so EC-C4 can never be silently skipped by an omitted call.

Idempotency against a double-send/double-create is entirely `ActionLedger`'s state machine — `EXECUTING` written before the adapter call, nothing ever auto-retrying `UNKNOWN`/`FAILED` (R5) — not a server-side deduplication token. Gmail's `messages.send` has no such token at all. Calendar's `events.insert` gets the ledger's `proposal_id` written into an extended property anyway, as defence in depth for a manual audit, but that property is never read back by anything in this codebase to decide whether to skip a create.

**Why:** WF-3's own diagram order is Time Resolver → ambiguity → `ask_user` loop → *then* `calendar_propose_event`; splitting resolution into its own tool is what makes that order enforceable rather than aspirational; a single tool that both resolves time and creates the proposal would let the model skip straight past an ambiguity by supplying a guessed absolute time, which is precisely what D-010 says never to allow. Re-checking conflicts inside `calendar_propose_event` itself (rather than trusting the model to have called `calendar_find_conflicts` first) is the same "belt and braces" instinct as D-034's atomic-write retry-then-fallback: a warning that depends on the model remembering an extra step is not a warning that is actually always shown.

**Uncertain:** The resolver's ambiguity vocabulary (`HOUR_12_OR_24`, `MISSING_DATE`, `MISSING_DURATION`) may miss a real case once actual speech is thrown at it — same footing as every other v1 threshold here.

**Files:** `agent/.../CalendarTools.kt`, `model/.../TimeResolver.kt`, `ports/.../MailPort.kt`, `ports/.../CalendarPort.kt`.

---

## D-074 — OAuth tokens get their own file, not `app.db`; `ActionLedger` reconciles on startup exactly per EC-A9
**Date:** 2026-09-01

**Decided:** `oauth_tokens` is a table in its own SQLite file (`oauth_tokens.db`, path configurable via `google.token_store_path`), owned entirely by `:integrations`' new `TokenStore`. This supersedes ARCHITECTURE §2, which places `oauth_tokens` inside `app.db`. `ActionLedger.reconcileOnStartup()` runs once in `Main.kt`, before anything else touches the ledger: `PROPOSED`/`APPROVED` rows become `CANCELLED`, `EXECUTING` rows become `UNKNOWN` — verbatim EC-A9 and R5's "nothing re-executes on restart."

**Why:** `:integrations` cannot depend on `:agent`'s `AgentDb` or `:vault`'s `AppDb` — no such edge exists in §1, and adding one would be exactly the kind of violation `verifyModuleGraph` exists to catch. `app.db` already has two schema owners after D-026/D-045 (`:vault`'s `folder_decisions`, `:agent`'s conversations/messages/cost_meter/now action_ledger); giving OAuth tokens a third, cross-cutting home inside a file neither module that would write it can reach is worse than one small dedicated file. Unlike the action ledger, `oauth_tokens.db` is not R10-precious — losing it costs one more consent-screen click, not data.

Reconciliation was verified safe against `ConversationStore` specifically, not just asserted: a turn's messages are only persisted once `AgentLoop.run` returns (`VoiceController.runTurn` calls `store.recordTurn` after `agentLoop.run(...)`, never before), so a crash while a gate is open leaves no orphaned `tool_use`/`tool_result` pairing in the conversation history to repair on restart — only the ledger row, which reconciliation now handles.

**Uncertain:** Nothing about the mechanism (both are directly unit-tested — see `ActionLedgerTest`). Whether the interrupted-`EXECUTING` case (an email that genuinely sent, but the app died before recording `DONE`) will ever actually surface to a real user, and whether "the ledger says UNKNOWN, check manually" is a good enough answer for that, is unmeasured until it happens once for real.

**Files:** `integrations/.../TokenStore.kt`, `agent/.../ActionLedger.kt`, `app/.../Main.kt`, `agent/src/test/.../ActionLedgerTest.kt`.

---

## D-075 — Real Google OAuth via the official Java client library, two least-privilege scopes, optional at startup; Gmail's raw message is hand-built, not via a mail library
**Date:** 2026-09-01

**Decided:** `:integrations` depends on `google-api-client`, `google-oauth-client-jetty`, `google-api-services-gmail`, `google-api-services-calendar` (versions found against Maven Central at implementation time — see the `libs.versions.toml` comment; unverified against a real build, since this environment has no JDK). `GoogleAuth` runs the interactive "loopback IP address" consent flow (`LocalServerReceiver`, opens a browser once) on first use, persists only the resulting access/refresh tokens to `TokenStore` (bypassing the library's own on-disk `DataStoreFactory` entirely), and reconstructs a `Credential` from those tokens on every later run, refreshing a minute early. Exactly two scopes are ever requested: `gmail.send` and `calendar.events` — this app cannot read or search Gmail even if a bug tried to. `google.client_id`/`client_secret` are optional in config: blank means `Main.kt` logs a warning and does not register `email_draft`/the calendar tools at all, rather than failing startup the way a missing `agent.api_key` does.

`GmailAdapter` builds the raw RFC 2822 message (`To`/`Cc`/`Subject`/body, base64url-encoded) by hand rather than pulling in a JavaMail/Jakarta Mail dependency for it.

**Why:** The official client library was chosen over a hand-rolled REST caller for the same reason D-044 chose the Anthropic SDK over hand-rolled JSON: OAuth token refresh, retry-on-5xx and request signing come for free instead of being a second thing to get subtly wrong. `TokenStore` bypassing the library's own persistence is deliberate, not an oversight — see D-074: the whole point was one file, at a path this app controls, not wherever `FileDataStoreFactory` defaults to.

Google is optional, unlike Claude/Gemini, because Steps 3-4's voice capture is a complete, useful product with no Google account at all — failing startup over a feature the user may not want yet would regress that. This is a narrower, later check than EC-G1's "named, actionable error at startup"; the feature is opt-in by credential presence, and the first *use* of `email_draft` without credentials configured simply never happens because the tool was never registered — there is no runtime 401 to discover mid-conversation, which is what EC-G1 actually protects against.

The hand-built raw message avoids a real dependency (JavaMail's MIME tree, attachments, multipart — none of which this app has any use for) for a ~20-line job, the same call `ConfigLoader`'s hand-rolled TOML reader (D-012) and the not-yet-built Zepto MCP client (D-009/D-023) already made for themselves. RFC 2047 encoded-word handling for a non-ASCII subject is included because EC-V5 means a transcript-derived subject genuinely can be mixed-script.

**Uncertain:** Everything here is built and, per this sandbox having no JDK, has compiled in the author's head and against documented API shapes but not against a real build or a real Google account — the same class of gap D-056/D-065/D-067 already logged for other external services, now applying to this one too. The library versions in `libs.versions.toml` were found via web search at implementation time and may need a bump the first time `./gradlew.bat build` actually runs against them. `EventDateTime`'s all-day date format and `Event.ExtendedProperties.setPrivate`'s exact generated-model shape are believed correct from the Calendar API's long-stable JSON schema but are unverified against a live response.

**Files:** `integrations/build.gradle.kts`, `gradle/libs.versions.toml`, `integrations/.../GoogleAuth.kt`, `GmailAdapter.kt`, `CalendarAdapter.kt`, `TokenStore.kt`, `model/.../AppConfig.kt` (`GoogleConfig`), `config.example.toml`, `app/.../Main.kt`.

---

## D-076 — `request_typed_input` is two mechanisms: a general model-callable tool, and `ProposalWindow`'s own inline retype — D-054 registered neither, this registers both
**Date:** 2026-09-01

**Decided:** `request_typed_input` (AUTONOMOUS, registered by `EmailTools`) lets the model itself ask for a typed value at any point in a turn — `VoiceController.handleTypedInput` speaks the prompt and suspends on a `CompletableDeferred`, exactly mirroring `handleAskUser`'s pattern but resolved by a keyboard overlay (`TypedInputOverlay`) instead of the next spoken utterance. Separately, `ProposalWindow`'s own verbatim-verify stage has its own "Let me retype it" inline text field per address field, which never goes through the model at all — it calls `ConfirmationGate.retypeVerbatim` directly, because the user is already looking at the exact field that needs correcting and a model round trip would add nothing.

**Why:** D-054 explicitly deferred this: "`request_typed_input` is listed as autonomous in §4 but belongs to Step 5... nothing in Step 3 has a verbatim field." Building only the general tool and not the inline correction (or vice versa) would leave one of WF-2's two documented typing moments unimplemented — "ask_user first if you don't [know the recipient]" needs the general tool when the model itself is the one missing information; "the spelled-back address is wrong" needs the inline correction when the *user* is the one flagging it, mid-review, with no new fact for the model to learn.

Both reuse `VaultTools.AskResult` (`Answered`/`NoAnswer`) rather than a parallel type — the distinction "got a usable value" vs "silence/cancelled/failed" is identical to what `ask_user` already needed (D-055), just keyboard-driven.

**Uncertain:** Nothing about the split. Whether the general `request_typed_input` tool's shape validation (email-shape checked inline, everything else just non-blank) is sufficient for a "phone number" or other kind the schema allows is unvalidated — no real call has exercised it.

**Files:** `agent/.../EmailTools.kt`, `app/.../VoiceController.kt`, `app/.../TypedInputOverlay.kt`.

---

## D-077 — The spoken summary is announced by `VoiceController` watching the gate open, not by the model's own reply
**Date:** 2026-09-01

**Decided:** `VoiceController.observeConfirmationGate` speaks `proposal.speechSummary` once, the instant a new proposal id appears in `ConfirmationGate.state` — before setting `MicState.AWAITING_CONFIRMATION`, so the mic-state label reads "Speaking" while the summary plays and only then "Waiting for you."

**Why:** Caught by tracing the actual control flow, not assumed: WF-2/WF-3 say "TTS speaks a SUMMARY of the body" / "TTS: tomorrow, Tuesday the 2nd, noon to 1 PM" as something that happens when the window opens, which reads as if it is the model's own next reply. It cannot be — the model's turn is itself suspended *inside* `ConfirmationGate.submit` for as long as the window is open (that is the entire point of the gate), so no `tool_result` reaches the model, and it gets no opportunity to say anything, until a human has already resolved the proposal. Left unfixed, the user would see the window appear in total silence and only hear anything once they had already clicked through it — exactly backwards from a workflow whose stated purpose is to help someone decide with the window and the narration together. `EmailTools`/`CalendarTools` already had to build a `speechSummary` string for the window's own header for a different reason (EC-T5: never speak the body/description verbatim); this reuses it as the thing actually spoken, from the one place with a real opportunity to speak it independently of the model's turn.

**Uncertain:** Whether re-announcing after a content edit (the summary is static, built once at proposal-creation time and never regenerated by `ConfirmationGate.editField`) would be worth the added noise — untested; the current call is "announce once, on open, and let the window's own text speak for edits from there."

**Files:** `app/src/main/kotlin/com/secondbrain/app/voice/VoiceController.kt`.

## D-078 — `JsonBridge.toJsonText` must not call `JsonValue.toString()`; that produced Java `Map` syntax, not JSON, and it silently broke every tool call, not just malformed ones

**Date:** 2026-09-01

**Decided:** `JsonBridge.toJsonText(value: JsonValue)` now returns `value.convert(JsonNode::class.java).toString()` instead of `value.toString()`. `ClaudeClient.toLlmResponse`'s `LlmBlock.ToolUse` mapping now calls `JsonBridge.toJsonText(it._input())` instead of `it._input().toString()` directly.

**Why:** Live-tested with real billing for the first time this session (D-072 through D-077 all landed before that was true), the very first tool call the running app made — `vault_search` with a one-field input — surfaced this: `ToolDispatcher` logged a clean, non-fatal "invalid input" warning containing `JSON input: {query=Hitler}`, and the very next line was `ClaudeClient` throwing `JsonParseException: Unexpected character ('q'...)` on all 3 retry attempts, exhausting them and reporting "Claude unreachable" to the user — a message that is actively wrong, since Claude answered fine both times. `{query=Hitler}` is `java.util.Map.toString()`'s format (bare keys, `=`), not JSON (`{"query":"Hitler"}`). `com.anthropic.core.JsonValue` (confirmed via `javap` on `anthropic-java-core-2.59.0.jar`, since the SDK ships no source) has no documented guarantee that its own `toString()` round-trips through JSON — it is a generic union wrapper, and inspection showed `ToolUseBlock._input()` (the only accessor `ClaudeClient` had ever called) returns that same generic `JsonValue`, whose `toString()` fell through to the boxed `Map`'s. `.convert(JsonNode::class.java)` asks the SDK to hand back the value through its own registered Jackson binding — `JsonNode.toString()` is a real, guaranteed JSON serialiser, unlike `Object.toString()` on whatever the union happens to be boxing today.

This was never a "malformed tool call from the model" bug, despite every log line pointing at `vault_search`'s input — Claude sent a perfectly normal tool call; this codebase corrupted it turning the SDK's response into `LlmBlock.ToolUse.inputJson`, before `ToolDispatcher` or any tool ever saw it. It would have fired identically on *any* tool call with a string argument, not just this one, and had simply never been exercised end-to-end against the real API before now — every earlier phase of this session either used a fake `LlmPort` in tests or hit a billing/config wall before a tool call round-tripped. The pre-existing `JsonBridge.toJsonText` (already Jackson-`.toString()`-based, used for persistence/logging, never previously called from `ClaudeClient`) had the identical latent bug; fixing it there means both call sites — and any future one — inherit the fix instead of it being re-broken the next time someone reaches for `.toString()` on a `JsonValue`.

**Uncertain:** Whether every `JsonValue` this codebase ever converts is `.convert(JsonNode::class.java)`-safe, or whether some shape (e.g. a bare scalar rather than an object) throws instead of converting — the only call sites are both object-shaped tool inputs, so this is untested for anything else. Separately: `send()`'s retry loop still treats any thrown `Exception` — including a genuine, un-retryable parsing bug, had one existed — as transport-flaky and burns all 3 attempts on it before giving up; this fix removes the only known trigger rather than changing that loop's classification, so a *different* deterministic bug in this path would still misreport as "Claude unreachable" today.

**Files:** `agent/src/main/kotlin/com/secondbrain/agent/JsonBridge.kt`, `agent/src/main/kotlin/com/secondbrain/agent/ClaudeClient.kt`.
