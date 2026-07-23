# New or Edited Plugin Checklist

Leave an unchecked box with a short explanation when a gate is not complete; do not silently remove
inapplicable checks.

- Plugin name: `Ollama`
- Slug: `ollama`
- Repository: `carmelosantana/minecraft-ollama`
- Owner: `Carmelo Santana`
- Target version: `0.3.0` (then `0.4.0` — see Scope; both releases share this §1)
- Paper version: `26.1.2 build 74`
- Java version: `25`
- Updater destination: `ollama.jar`
- External services: `Ollama HTTP API` — opt-in, `enabled: false` by default
- Status: `active`
- Autonomy: `autonomous`

Maven `groupId`/`artifactId`: `org.xpfarm` / `ollama` — **the group moves from `com.carmelosantana`
in 0.3.0** (see gate 3). `artifactId`, releasable JAR (`ollama-<version>.jar`), and updater
destination (`ollama.jar`) are unchanged by that move, so the updater manifest needs no edit.
`plugin.yml` name: `Ollama`, main class `org.xpfarm.ollama.OllamaPlugin`.

---

## READ THIS FIRST — scope of this file

This checklist covers the **llama companion feature**, planned 2026-07-22, shipping as two releases:

- **`0.3.0`** — Ollama HTTP client currency and correctness. Prerequisite, independently shippable.
- **`0.4.0`** — the craftable llama companion itself.

The prior checklist for `v0.2.1` was a **backfill** written 2026-07-21 and is preserved verbatim at
`docs/PLUGIN_CHECKLIST-0.2.1-backfill.md`. It carries the only trustworthy runtime evidence this
plugin has ever produced (the 2026-07-20 rig boot and the 2026-07-19 / 2026-07-21 matrix runs) and
is cited from `xpfarm-plugin-toolkit/CURRENT_STATE.md`. **Do not treat that file as superseded** —
gates 7a and 7b in it remain the plugin's runtime record until this cycle produces new evidence.

Design document: `docs/superpowers/specs/2026-07-22-llama-companion-design.md`. It carries the
research citations behind every non-obvious decision below.

**Autonomy is `autonomous`**, chosen by Carmelo Santana on 2026-07-22 during the gate 1 interview.
That choice **is** the GitHub push authorization for this entire pipeline, granted once, in writing,
here. Downstream skills do not re-prompt before pushing, tagging, releasing, enrolling, or
deploying. Evidence standards are unchanged: a failed `mvn verify`, a red or still-running Actions
run, a checksum mismatch, a plugin that does not enable, or a failed updater dry-run halts the
pipeline and reports what failed.

---

## 1. Scope

- [x] Status is explicitly recorded as active, experimental, or excluded. **`active`.** The plugin
      is enrolled in `minecraft-plugin-updater/plugins.json` unpinned and enabled, and appears in
      the Active Plugin Releases table of `xpfarm-plugin-toolkit/CURRENT_STATE.md`. No gates are
      intentionally withheld for either release.
- [x] Purpose, commands, events, permissions, configuration, persistence, and acceptance checks are
      defined. Recorded below, from the 2026-07-22 requirements interview plus two dispatched deep
      research passes.
- [x] Known limitations and any intentionally withheld gates are recorded. See Known limitations
      and Unverified below. **No gates are withheld.**

### Player-facing purpose

A player can craft a llama companion that follows them, that they can talk to, and that notices
things about their gear — like carrying a shield without having it equipped. Asking it a question
gets an answer from the server's Ollama model; its reminders come from fixed rules and work even
when Ollama is switched off.

### Release 0.3.0 — API client currency (no player-facing change)

Fixes and modernizes the Ollama HTTP client so the companion can stand on it. **This release also
discharges follow-up 1 of the 0.2.1 backfill** — the first time `/ollama` is exercised end to end
against a real endpoint in-game.

| Change | Driver |
| --- | --- |
| Delete `ChatRequest.system`; prepend a `role: "system"` message instead | `/api/chat` has never had a top-level `system` field, at any Ollama version. Gin drops unknown keys, so the request returns 200 and the prompt is discarded. `chatWithSystemPrompt()` has never worked. |
| Apache HttpClient 4.5.14 → `java.net.http.HttpClient` | Built into Java 25. Removes a shaded dependency instead of adding one; provides real timeouts and `BodyHandlers.ofLines()`. 4.5.14 is the terminal 4.x release. |
| Apply `api.timeout` and `api.max_retries` | Both are read from config today and never used. |
| Send `think` explicitly (default `false`); parse and discard `thinking` | Since Ollama v0.12.4, **omitting `think` means `true`** on capable models. |
| `format`: `String` → `JsonElement` | As typed it can only send `"json"`; a JSON Schema is impossible. |
| Add `done_reason` to both response models | Otherwise a truncated answer is indistinguishable from a complete one. |
| Status-code dispatch (400/404/499/500/503/timeout) | Today a non-200 is logged and the body parsed and delivered anyway. |
| Concurrency gate — `Semaphore`, default 1 | `OLLAMA_NUM_PARALLEL` defaults to 1, and a **warm** model does not 503 when overloaded; it stalls on a semaphore indefinitely. Overload appears as unbounded latency, never as an error. |
| `/api/show` capability probe, cached per model | Sending `think` to a non-thinking model is a hard 400. |
| Model preload on enable (empty prompt) | Pays the cold-load cost at startup rather than on a player's first message. |
| Gson 2.10.1 → 2.14.0 | Four years behind. |

Metadata corrections in the same release: `api-version` → `'26.1'`, `version: '${project.version}'`,
`groupId` → `org.xpfarm`, and ViaVersion documented as a hard runtime requirement. See gates 3 and 4.

### Release 0.4.0 — the companion

New package `org.xpfarm.ollama.companion`. The llama is **downed, not killed**: at lethal damage it
collapses, despawns, and returns the summon item, rather than dying permanently or being immortal.

**Commands**

| Command | Arguments | Permission | Who |
| --- | --- | --- | --- |
| `/llama ask` | `<text>` | `ollama.llama.use` | everyone |
| `/llama recipe` | — | `ollama.llama.use` | everyone |
| `/llama dismiss` | — | `ollama.llama.use` | everyone |
| `/llama give` | `[player]` | `ollama.llama.give` | op |

`/llama give` is an admin and test affordance, not a player-facing bypass of crafting. It is also
how gate 7a summons a companion without crafting one.

`plugin.yml` command descriptions stay short: Bedrock 1.21.130+ caps command name and description
length, and overlong descriptions have historically prevented Bedrock clients from joining at all.

**Events**

| Event | Why |
| --- | --- |
| `PlayerInteractAtEntityEvent` | Right-click to open the conversation. The parent `PlayerInteractEntityEvent` is `@ApiStatus.Obsolete` on 26.x. Guarded with `getHand() != EquipmentSlot.HAND` — a single Bedrock tap fires up to two interact packets. |
| `EntityDamageEvent` (`HIGHEST`, `ignoreCancelled`) | Downed state. Lethal → cancel, `setHealth(1)`, `setAware(false)`, despawn, return item. `VOID` → cancel **and** teleport back. `KILL` passes through so `/kill @e` still works for admins. |
| `PlayerPortalEvent`, `PlayerChangedWorldEvent` | Mobs do not follow through portals; the companion is teleported explicitly via `teleportAsync`. |
| `PlayerJoinEvent` | `discoverRecipe()` — `Bukkit.addRecipe` alone does not populate a recipe book. |
| `CraftItemEvent` / `PrepareItemCraftEvent` | Server-side validation of the summon craft. |
| `EntityMountEvent` | Backstop cancel; right-click with an empty hand mounts a vanilla llama. |

**Permissions**

| Permission | Default | Gates |
| --- | --- | --- |
| `ollama.llama.use` | `true` | Crafting, summoning, conversing with your own companion |
| `ollama.llama.give` | `op` | `/llama give` |

**Configuration** — new `companion:` block

| Key | Type | Default | Validation |
| --- | --- | --- | --- |
| `companion.enabled` | boolean | `true` | — |
| `companion.invulnerable` | boolean | `true` | Baseline only; downed handling is always active |
| `companion.follow_interval` | int (ticks) | `10` | 1–100 |
| `companion.teleport_distance` | int (blocks) | `24` | 8–128 |
| `companion.nudges.enabled` | boolean | `true` | — |
| `companion.nudges.cooldown` | int (seconds) | `300` | ≥ 30 |
| `companion.nudges.rules` | list | `[shield, food, tool_durability, torches]` | Unknown names rejected at load with a logged warning |

Conversation additionally requires the existing top-level `enabled: true` and a reachable endpoint.
Crafting, following, downed/revive, and nudges do not.

**Persistence**

Entity PDC only — Paper writes a `BukkitValues` compound into entity NBT, which survives chunk
unload and restart with no custom storage layer. The companion's UUID is stored in the owner's
player PDC as a `STRING` (there is no `PersistentDataType.UUID`); the owner's UUID is stored in the
mob's PDC. Resolution is `Bukkit.getEntity(uuid)` — O(1), does not load chunks. **A `null` result
means the chunk is unloaded, not that the companion was deleted**; the binding is dropped only on an
explicit removal event. `world.getEntities()` is never scanned. Nothing is written to disk directly.

**Dependencies**

Unchanged hard dependencies: none. `softdepend: [ViaVersion]` stays, but ViaVersion is promoted in
documentation to a **hard runtime requirement** — Geyser emulates a Java 26.2 client and this server
is 26.1.2, so ViaVersion is what bridges the gap. No Geyser, Floodgate, or Cumulus dependency is
added: the Paper Dialog API is auto-converted to Bedrock forms by Geyser, so one code path serves
both editions. Shading Cumulus is a documented `LinkageError` generator and is not done.

**External integrations**

`Ollama HTTP API` only — unchanged in kind. Still opt-in and `enabled: false` by default, still
bounded by `api.timeout` (which 0.3.0 makes real rather than decorative), and now additionally
bounded by a client-side concurrency gate. No credential field exists in config. No new external
service is introduced by either release.

### Acceptance checks

Testable pass/fail conditions. These become gate 6 unit tests and gate 7a runtime verification.

**0.3.0**

1. A chat request built with a system prompt places it as a `role: "system"` message and carries no
   top-level `system` field.
2. `api.timeout` elapsing aborts the request and surfaces an error, rather than pinning a thread.
3. `think` is present in every chat and generate request body.
4. A model without the `thinking` capability is never sent `think: true`.
5. `format` set to a JSON Schema object serializes as an object, not a string.
6. 400, 404, 499, 500, 503, and timeout each map to their distinct documented action.
7. With the gate at 1, a second concurrent request is rejected with a player-visible message rather
   than queued.
8. `/ollama chat` returns a real generated answer from a live endpoint, in-game, on a stack.

**0.4.0**

9. The recipe produces exactly one PDC-tagged summon item from vanilla ingredients.
10. Placing the summon item spawns a llama bound bidirectionally to the placing player.
11. The companion follows across chunk boundaries and through a nether portal.
12. Lethal damage returns the summon item and does not kill the entity; `/kill` does kill it.
13. Void damage returns the companion to the owner rather than losing it.
14. The companion survives a full server restart and is re-resolved by UUID.
15. Right-clicking opens the dialog on Java and on Bedrock.
16. A player carrying a shield with an empty offhand receives the shield nudge; a player with the
    shield equipped does not.
17. With Ollama unreachable, crafting, following, downing, and nudges all still work, and
    conversation fails with an in-character message. The server stays available.
18. The nudge cooldown is honored per player.

### Known limitations

Bedrock, verified against Geyser and Floodgate sources on 2026-07-22:

1. Custom recipes do not appear in the Bedrock recipe book. Hand-placing ingredients works, because
   only the recipe *output* carries custom data and the Java server resolves the craft.
   `/llama recipe` mitigates discoverability.
2. Geyser's recipe-book auto-craft is currently broken for **all** recipes including vanilla —
   open issue GeyserMC/Geyser#6563, opened 2026-07-21.
3. Chat over 256 characters is dropped by Geyser before reaching the server; no chat event fires.
   This bounds free-form prompt length for Bedrock players.
4. No tab completion, ever — Bedrock sends no packet indicating the player is in the chat UI.
5. Dialog text inputs are single-line and default to a 32-character limit; `max_length` must be set
   explicitly and `multiline` is ignored.
6. The summon item renders as a plain vanilla item on Bedrock. `custom_model_data` requires a
   Bedrock resource pack plus hand-written Geyser item mappings, which are out of scope.
7. Off-hand interaction is unreliable (GeyserMC/Geyser#3480). Nothing depends on it.
8. A single Bedrock tap fires up to two interact events; every listener guards on `EquipmentSlot.HAND`.
9. ViaVersion is mandatory, not optional.

General:

10. The downed mechanic has **no vanilla precedent** — wolves die permanently, and Allays are
    damageable (they merely do not despawn). It is entirely plugin-side behavior.
11. Conversation quality depends on the operator's model choice, which this plugin does not control.
12. Streaming responses, tool calling, embeddings, and multiple companions per player are out of
    scope. Streaming and tool calling both become *possible* after 0.3.0 and are the natural 0.5.0
    conversation.

### Unverified — to confirm at runtime, not asserted

- Which Bukkit events CraftBukkit 26.1 fires from the merged interact packet. MCProtocolLib split
  `ServerboundInteractPacket` from `ServerboundAttackPacket` on 2026-03-14; no Paper-side
  documentation of the resulting event behavior was found.
- Whether explosion knockback still displaces an entity with `setInvulnerable(true)`.
- Whether the Bedrock client populates the crafting output slot with zero matching `CraftingData`
  entries. Source reading and two issue reports suggest yes; no authoritative statement found.
- Cold-model-load latency — no official figure is published; it must be measured on the target host.

## 2. Repository

- [x] Repository is `carmelosantana/minecraft-ollama` with an SSH `origin` and `main` branch.
      `git remote -v` → `origin git@github.com:carmelosantana/minecraft-ollama.git (fetch/push)`.
      0.3.0 work is on branch `feat/api-currency`, an isolated worktree off local
      `feat/llama-companion`, not `main` — not pushed, tagged, or released by this cycle.
- [x] Existing user-owned worktree changes were identified and preserved. The main checkout at
      `.../Plugins/ollama` is held by a concurrent 0.4.0 agent and was left provably untouched:
      it stayed clean at `ae09bbb` throughout (verified at start and end). The in-flight
      `PluginDescriptorTest` and `plugin.yml` quoting recorded at `ae09bbb` are the base this
      branch built on.
- [x] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or
      documentation. `rg -n 'herobrinesystems' . -g '!target/**' -g '!.git/**'` returns only the
      two checklist lines that *describe* this check — no reference in any shipped artifact.

## 3. Metadata

- [x] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent. `LICENSE`
      is the full AGPL-3.0 text; `pom.xml` declares `<name>GNU Affero General Public License v3.0
      or later</name>` pointing at `https://www.gnu.org/licenses/agpl-3.0.html`. Unchanged by
      0.3.0; verified consistent.
- [x] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present. `pom.xml`
      `<url>https://xpfarm.org</url>` and a new `<developers>` block naming Carmelo Santana;
      `plugin.yml` `website: https://xpfarm.org` and `author: Carmelo Santana`. The POM previously
      had no `<developers>` block at all (added to satisfy `minecraft-plugin-scaffold` gate 3).
- [x] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is
      documented. Added to a new `## Server` section of `README.md`. It had appeared nowhere in the
      repository outside checklist prose. The section states the plugin ships `enabled: false`, so
      the hostname is documented without implying a live integration that has never been confirmed.
- [x] New work uses the `org.xpfarm` Maven group. `pom.xml` `<groupId>org.xpfarm</groupId>`, moved
      from `com.carmelosantana`, closing the group/package split the 0.2.1 backfill recorded as its
      follow-up 3. `artifactId`, JAR name, and updater destination are unchanged, so the updater
      manifest needs no edit.
- [x] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are
      consistent. Slug `ollama`; `pom.xml` `<artifactId>ollama</artifactId>`; releasable JAR
      `ollama-0.3.0.jar`; updater destination `ollama.jar`; `plugin.yml` `name: Ollama`,
      `main: org.xpfarm.ollama.OllamaPlugin`. Confirmed against the built JAR's embedded descriptor.
- [x] No secrets committed in source, defaults, tests, logs, history, or documentation. `config.yml`
      has no credential field; the endpoint is a localhost default. Reviewed across all 0.3.0 diffs.

**0.3.0 divergences and findings** — recorded here rather than silently absorbed:

- **Maven resource filtering had to be turned on, then narrowed.** `pom.xml` had no `<resources>`
  block, so `version: '${project.version}'` would have shipped as a literal string and Paper would
  have rejected the descriptor with `InvalidDescriptionException` — the plugin absent from
  `/plugins` rather than present-and-disabled. Filtering was enabled (Task A1) and then narrowed
  (Task B5) to `plugin.yml` only, because the `ollama-*.md` files are LLM system-prompt templates
  and a future `${...}` in one would otherwise be silently mangled at build time. Verified: the
  four `ollama-*.md` and `config.yml` are byte-identical between source and `target/classes`; only
  `plugin.yml` is substituted. Neither step is in the design doc's 0.3.0 table; the table's
  `version: '${project.version}'` row silently depended on the first.
- **The compiler plugin's `<source>21</source><target>21</target>` was removed.** It was dead:
  `maven.compiler.release=25` already took precedence, confirmed by `javap` reporting
  `major version: 69` on the pre-change build. Leaving it would have left a false claim about the
  build in the POM.
- **`performance.max_concurrent_requests` default changed `5` → `1`** and now drives the concurrency
  gate. `OLLAMA_NUM_PARALLEL` defaults to 1, and a warm model does not 503 when overloaded — it
  stalls on an internal semaphore indefinitely, so a default of 5 would stall four requests rather
  than refuse them. `config.yml` documents that operators must raise `OLLAMA_NUM_PARALLEL` to match.
- **Retry reconciliation:** `api.max_retries` is a ceiling; the per-status cap narrows it. 500
  retries at most once (Ollama self-heals OOM by evicting models), 503 uses the full budget, and
  `max_retries: 0` disables retries everywhere. An overall deadline also bounds a 503 loop to
  roughly `2 × api.timeout` so it cannot hold the concurrency gate for `(retries+1) × timeout`.
- **Acceptance check 3 is implemented conditionally.** As worded it says `think` is present in
  *every* chat and generate body, which cannot hold alongside check 4 and Finding 2 — sending the
  field to a model without the thinking capability is a hard 400. It is implemented as "present
  whenever `/api/show` reports the model thinking-capable", and omitted otherwise, including when
  the probe fails. Omission is the pre-0.3.0 wire format, so a probe outage cannot regress it.

## 4. Compatibility

- [x] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'`,
      matching the API compiled against. `mvn clean verify` → `BUILD SUCCESS`; the embedded
      descriptor in `target/ollama-0.4.0.jar` reads `api-version: '26.1'` (quoted String, not the
      old `'1.21'`). The version-sensitive Paper Dialog API (`io.papermc.paper.registry.data.dialog.*`)
      resolved and compiled cleanly against `paper-api 26.1.2` — no reconciliation against the
      decompiled jar was needed.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and
      declared. No hard dependencies. `softdepend: [ViaVersion]` retained for load ordering. Apache
      HttpClient was removed as a bundled dependency (0 `org/apache/http` classes in the shaded
      JAR); Gson 2.14.0 remains, shade-relocated to `org.xpfarm.ollama.libs.gson` (230 classes).
- [x] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and
      protocol behavior. **0.4.0 ships the reviewed surface and the code matches the design.** Every
      Bedrock finding from the 2026-07-22 review is honored in shipped code: entity listeners guard
      `event.getHand() != EquipmentSlot.HAND` (the two-interact-packet reality —
      `CompanionPlaceListener`, `CompanionInteractionListener`); the dialog text input sets
      `maxLength(256)` explicitly rather than accepting Geyser's 32-char default (`CompanionDialog`);
      the conversation UI is a Paper Dialog auto-converted by Geyser, with **no** Geyser/Floodgate/
      Cumulus dependency (0 such classes shaded); the summon item's identity is a PDC tag on the
      output only, so hand-crafting resolves server-side despite the Bedrock recipe-book gap; and
      `/llama recipe` mitigates that gap. ViaVersion is documented as a hard runtime requirement in
      `README.md`. **Client-side Bedrock rendering** (the dialog actually drawn as a Cumulus form, a
      real Bedrock tap) was **not** exercised — gate 7a does no client joins — and is carried to the
      gate-12 play-test obligation.

**The Bedrock review the 0.2.1 backfill recorded as "NEVER PERFORMED" was performed on 2026-07-22**
as part of gate 1 research, against Geyser and Floodgate sources at `master` and the decompiled
`paper-api-26.1.2.build.74-stable.jar`. Its findings are in §1 Known limitations and in
`docs/superpowers/specs/2026-07-22-llama-companion-design.md`. The box stays unchecked here because
the review covers the *planned* 0.4.0 surface, not shipped code — gate 4 ticks it when the code
exists and matches. Three facts it established that bind implementation:

- `api-version` must become `'26.1'`. `paper-api 26.1.2` is Minecraft Java **26.1**, not 1.21;
  Mojang moved to `YY.D[.H]` versioning in 2026. A lower value opts the JAR into Paper's
  `Commodore` bytecode rewrites. An uncommitted worktree change currently quotes `'1.21'` — right
  instinct about quoting, wrong value.
- **ViaVersion is a hard runtime requirement.** Geyser emulates a Java 26.2 client against this
  26.1.2 server; ViaVersion is what makes that connection possible. `softdepend` stays for load
  order, but the documentation must stop implying it is optional.
- **No Geyser, Floodgate, or Cumulus dependency is needed.** The Paper Dialog API is auto-converted
  to Bedrock forms by Geyser, so one code path serves both editions.

## 5. External services

- [x] External integrations are disabled by default or require explicit configuration and have
      bounded timeouts. `enabled: false` by default (`config.yml`). 0.3.0 makes `api.timeout` real:
      it was read and never applied, so a stalled Ollama pinned an executor thread forever. It is
      now applied structurally on every request path — the single `newRequest()` factory always
      sets `.timeout(...)`, covered by `OllamaHttpTest.timeoutAbortsRatherThanPinningTheThread` and
      `aGetCarriesTheTimeoutToo`. A client-side concurrency gate additionally bounds load.
- [x] Ollama endpoint is optional and failure-tolerant. With the endpoint unreachable, a request
      surfaces an error `GenerateResponse`/`ChatResponse` (never a dropped callback), the model
      preload logs and continues, and the capability probe degrades to "unknown" (omit `think`)
      rather than throwing. Covered by `OllamaHttpTest` (refused-connection, timeout) and
      `ModelCapabilitiesTest` (unreachable-endpoint, 200-non-JSON).
- [x] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets.
      `onEnable()` early-returns when `enabled: false`; `preload()` and `testConnection()` run on
      the executor and never throw on the main thread. Player-facing messages carry no endpoint URL,
      body, or stack trace — pinned by a test looping `StatusPolicy.Action.values()` asserting no
      message contains `http://`, `://`, `/api/`, `localhost`, `127.0.0.1`, or `Exception`, and by a
      test asserting a really-thrown `OllamaHttpException.getPlayerMessage()` is clean while its
      `getMessage()` detail (server-log only) may carry the URI.

## 6. Tests and build

- [x] Unit tests cover separable logic, configuration, serialization, permissions, and failure
      paths. **105 tests** (67 at the 0.3.0 baseline + 38 new for the companion), up from 12 at the
      0.2.1 baseline. 0.3.0 surface: `StatusPolicyTest`, `ChatRequestSerializationTest`/
      `GenerateRequestSerializationTest`, `OllamaHttpTest`, `ModelCapabilitiesTest`,
      `OllamaAPIThinkGatingThreadTest`, `OllamaAPITest`, `PluginDescriptorTest` (now also asserting
      the `llama` command and `ollama.llama.use`/`ollama.llama.give` permissions). Companion surface,
      each testing the separable pure logic while Bukkit-wired shells are left to gate 7a:
      `CompanionKeysTest`, `CompanionItemTest` (PDC read contract), `CompanionRecipeTest` (recipe
      key), `CompanionRegistryTest` (bind/resolve incl. the null-resolution case), `CompanionEntityTest`
      (downed-flag round-trip), `CompanionPlaceListenerTest` (the one-per-player decision),
      `FollowTaskTest` (teleport-catch-up threshold), `DownedStateListenerTest` (the damage classifier
      — KILL/VOID/lethal/block/ignore), `InventorySnapshotTest`, `InventoryAdvisorTest` (the four nudge
      rules + enabled-set gating), `CompanionContextTest` (own-state-only rendering), `CommandSuggesterTest`
      (parse-not-execute), `CompanionConversationTest` (system prompt as a leading `role:"system"`
      message, never a top-level field), `NudgeTaskTest` (per-player cooldown boundary),
      `LlamaCommandTest` (subcommand routing), `CompanionManagerConfigTest` (rule parsing).
- [x] `PluginDescriptorTest` parses `plugin.yml` and `config.yml` with SnakeYAML and asserts
      `name`, `main`, a `String`-typed `api-version` (now `'26.1'`), a fully-substituted `version`
      (asserts no `${` survives), the `website`, every command, every permission, and the soft
      dependencies. It reads the Maven-filtered `target/classes/plugin.yml` in preference to source,
      so the `version` assertion *is* the filtering proof.
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds. Final: `Tests run: 105,
      Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS` (0.4.0). (0.3.0 was 67.)
- [x] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are
      excluded. `unzip -p target/ollama-0.4.0.jar plugin.yml` shows `version: '0.4.0'` and
      `api-version: '26.1'` fully substituted, and declares the `llama` command with
      `ollama.llama.use`/`ollama.llama.give`; **0** `org/apache/http` classes; 230 relocated Gson
      classes under `org/xpfarm/ollama/libs/gson` (0 unrelocated `com/google/gson`); 26 companion
      classes under `org/xpfarm/ollama/companion/` and the `llama-companion.md` personality resource
      present. A `target/original-ollama-0.4.0.jar` exists as normal maven-shade output but is
      excluded from the release by `.github/workflows/build.yml` (`! -name 'original-*'` — verified
      at 0.3.0).

## 7. Matrix

**Gate 7a — single-plugin runtime verification — PASSED 2026-07-22.** This is the discharge of
follow-up 1 of the 0.2.1 backfill: **the first time `/ollama` has been exercised end to end against
a real endpoint in-game.** Every prior runtime record proved only that a dormant `onEnable()`
(shipping `enabled: false`) does not throw. Booted via the shared rig on a fresh-volume Legendary
stack with this repo's `scripts/extra-services.yml` Ollama sidecar.

> **Rig note.** The rig derives its Docker Compose project name from the worktree directory
> basename, and the delegation's worktree name `ollama-0.3.0` contains dots, which Compose rejects
> (`invalid project name "…-ollama-0.3.0-…"`). Gate 7a was therefore run from a throwaway
> detached-HEAD worktree with a dot-free name (`ollama-rigtest`) at the same commit; it was torn
> down and removed afterward, and the main checkout was never touched. The dot-in-basename is a
> shared-rig limitation, not a plugin defect — flagged for the toolkit, not fixed here.

- [x] Paper, Geyser, Floodgate, and ViaVersion start successfully together. Rig `up` self-verified
      Paper's own `Done (21.142s)! For help`, a real Minecraft protocol handshake on the Java port
      (`Paper 26.1.2 | protocol 775`), and RCON `plugins` listing all four **green**:
      `floodgate, Geyser-Spigot, Ollama, ViaVersion`.
- [x] Affected commands, permissions, and configuration reload were exercised over RCON with no
      server-wide hot reload. With `enabled: true` and `api.endpoint: http://ollama:11434`:
      `/ollama version` and `/ollama status` returned model/endpoint; `/ollama reload` reloaded
      config live (raised `api.timeout` took effect); `/ollama debug on` toggled debug.
      **`/ollama say` returned a real generated answer from the live `llama3.2:1b` endpoint** —
      *"A Creeper is a hostile mob in Minecraft that can explode on impact, dealing damage to
      players and structures nearby."* — with `"done":true,"done_reason":"stop"` (a complete, not
      truncated, answer). This is **acceptance check 8**. The Ollama sidecar's gin access log
      independently confirms the wire traffic: `GET /api/version` 200, `POST /api/show` 200 (the
      capability probe), `POST /api/generate` 200. The sent request body showed a top-level
      `system` field (correct for `/api/generate`) and **no `think` field** — `llama3.2:1b` is not
      thinking-capable, so the `/api/show` probe returned FALSE and `applyThinkGating` omitted it,
      confirming Finding 2 and acceptance checks 3–4 end to end against real Ollama.
- [x] Ollama unavailable-endpoint test keeps the server and plugins available. `api.timeout`
      genuinely fires (acceptance check 2 in vivo): a generation exceeding the timeout returned a
      gin 500 at exactly 30.00s rather than hanging — before 0.3.0 the value was never applied and
      the thread would pin forever. With the sidecar **stopped**, `/ollama say` failed fast with a
      single `WARN` (`could not reach Ollama at http://ollama:11434`), no stack trace, no `SEVERE`,
      and the server stayed up (`list` answered immediately). This is acceptance check 17 for the
      0.3.0 surface (conversation degrades to an error; the server stays available).
- [ ] **7b — full-roster matrix — not run. Out-of-band and NOT required for this release.** Gate 7b
      installs all updater-managed plugins together on one stack; it is triggered by an updater
      manifest change or a Paper/Geyser/Floodgate/ViaVersion bump, none of which this release makes
      (`artifactId`, JAR name, and updater destination are unchanged). Left for
      `minecraft-plugin-matrix` if a future trigger warrants it.

**Behaviors gate 7a could not reach (carried to the gate 12 play-test obligation):**

- `/ollama chat` requires a `Player` receiver (`handleChat` guards `sender instanceof Player`), so
  it cannot be driven from the RCON console. The `/api/chat` path — and specifically the
  system-prompt-as-`role:"system"`-message fix — is proven by unit tests
  (`ChatRequestSerializationTest`) but was **not** exercised in-game. A real Java/Bedrock client
  join is needed to confirm it live.
- The concurrency-gate rejection (acceptance check 7) is proven by `OllamaHttpTest` but was not
  reproduced in vivo — single-threaded RCON cannot issue two truly concurrent requests.
- No Java or Bedrock client attached (gate 7a does not do client joins by design); nothing about
  Bedrock rendering or client-side behavior was verified.
- **Model note:** verified against `llama3.2:1b` (small, CPU-only, non-thinking) rather than the
  config default `llama3.2`, to keep the sidecar pull and generation fast. A thinking-capable model
  (which would exercise the `think: false` *positive* path) was not run — the omit-when-not-capable
  path was confirmed instead.

**Gate 7a (0.4.0 companion) — runtime-verified 2026-07-22.** Booted via the shared rig
(`scripts/test-stack.sh` → `xpfarm-test-stack`) on a fresh-volume Legendary stack with the
`scripts/extra-services.yml` Ollama sidecar (auto-layered by the current rig — no `--with` flag).
Paper self-verified `Done (19.194s)`, a real Minecraft handshake (`Paper 26.1.2 | protocol 775`),
and RCON `plugins` listing all four **green**: `floodgate, Geyser-Spigot, Ollama, ViaVersion`. The
`ollama-0.4.0.jar` under test was the shaded release JAR.

> **Rig note (0.4.0).** This run's worktree basename `agent-a3a17506cba37acfa` is **dot-free**, so
> the dotted-Compose-name limitation the 0.3.0 note records did not bite — the project name
> `xpfarm-plugin-test-agent-a3a17506cba37acfa-91dbf402` was accepted directly. The limitation is
> still real for dotted worktree names; it simply did not apply here.

- [x] **The companion is decoupled from the Ollama master switch (acceptance check 17; plan Task 18
      — the load-bearing change).** With the shipped default `enabled: false`, the log shows the API
      staying dormant *and* the companion coming up anyway, verbatim:
      `[Ollama] Ollama integration is disabled; no API client or listeners were started.` →
      `[Ollama] Registered companion recipe ollama:companion_recipe` →
      `[Ollama] Llama companion enabled (conversation dormant — Ollama disabled)`. Flipping
      `enabled: true` (endpoint `http://ollama:11434`, model `llama3.2:1b`) and restarting, the same
      line loses its suffix — `[Ollama] Llama companion enabled` — followed by
      `[Ollama] ✅ Successfully connected to Ollama API` and `[Ollama] Preloaded Ollama model
      llama3.2:1b`. Recipe registration and companion-subsystem enablement occur on both paths;
      conversation is the only thing gated on the API, and it degrades rather than fails.
- [x] **The `/llama` command surface is wired and the safety guards hold (plan Task 17), over RCON:**
      `/llama recipe` → `Llama Companion recipe:  W L W (W=White Wool, L=Lead)  H G H (H=Hay Bale,
      G=Gold Ingot)  W L W`; `/llama help` → the usage line; `/llama give` (console, no player) →
      `Player not found.`; **`/llama ask hi there` (console) → `Only players can talk to a llama.`**
      — the Player-guard is enforced and no command is executed; `/llama frobnicate` →
      `Unknown subcommand. Try /llama help`.
- [x] **The conversation-backing Ollama endpoint is functional end to end.** A real generation
      completed against `llama3.2:1b` via the console-drivable `/ollama say` (the companion's
      `/llama ask` uses the same backend but is Player-gated — see below): request logged with the
      system prompt, response
      `"In Minecraft, grass appears green in most biomes."` with `"done":true,"done_reason":"stop"`.
      The sidecar's gin log independently confirms the plugin-container wire traffic (`172.30.0.3`):
      `GET /api/version 200`, `POST /api/show 200` (capability probe), `POST /api/generate 200` (both
      the enable-time preload at 8.5s and the interactive reply at 29.97s — the 1b model runs
      ~0.45 tok/s on this CPU, so `api.timeout` was raised to 180 for the interactive call). Two
      0.3.0 safety behaviors also reproduced **in vivo**: the concurrency gate rejected a second
      in-flight request (`Ollama generate request failed: concurrency gate full (0 permits free)`)
      and `api.timeout` fired at exactly 30s on the default (`timed out after 30s`, gin `500 |
      30.00s`), the server staying up throughout.

**Behaviors gate 7a could NOT reach for 0.4.0 (carried to the gate-12 play-test obligation):** every
companion behavior that needs a real client join or a spawned entity is undriveable from the
headless RCON console with no player online. Specifically **not** exercised in-game:

- **Acceptance 9–10** — crafting the summon item on a real grid, and placing it to spawn a
  bidirectionally-bound companion. (Recipe *registration* is confirmed above; a player crafting/
  placing is not.)
- **Acceptance 11** — follow across chunk boundaries and through a nether portal (needs a moving
  player). Pure teleport-threshold logic is unit-tested (`FollowTaskTest`).
- **Acceptance 12–14** — lethal damage → downed + charm return while `/kill` still kills; void
  rescue; survive a restart and re-resolve by UUID (all need a spawned companion). The damage
  classifier is unit-tested (`DownedStateListenerTest`).
- **Acceptance 15** — the right-click dialog rendered on a Java client and as a Geyser/Cumulus form
  on Bedrock (needs a client join). The dialog builds against the real `paper-api 26.1.2` Dialog API
  — it **compiled verbatim**, no reconciliation needed — but was not drawn.
- **Acceptance 16, 18** — the shield nudge and the per-player nudge cooldown in-game (need a player
  with a companion). The rules and the cooldown boundary are unit-tested (`InventoryAdvisorTest`,
  `NudgeTaskTest`).
- **A real `/llama ask` reply in character** — the command guards `sender instanceof Player`, so it
  cannot be driven from RCON even with Ollama up; the underlying backend is proven functional above
  and the request assembly (system prompt as a leading `role:"system"` message, own-state-only
  context) is unit-tested (`CompanionConversationTest`), but the in-game round-trip awaits a client.

**Two deliberate deviations recorded (from the plan's Self-review):**

1. **The companion keeps its own per-player conversation history**, a `Map<UUID,List<ChatMessage>>`
   on `CompanionConversation`, rather than reusing the shared `ChatSessionManager` that `/ollama
   chat` uses. Reason: the llama has a distinct personality system prompt, and interleaving its
   turns with `/ollama chat` would blend two personas in one transcript. This departs from the
   design doc's "reuses the `ChatSessionManager`" wording; the intent (a held conversation) is
   preserved and the persona/privacy separation is improved.
2. **The companion assembles its own bounded prompt context** (`InventorySnapshot` /
   `CompanionContext`) instead of routing through `SystemPromptManager`, whose `fillContextVariables`
   injects nearby players (50-block radius) and player activity logs — both out of the approved
   own-state-only scope. The type shape (`InventorySnapshot` has no field for another player and no
   chat history) enforces the privacy boundary structurally.

A third, smaller reconciliation: the Task 8 portal-follow listener implements
`PlayerChangedWorldEvent` only (the plan's prose also named `PlayerPortalEvent`, but the plan's own
code block used only the former). A nether/end portal traversal *is* a world change, so the single
handler covers the acceptance-11 portal case; `PlayerPortalEvent` fires before the destination
resolves and would be the wrong hook. Left as implemented.

## 8. CI/CD — v0.3.0 verified 2026-07-23

- [x] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25
      build, artifact, checksum, and release behavior. `.github/workflows/build.yml` triggers on
      `push` to `main`, `push` of `v*` tags, `pull_request` to `main`, and `workflow_dispatch`;
      builds with Temurin Java 25; runs `mvn clean verify`; writes `SHA256SUMS.txt`; uploads release
      assets only on `refs/tags/v` with `! -name 'original-*'`.
- [x] Successful main Actions run is recorded before tagging. Commit `38c3a81` pushed to `main`;
      run `29970690019` **completed / success** (`build in 43s`, "Test and package" green) **before**
      the tag was created. Fail-closed rule honored: the run was watched to a completed success, not
      tagged in-flight.
- [x] Workflow permissions contain no broader access than the documented contract. Exactly
      `permissions: contents: write` at the top level, no job-level escalation; the only token is
      `GH_TOKEN: ${{ github.token }}` for `gh release`.

## 9. Release — v0.3.0 published 2026-07-23

- [x] Semantic version matches the POM, plugin metadata, and `v<version>` tag. `pom.xml`
      `<version>0.3.0</version>`; embedded `plugin.yml` `version: '0.3.0'` (Maven-filtered); annotated
      tag `v0.3.0` on commit `38c3a81` — the same commit run `29970690019` verified green.
- [x] Successful tag Actions run and GitHub release are recorded. Tag run `29970750451`
      **completed / success** with "Upload tagged release assets"; release published (not draft, not
      prerelease) at `github.com/carmelosantana/minecraft-ollama/releases/tag/v0.3.0` by
      `github-actions[bot]`.
- [x] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*`
      JAR. Assets: `ollama-0.3.0.jar` (1 match for `^ollama-[0-9].*\.jar$`) and `SHA256SUMS.txt`;
      zero `original-*`.
- [x] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`. Downloaded and checked:
      `ollama-0.3.0.jar: OK`.

## 10. Updater

- [ ] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs, enabled state, and optional pin.
- [ ] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure behaviors pass.
- [ ] Updater dry-run uses a disposable directory and never a production plugin directory.
- [ ] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.

## 11. Deployment

Not a gate. Deployment is updater pickup: a verified release plus a correct manifest entry is all
this lifecycle owes. Leaving this section entirely unticked is the normal resting state and blocks
nothing — not release, not enrolment, not handoff.

- [ ] Enrolment confirmed live and correct: release sound, manifest entry on `origin/main`, gate 10 genuinely completed.
- [ ] Deployment evidence recorded, if and only if an operator relayed some. Otherwise note "enrolled, not known to be deployed" and leave unticked.

## 12. Handoff

- [ ] Current-state documentation refreshed with release, CI, updater, deployment, and local pending state.
- [ ] Known limitations, skipped checks, configuration or migration notes, rollback guidance, and follow-up owner are recorded.
- [ ] Evidence distinguishes source commit, published tag/release, updater state, and deployed state without exposing secrets.
- [ ] Client play-test obligation recorded with a named owner and a target date: `<owner>` / `<date>`.
- [ ] Client play-test outcome recorded once performed, covering Java join, Bedrock join, and any form, inventory, or rendered item behavior this plugin introduces. Leave unchecked with the owner and date above until the team has run it; an unchecked box here does not block a release, but an unrecorded obligation is a gate 12 failure.
- [ ] Public deployment reachability confirmed during that pass: `play.xpfarm.org` reaches the intended Java and Bedrock entry points.
