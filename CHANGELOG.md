# Changelog

All notable changes to Ollama are documented here.

## 0.4.0 - 2026-07-22

### Added

- **A craftable llama companion.** Craft it from vanilla ingredients (white wool, lead, hay bale,
  gold ingot — `/llama recipe` prints the shape), place the summon item, and a llama bound to you
  appears, follows you across chunk boundaries and through nether portals, and can be talked to.
- **Conversation backed by Ollama.** Right-click your companion to open a dialog (a Paper Dialog,
  which Geyser auto-converts to a Bedrock form — no Geyser/Floodgate/Cumulus dependency), or use
  `/llama ask <message>`. The companion carries a distinct personality via a leading
  `role: "system"` message and sees **only the asking player's own state** (inventory, armor,
  hands, health/hunger, biome, dimension, time) — never other players and never anyone's chat
  history. Any command the model proposes is surfaced as click-to-prefill text and is **never
  executed**.
- **Downed, not dead.** At lethal damage the companion collapses and its summon item returns to
  you rather than dying permanently; void damage teleports it back to you; `/kill` still removes it
  for admins.
- **Deterministic, LLM-free nudges.** The companion reminds you (once per cooldown, per player)
  about a carried-but-unequipped shield, missing food, a tool about to break, or no torches — pure
  rules, instant and incapable of hallucinating.
- **Commands:** `/llama ask|recipe|dismiss|give`. `give` is op-gated (`ollama.llama.give`); the
  rest use `ollama.llama.use` (default true).
- **Config:** a new `companion:` block (`enabled`, `invulnerable`, `follow_interval`,
  `teleport_distance`, and `nudges` with `enabled`, `cooldown`, `rules`).

### Changed

- **The companion is decoupled from the Ollama master switch.** Crafting, following, downed
  recovery, and nudges work even when the top-level `enabled: false` or the endpoint is
  unreachable; only conversation needs Ollama, and it degrades to an in-character line rather than
  failing. `onEnable` initializes the companion whenever `companion.enabled` is true, independently
  of `enabled`.

### Notes

- Conversation keeps its **own** per-player history, separate from `/ollama chat`'s session
  manager, so the two personas never blend and no out-of-scope player data can leak into the
  companion prompt.
- Bedrock: custom recipes do not appear in the recipe book (hand-crafting still resolves
  server-side; `/llama recipe` mitigates discoverability); dialog text inputs are single-line with
  an explicit `max_length`; ViaVersion remains a hard runtime requirement.

## 0.3.0 - 2026-07-22

### Fixed

- **The system prompt now reaches the model on `/api/chat`.** `ChatRequest` carried a top-level
  `system` field, which `/api/chat` has never accepted at any Ollama version — the server drops
  unknown keys and returns `200 OK`, so `chatWithSystemPrompt()` had never once worked and there
  was nothing in any log to say so. The prompt is now sent as a leading `role: "system"` message.
  `/api/generate` keeps its top-level `system`, which it genuinely does accept.
- `api.timeout` and `api.max_retries` are applied. Both were read from config and then never used,
  so a stalled Ollama pinned an executor thread indefinitely.
- A non-200 response is no longer parsed and handed to the caller as though it were an answer.
- `ChatRequest.setMessages` defensively copies, so applying a system prompt no longer appends a
  message to the caller's own chat history on every turn.

### Changed

- Replaced Apache HttpClient 4.5.14 with the JDK's `java.net.http.HttpClient`. This removes a
  shaded dependency rather than adding one; 4.5.14 was the terminal 4.x release.
- Added a client-side concurrency gate defaulting to 1, matching `OLLAMA_NUM_PARALLEL`. Surplus
  requests are refused immediately rather than stalling — a warm Ollama model never returns 503.
- `think` is now sent explicitly as `false` for models a `/api/show` probe reports as
  thinking-capable. Since Ollama v0.12.4, omitting it means `true`, so every request against a
  reasoning model silently paid for output that was then discarded.
- `format` is typed as a JSON element rather than a string, so a JSON Schema is expressible.
- Added `done_reason` to both response models: an answer truncated at `num_predict` was previously
  indistinguishable from a complete one.
- The configured model is preloaded on enable, so the first player request does not pay cold-load.
- Maven `groupId` moved `com.carmelosantana` → `org.xpfarm`, matching the Java package. The
  `artifactId`, releasable JAR name, and updater destination are unchanged.
- `plugin.yml` `api-version` `'1.21'` → `'26.1'`, and `version` is now substituted from the POM.
- Gson 2.10.1 → 2.14.0.

### Documented

- ViaVersion is a hard runtime requirement for Bedrock connectivity, not an optional soft
  dependency.

## 0.2.1 - 2026-07-19

### Fixed

- SHA256SUMS.txt now records bare JAR filenames instead of the build-time
  `target/` path, so `sha256sum --check` works against downloaded release assets.

## 0.2.0 - 2026-07-13

### Changed

- Updated the build baseline to Paper 26.1.2 and Java 25.
- Updated Maven compiler and shading plugins for Java 25 bytecode.
- Added GitHub Actions for tests, release JARs, SHA-256 checksums, and tagged releases.
- Made integration opt-in and resilient when the Ollama endpoint is unavailable.\n- Disabled generated command execution by default.

### Tested

- Paper 26.1.2 build 74
- Geyser 2.11.0
- Floodgate 2.2.5 build 138
- ViaVersion 5.11.0
