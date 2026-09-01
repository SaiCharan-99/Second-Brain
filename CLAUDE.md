# CLAUDE.md — Second Brain

Operating rules for Claude Code in this repository. Read this file and `ARCHITECTURE.md` before writing any code. If a rule here conflicts with something you infer from the codebase, this file wins — and if the codebase is actually right, fix this file and log it in `DECISIONS.md`.

This repository is **standalone**. The SDLC / BluePrint Lens project lives elsewhere. Do not import from it, reference it, or design for a future merge.

---

## The ten rules

**R1 — Templates render, models fill.**
The LLM produces structured data (`NoteDraft`, `EmailProposal`, `GroceryList`). Deterministic Kotlin functions produce bytes. `NoteRenderer` is the only thing in this codebase that emits `.md` content. If you are about to ask a model for raw file content, stop.

**R2 — Two tool classes, declared at registration.**
Every tool is `AUTONOMOUS` or `GATED`. There is no third class and no runtime promotion. Gated tools never execute from a model call; they create a `Proposal` and suspend the loop. **There is no `email_send` tool, no `calendar_create` tool, no `place_order` tool that the model can invoke directly.** Adding one, even temporarily for testing, is not allowed.

**R3 — Fail closed on classification.**
Any tool whose mutation status is unclear — especially dynamically bridged MCP tools — defaults to `GATED`. A read-only tool wrongly gated costs one click. A mutating tool wrongly autonomous costs money or sends the wrong email.

**R4 — Every path goes through `PathSafety`.**
Every path-bearing argument from a model, on every tool, without exception. Canonicalise against the vault root; reject anything that escapes, including via symlink. This is not defence in depth, it is the only defence.

**R5 — The ledger is the source of truth for irreversible actions, and it never auto-retries.**
`proposal_id` is the idempotency key. Write `EXECUTING` before the external call, `DONE`/`FAILED` after. A lost response becomes `UNKNOWN` and is surfaced to the user. On startup, orphaned `PROPOSED`/`APPROVED` rows become `CANCELLED` and orphaned `EXECUTING` rows become `UNKNOWN`. Nothing re-executes on restart.

**R6 — Approved means the payload the user saw, not the payload the model drafted.**
When the user edits a field and approves, snapshot the **edited** payload to the ledger and execute that. Content edits reset approval to `PROPOSED`. Verbatim-field edits do not.

**R7 — Caps and thresholds live in config, never in prompts.**
Tool-call iteration cap, `vault_tree` depth, Folder Guard similarity threshold, top-level folder cap, spoken-response length cap, session cost ceiling — all in `AppConfig` / `VaultConfig`. A prompt asking a model to "keep it short" is not a cap.

**R8 — Phase boundaries are hard context resets.**
`CAPTURE`, `EMAIL`, `CALENDAR`, `COMMERCE`, `QUERY`. Transitions reset to the system prompt plus a one-paragraph carry-over. Within a phase, keep the last 8 turns plus a rolling summary. After any gated action resolves, the phase ends.

**R9 — Speech is the default, typing is the exception, and there are exactly two exceptions.**
Verbatim fields (email addresses, phone numbers, ambiguous quantities) and confirmation clicks. Any change that adds a third goes in `DECISIONS.md` with a justification.

**R10 — `index.db` is disposable, `app.db` is precious, audio is never deleted before its transcript commits.**
Never store unrecoverable state in `index.db`. Never store keys in either. Never delete a session WAV until its transcript is written to disk.

---

## Module map

| Module | Owns | May depend on |
|---|---|---|
| `:model` | Data classes only, plus pure logic with zero I/O. `NoteDraft`, `Proposal`, `Utterance`, `AppConfig`, `TimeResolver`/`ResolvedTimeRange` (moved here from `:vault` — D-069: `:agent`'s calendar tools need it directly and cannot depend on `:vault`). | kotlinx-serialization only |
| `:ports` | Interfaces only. `SttPort`, `TtsPort`, `VaultStore`, `MailPort`, `CalendarPort`, `CommerceAdapter`, `LlmPort`. | `:model` |
| `:vault` | `PathSafety`, `Slugifier`, `NoteRenderer`, `FolderGuard`, `LinkResolver`, `AtomicWriter`, `VaultWriter`, `VaultIndex`, `FileWatcher`. | `:model`, `:ports` |
| `:voice` | `JvmAudioCapture`, `JvmAudioPlayback`, `VoiceGate`, `GeminiStt`, `KokoroTts`, `SpeechNormalizer`. | `:model`, `:ports` |
| `:agent` | `ClaudeClient`, `ToolRegistry`, `ToolDispatcher`, `AgentLoop`, `ConfirmationGate`, `ActionLedger`, `ConversationStore`, `CostMeter`, prompts. | `:model`, `:ports` |
| `:integrations` | `GmailAdapter`, `CalendarAdapter`, `McpClient`, `McpCommerceAdapter`, `FakeCommerceAdapter`, `MutationClassifier`. | `:model`, `:ports` |
| `:app` | Compose UI, `ProposalWindow`, composition root, `main()`. | everything |

**`:agent` must not depend on `:vault`, `:voice`, or `:integrations`.** It talks to ports. `:app` is the only module that knows concrete implementations exist. If you find yourself adding an edge outside this table, you have found a missing port, not a missing dependency.

---

## Build order

Do not start a step before the previous step's exit criteria in `ARCHITECTURE.md` §7 pass.

1. Skeleton, config, voice loop (no LLM) — includes three blocking spikes
2. Vault core (no LLM) — 40+ unit tests
3. Agent loop + vault tools — **the walking skeleton and the main quality gate**
4. Dashboard UI
5. Confirmation gate + email
6. Calendar
7. Zepto MCP + grocery ordering

---

## DECISIONS.md discipline

Append-only. Never edit or delete a past entry; supersede it with a new one. Every entry has all four sections:

```markdown
## D-00N — <short title>
**Date:** YYYY-MM-DD

**Decided:** what was chosen, in one or two sentences.

**Why:** the reasoning, including what was rejected and on what grounds.

**Uncertain:** what remains unvalidated. Write "nothing" only if that is true.

**Files:** the paths this touches.
```

Log an entry for: every architectural choice, every spike result, every threshold tuned against real data, every accepted risk, every superseded decision. Spike results are decisions — an answer to "what does the Kokoro endpoint actually return" belongs here.

---

## Working style

- **Make the decision, then explain it.** You are the technical lead here, not a menu. When there is a choice, pick one, say why, and say what you rejected. If you genuinely cannot choose without information only Udit has, ask one specific question rather than listing options.
- **Push back when the design is wrong.** Including when it is a design in this file. Being agreeable at the cost of being right is not useful.
- **No padding.** No "great question", no restating the request, no summarising what you just wrote unless asked.
- **Never design ahead of what is validated.** Three of the six external services in this system are unproven. Do not build abstractions for capabilities you have not confirmed exist.
- **One unknown at a time.** If a change stacks two or more unvalidated dependencies, say so explicitly and propose a way to test one of them first.
- **Log as you go.** Thinking, notable bash commands, decisions, file changes.

---

## Testing expectations

| Module | Bar |
|---|---|
| `:vault` | Near-exhaustive unit tests. This is the module that must not lose data. Path traversal, slug collisions, Folder Guard thresholds, link resolution boundaries, atomic-write crash simulation, index rebuild. |
| `:agent` | Tool dispatch, schema validation, iteration cap, gate suspend/resume, ledger state machine including every crash-recovery path. Fake `LlmPort`. |
| `:integrations` | `FakeCommerceAdapter` with seeded stock-outs and price changes. Mutation Classifier over a fixture set of tool names. |
| `:voice` | Manual + latency measurement. Audio is hard to unit test; measure instead. `SpeechNormalizer` gets real unit tests. |
| `:app` | Manual. Compose UI tests are not worth the time on this timeline. |

Every edge case ID in `ARCHITECTURE.md` §6 needs either a test or an explicit accepted-risk entry in `DECISIONS.md`. Not both, but never neither.

---

## Hard prohibitions

- Never write an API key into a log, a transcript, a note, a commit, or a chat message.
- Never add `config.toml` to git. Verify `.gitignore` before writing the first key.
- Never auto-retry an irreversible action.
- Never fabricate an email address, phone number, or product substitution.
- Never speak an email body verbatim. Summarise; the text is on screen.
- Never demo `FakeCommerceAdapter` without a visible label saying it is fake.
- Never add a direct-execution tool for a gated action, even behind a debug flag.
