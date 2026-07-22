# Llama Companion — Design

- Date: 2026-07-22
- Plugin: `Ollama` (`carmelosantana/minecraft-ollama`)
- Releases covered: `0.3.0` (API client currency) and `0.4.0` (companion feature)
- Status: approved by Carmelo Santana, 2026-07-22
- Autonomy: `autonomous` — recorded in `docs/PLUGIN_CHECKLIST.md`

## Summary

Add a craftable llama companion that follows its owner, holds a conversation, and gives
inventory-aware advice. Ship it in two releases: `0.3.0` fixes and modernizes the Ollama HTTP
client the companion depends on, and `0.4.0` adds the companion itself.

The split exists because the client carries a live correctness bug that the companion would
otherwise be built directly on top of, invisibly (see [Finding 1](#finding-1-the-system-prompt-has-never-worked)).

## Research basis

Every non-obvious claim in this document came from one of two dispatched deep-research passes on
2026-07-22, verified against primary sources — the Ollama Go source at `v0.32.2`, `docs.ollama.com`,
Maven Central metadata, the decompiled `paper-api-26.1.2.build.74-stable.jar`, and the Geyser and
Floodgate sources at `master`. Findings that could not be verified are listed under
[Unverified](#unverified--confirm-at-runtime) rather than asserted.

### Finding 1: the system prompt has never worked

Ollama's `/api/chat` has no top-level `system` field and never has — verified across `v0.1.35`,
`v0.3.14`, `v0.5.7`, `v0.7.0`, `v0.12.0`, and `main`. The server binds request JSON with Gin's
`ShouldBindJSON`, and `DisallowUnknownFields` appears nowhere in `routes.go` or `types.go`, so Go's
`encoding/json` silently drops the key.

`ChatRequest.system` therefore serializes into a request that returns `200 OK` with the system
prompt discarded. `OllamaAPI.chatWithSystemPrompt()` has never once applied a system prompt.

This matters here specifically because a companion with a personality *is* a system prompt. Built on
the current client, the llama would have no personality and no diagnosable reason why.

`/api/generate` *does* accept a top-level `system`. The asymmetry between the two endpoints is the
trap.

### Finding 2: thinking is on by default

Since Ollama `v0.12.4`, omitting `think` on a thinking-capable model means `think: true`. From
`server/routes.go`:

```go
if slices.Contains(modelCaps, model.CapabilityThinking) {
    caps = append(caps, model.CapabilityThinking)
    if req.Think == nil {
        req.Think = &api.ThinkValue{Value: true}
    }
}
```

The client never sets `think`. Against qwen3, gpt-oss, or deepseek-r1 every request silently pays a
reasoning pass whose output lands in `message.thinking` and is then discarded by Gson. Invisible
latency for output nobody reads.

Sending `think` to a model *without* the capability is a hard 400, so the value must be gated on a
`/api/show` capability probe.

### Finding 3: overload manifests as latency, not errors

`OLLAMA_NUM_PARALLEL` defaults to `1` (`envconfig/config.go`). The important part is what happens
past that limit. `OLLAMA_MAX_QUEUE` (512, then 503) applies **only to requests that require a model
load or reload**. For an already-loaded model, `useLoadedRunner` increments a refcount with no
parallelism check at all and hands the request to the runner, where it blocks on
`semaphore.Acquire` — which waits indefinitely, cancellable only by request context.

So on a warm model the server will essentially never push back. Request 1000 does not get rejected;
it stalls behind 999 others until the client's own timeout fires. **Client-side concurrency limiting
is mandatory, not an optimization.**

### Finding 4: this is not Minecraft 1.21

`paper-api 26.1.2` is Minecraft Java **26.1**. Mojang moved to `YY.D[.H]` versioning in 2026 and
26.1 succeeded the 1.21 line. Confirmed from the jar: `apiVersioning.json` reports
`{"version":"26.1.2.build.74-stable","currentApiVersion":"26.1.2"}`.

`plugin.yml` declares `api-version: '1.21'`, which loads but opts the JAR into Paper's `Commodore`
bytecode rewrites. The toolkit template independently requires `'26.1'`.

Consequence for Bedrock: Geyser emulates a Java **26.2** client, and this server is 26.1.2.
ViaVersion is what bridges that gap, making it a **hard runtime requirement** rather than the
`softdepend` nicety currently declared.

### Finding 5: the UI problem is already solved

Paper 26.1.2 ships a native Dialog API (`io.papermc.paper.dialog.Dialog`, with `TextDialogInput`,
`BooleanDialogInput`, `SingleOptionDialogInput`, `NumberRangeDialogInput`, `ActionButton`), and
Adventure 4.26.1 provides `Audience#showDialog` — `Player` is an `Audience`.

Geyser converts Java dialogs into Bedrock Cumulus forms automatically. One code path serves both
editions: no Geyser dependency, no Floodgate, no Cumulus, no reflection, no shading. Shading Cumulus
is in fact a documented `LinkageError` generator.

Evidence the path works on this exact stack: Geyser issue #6377, filed against Paper 26.1.2 +
Geyser 2.10.0 + Bedrock 26.20, was a text-input bug *in that translation*, since fixed in PR #6387.

### Finding 6: three obvious approaches are dead ends

- **Tamed llamas do not follow their owner.** Vanilla llamas follow leashed caravans and players
  holding hay bales. `setOwner(player)` sets an ownership flag and produces no follow behavior.
- **`VanillaGoal.FOLLOW_OWNER` is unusable.** It exists in the jar as a `GoalKey<Tameable>`, and
  `Llama` is `Tameable`, but `GoalKey` is only an identifier — `MobGoals.addGoal` needs a `Goal<T>`
  instance and Paper ships no factory for vanilla goals. Via NMS it also fails: vanilla
  `FollowOwnerGoal` requires `TamableAnimal`, and `LlamaEntity` descends from `AbstractHorseEntity`.
- **`setInvulnerable(true)` does not stop the void or `/kill`.** The
  `#minecraft:bypasses_invulnerability` damage-type tag contains exactly `minecraft:generic_kill`
  and `minecraft:out_of_world`. It also does not stop creative-mode players.

### Finding 7: no vanilla precedent for "downed"

Wolves die permanently; there is no vanilla revival. Allays are *not* unkillable — they have 20 HP
and take damage normally; what is true of them is that they do not despawn. The downed-and-
recoverable design chosen here is entirely plugin-side, with no vanilla behavior to inherit.

## Design decisions

Each of these was an explicit choice, not a default.

| Decision | Choice | Rationale |
|---|---|---|
| Mortality | Downed, not dead | Damage and healing stay meaningful; the pet is never permanently lost. Decouples "death" from "loss" so both stakes and attachment survive. |
| Conversation | Right-click session + `/llama ask` fallback | Reuses `ChatSessionManager`; keeps public chat clean; needs no custom UI, so Bedrock parity is free. |
| Command help | Suggest only, never execute | A hallucinated command becomes wrong text on screen, not a executed command. Leaves `commands.enable_execution: false` untouched. |
| Proactive advice | Deterministic rules; LLM for conversation only | Rules are instant, free, and cannot hallucinate. Per Finding 3, per-player LLM timers would not survive a populated server. |
| Sequencing | `0.3.0` API, then `0.4.0` companion | Per Finding 1, building on the current client means debugging two layers at once on a code path never exercised in-game. |
| Recipe cost | Early-mid game (wool, lead, hay bale, gold) | The companion is most useful to a *new* player; gating it behind endgame inverts its purpose. |
| Context scope | Asking player's own state only | Delivers the inventory awareness asked for without sending anyone else's chat or activity to a model. |
| Bedrock acquisition | Vanilla ingredients + `/llama recipe` + op-guarded `/llama give` | Only the output carries custom data, so hand-crafting resolves server-side. `give` is an admin/debug affordance, not a player bypass. |
| Placement | Same plugin, new `companion` package | The feature is tightly coupled to the API client, session manager, and config. A separate plugin would mean a second repo, a second updater entry, and a version-skew support surface. |

## Release 0.3.0 — API client currency

### Scope

| Change | Location | Driver |
|---|---|---|
| Delete `ChatRequest.system`; prepend a `role: "system"` message | `api/models/ChatRequest.java`, `api/OllamaAPI.java` | Finding 1 — live bug |
| Replace Apache HttpClient 4.5.14 with `java.net.http.HttpClient` | `api/OllamaAPI.java`, `pom.xml` | Built into Java 25; removes a shaded dependency rather than adding one; real `.timeout()`; `BodyHandlers.ofLines()` for NDJSON. 4.5.14 is the terminal 4.x release. |
| Apply `api.timeout` and `api.max_retries` | `api/OllamaAPI.java` | Both read and then never used |
| Send `think` explicitly (default `false`); parse and discard `thinking` | request and response models | Finding 2 |
| `format`: `String` → `JsonElement` | `ChatRequest`, `GenerateRequest` | As typed it can only send `"json"`; a JSON Schema is impossible |
| Add `done_reason` | `ChatResponse`, `GenerateResponse` | Cannot otherwise distinguish a complete answer from one truncated at `num_predict` |
| Status-code dispatch | `api/OllamaAPI.java` | Currently a non-200 is logged and the body parsed and delivered anyway |
| Concurrency gate (`Semaphore`, default 1) | `api/OllamaAPI.java` | Finding 3 |
| `/api/show` capability probe, cached per model | new `api/ModelCapabilities.java` | Prerequisite for safely sending `think` |
| Model preload on enable (empty prompt) | `OllamaPlugin.java` | Pays cold-load cost at startup, not on a player's first message |
| `api-version: '1.21'` → `'26.1'` | `plugin.yml` | Finding 4 |
| `version: '${project.version}'` | `plugin.yml` | Removes the drift hazard recorded in the prior checklist |
| ViaVersion documented as a hard runtime requirement | `README.md`, checklist | Finding 4 |
| `groupId` `com.carmelosantana` → `org.xpfarm` | `pom.xml` | Prior checklist follow-up 3 |
| Gson 2.10.1 → 2.14.0 | `pom.xml` | Four years behind |

`artifactId`, releasable JAR name, and updater destination are unchanged by the group move, so the
updater manifest needs no edit.

### Status-code dispatch table

| Status | Meaning | Action |
|---|---|---|
| 400 | Our request is malformed, or capability missing | Log loudly, do not retry |
| 404 | Model not installed | Tell the player, do not retry |
| 499 | We cancelled | Ignore |
| 500 | Possibly OOM; Ollama self-heals by evicting models | Back off, retry once |
| 503 | Queue full (load path only) | Back off, retry |
| timeout, no status | The semaphore-stall case of Finding 3 | Treat as backpressure and shed load |

Match on status codes, never on message text: Ollama emits two different 404 body formats
(`'%s'` and `%q`) depending on which internal path failed.

## Release 0.4.0 — the companion

### Components

One class per job, in a new `org.xpfarm.ollama.companion` package.

- **`CompanionItem`** — the summon item. Tagged via `ItemStack#editPersistentDataContainer`.
  `DataComponentTypes.CUSTOM_DATA` does not exist in this API — the jar exposes 96 component fields
  and that is not one of them. PDC is the only path.
- **`CompanionRecipe`** — `ShapedRecipe` registered through `Bukkit.addRecipe()` in `onEnable()`.
  There is no recipe lifecycle event on 26.1.2: `LifecycleEvents` has exactly `COMMANDS`, `TAGS`,
  and `DATAPACK_DISCOVERY`, and `RegistryEvents` has no `RECIPE`. `Bukkit.addRecipe` is not
  deprecated and remains the supported path. Vanilla ingredients only. `discoverRecipe()` on join,
  since `addRecipe` alone does not populate a recipe book.
- **`CompanionRegistry`** — bidirectional ownership. Mob UUID in the player's PDC as `STRING`
  (there is no `PersistentDataType.UUID`), owner UUID in the mob's PDC. Resolve with
  `Bukkit.getEntity(uuid)`: an O(1) lookup that does not load chunks. Never scan
  `world.getEntities()`. **A `null` result means the chunk is unloaded, not that the mob is gone** —
  the binding is dropped only on an explicit removal event.
- **`CompanionEntity`** — spawn and configure. `setRemoveWhenFarAway(false)` **and**
  `setPersistent(true)`; these are different properties and only the former stops despawn. Strip
  vanilla goals so they do not fight our navigation every tick.
- **`FollowTask`** — 10-tick repeating task calling `pathfinder.moveTo(Location)`. The `Location`
  overload is used deliberately; `moveTo(LivingEntity, double)` has a history of being broken.
  `teleportAsync` catch-up past roughly 16–24 blocks. Mobs do not follow through portals, so
  `PlayerPortalEvent` and `PlayerChangedWorldEvent` teleport the companion explicitly;
  `teleportAsync` loads or generates the destination chunk first, which is why it is the correct
  call.
- **`DownedState`** — `EntityDamageEvent` at `HIGHEST, ignoreCancelled`. Lethal damage (by
  `getFinalDamage()`) → cancel, `setHealth(1)`, `setAware(false)`, mark downed in PDC, despawn,
  return the summon item. `DamageCause.VOID` → cancel **and teleport back**, since cancelling alone
  leaves the entity falling forever. `DamageCause.KILL` passes through deliberately so `/kill @e`
  still works for admins. `setAware(false)` rather than `setAI(false)`, so the entity still responds
  to physics.
- **`CompanionInteraction`** — `PlayerInteractAtEntityEvent`. The parent
  `PlayerInteractEntityEvent` is `@ApiStatus.Obsolete` on 26.x (confirmed in the class bytecode);
  Paper's note reads "this event is no longer called without being a PlayerInteractAtEntityEvent."
  Guard `getHand() != EquipmentSlot.HAND` — a single Bedrock tap sends up to two interact packets,
  main-hand then off-hand. Cancel the event to prevent mounting (right-click with an empty hand
  mounts a llama), and open the UI **one tick later** or vanilla's container close eats it.
- **`CompanionDialog`** — Paper Dialog API, per Finding 5. Set `max_length` explicitly: Geyser
  defaults dialog text inputs to 32 characters and does not read `multiline`, so a multiline input
  degrades to single-line.
- **`InventoryAdvisor`** — deterministic rules, no LLM. Shield carried but not in the offhand; no
  food; tool near breaking; no torches entering low light. Nullability differs by accessor:
  `getItemInMainHand()`, `getItemInOffHand()`, and `getItem(EquipmentSlot)` are `@NotNull` and
  return AIR, while `getHelmet()` and siblings are `@Nullable`. `contains()` searches
  `getStorageContents()`, which excludes armor and offhand — exactly the semantics the shield rule
  needs, and load-bearing.
- **`CompanionContext`** — assembles prompt context: hotbar, inventory, armor, offhand,
  health/hunger, biome, dimension, time of day, and the player's own recent block activity. Read on
  the main thread, then handed to the async call.
- **`CommandSuggester`** — proposes commands as click-to-prefill chat text on Java and plain text on
  Bedrock. Never executes. Uses a JSON Schema through the now-object-typed `format`.

### Commands and permissions

| Command | Arguments | Permission | Default |
|---|---|---|---|
| `/llama ask` | `<text>` | `ollama.llama.use` | true |
| `/llama recipe` | — | `ollama.llama.use` | true |
| `/llama dismiss` | — | `ollama.llama.use` | true |
| `/llama give` | `[player]` | `ollama.llama.give` | op |

`plugin.yml` command descriptions stay short: Bedrock 1.21.130+ caps command name and description
lengths, and overlong descriptions have historically prevented Bedrock clients from joining at all.

### Configuration

A new `companion:` block, following the plugin's existing opt-in posture:

```yaml
companion:
  enabled: true          # crafting, following, and nudges — no Ollama needed
  invulnerable: true     # baseline; downed-state handling is always active
  follow_interval: 10    # ticks between pathfinder re-issues
  teleport_distance: 24  # blocks before teleport catch-up
  nudges:
    enabled: true
    cooldown: 300        # seconds between unprompted remarks, per player
    rules: [shield, food, tool_durability, torches]
```

Conversation additionally requires the existing top-level `enabled: true` and a reachable endpoint.

### Persistence

Entity PDC survives chunk unload and restart automatically — Paper writes a `BukkitValues` compound
into entity NBT. No custom storage layer is needed. Player-side, the companion UUID lives in the
player's PDC. Nothing is written to disk by this feature directly.

## Error handling

**The companion degrades; it does not fail.** Crafting, following, downed and revive, and the
rule-based nudges have zero dependency on Ollama. With the endpoint unreachable or `enabled: false`,
the llama still crafts, still follows, and still mentions the shield — it says it cannot think right
now. Nothing about a dead endpoint can fail startup or block a tick.

- Concurrency gate full → an immediate in-character message, not a silent 40-second stall.
- Async HTTP returns via `player.getScheduler().run(plugin, task, retired)`; the `retired` callback
  covers the player logging out mid-generation. `EntityScheduler.run` returns `@Nullable`, where
  null means already retired.
- The existing `BukkitScheduler` async-to-sync pattern remains valid on 26.1.2 — Folia has not been
  merged into Paper, and the `Runnable` overloads are not deprecated. No migration is required.

## Testing

**Unit.** Recipe shape and PDC round-trip; registry bind and resolve including the unloaded-chunk
case; advisor rules table-driven; downed-state transitions including VOID and KILL; context builder
scope (asserting no other-player data is included); status-code mapping; and a regression test
asserting the system prompt is placed as a `role: "system"` message rather than a top-level field.

**`PluginDescriptorTest`.** Already present but uncommitted in the worktree. Extend it to cover the
new commands and permissions, and to assert `api-version` is the `String` `'26.1'`.

**Gate 7a runtime.** `/llama give` → place → follow across chunk boundaries and a portal →
right-click dialog on both Java and Bedrock → kill it and confirm downed plus item return → restart
the server and confirm persistence → stop Ollama and confirm graceful degradation.

## Known limitations

Bedrock, all verified against Geyser and Floodgate sources:

1. Custom recipes do not appear in the Bedrock recipe book. Hand-placing ingredients works because
   the Java server resolves the craft; `/llama recipe` mitigates discoverability.
2. Geyser's recipe-book auto-craft is currently broken for *all* recipes including vanilla
   (open issue #6563, opened 2026-07-21).
3. Chat over 256 characters is dropped by Geyser before it reaches the server — no chat event fires.
   This bounds free-form prompt length for Bedrock players.
4. No tab completion, ever. Bedrock sends no packet indicating the player is in the chat UI.
5. Dialog text inputs are single-line and default to a 32-character limit.
6. The summon item renders as a plain vanilla item; `custom_model_data` requires a Bedrock resource
   pack plus hand-written Geyser item mappings.
7. Off-hand interaction is unreliable (Geyser #3480).
8. A single Bedrock tap fires up to two interact events.
9. ViaVersion is mandatory, not optional (Finding 4).

General:

10. There is no vanilla precedent for the downed mechanic (Finding 7); it is entirely plugin-side.
11. Conversation quality depends on the operator's model choice, which this plugin does not control.

## Unverified — confirm at runtime

- Which Bukkit events CraftBukkit 26.1 fires from the merged interact packet. MCProtocolLib split
  `ServerboundInteractPacket` from `ServerboundAttackPacket` on 2026-03-14 and no Paper-side
  documentation of the resulting event behavior was found.
- Whether explosion knockback still displaces an entity with `setInvulnerable(true)`.
- Whether the Bedrock client populates the crafting output slot with zero matching `CraftingData`
  entries. Source reading and two issue reports suggest yes; no authoritative statement found.
- Cold-model-load latency. No official figure is published; it must be measured on the target host.

## Out of scope

Streaming responses, tool calling, embeddings, and multiple companions per player. Streaming and
tool calling are both newly *possible* after `0.3.0` and are the natural `0.5.0` conversation, but
neither is required by this feature.
