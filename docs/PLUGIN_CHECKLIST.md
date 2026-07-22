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
      descriptor in `target/ollama-0.3.0.jar` reads `api-version: '26.1'` (quoted String, not the
      old `'1.21'`). Compiled bytecode is `major version: 69` (Java 25) via `javap`.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and
      declared. No hard dependencies. `softdepend: [ViaVersion]` retained for load ordering. Apache
      HttpClient was removed as a bundled dependency (0 `org/apache/http` classes in the shaded
      JAR); Gson 2.14.0 remains, shade-relocated to `org.xpfarm.ollama.libs.gson` (230 classes).
- [ ] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and
      protocol behavior. **Unchecked:** the review performed 2026-07-22 covers the *planned* 0.4.0
      Bedrock surface (dialogs, interaction, recipes), and 0.3.0 ships none of it — 0.3.0 adds no
      new player-facing or Bedrock-facing surface. Gate 4 ticks this when the 0.4.0 code exists and
      matches. **The ViaVersion documentation fact this review established IS discharged by 0.3.0:**
      `README.md` now documents ViaVersion as a hard runtime requirement, not an optional soft
      dependency.

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
      paths. 67 tests, up from 12 at the 0.2.1 baseline: `StatusPolicyTest` (status→action mapping,
      retry budget), `ChatRequestSerializationTest`/`GenerateRequestSerializationTest` (the
      system-prompt fix, `think` gating, `format` as object, `done_reason`), `OllamaHttpTest`
      (timeout, concurrency gate, retry, deadline, ungated metadata, malformed URI, no-leak),
      `ModelCapabilitiesTest` (per-model cache, unknown-not-cached, 200-non-JSON),
      `OllamaAPIThinkGatingThreadTest` (probe runs off the main thread), plus `OllamaAPITest` and
      `PluginDescriptorTest`.
- [x] `PluginDescriptorTest` parses `plugin.yml` and `config.yml` with SnakeYAML and asserts
      `name`, `main`, a `String`-typed `api-version` (now `'26.1'`), a fully-substituted `version`
      (asserts no `${` survives), the `website`, every command, every permission, and the soft
      dependencies. It reads the Maven-filtered `target/classes/plugin.yml` in preference to source,
      so the `version` assertion *is* the filtering proof.
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds. Final: `Tests run: 67,
      Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`.
- [x] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are
      excluded. `unzip -p target/ollama-0.3.0.jar plugin.yml` shows `version: '0.3.0'` and
      `api-version: '26.1'` fully substituted; 0 `org/apache/http` classes; 230 relocated Gson
      classes. A `target/original-ollama-0.3.0.jar` exists as normal maven-shade output but is
      excluded from the release by `.github/workflows/build.yml` (`! -name 'original-*'` in the
      SHA256SUMS step, the upload-artifact glob, and the `gh release upload` find — verified).

## 7. Matrix

- [ ] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers every updater-managed plugin.
- [ ] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately.
- [ ] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
- [ ] Affected commands, permissions, persistence, and configuration reload were exercised over RCON with no server-wide hot reload.
- [ ] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable.

## 8. CI/CD

- [ ] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior.
- [ ] Successful main Actions run is recorded before tagging.
- [ ] Workflow permissions contain no broader access than the documented contract.

## 9. Release

- [ ] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
- [ ] Successful tag Actions run and GitHub release are recorded.
- [ ] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*` JAR.
- [ ] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`.

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
