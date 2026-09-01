# Acte.me

Second Brain is a voice-first Windows desktop assistant for capturing knowledge, browsing a Markdown vault, drafting email, scheduling calendar events, and building grocery orders from speech or images.

The application combines Gemini speech services, Claude tool use, a local Markdown vault, Google integrations, and an optional Zepto MCP commerce workflow. Irreversible actions are never exposed as ordinary model tools: email, calendar insertion, and order placement pass through a visible confirmation window and a durable action ledger.

## What it can do

- Capture spoken thoughts and organize them as Markdown notes.
- Browse notes, folders, backlinks, and unresolved wikilinks in a three-pane vault UI.
- Attach an image or capture one from the laptop camera, then describe what to do with it by voice.
- Convert photographed handwritten material into a Markdown note.
- Recognize a photographed product and search for related catalogue products.
- Read a photographed grocery list, search several categories concurrently, and display product comparisons.
- Save chosen products and quantities in a persistent Saved Cart across sessions.
- Move selected Saved Cart items to Zepto's cart before entering the existing guarded order workflow.
- Draft email and calendar events when optional Google OAuth credentials are configured.
- Speak responses, support push-to-talk and barge-in, and fall back to platform speech when configured.

## Image and shopping workflows

### Grocery list

1. On the Voice screen, choose **Camera** or **Attach a photo**.
2. Capture or select the grocery-list image.
3. Hold the microphone and ask for product options, quantities, prices, or a budget.
4. Review the comparison window and save the preferred product from each category.
5. Open **Shopping** to adjust quantities and select what to buy now.
6. Add the selected lines to the provider cart.
7. Return to Voice and ask to place the order. Review the final server cart and explicitly confirm it in the order window.

### Product recognition

Capture a product and say something such as:

> Find ten stainless-steel bottles similar to this and let me save one for later.

Recognition alone never adds or orders an item. A specific catalogue result and quantity must be selected.

### Handwritten note

Capture the page and say:

> Convert the information in this image to Markdown and store it in my Second Brain.

The note follows the same rendering, atomic-write, indexing, and link-resolution path as voice-created notes.

## Safety model

The repository treats side effects differently from read-only or reversible operations:

- Vault searches, catalogue searches, and reversible cart preparation may run autonomously.
- Email send, calendar insert, and order placement require a `ConfirmationGate` proposal.
- Every gated action is recorded in `ActionLedger` before execution.
- A lost response to an irreversible action becomes `UNKNOWN`; it is never retried automatically.
- The cart is reread from the provider before an order proposal is shown.
- Product substitutions are never silent.
- Cash on delivery is the only supported payment path.
- A configurable order ceiling requires additional acknowledgement.

> [!WARNING]
> Zepto's MCP server has no sandbox. With `commerce.enabled = true` and `commerce.use_fake = false`, an approved order is a real order. Start with the fake catalogue.

## Architecture

The project uses seven Gradle modules with enforced dependency direction:

```text
:model  <-  :ports  <-  :agent
                    ^
          :vault / :voice / :integrations
                    ^
                   :app
```

| Module | Responsibility |
|---|---|
| `model` | Pure domain models, configuration, proposals, commerce values, and workflow state |
| `ports` | Interfaces for storage, speech, LLMs, Google services, and commerce |
| `vault` | Markdown rendering, safe paths, SQLite index, folder guard, links, and file watching |
| `voice` | Audio capture/playback, speech-to-text, text-to-speech, sessions, and device handling |
| `agent` | Claude loop, tool registry, conversation store, confirmation gate, ledger, and Saved Cart store |
| `integrations` | Gmail, Google Calendar, Zepto MCP, OAuth token stores, and deterministic fake adapters |
| `app` | Compose Desktop UI and the composition root |

`verifyModuleGraph` fails the build if a module adds a forbidden dependency edge.

For the complete design and decision history, read [ARCHITECTURE.md](ARCHITECTURE.md), [DECISIONS.md](DECISIONS.md), and [CLAUDE.md](CLAUDE.md).

## Requirements

- Windows 10 or 11
- JDK 17
- A microphone for voice capture
- A webcam for live capture; image file upload remains available without one
- A Gemini API key for speech-to-text and Gemini text-to-speech
- An Anthropic API key for the agent
- Optional Google Desktop OAuth credentials for Gmail and Calendar
- Optional Zepto account for the live commerce adapter

The project is built with the included Gradle wrapper; a separate Gradle installation is unnecessary.

## Quick start

Run these commands from PowerShell in the repository root.

1. Confirm Java 17 is active:

   ```powershell
   java -version
   ```

2. Create the application directory and copy the configuration template:

   ```powershell
   New-Item -ItemType Directory -Force "$env:USERPROFILE\.secondbrain"
   Copy-Item .\config.example.toml "$env:USERPROFILE\.secondbrain\config.toml"
   ```

3. Edit `%USERPROFILE%\.secondbrain\config.toml` and set:

   ```toml
   [stt]
   api_key = "your-gemini-key"

   [tts]
   api_key = "your-gemini-key"

   [agent]
   api_key = "your-anthropic-key"
   ```

   The same Gemini key can normally be used for both speech sections. Do not commit `config.toml` or any token database.

4. Run the desktop application:

   ```powershell
   .\gradlew.bat :app:run
   ```

Configuration values can also be supplied as `SECONDBRAIN_<SECTION>_<KEY>` environment variables. Environment variables take precedence over `config.toml`; for example, `SECONDBRAIN_AGENT_API_KEY` overrides `[agent].api_key`.

## Optional integrations

### Gmail and Google Calendar

Create an OAuth 2.0 **Desktop app** client in Google Cloud, enable the Gmail and Google Calendar APIs, and set:

```toml
[google]
client_id = "..."
client_secret = "..."
```

The app requests only `gmail.send` and `calendar.events`. Leaving these fields blank keeps the rest of the application working and omits the Google tools.

### Commerce demo

Use the deterministic fake catalogue first:

```toml
[commerce]
enabled = true
use_fake = true
```

The UI labels fake-commerce proposals as demo data. It includes deterministic stock and price behavior for safe end-to-end testing.

### Live Zepto MCP

After validating the fake workflow:

```toml
[commerce]
enabled = true
use_fake = false
mcp_url = "https://mcp.zepto.co.in/mcp"
order_ceiling_inr = 2000
max_search_results = 10
max_comparison_concurrency = 4
```

Open the app and choose **Sign in to Zepto**. OAuth opens in the browser and stores tokens locally. Product search distinguishes a genuine no-match response from provider, authentication, store-selection, and session failures.

## Using the app

- Hold the on-screen microphone or press and hold `Space`, speak, then release to send.
- Press during speech playback to interrupt it.
- Use **Camera** for a floating live-preview window with capture, retake, camera selection, and cancel controls.
- Use **Attach a photo** when a camera is unavailable or the image already exists.
- A pending image is sent with the next spoken instruction, or can be sent without a spoken caption.
- Use **Vault** to navigate saved notes and backlinks.
- Use **Shopping** to manage products saved from comparison results.
- Review every email, event, or order proposal before confirming it.

## Data and privacy

By default, application state is stored under `%USERPROFILE%\.secondbrain`:

- `vault/` contains Markdown notes and is the durable source of truth.
- `index.db` is a rebuildable vault index.
- `app.db` stores conversations, the action ledger, and Saved Cart state.
- Session audio and logs use paths controlled by `config.toml`.
- Google and Zepto OAuth tokens are stored in separate local SQLite files.

Images, audio, transcripts, and prompts may be sent to the configured cloud providers as required by the selected workflow. Review provider policies before using sensitive material.

## Development

Run all tests and the architectural dependency check:

```powershell
.\gradlew.bat test verifyModuleGraph
```

Run selected verification tasks:

```powershell
.\gradlew.bat :app:cameraSpike
.\gradlew.bat :app:zeptoDiscover
.\gradlew.bat :app:capture --args="thoughts.txt"
```

`zeptoDiscover` requires an existing Zepto sign-in and performs provider discovery/read calls. Inspect the task and current decision record before using it against a live account.

Build a Windows installer:

```powershell
.\gradlew.bat :app:packageMsi
```

Generated packages are written under `app/build/compose/binaries`.

## Design constraints

- `NoteRenderer` is the only producer of persisted Markdown note bytes.
- Paths are validated before vault access; traversal and symlink escapes are rejected.
- Thresholds, model IDs, endpoints, retry counts, and cost limits belong in configuration.
- External systems are accessed through ports and replaceable adapters.
- Existing provider cart state is server truth; Saved Cart is a separate local planning workspace.
- UI code does not call irreversible provider methods directly.
- New architectural exceptions require an append-only entry in `DECISIONS.md`.

## Known limitations

- Desktop packaging is currently Windows-focused.
- Commerce product IDs and availability are tied to the active provider store and may become stale between saving and checkout.
- A live Zepto workflow cannot be safely automated in CI because there is no provider sandbox.
- Handwriting and product recognition depend on image quality and model confidence; unclear input may require clarification.
- Camera support depends on the operating-system driver. File upload is the fallback.

