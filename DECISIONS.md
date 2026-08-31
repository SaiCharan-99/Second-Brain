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
