# Llama Companion (0.4.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a craftable llama companion that follows its owner, holds a conversation backed by Ollama, and gives deterministic inventory-aware advice — degrading gracefully when Ollama is unavailable.

**Architecture:** A new `org.xpfarm.ollama.companion` package, wired through one `CompanionManager` facade that `OllamaPlugin` initializes **independently of the top-level `enabled` flag** (crafting, following, downing, and nudges must work with Ollama off). Bukkit-free logic (advisor rules, prompt-context assembly, command-suggestion parsing, item identity, ownership binding) is separated from Bukkit-wired shells so it is unit-testable, exactly as 0.3.0 split `OllamaHttp` from `OllamaAPI`. Conversation reuses the 0.3.0 `OllamaAPI.chatWithRequest(...)` path — the concurrency gate, `think`-gating, and main-thread callback come for free.

**Tech Stack:** Java 25, Paper API 26.1.2 (Minecraft Java **26.1**, not 1.21), Gson 2.14.0, JUnit 5 + Mockito (already on the test classpath), Maven shade. The Paper Dialog API (`io.papermc.paper.dialog.Dialog`) is used for the conversation UI — Geyser auto-converts it to Bedrock forms, so there is **no** Geyser/Floodgate/Cumulus dependency.

## Global Constraints

- **Base branch:** `feat/llama-companion` (carries the merged 0.3.0 client). Work on this branch; do not create a new worktree — the 0.3.0 track already vacated `../ollama-0.3.0`.
- **Build command (Maven is not on the non-interactive PATH):** first run
  `export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"`, then
  `mvn --batch-mode --no-transfer-progress clean verify`. Yields Temurin 25.0.3 / Maven 3.9.16.
- **Baseline:** 67 tests pass, `BUILD SUCCESS`, at `7c6b2f2`. Never finish below that count.
- **Version:** bump `pom.xml` to `0.4.0`. `plugin.yml` uses `version: '${project.version}'` (already) — never hardcode. `api-version` stays `'26.1'`.
- **Group/naming (locked, do not change):** `groupId` `org.xpfarm`, package root `org.xpfarm.ollama`, `artifactId` `ollama`, JAR `ollama-<version>.jar`, updater destination `ollama.jar`. The updater manifest needs no edit.
- **No new runtime dependency.** No Geyser, Floodgate, Cumulus, HTTP, or LLM library. Paper API is `provided`; Gson is the only shaded runtime dep and stays relocated to `org.xpfarm.ollama.libs.gson`.
- **Threading:** never touch Bukkit API off the main thread. All Ollama I/O goes through `OllamaAPI`, whose callbacks are already delivered on the main thread via `Bukkit.getScheduler().runTask`. Do not add a second HTTP path.
- **Context scope (approved, privacy-load-bearing):** the companion prompt carries the **asking player's own state only** — hotbar, inventory, armor, offhand, health/hunger, biome, dimension, time, their own recent block activity. **Never** nearby players, never chat history of anyone. Do **not** reuse `SystemPromptManager`'s templates: `fillContextVariables` injects nearby players (`getVisiblePlayers`, 50-block radius) and player logs, both out of scope here.
- **Command help never executes.** `CommandSuggester` produces click-to-prefill (Java) / plain (Bedrock) text only. It must not run commands; `commands.enable_execution` stays untouched.
- **Bedrock reality (record, design around, do not fight):** custom recipes are absent from the Bedrock recipe book (hand-crafting still works); chat over 256 chars is dropped by Geyser; dialog text inputs are single-line, default 32-char limit — set `max_length` explicitly; a single Bedrock tap fires up to two interact packets — every entity listener guards `event.getHand() != EquipmentSlot.HAND`.
- **Design doc:** `docs/superpowers/specs/2026-07-22-llama-companion-design.md`. It is the source of truth; this plan implements it. Deviations get recorded in `docs/PLUGIN_CHECKLIST.md`, not made silently.

---

## File structure

**New package `org.xpfarm.ollama.companion`:**

| File | Responsibility | Bukkit-coupled? |
| --- | --- | --- |
| `CompanionKeys.java` | Central `NamespacedKey` constants (item tag, entity→owner, player→entity). One source of truth for PDC keys. | thin |
| `CompanionItem.java` | Build the summon `ItemStack` (PDC-tagged, named); identify whether a stack is a summon item. | yes (ItemStack) |
| `CompanionRecipe.java` | Register the `ShapedRecipe`; `discoverRecipe` on join. | yes |
| `CompanionRegistry.java` | Bidirectional UUID binding player↔companion via PDC; bind/resolve/unbind. Resolve via `Bukkit.getEntity` (O(1), no chunk load). | yes |
| `CompanionEntity.java` | Spawn & configure a bound llama: `setPersistent(true)`, `setRemoveWhenFarAway(false)`, strip vanilla goals, tag owner. | yes |
| `FollowTask.java` | 10-tick repeating pathfinder re-issue + teleport catch-up. | yes |
| `DownedStateListener.java` | `EntityDamageEvent`: lethal→down+return item; VOID→cancel+teleport back; KILL passes through. | yes |
| `CompanionInteractionListener.java` | `PlayerInteractAtEntityEvent`→open dialog (hand-guarded, mount-cancelled, +1 tick). | yes |
| `CompanionDialog.java` | Build the Paper `Dialog`; handle the text submission → conversation. | yes |
| `InventorySnapshot.java` | Immutable plain record of the player's own gear/state. No Bukkit types leak out. | boundary |
| `InventoryAdvisor.java` | Pure rules over `InventorySnapshot` → list of nudge strings. **Unit-tested.** | no |
| `NudgeTask.java` | Periodic per-player nudge with per-player cooldown. | yes |
| `CompanionContext.java` | Assemble the bounded prompt-context string from an `InventorySnapshot`. **Unit-tested.** | no |
| `CommandSuggester.java` | Parse an LLM reply into displayable + click-to-prefill command text. **Unit-tested.** | no |
| `CompanionConversation.java` | Orchestrate ask→context→system prompt→`OllamaAPI.chatWithRequest`→deliver; degrade when API absent. | yes |
| `LlamaCommand.java` | `/llama ask|recipe|dismiss|give` executor + tab completer. | yes |
| `CompanionManager.java` | Subsystem facade: owns registry/recipe/tasks/listeners; lifecycle; `companion.enabled` gate. | yes |

**Resource:** `src/main/resources/llama-companion.md` — the personality system prompt (companion-specific; not routed through `SystemPromptManager`).

**Modified:** `plugin.yml` (new `llama` command + `ollama.llama.*` permissions), `config.yml` (new `companion:` block), `OllamaPlugin.java` (decouple companion init from `enabled`), `pom.xml` (version `0.4.0`), `docs/PLUGIN_CHECKLIST.md` (gate evidence).

**Test files** mirror the package under `src/test/java/org/xpfarm/ollama/companion/`.

---

## Task 0: Version bump and config/descriptor scaffolding

**Files:**
- Modify: `pom.xml` (version)
- Modify: `src/main/resources/plugin.yml` (command + permissions)
- Modify: `src/main/resources/config.yml` (companion block)
- Modify: `src/test/java/org/xpfarm/ollama/PluginDescriptorTest.java` (assert new command + permissions)

**Interfaces:**
- Produces: the `llama` command registration and `ollama.llama.use` / `ollama.llama.give` permission nodes that Task 17 and Task 18 rely on; the `companion.*` config keys every later task reads.

- [ ] **Step 1: Extend the descriptor test to expect the new surface**

In `PluginDescriptorTest.java`, add assertions (follow the existing SnakeYAML-parsing style in that file — it already loads `plugin.yml` into a `Map`):

```java
    @Test
    void declaresLlamaCommand() {
        Map<String, Object> commands = section(PLUGIN_YML, "commands");
        assertTrue(commands.containsKey("llama"), "plugin.yml must declare the llama command");
    }

    @Test
    void declaresCompanionPermissions() {
        Map<String, Object> permissions = section(PLUGIN_YML, "permissions");
        assertTrue(permissions.containsKey("ollama.llama.use"), "ollama.llama.use must be declared");
        assertTrue(permissions.containsKey("ollama.llama.give"), "ollama.llama.give must be declared");
    }
```

If the file has no `section(Path, String)` helper, add one that casts the parsed root map's child map (the file already parses the YAML root; reuse that parse). Keep it consistent with the existing helpers.

- [ ] **Step 2: Run the new tests, verify they fail**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=PluginDescriptorTest`
Expected: FAIL — `llama` command / permission keys absent.

- [ ] **Step 3: Bump the POM version**

In `pom.xml`, change `<version>0.3.0</version>` to `<version>0.4.0</version>`.

- [ ] **Step 4: Add the command and permissions to `plugin.yml`**

Under `commands:` add (keep the description short — Bedrock caps command description length):

```yaml
  llama:
    description: Llama companion
    usage: /llama <ask|recipe|dismiss|give>
    permission: ollama.llama.use
    permission-message: You don't have permission to use the llama companion
```

Under `permissions:` add:

```yaml
  ollama.llama.use:
    description: Craft, summon, and talk to a llama companion
    default: true
  ollama.llama.give:
    description: Give a companion summon item to a player
    default: op
```

- [ ] **Step 5: Add the `companion:` block to `config.yml`**

Append to `config.yml`:

```yaml
# Llama Companion
# Crafting, following, downed-recovery, and nudges work even when the top-level
# `enabled` above is false or the Ollama endpoint is unreachable. Only the
# conversation feature needs Ollama.
companion:
  # Master switch for the companion feature, independent of the Ollama `enabled` flag.
  enabled: true

  # Baseline damage immunity. Downed-recovery (return the summon item at lethal
  # damage) is always active regardless of this value; this only blocks routine damage.
  invulnerable: true

  # Ticks between follow-path re-issues. Lower = tighter following, more pathfinding cost.
  follow_interval: 10

  # Blocks of separation before the companion teleports to catch up.
  teleport_distance: 24

  nudges:
    # Unprompted, rule-based reminders. No Ollama call — instant and cannot hallucinate.
    enabled: true
    # Seconds between nudges, per player.
    cooldown: 300
    # Which rules are active. Unknown names are dropped with a logged warning.
    rules:
      - shield
      - food
      - tool_durability
      - torches
```

- [ ] **Step 6: Run the descriptor tests, verify they pass**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=PluginDescriptorTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/plugin.yml src/main/resources/config.yml src/test/java/org/xpfarm/ollama/PluginDescriptorTest.java
git commit -m "chore(companion): scaffold 0.4.0 descriptor, command, permissions, and config"
```

---

## Task 1: CompanionKeys — namespaced key constants

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionKeys.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionKeysTest.java`

**Interfaces:**
- Produces:
  - `CompanionKeys.item(Plugin)` → `NamespacedKey` — marks a stack as a summon item.
  - `CompanionKeys.owner(Plugin)` → `NamespacedKey` — stored on the entity, value = owner UUID string.
  - `CompanionKeys.companion(Plugin)` → `NamespacedKey` — stored on the player, value = companion UUID string.
  - `CompanionKeys.downed(Plugin)` → `NamespacedKey` — stored on the entity, value = byte flag.

- [ ] **Step 1: Write the failing test**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class CompanionKeysTest {

    private Plugin pluginNamed(String name) {
        Plugin p = Mockito.mock(Plugin.class);
        // NamespacedKey lowercases the plugin name for its namespace.
        Mockito.when(p.getName()).thenReturn(name);
        return p;
    }

    @Test
    void keysAreStableAndDistinct() {
        Plugin plugin = pluginNamed("Ollama");
        assertEquals("ollama:companion_item", CompanionKeys.item(plugin).toString());
        assertEquals("ollama:companion_owner", CompanionKeys.owner(plugin).toString());
        assertEquals("ollama:companion_uuid", CompanionKeys.companion(plugin).toString());
        assertEquals("ollama:companion_downed", CompanionKeys.downed(plugin).toString());
        assertNotEquals(CompanionKeys.owner(plugin), CompanionKeys.companion(plugin));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionKeysTest`
Expected: FAIL — `CompanionKeys` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Central registry of the plugin's persistent-data keys for the companion feature.
 *
 * <p>One source of truth so the item tag, the entity's owner link, and the player's companion
 * link cannot drift apart across the classes that read and write them.
 */
public final class CompanionKeys {

    private CompanionKeys() {}

    /** Marks an {@code ItemStack} as a companion summon item. */
    public static NamespacedKey item(Plugin plugin) {
        return new NamespacedKey(plugin, "companion_item");
    }

    /** Stored on the entity; value is the owner's UUID as a string. */
    public static NamespacedKey owner(Plugin plugin) {
        return new NamespacedKey(plugin, "companion_owner");
    }

    /** Stored on the player; value is the bound companion's UUID as a string. */
    public static NamespacedKey companion(Plugin plugin) {
        return new NamespacedKey(plugin, "companion_uuid");
    }

    /** Stored on the entity; byte flag marking the companion as downed. */
    public static NamespacedKey downed(Plugin plugin) {
        return new NamespacedKey(plugin, "companion_downed");
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionKeysTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionKeys.java src/test/java/org/xpfarm/ollama/companion/CompanionKeysTest.java
git commit -m "feat(companion): add central NamespacedKey registry"
```

---

## Task 2: CompanionItem — build and identify the summon item

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionItem.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionItemTest.java`

**Interfaces:**
- Consumes: `CompanionKeys.item(Plugin)`.
- Produces:
  - `new CompanionItem(Plugin)`.
  - `ItemStack create(int amount)` — a `LEAD`-based (see note) named summon item, PDC-tagged.
  - `boolean isSummonItem(ItemStack)` — true iff the stack carries the tag.

**Note on base material:** use `Material.LEAD` as the summon item's base — it reads as "leash a companion", is early-game, and only the *output* carries custom data so the recipe resolves server-side for Bedrock. The tag, not the material, is the identity.

- [ ] **Step 1: Write the failing test**

Bukkit `ItemStack`/`ItemMeta` need a served Bukkit registry; the existing tests avoid that by not constructing real `ItemStack`s. Here, use a Mockito-based test that verifies the PDC read/write contract without a live server:

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class CompanionItemTest {

    private Plugin plugin() {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn("Ollama");
        return p;
    }

    @Test
    void identifiesATaggedStack() {
        Plugin plugin = plugin();
        NamespacedKey key = CompanionKeys.item(plugin);

        ItemStack stack = mock(ItemStack.class);
        when(stack.hasItemMeta()).thenReturn(true);
        ItemMeta meta = mock(ItemMeta.class);
        when(stack.getItemMeta()).thenReturn(meta);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(eq(key), any())).thenReturn(true);

        assertTrue(new CompanionItem(plugin).isSummonItem(stack));
    }

    @Test
    void rejectsAnUntaggedStack() {
        Plugin plugin = plugin();
        ItemStack stack = mock(ItemStack.class);
        when(stack.hasItemMeta()).thenReturn(false);
        assertFalse(new CompanionItem(plugin).isSummonItem(stack));
    }

    @Test
    void rejectsNull() {
        assertFalse(new CompanionItem(plugin()).isSummonItem(null));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionItemTest`
Expected: FAIL — `CompanionItem` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Builds and identifies the companion summon item.
 *
 * <p>Identity is the persistent-data tag, never the material. Only the output of the recipe
 * carries this tag, which is why the recipe still resolves server-side for Bedrock players even
 * though Geyser strips NBT from recipe-book previews.
 */
public final class CompanionItem {

    private final NamespacedKey key;

    public CompanionItem(Plugin plugin) {
        this.key = CompanionKeys.item(plugin);
    }

    public ItemStack create(int amount) {
        ItemStack stack = new ItemStack(Material.LEAD, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Llama Companion", NamedTextColor.AQUA)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                Component.text("Place to summon your companion.", NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isSummonItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(key, PersistentDataType.BYTE);
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionItemTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionItem.java src/test/java/org/xpfarm/ollama/companion/CompanionItemTest.java
git commit -m "feat(companion): build and identify the PDC-tagged summon item"
```

---

## Task 3: CompanionRecipe — register the shaped recipe

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionRecipe.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionRecipeTest.java`

**Interfaces:**
- Consumes: `CompanionItem.create(int)`, `CompanionKeys` (a recipe key).
- Produces:
  - `new CompanionRecipe(Plugin, CompanionItem)`.
  - `ShapedRecipe build()` — a vanilla-ingredient recipe whose result is the summon item.
  - `NamespacedKey key()` — the recipe key, for `discoverRecipe`.
  - `void register(Server)` — calls `server.addRecipe(build())`.

**Recipe (early-game, vanilla ingredients only):**
```
 W L W      W = WHITE_WOOL   L = LEAD
 H G H      H = HAY_BLOCK    G = GOLD_INGOT
 W L W
```
Reachable in an hour or two, no nether trip — per the design's cost decision.

- [ ] **Step 1: Write the failing test**

`ShapedRecipe`/`NamespacedKey` construction needs no live server for the key; assert the recipe key is stable. Recipe *contents* need Bukkit registries, so verify only what is testable without a server — the key — and defer shape verification to gate 7a.

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class CompanionRecipeTest {

    @Test
    void recipeKeyIsStable() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Ollama");
        CompanionRecipe recipe = new CompanionRecipe(plugin, new CompanionItem(plugin));
        assertEquals("ollama:companion_recipe", recipe.key().toString());
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionRecipeTest`
Expected: FAIL — `CompanionRecipe` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;

/**
 * The companion's crafting recipe. Vanilla ingredients only, so the result — and only the result —
 * carries custom data; that is what lets Bedrock players hand-craft it even though custom recipes
 * do not appear in their recipe book.
 */
public final class CompanionRecipe {

    private final Plugin plugin;
    private final CompanionItem item;
    private final NamespacedKey key;

    public CompanionRecipe(Plugin plugin, CompanionItem item) {
        this.plugin = plugin;
        this.item = item;
        this.key = new NamespacedKey(plugin, "companion_recipe");
    }

    public NamespacedKey key() {
        return key;
    }

    public ShapedRecipe build() {
        ShapedRecipe recipe = new ShapedRecipe(key, item.create(1));
        recipe.shape("WLW", "HGH", "WLW");
        recipe.setIngredient('W', Material.WHITE_WOOL);
        recipe.setIngredient('L', Material.LEAD);
        recipe.setIngredient('H', Material.HAY_BLOCK);
        recipe.setIngredient('G', Material.GOLD_INGOT);
        return recipe;
    }

    /** Registers the recipe. Safe to call once at enable; there is no recipe lifecycle event on 26.1. */
    public void register(Server server) {
        // removeRecipe first so a /reload does not throw on a duplicate key.
        server.removeRecipe(key);
        server.addRecipe(build());
        plugin.getLogger().info("Registered companion recipe " + key);
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionRecipeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionRecipe.java src/test/java/org/xpfarm/ollama/companion/CompanionRecipeTest.java
git commit -m "feat(companion): register the vanilla-ingredient shaped recipe"
```

---

## Task 4: CompanionRegistry — bidirectional ownership binding

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionRegistry.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionRegistryTest.java`

**Interfaces:**
- Consumes: `CompanionKeys.owner(Plugin)`, `CompanionKeys.companion(Plugin)`.
- Produces:
  - `new CompanionRegistry(Plugin)`.
  - `void bind(Player owner, LivingEntity companion)` — writes both PDC links.
  - `UUID companionOf(Player owner)` — reads the player's link, or `null`.
  - `UUID ownerOf(LivingEntity companion)` — reads the entity's link, or `null`.
  - `boolean isOwner(Player player, LivingEntity companion)`.
  - `void unbind(Player owner)` — clears the player's link only (the entity is being removed).

- [ ] **Step 1: Write the failing test**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class CompanionRegistryTest {

    private Plugin plugin() {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn("Ollama");
        return p;
    }

    @Test
    void resolvesCompanionUuidFromPlayerPdc() {
        Plugin plugin = plugin();
        NamespacedKey key = CompanionKeys.companion(plugin);
        UUID companionId = UUID.randomUUID();

        Player owner = mock(Player.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(owner.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(eq(key), eq(PersistentDataType.STRING))).thenReturn(companionId.toString());

        assertEquals(companionId, new CompanionRegistry(plugin).companionOf(owner));
    }

    @Test
    void returnsNullWhenNoCompanionBound() {
        Plugin plugin = plugin();
        Player owner = mock(Player.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(owner.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenReturn(null);
        assertNull(new CompanionRegistry(plugin).companionOf(owner));
    }

    @Test
    void isOwnerComparesEntityLinkToPlayerId() {
        Plugin plugin = plugin();
        NamespacedKey ownerKey = CompanionKeys.owner(plugin);
        UUID playerId = UUID.randomUUID();

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        LivingEntity companion = mock(LivingEntity.class);
        PersistentDataContainer epdc = mock(PersistentDataContainer.class);
        when(companion.getPersistentDataContainer()).thenReturn(epdc);
        when(epdc.get(eq(ownerKey), eq(PersistentDataType.STRING))).thenReturn(playerId.toString());

        assertTrue(new CompanionRegistry(plugin).isOwner(player, companion));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionRegistryTest`
Expected: FAIL — `CompanionRegistry` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Bidirectional ownership between a player and their companion, stored in persistent data on both
 * sides. Resolution goes through {@link org.bukkit.Bukkit#getEntity(UUID)} at the call site — an
 * O(1) lookup that does not load chunks. A null resolution means the chunk is unloaded, not that
 * the companion is gone; the binding is dropped only on explicit removal.
 */
public final class CompanionRegistry {

    private final NamespacedKey ownerKey;
    private final NamespacedKey companionKey;

    public CompanionRegistry(Plugin plugin) {
        this.ownerKey = CompanionKeys.owner(plugin);
        this.companionKey = CompanionKeys.companion(plugin);
    }

    public void bind(Player owner, LivingEntity companion) {
        owner.getPersistentDataContainer()
                .set(companionKey, PersistentDataType.STRING, companion.getUniqueId().toString());
        companion.getPersistentDataContainer()
                .set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
    }

    public UUID companionOf(Player owner) {
        return parse(owner.getPersistentDataContainer().get(companionKey, PersistentDataType.STRING));
    }

    public UUID ownerOf(LivingEntity companion) {
        return parse(companion.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    public boolean isOwner(Player player, LivingEntity companion) {
        UUID owner = ownerOf(companion);
        return owner != null && owner.equals(player.getUniqueId());
    }

    public void unbind(Player owner) {
        owner.getPersistentDataContainer().remove(companionKey);
    }

    private static UUID parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionRegistryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionRegistry.java src/test/java/org/xpfarm/ollama/companion/CompanionRegistryTest.java
git commit -m "feat(companion): bidirectional ownership binding via PDC"
```

---

## Task 5: CompanionEntity — spawn and configure a bound llama

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionEntity.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionEntityTest.java`

**Interfaces:**
- Consumes: `CompanionRegistry.bind(Player, LivingEntity)`.
- Produces:
  - `new CompanionEntity(Plugin, CompanionRegistry)`.
  - `Llama summon(Player owner, Location at)` — spawns, configures, binds, returns the llama.
  - `boolean isDowned(Llama)` / `void setDowned(Llama, boolean)` — PDC downed flag.

**Configuration applied on spawn (each is load-bearing — the comments say why):**
- `setPersistent(true)` — saved to disk.
- `setRemoveWhenFarAway(false)` — this, not `setPersistent`, is what stops vanilla despawn.
- `setTamed(true)` + `setOwner(owner)` — ownership flag (does *not* create follow behavior; `FollowTask` does).
- `setAware(false)` is **not** applied at spawn (only when downed) — an unaware mob ignores our pathfinder too.
- Strip vanilla goals so they do not fight `FollowTask`: `Bukkit.getMobGoals().removeAllGoals(llama)`.

- [ ] **Step 1: Write the failing test (downed flag round-trip — the unit-testable core)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Llama;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class CompanionEntityTest {

    private Plugin plugin() {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn("Ollama");
        return p;
    }

    @Test
    void readsDownedFlagTrueWhenSet() {
        Plugin plugin = plugin();
        NamespacedKey key = CompanionKeys.downed(plugin);
        Llama llama = mock(Llama.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(llama.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getOrDefault(eq(key), eq(PersistentDataType.BYTE), eq((byte) 0))).thenReturn((byte) 1);

        assertTrue(new CompanionEntity(plugin, new CompanionRegistry(plugin)).isDowned(llama));
    }

    @Test
    void readsDownedFlagFalseByDefault() {
        Plugin plugin = plugin();
        NamespacedKey key = CompanionKeys.downed(plugin);
        Llama llama = mock(Llama.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(llama.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getOrDefault(eq(key), eq(PersistentDataType.BYTE), eq((byte) 0))).thenReturn((byte) 0);

        assertFalse(new CompanionEntity(plugin, new CompanionRegistry(plugin)).isDowned(llama));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionEntityTest`
Expected: FAIL — `CompanionEntity` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Spawns and configures a companion llama, and reads/writes its downed flag.
 *
 * <p>The two persistence properties set here are different and both required: {@code setPersistent}
 * saves the entity to disk, while {@code setRemoveWhenFarAway(false)} is the one that actually stops
 * vanilla despawn. Vanilla goals are stripped so {@link FollowTask}'s pathfinder is not fought every
 * tick — tamed llamas do not follow their owner on their own.
 */
public final class CompanionEntity {

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final NamespacedKey downedKey;

    public CompanionEntity(Plugin plugin, CompanionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.downedKey = CompanionKeys.downed(plugin);
    }

    public Llama summon(Player owner, Location at) {
        Llama llama = at.getWorld().spawn(at, Llama.class, spawned -> {
            spawned.setPersistent(true);
            spawned.setRemoveWhenFarAway(false);
            spawned.setTamed(true);
            spawned.setOwner(owner);
            spawned.customName(net.kyori.adventure.text.Component.text(owner.getName() + "'s Llama"));
            spawned.setCustomNameVisible(true);
        });
        Bukkit.getMobGoals().removeAllGoals(llama);
        registry.bind(owner, llama);
        return llama;
    }

    public boolean isDowned(Llama llama) {
        return llama.getPersistentDataContainer()
                .getOrDefault(downedKey, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    public void setDowned(Llama llama, boolean downed) {
        if (downed) {
            llama.getPersistentDataContainer().set(downedKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            llama.getPersistentDataContainer().remove(downedKey);
        }
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionEntityTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionEntity.java src/test/java/org/xpfarm/ollama/companion/CompanionEntityTest.java
git commit -m "feat(companion): spawn and configure a bound, non-despawning llama"
```

**Gate 7a runtime check (cannot be unit-tested — record for gate 7a):** `/llama give` then place → a llama spawns, is named, is tamed to the owner, and does not despawn when the owner walks 200 blocks away and back.

---

## Task 6: Summon-on-place listener

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionPlaceListener.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionPlaceListenerTest.java`

**Interfaces:**
- Consumes: `CompanionItem.isSummonItem(ItemStack)`, `CompanionEntity.summon(Player, Location)`, `CompanionRegistry.companionOf(Player)`.
- Produces: an `org.bukkit.event.Listener` handling `PlayerInteractEvent` (right-click block/air with a summon item). One companion per player: if the player already has a live companion, refuse and tell them.

**Behavior:** on right-click with a summon item, cancel the interaction, consume one item, spawn at the clicked location (or the player's location for air). If `companionOf(player)` resolves to a live entity, do not spawn a second — send "You already have a companion nearby."

- [ ] **Step 1: Write the failing test (already-has-companion guard — the testable core)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class CompanionPlaceListenerTest {

    @Test
    void reportsAnExistingLiveCompanion() {
        CompanionRegistry registry = mock(CompanionRegistry.class);
        Player player = mock(Player.class);
        UUID companionId = UUID.randomUUID();
        when(registry.companionOf(player)).thenReturn(companionId);

        // hasLiveCompanion is the pure decision point extracted for testing: a bound UUID that
        // resolves to a non-null entity means "already has one". Bukkit.getEntity is passed in.
        CompanionPlaceListener listener = new CompanionPlaceListener(null, null, null, registry);
        assertTrue(listener.hasLiveCompanion(player, id -> mock(org.bukkit.entity.Entity.class)));
    }

    @Test
    void noCompanionWhenUnbound() {
        CompanionRegistry registry = mock(CompanionRegistry.class);
        Player player = mock(Player.class);
        when(registry.companionOf(player)).thenReturn(null);
        CompanionPlaceListener listener = new CompanionPlaceListener(null, null, null, registry);
        assertTrue(!listener.hasLiveCompanion(player, id -> null));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionPlaceListenerTest`
Expected: FAIL — `CompanionPlaceListener` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/** Turns a right-click with a summon item into a spawned, bound companion. One per player. */
public final class CompanionPlaceListener implements Listener {

    private final Plugin plugin;
    private final CompanionItem item;
    private final CompanionEntity entity;
    private final CompanionRegistry registry;

    public CompanionPlaceListener(Plugin plugin, CompanionItem item, CompanionEntity entity,
            CompanionRegistry registry) {
        this.plugin = plugin;
        this.item = item;
        this.entity = entity;
        this.registry = registry;
    }

    /** Pure decision: does the player have a companion UUID that still resolves to an entity? */
    public boolean hasLiveCompanion(Player player, Function<UUID, Entity> resolver) {
        UUID id = registry.companionOf(player);
        return id != null && resolver.apply(id) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        // Guard the double-fire: only act on the main hand.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (!item.isSummonItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (hasLiveCompanion(player, Bukkit::getEntity)) {
            player.sendMessage(Component.text("You already have a companion nearby.", NamedTextColor.YELLOW));
            return;
        }

        Location at = event.getClickedBlock() != null
                ? event.getClickedBlock().getLocation().add(0.5, 1, 0.5)
                : player.getLocation();
        entity.summon(player, at);

        var held = player.getInventory().getItemInMainHand();
        held.setAmount(held.getAmount() - 1);
        player.sendMessage(Component.text("Your llama companion appears.", NamedTextColor.AQUA));
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionPlaceListenerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionPlaceListener.java src/test/java/org/xpfarm/ollama/companion/CompanionPlaceListenerTest.java
git commit -m "feat(companion): summon on right-click, one companion per player"
```

**Gate 7a runtime check:** placing a summon item spawns exactly one companion; a second placement while one is alive is refused with a message.

---

## Task 7: FollowTask — pathfinder follow with teleport catch-up

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/FollowTask.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/FollowTaskTest.java`

**Interfaces:**
- Consumes: `CompanionRegistry.companionOf(Player)`, `CompanionEntity.isDowned(Llama)`, config `companion.follow_interval`, `companion.teleport_distance`.
- Produces:
  - `new FollowTask(Plugin, CompanionRegistry, CompanionEntity, int teleportDistance)`.
  - `static boolean shouldTeleport(double distance, int teleportDistance)` — pure catch-up decision.
  - `void start(int intervalTicks)` / `void cancel()` — schedule/stop the repeating task.

**Behavior each tick:** for every online player, resolve their companion via `Bukkit.getEntity`; skip if null (chunk unloaded) or downed or in a different world; if separated beyond `teleport_distance`, `teleportAsync` to the owner; otherwise `llama.getPathfinder().moveTo(owner.getLocation(), speed)`. Use the `Location` overload deliberately — the `LivingEntity` overload has a history of being broken.

- [ ] **Step 1: Write the failing test (the pure teleport decision)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FollowTaskTest {

    @Test
    void teleportsBeyondThreshold() {
        assertTrue(FollowTask.shouldTeleport(24.1, 24));
        assertTrue(FollowTask.shouldTeleport(100.0, 24));
    }

    @Test
    void walksWithinThreshold() {
        assertFalse(FollowTask.shouldTeleport(5.0, 24));
        assertFalse(FollowTask.shouldTeleport(24.0, 24));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=FollowTaskTest`
Expected: FAIL — `FollowTask` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Re-issues a pathfinder move to each companion's owner, teleporting to catch up past a threshold. */
public final class FollowTask {

    private static final double FOLLOW_SPEED = 1.3D;

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final CompanionEntity entity;
    private final int teleportDistance;
    private BukkitRunnable task;

    public FollowTask(Plugin plugin, CompanionRegistry registry, CompanionEntity entity,
            int teleportDistance) {
        this.plugin = plugin;
        this.registry = registry;
        this.entity = entity;
        this.teleportDistance = teleportDistance;
    }

    public static boolean shouldTeleport(double distance, int teleportDistance) {
        return distance > teleportDistance;
    }

    public void start(int intervalTicks) {
        cancel();
        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        this.task.runTaskTimer(plugin, intervalTicks, Math.max(1, intervalTicks));
    }

    private void tick() {
        for (Player owner : Bukkit.getOnlinePlayers()) {
            UUID id = registry.companionOf(owner);
            if (id == null) {
                continue;
            }
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Llama llama) || llama.isDead()) {
                continue;
            }
            if (entity.isDowned(llama)) {
                continue;
            }
            if (!llama.getWorld().equals(owner.getWorld())) {
                llama.teleportAsync(owner.getLocation());
                continue;
            }
            double distance = llama.getLocation().distance(owner.getLocation());
            if (shouldTeleport(distance, teleportDistance)) {
                llama.teleportAsync(owner.getLocation());
            } else if (distance > 3.0) {
                llama.getPathfinder().moveTo(owner.getLocation(), FOLLOW_SPEED);
            }
        }
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=FollowTaskTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/FollowTask.java src/test/java/org/xpfarm/ollama/companion/FollowTaskTest.java
git commit -m "feat(companion): pathfinder follow with teleport catch-up"
```

**Gate 7a runtime check:** the companion follows across chunk boundaries; separating past `teleport_distance` teleports it; a downed companion does not follow.

---

## Task 8: Portal and world-change follow

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionPortalListener.java`
- Test: covered by gate 7a (event plumbing only — no pure logic to unit-test)

**Interfaces:**
- Consumes: `CompanionRegistry.companionOf(Player)`.
- Produces: an `org.bukkit.event.Listener` on `PlayerPortalEvent` and `PlayerChangedWorldEvent` that `teleportAsync`-es the companion to the owner one tick after the player arrives (mobs do not follow through portals on their own).

- [ ] **Step 1: Implement (no unit test — pure event plumbing; gate 7a verifies)**

```java
package org.xpfarm.ollama.companion;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.Plugin;

/** Teleports a companion to its owner after the owner changes world, since mobs do not follow through portals. */
public final class CompanionPortalListener implements Listener {

    private final Plugin plugin;
    private final CompanionRegistry registry;

    public CompanionPortalListener(Plugin plugin, CompanionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player owner = event.getPlayer();
        UUID id = registry.companionOf(owner);
        if (id == null) {
            return;
        }
        // One tick later: the player's destination is loaded and their location is final.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Llama llama && !llama.isDead()) {
                llama.teleportAsync(owner.getLocation());
            }
        });
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn --batch-mode --no-transfer-progress test-compile`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionPortalListener.java
git commit -m "feat(companion): follow the owner across world changes"
```

**Gate 7a runtime check:** walking through a nether portal brings the companion along within a tick.

---

## Task 9: DownedStateListener — downed, not dead

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/DownedStateListener.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/DownedStateListenerTest.java`

**Interfaces:**
- Consumes: `CompanionRegistry.ownerOf(LivingEntity)`, `CompanionEntity.setDowned(Llama, boolean)`, `CompanionItem.create(int)`, config `companion.invulnerable`.
- Produces: an `org.bukkit.event.Listener` on `EntityDamageEvent` at `HIGHEST, ignoreCancelled=true`, plus a pure classifier:
  - `enum Outcome { IGNORE, BLOCK, DOWN, VOID_RESCUE, ALLOW_KILL }`
  - `static Outcome classify(DamageCause cause, double finalDamage, double health, boolean invulnerable)`.

**Rules (from the design):**
- `KILL` (i.e. `/kill`) → `ALLOW_KILL` — passes through so admins can remove it.
- `VOID` → `VOID_RESCUE` — cancel and teleport back (cancelling alone leaves it falling forever).
- lethal (`finalDamage >= health`) → `DOWN` — cancel, `setHealth(1)`, `setAware(false)`, mark downed, despawn, return the summon item to the owner.
- otherwise, if `invulnerable` → `BLOCK` (cancel); else `IGNORE` (let normal damage through, but never past lethal — a subsequent lethal hit re-enters `DOWN`).

- [ ] **Step 1: Write the failing test (the pure classifier)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

final class DownedStateListenerTest {

    @Test
    void killAlwaysPassesThrough() {
        assertEquals(DownedStateListener.Outcome.ALLOW_KILL,
                DownedStateListener.classify(DamageCause.KILL, 100, 20, true));
    }

    @Test
    void voidTriggersRescue() {
        assertEquals(DownedStateListener.Outcome.VOID_RESCUE,
                DownedStateListener.classify(DamageCause.VOID, 100, 20, true));
    }

    @Test
    void lethalDamageDowns() {
        assertEquals(DownedStateListener.Outcome.DOWN,
                DownedStateListener.classify(DamageCause.ENTITY_ATTACK, 25, 20, false));
        assertEquals(DownedStateListener.Outcome.DOWN,
                DownedStateListener.classify(DamageCause.ENTITY_ATTACK, 20, 20, true));
    }

    @Test
    void nonLethalBlockedWhenInvulnerable() {
        assertEquals(DownedStateListener.Outcome.BLOCK,
                DownedStateListener.classify(DamageCause.ENTITY_ATTACK, 5, 20, true));
    }

    @Test
    void nonLethalAllowedWhenNotInvulnerable() {
        assertEquals(DownedStateListener.Outcome.IGNORE,
                DownedStateListener.classify(DamageCause.ENTITY_ATTACK, 5, 20, false));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=DownedStateListenerTest`
Expected: FAIL — `DownedStateListener` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.plugin.Plugin;

/**
 * Implements downed-not-dead: a companion collapses and returns its summon item at lethal damage,
 * rather than dying permanently. {@code setInvulnerable(true)} alone would not do this — it blocks
 * neither the void nor {@code /kill} — so damage is intercepted here instead.
 */
public final class DownedStateListener implements Listener {

    public enum Outcome { IGNORE, BLOCK, DOWN, VOID_RESCUE, ALLOW_KILL }

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final CompanionEntity entity;
    private final CompanionItem item;
    private final boolean invulnerable;

    public DownedStateListener(Plugin plugin, CompanionRegistry registry, CompanionEntity entity,
            CompanionItem item, boolean invulnerable) {
        this.plugin = plugin;
        this.registry = registry;
        this.entity = entity;
        this.item = item;
        this.invulnerable = invulnerable;
    }

    public static Outcome classify(DamageCause cause, double finalDamage, double health,
            boolean invulnerable) {
        if (cause == DamageCause.KILL) {
            return Outcome.ALLOW_KILL;
        }
        if (cause == DamageCause.VOID) {
            return Outcome.VOID_RESCUE;
        }
        if (finalDamage >= health) {
            return Outcome.DOWN;
        }
        return invulnerable ? Outcome.BLOCK : Outcome.IGNORE;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Llama llama)) {
            return;
        }
        UUID ownerId = registry.ownerOf(llama);
        if (ownerId == null) {
            return; // not a companion
        }
        Outcome outcome = classify(event.getCause(), event.getFinalDamage(), llama.getHealth(),
                invulnerable);
        switch (outcome) {
            case ALLOW_KILL -> { /* let it die */ }
            case BLOCK -> event.setCancelled(true);
            case IGNORE -> { /* normal damage */ }
            case VOID_RESCUE -> {
                event.setCancelled(true);
                Player owner = Bukkit.getPlayer(ownerId);
                if (owner != null) {
                    llama.teleportAsync(owner.getLocation());
                }
            }
            case DOWN -> {
                event.setCancelled(true);
                down(llama, ownerId);
            }
        }
    }

    private void down(Llama llama, UUID ownerId) {
        entity.setDowned(llama, true);
        llama.setAware(false);
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null) {
            registry.unbind(owner);
            owner.getInventory().addItem(item.create(1));
            owner.sendMessage(Component.text("Your llama was downed — its charm returns to you.",
                    NamedTextColor.RED));
        }
        llama.remove();
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=DownedStateListenerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/DownedStateListener.java src/test/java/org/xpfarm/ollama/companion/DownedStateListenerTest.java
git commit -m "feat(companion): downed-not-dead damage handling"
```

**Gate 7a runtime check:** lethal damage returns the summon item and removes the entity (not a death drop); `/kill @e` still kills it; void damage returns it to the owner instead of losing it.

---

## Task 10: InventorySnapshot — a Bukkit-free view of the player's own state

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/InventorySnapshot.java`
- Create: `src/main/java/org/xpfarm/ollama/companion/InventorySnapshots.java` (the Bukkit→snapshot boundary)
- Test: `src/test/java/org/xpfarm/ollama/companion/InventorySnapshotTest.java`

**Interfaces:**
- Produces:
  - `record InventorySnapshot(List<String> hotbar, List<String> storage, Map<String,String> armor, String mainHand, String offHand, boolean carriesShield, boolean shieldEquipped, boolean hasFood, int lowestToolDurabilityPct, boolean hasTorches, int health, int hunger, String biome, String dimension, long timeOfDay)`.
  - `InventorySnapshots.of(Player)` → `InventorySnapshot` — the only method that touches Bukkit; keeps the rest of the pipeline pure and testable.

**Design fidelity:** this record holds the asking player's own state only. It has no field for other players and no chat history — that scope boundary is enforced by the type, not by discipline downstream.

- [ ] **Step 1: Write the failing test (the record is a plain value; test it directly)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InventorySnapshotTest {

    private InventorySnapshot snapshot(boolean carriesShield, boolean shieldEquipped) {
        return new InventorySnapshot(List.of(), List.of(), Map.of(), "AIR", "AIR",
                carriesShield, shieldEquipped, true, 100, true, 20, 20, "PLAINS", "world", 1000L);
    }

    @Test
    void distinguishesCarriedFromEquippedShield() {
        assertTrue(snapshot(true, false).carriesShield());
        assertFalse(snapshot(true, false).shieldEquipped());
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=InventorySnapshotTest`
Expected: FAIL — `InventorySnapshot` does not exist.

- [ ] **Step 3: Implement the record**

```java
package org.xpfarm.ollama.companion;

import java.util.List;
import java.util.Map;

/**
 * An immutable, Bukkit-free view of the asking player's own state. By construction it carries no
 * information about any other player and no chat history — the approved context scope is enforced by
 * this type's shape, not by downstream discipline.
 */
public record InventorySnapshot(
        List<String> hotbar,
        List<String> storage,
        Map<String, String> armor,
        String mainHand,
        String offHand,
        boolean carriesShield,
        boolean shieldEquipped,
        boolean hasFood,
        int lowestToolDurabilityPct,
        boolean hasTorches,
        int health,
        int hunger,
        String biome,
        String dimension,
        long timeOfDay) {
}
```

- [ ] **Step 4: Implement the Bukkit boundary**

```java
package org.xpfarm.ollama.companion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** The single place that reads a live {@link Player} into an {@link InventorySnapshot}. Main thread only. */
public final class InventorySnapshots {

    private InventorySnapshots() {}

    public static InventorySnapshot of(Player player) {
        PlayerInventory inv = player.getInventory();

        List<String> hotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            hotbar.add(nameOf(inv.getItem(i)));
        }
        List<String> storage = new ArrayList<>();
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.getType().isAir()) {
                storage.add(s.getType().name());
            }
        }
        Map<String, String> armor = new LinkedHashMap<>();
        armor.put("head", nameOf(inv.getHelmet()));
        armor.put("chest", nameOf(inv.getChestplate()));
        armor.put("legs", nameOf(inv.getLeggings()));
        armor.put("feet", nameOf(inv.getBoots()));

        boolean carriesShield = inv.contains(Material.SHIELD)
                || inv.getItemInOffHand().getType() == Material.SHIELD;
        boolean shieldEquipped = inv.getItemInOffHand().getType() == Material.SHIELD;

        boolean hasFood = false;
        boolean hasTorches = false;
        int lowestToolPct = 100;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                continue;
            }
            if (s.getType().isEdible()) {
                hasFood = true;
            }
            if (s.getType() == Material.TORCH || s.getType() == Material.SOUL_TORCH) {
                hasTorches = true;
            }
            lowestToolPct = Math.min(lowestToolPct, durabilityPct(s));
        }

        return new InventorySnapshot(hotbar, storage, armor,
                nameOf(inv.getItemInMainHand()), nameOf(inv.getItemInOffHand()),
                carriesShield, shieldEquipped, hasFood, lowestToolPct, hasTorches,
                (int) Math.round(player.getHealth()), player.getFoodLevel(),
                player.getWorld().getBlockAt(player.getLocation()).getBiome().toString(),
                player.getWorld().getName(), player.getWorld().getTime());
    }

    private static String nameOf(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? "AIR" : stack.getType().name();
    }

    /** Durability as a percentage; 100 for items that do not take damage. */
    private static int durabilityPct(ItemStack stack) {
        if (!(stack.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) || !dmg.hasDamage()) {
            return 100;
        }
        short max = stack.getType().getMaxDurability();
        if (max <= 0) {
            return 100;
        }
        return (int) Math.round(100.0 * (max - dmg.getDamage()) / max);
    }
}
```

- [ ] **Step 5: Run the test, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=InventorySnapshotTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/InventorySnapshot.java src/main/java/org/xpfarm/ollama/companion/InventorySnapshots.java src/test/java/org/xpfarm/ollama/companion/InventorySnapshotTest.java
git commit -m "feat(companion): own-state-only inventory snapshot with a Bukkit boundary"
```

---

## Task 11: InventoryAdvisor — pure nudge rules

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/InventoryAdvisor.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/InventoryAdvisorTest.java`

**Interfaces:**
- Consumes: `InventorySnapshot`.
- Produces:
  - `new InventoryAdvisor(Set<String> enabledRules)`.
  - `List<String> advise(InventorySnapshot)` — zero or more nudge strings, honoring the enabled-rule set.

**Rules (each keyed by config name):**
- `shield` — `carriesShield && !shieldEquipped` → "You've got a shield but it's not in your off-hand."
- `food` — `!hasFood` → "You're out of food — might want to grab some before you get hungry."
- `tool_durability` — `lowestToolDurabilityPct <= 10` → "One of your tools is about to break."
- `torches` — `!hasTorches` → "No torches on you — it's dark out there."

- [ ] **Step 1: Write the failing test**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class InventoryAdvisorTest {

    private InventorySnapshot snap(boolean carriesShield, boolean shieldEquipped, boolean hasFood,
            int toolPct, boolean hasTorches) {
        return new InventorySnapshot(List.of(), List.of(), Map.of(), "AIR", "AIR",
                carriesShield, shieldEquipped, hasFood, toolPct, hasTorches, 20, 20, "PLAINS",
                "world", 1000L);
    }

    @Test
    void nudgesAnUnequippedShield() {
        List<String> out = new InventoryAdvisor(Set.of("shield"))
                .advise(snap(true, false, true, 100, true));
        assertEquals(1, out.size());
        assertTrue(out.get(0).toLowerCase().contains("shield"));
    }

    @Test
    void silentWhenShieldEquipped() {
        assertTrue(new InventoryAdvisor(Set.of("shield"))
                .advise(snap(true, true, true, 100, true)).isEmpty());
    }

    @Test
    void respectsDisabledRules() {
        // food is missing, but only the shield rule is enabled → no food nudge
        assertTrue(new InventoryAdvisor(Set.of("shield"))
                .advise(snap(false, false, false, 100, true)).isEmpty());
    }

    @Test
    void firesMultipleEnabledRules() {
        List<String> out = new InventoryAdvisor(Set.of("food", "torches"))
                .advise(snap(false, false, false, 100, false));
        assertEquals(2, out.size());
    }

    @Test
    void toolDurabilityFiresAtThreshold() {
        assertEquals(1, new InventoryAdvisor(Set.of("tool_durability"))
                .advise(snap(false, false, true, 10, true)).size());
        assertTrue(new InventoryAdvisor(Set.of("tool_durability"))
                .advise(snap(false, false, true, 11, true)).isEmpty());
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=InventoryAdvisorTest`
Expected: FAIL — `InventoryAdvisor` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, LLM-free reminders. Instant, free, and incapable of hallucinating — which is why
 * unprompted advice is rules, not model output (a warm Ollama serves one request at a time, so
 * per-player LLM nudges would not survive a populated server).
 */
public final class InventoryAdvisor {

    private final Set<String> enabled;

    public InventoryAdvisor(Set<String> enabledRules) {
        this.enabled = enabledRules;
    }

    public List<String> advise(InventorySnapshot s) {
        List<String> out = new ArrayList<>();
        if (enabled.contains("shield") && s.carriesShield() && !s.shieldEquipped()) {
            out.add("You've got a shield but it's not in your off-hand.");
        }
        if (enabled.contains("food") && !s.hasFood()) {
            out.add("You're out of food — might want to grab some before you get hungry.");
        }
        if (enabled.contains("tool_durability") && s.lowestToolDurabilityPct() <= 10) {
            out.add("One of your tools is about to break.");
        }
        if (enabled.contains("torches") && !s.hasTorches()) {
            out.add("No torches on you — it's dark out there.");
        }
        return out;
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=InventoryAdvisorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/InventoryAdvisor.java src/test/java/org/xpfarm/ollama/companion/InventoryAdvisorTest.java
git commit -m "feat(companion): deterministic inventory nudge rules"
```

---

## Task 12: CompanionContext — assemble the bounded prompt context

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionContext.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionContextTest.java`

**Interfaces:**
- Consumes: `InventorySnapshot`.
- Produces: `static String describe(InventorySnapshot)` — a compact, human-readable context block for the system prompt. Own-state only.

- [ ] **Step 1: Write the failing test**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CompanionContextTest {

    @Test
    void describesOwnStateAndNeverOtherPlayers() {
        InventorySnapshot s = new InventorySnapshot(
                List.of("DIAMOND_SWORD", "TORCH"), List.of("IRON_INGOT"),
                Map.of("head", "IRON_HELMET"), "DIAMOND_SWORD", "SHIELD",
                true, true, true, 80, true, 18, 17, "PLAINS", "world", 6000L);
        String out = CompanionContext.describe(s);
        assertTrue(out.contains("DIAMOND_SWORD"));
        assertTrue(out.contains("PLAINS"));
        assertTrue(out.contains("18")); // health
        // No leakage channel exists in the type, but assert the prose never invents a "nearby" line.
        assertFalse(out.toLowerCase().contains("nearby player"));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionContextTest`
Expected: FAIL — `CompanionContext` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

/** Renders an {@link InventorySnapshot} into a compact context block for the companion's system prompt. */
public final class CompanionContext {

    private CompanionContext() {}

    public static String describe(InventorySnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Player state (this player only):\n");
        sb.append("- Health: ").append(s.health()).append("/20, Hunger: ").append(s.hunger()).append("/20\n");
        sb.append("- Dimension: ").append(s.dimension()).append(", Biome: ").append(s.biome())
                .append(", Time: ").append(s.timeOfDay()).append("\n");
        sb.append("- Main hand: ").append(s.mainHand()).append(", Off hand: ").append(s.offHand()).append("\n");
        sb.append("- Hotbar: ").append(String.join(", ", s.hotbar())).append("\n");
        sb.append("- Armor: ").append(s.armor()).append("\n");
        if (!s.storage().isEmpty()) {
            sb.append("- Inventory: ").append(String.join(", ", s.storage())).append("\n");
        }
        sb.append("- Carries shield: ").append(s.carriesShield())
                .append(" (equipped: ").append(s.shieldEquipped()).append(")\n");
        sb.append("- Has food: ").append(s.hasFood())
                .append(", Has torches: ").append(s.hasTorches())
                .append(", Lowest tool durability: ").append(s.lowestToolDurabilityPct()).append("%\n");
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionContextTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionContext.java src/test/java/org/xpfarm/ollama/companion/CompanionContextTest.java
git commit -m "feat(companion): render bounded own-state prompt context"
```

---

## Task 13: CommandSuggester — parse a reply into click-to-prefill text

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CommandSuggester.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CommandSuggesterTest.java`

**Interfaces:**
- Produces:
  - `record Suggestion(String prose, List<String> commands)`.
  - `static Suggestion parse(String reply)` — split a model reply into prose and any `/command` lines it proposed. Never executes anything.

**Parsing rule:** a line whose first non-whitespace character is `/` is a suggested command; everything else is prose. Commands are surfaced for click-to-prefill (Java) or copy (Bedrock); the plugin never runs them.

- [ ] **Step 1: Write the failing test**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CommandSuggesterTest {

    @Test
    void extractsSlashCommandLines() {
        CommandSuggester.Suggestion s = CommandSuggester.parse(
                "Try this to give yourself a torch:\n/give @s torch 16\nThen place it down.");
        assertEquals(1, s.commands().size());
        assertEquals("/give @s torch 16", s.commands().get(0));
        assertTrue(s.prose().contains("Try this"));
        assertTrue(s.prose().contains("place it down"));
    }

    @Test
    void noCommandsMeansEmptyList() {
        CommandSuggester.Suggestion s = CommandSuggester.parse("Just dig straight down. (Don't.)");
        assertTrue(s.commands().isEmpty());
        assertEquals("Just dig straight down. (Don't.)", s.prose().trim());
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CommandSuggesterTest`
Expected: FAIL — `CommandSuggester` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a model reply into prose and proposed {@code /commands}. It never runs a command — the
 * player reads the suggestion and chooses to run it themselves. A hallucinated command is therefore
 * wrong text on screen, not an executed action.
 */
public final class CommandSuggester {

    private CommandSuggester() {}

    public record Suggestion(String prose, List<String> commands) {}

    public static Suggestion parse(String reply) {
        List<String> commands = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        for (String line : reply.split("\n", -1)) {
            if (line.strip().startsWith("/")) {
                commands.add(line.strip());
            } else {
                if (prose.length() > 0) {
                    prose.append("\n");
                }
                prose.append(line);
            }
        }
        return new Suggestion(prose.toString(), commands);
    }
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CommandSuggesterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CommandSuggester.java src/test/java/org/xpfarm/ollama/companion/CommandSuggesterTest.java
git commit -m "feat(companion): parse suggested commands without executing them"
```

---

## Task 14: CompanionConversation — orchestrate ask and degrade gracefully

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionConversation.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionConversationTest.java`
- Create: `src/main/resources/llama-companion.md` (the personality system prompt)

**Interfaces:**
- Consumes: `OllamaAPI.chatWithRequest(ChatRequest, Player, Consumer<ChatResponse>)`, `CompanionContext.describe(InventorySnapshot)`, `CommandSuggester.parse(String)`, `ChatMessage`.
- Produces:
  - `new CompanionConversation(OllamaPlugin, String personalityPrompt)`.
  - `boolean canConverse()` — false when `plugin.getOllamaAPI() == null` (top-level `enabled: false`).
  - `static String degradedMessage()` — the in-character "can't think right now" line.
  - `ChatRequest buildRequest(String model, List<ChatMessage> history, String userText, InventorySnapshot snapshot, String personality)` — pure: assembles messages + system prompt via `ChatRequest.setSystemPrompt`. **Unit-tested.**
  - `void ask(Player player, String text)` — the wired entry point.

**Session storage (deliberate deviation, recorded in the checklist):** the companion keeps its **own** per-player history map, *not* the shared `ChatSessionManager` used by `/ollama chat`. Reason: the llama has a distinct personality system prompt; interleaving its turns with `/ollama chat` history would blend two personas in one transcript. This departs from the design doc's "reuses the ChatSessionManager" wording; the intent (a held conversation) is preserved, the persona separation is improved. Record under gate 12 notes.

- [ ] **Step 1: Write the failing test (pure request assembly + degrade message)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.xpfarm.ollama.api.models.ChatMessage;
import org.xpfarm.ollama.api.models.ChatRequest;

final class CompanionConversationTest {

    private InventorySnapshot snapshot() {
        return new InventorySnapshot(List.of("TORCH"), List.of(), Map.of(), "AIR", "SHIELD",
                true, true, true, 100, true, 20, 20, "PLAINS", "world", 1000L);
    }

    @Test
    void buildsSystemPromptAsLeadingSystemMessage() {
        ChatRequest req = CompanionConversation.buildRequest(
                "llama3.2", List.of(ChatMessage.user("hi")), "what should I build?",
                snapshot(), "You are a friendly llama.");

        // The system prompt must be the FIRST message with role system — never a top-level field.
        assertEquals("system", req.getMessages().get(0).getRole());
        assertTrue(req.getMessages().get(0).getContent().contains("friendly llama"));
        // Context is folded into the system message (own-state only).
        assertTrue(req.getMessages().get(0).getContent().contains("PLAINS"));
        // The new user turn is last.
        ChatMessage last = req.getMessages().get(req.getMessages().size() - 1);
        assertEquals("user", last.getRole());
        assertEquals("what should I build?", last.getContent());
    }

    @Test
    void degradedMessageIsInCharacter() {
        assertFalse(CompanionConversation.degradedMessage().isBlank());
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionConversationTest`
Expected: FAIL — `CompanionConversation` does not exist.

- [ ] **Step 3: Create the personality prompt resource**

`src/main/resources/llama-companion.md`:

```markdown
You are a friendly, slightly sassy llama companion in Minecraft, bonded to one player.
Speak in short, warm, in-character lines — you are a pet, not a manual.
You can see only THIS player's own state (below). You never know about other players.

When the player asks how to do something that a command would help with, you MAY suggest a
single Minecraft command on its own line starting with "/". You never run commands yourself —
you just suggest, and the player decides. Never suggest destructive commands.

Keep replies under three sentences unless asked for detail.
```

- [ ] **Step 4: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.xpfarm.ollama.OllamaPlugin;
import org.xpfarm.ollama.api.models.ChatMessage;
import org.xpfarm.ollama.api.models.ChatRequest;
import org.xpfarm.ollama.api.models.ChatResponse;

/** Orchestrates a companion conversation turn, degrading to an in-character line when Ollama is off. */
public final class CompanionConversation {

    private static final int MAX_HISTORY = 10;

    private final OllamaPlugin plugin;
    private final String personality;
    // The companion's OWN history, separate from /ollama chat, so personas do not blend.
    private final Map<UUID, List<ChatMessage>> history = new ConcurrentHashMap<>();

    public CompanionConversation(OllamaPlugin plugin, String personality) {
        this.plugin = plugin;
        this.personality = personality;
    }

    public boolean canConverse() {
        return plugin.getOllamaAPI() != null;
    }

    public static String degradedMessage() {
        return "*the llama blinks slowly* ...I can't think clearly right now. Try me again later.";
    }

    /** Pure assembly: history + new user turn, with the personality and own-state context as a system message. */
    public static ChatRequest buildRequest(String model, List<ChatMessage> history, String userText,
            InventorySnapshot snapshot, String personality) {
        List<ChatMessage> messages = new ArrayList<>(history);
        messages.add(ChatMessage.user(userText));
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setMessages(messages);
        request.setStream(false);
        String system = personality + "\n\n" + CompanionContext.describe(snapshot);
        request.setSystemPrompt(system); // leading role:system message — never a top-level field
        return request;
    }

    public void ask(Player player, String text) {
        if (!canConverse()) {
            player.sendMessage(Component.text(degradedMessage(), NamedTextColor.GRAY));
            return;
        }
        InventorySnapshot snapshot = InventorySnapshots.of(player); // main thread
        List<ChatMessage> hist = history.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        ChatRequest request = buildRequest(plugin.getOllamaAPI().getDefaultModel(),
                hist, text, snapshot, personality);

        plugin.getOllamaAPI().chatWithRequest(request, player, response -> deliver(player, text, response));
    }

    private void deliver(Player player, String userText, ChatResponse response) {
        if (response == null || response.getError() != null || response.getMessage() == null) {
            player.sendMessage(Component.text(degradedMessage(), NamedTextColor.GRAY));
            return;
        }
        String reply = response.getMessage().getContent();
        // Persist the turn (bounded).
        List<ChatMessage> hist = history.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        hist.add(ChatMessage.user(userText));
        hist.add(ChatMessage.assistant(reply));
        while (hist.size() > MAX_HISTORY) {
            hist.remove(0);
        }

        CommandSuggester.Suggestion s = CommandSuggester.parse(reply);
        player.sendMessage(Component.text("🦙 ", NamedTextColor.AQUA)
                .append(Component.text(s.prose(), NamedTextColor.WHITE)));
        for (String cmd : s.commands()) {
            player.sendMessage(Component.text("  ▶ " + cmd, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.suggestCommand(cmd))); // prefill, never run
        }
    }

    public void forget(Player player) {
        history.remove(player.getUniqueId());
    }
}
```

- [ ] **Step 5: Run the test, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionConversationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionConversation.java src/main/resources/llama-companion.md src/test/java/org/xpfarm/ollama/companion/CompanionConversationTest.java
git commit -m "feat(companion): conversation orchestration with graceful degradation"
```

**Gate 7a runtime check:** with Ollama up, `/llama ask` returns an in-character reply; a suggested `/command` appears as click-to-prefill and does NOT execute. With Ollama down, it returns the degraded line and the server stays up.

---

## Task 15: CompanionDialog + interaction — right-click to talk

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionDialog.java`
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionInteractionListener.java`
- Test: covered by gate 7a (Paper Dialog API + events; no pure logic beyond what Task 14 tests)

**Interfaces:**
- Consumes: `CompanionConversation.ask(Player, String)`, `CompanionRegistry.isOwner(Player, LivingEntity)`.
- Produces:
  - `CompanionDialog.open(Player)` — shows a Paper `Dialog` with a single text input (`max_length` set explicitly high — Geyser defaults to 32 and ignores multiline), whose submit calls `conversation.ask`.
  - `CompanionInteractionListener` — `PlayerInteractAtEntityEvent`, hand-guarded, owner-checked, cancels the vanilla mount, opens the dialog one tick later.

- [ ] **Step 1: Implement the dialog**

```java
package org.xpfarm.ollama.companion;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * The conversation UI. A Paper Dialog with one text input; Geyser auto-converts it to a Bedrock
 * form, so this single path serves both editions with no Geyser dependency. max_length is set
 * explicitly because Geyser defaults dialog inputs to 32 chars and ignores multiline.
 */
public final class CompanionDialog {

    private final CompanionConversation conversation;

    public CompanionDialog(CompanionConversation conversation) {
        this.conversation = conversation;
    }

    public void open(Player player) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Talk to your llama"))
                        .body(List.of(DialogBody.plainMessage(Component.text("What do you want to ask?"))))
                        .inputs(List.of(DialogInput.text("prompt", Component.text("You:"))
                                .maxLength(256)   // Bedrock chat is dropped past 256 anyway; match it.
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Ask"))
                                .action(DialogAction.customClick((response, audience) -> {
                                    String text = response.getText("prompt");
                                    if (audience instanceof Player p && text != null && !text.isBlank()) {
                                        conversation.ask(p, text.strip());
                                    }
                                }, null))
                                .build(),
                        ActionButton.builder(Component.text("Never mind")).build())));
        player.showDialog(dialog);
    }
}
```

**Note for the implementer:** the exact Paper Dialog builder API (`io.papermc.paper.registry.data.dialog.*`) must be confirmed against `paper-api 26.1.2` at build time — the package moved during 26.x. If a symbol above does not resolve, find the current equivalent in the decompiled jar (`DialogBase`, `DialogInput.text`, `ActionButton`, `DialogAction.customClick`) rather than inventing one. This is the one task where the API surface is version-sensitive; verify, do not guess.

- [ ] **Step 2: Implement the interaction listener**

```java
package org.xpfarm.ollama.companion;

import org.bukkit.Bukkit;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/**
 * Opens the conversation on right-click. Uses PlayerInteractAtEntityEvent because the parent
 * PlayerInteractEntityEvent is @ApiStatus.Obsolete on 26.x. Guards the hand (a single Bedrock tap
 * fires up to two interact packets) and opens the dialog one tick later so vanilla's mount/container
 * close does not swallow it.
 */
public final class CompanionInteractionListener implements Listener {

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final CompanionDialog dialog;

    public CompanionInteractionListener(Plugin plugin, CompanionRegistry registry, CompanionDialog dialog) {
        this.plugin = plugin;
        this.registry = registry;
        this.dialog = dialog;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // guard the Bedrock double-fire
        }
        if (!(event.getRightClicked() instanceof Llama llama)) {
            return;
        }
        Player player = event.getPlayer();
        if (!registry.isOwner(player, llama)) {
            return; // only the owner talks to it; also lets non-companion llamas behave normally
        }
        event.setCancelled(true); // stop the vanilla mount
        Bukkit.getScheduler().runTask(plugin, () -> dialog.open(player));
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `mvn --batch-mode --no-transfer-progress test-compile`
Expected: SUCCESS. If the Dialog API symbols do not resolve, reconcile against the jar per the note in Step 1 before proceeding.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionDialog.java src/main/java/org/xpfarm/ollama/companion/CompanionInteractionListener.java
git commit -m "feat(companion): right-click dialog conversation UI (Bedrock-safe via Paper Dialog)"
```

**Gate 7a runtime check:** right-clicking your own companion opens the dialog on Java **and** on a Bedrock client (via Geyser form translation); right-clicking a non-owned llama does nothing special; the companion is not mounted.

---

## Task 16: NudgeTask — periodic rule-based reminders with cooldown

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/NudgeTask.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/NudgeTaskTest.java`

**Interfaces:**
- Consumes: `InventoryAdvisor.advise(InventorySnapshot)`, `CompanionRegistry.companionOf(Player)`, config `companion.nudges.cooldown`.
- Produces:
  - `new NudgeTask(Plugin, CompanionRegistry, InventoryAdvisor, long cooldownSeconds)`.
  - `boolean offCooldown(UUID player, long nowMillis)` — pure cooldown check. **Unit-tested.**
  - `void markSpoken(UUID player, long nowMillis)`.
  - `void start(int intervalTicks)` / `void cancel()`.

**Behavior each tick:** for every online player that has a companion and is off cooldown, take an `InventorySnapshot`, run the advisor, and if it returns anything, speak one nudge (the first) in the llama's voice, then `markSpoken`. Only players with a live companion get nudged.

- [ ] **Step 1: Write the failing test (pure cooldown)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NudgeTaskTest {

    @Test
    void offCooldownWhenNeverSpoken() {
        NudgeTask task = new NudgeTask(null, null, null, 300);
        assertTrue(task.offCooldown(UUID.randomUUID(), 1_000_000L));
    }

    @Test
    void onCooldownUntilIntervalElapses() {
        NudgeTask task = new NudgeTask(null, null, null, 300);
        UUID id = UUID.randomUUID();
        task.markSpoken(id, 1_000_000L);
        assertFalse(task.offCooldown(id, 1_000_000L + 299_000L)); // 299s later, still cooling
        assertTrue(task.offCooldown(id, 1_000_000L + 300_000L));  // 300s later, ready
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=NudgeTaskTest`
Expected: FAIL — `NudgeTask` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Speaks one deterministic reminder per player per cooldown, only to players with a live companion. */
public final class NudgeTask {

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final InventoryAdvisor advisor;
    private final long cooldownMillis;
    private final Map<UUID, Long> lastSpoken = new ConcurrentHashMap<>();
    private BukkitRunnable task;

    public NudgeTask(Plugin plugin, CompanionRegistry registry, InventoryAdvisor advisor,
            long cooldownSeconds) {
        this.plugin = plugin;
        this.registry = registry;
        this.advisor = advisor;
        this.cooldownMillis = cooldownSeconds * 1000L;
    }

    public boolean offCooldown(UUID player, long nowMillis) {
        Long last = lastSpoken.get(player);
        return last == null || nowMillis - last >= cooldownMillis;
    }

    public void markSpoken(UUID player, long nowMillis) {
        lastSpoken.put(player, nowMillis);
    }

    public void start(int intervalTicks) {
        cancel();
        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        this.task.runTaskTimer(plugin, intervalTicks, Math.max(1, intervalTicks));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (registry.companionOf(player) == null || !offCooldown(player.getUniqueId(), now)) {
                continue;
            }
            List<String> advice = advisor.advise(InventorySnapshots.of(player));
            if (!advice.isEmpty()) {
                player.sendMessage(Component.text("🦙 ", NamedTextColor.AQUA)
                        .append(Component.text(advice.get(0), NamedTextColor.WHITE)));
                markSpoken(player.getUniqueId(), now);
            }
        }
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=NudgeTaskTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/NudgeTask.java src/test/java/org/xpfarm/ollama/companion/NudgeTaskTest.java
git commit -m "feat(companion): rule-based nudges with per-player cooldown"
```

**Gate 7a runtime check:** with a shield in inventory but not equipped, the companion nudges about it once, then stays quiet for the cooldown.

---

## Task 17: LlamaCommand — /llama ask|recipe|dismiss|give

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/LlamaCommand.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/LlamaCommandTest.java`

**Interfaces:**
- Consumes: `CompanionConversation.ask(Player, String)`, `CompanionRecipe.key()`, `CompanionItem.create(int)`, `CompanionRegistry`, `CompanionConversation.forget(Player)`.
- Produces:
  - `enum Sub { ASK, RECIPE, DISMISS, GIVE, HELP, UNKNOWN }`.
  - `static Sub route(String[] args)` — pure subcommand routing. **Unit-tested.**
  - `LlamaCommand implements CommandExecutor, TabCompleter`.

**Subcommands:**
- `ask <text>` — `conversation.ask(player, text)`.
- `recipe` — print the crafting shape in chat (Bedrock recipe-book mitigation).
- `dismiss` — resolve the companion, remove it, return the summon item, unbind, `forget`.
- `give [player]` — `ollama.llama.give` only; give the summon item to the target (default self).

- [ ] **Step 1: Write the failing test (pure router + permission constant)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LlamaCommandTest {

    @Test
    void routesKnownSubcommands() {
        assertEquals(LlamaCommand.Sub.ASK, LlamaCommand.route(new String[] {"ask", "hi"}));
        assertEquals(LlamaCommand.Sub.RECIPE, LlamaCommand.route(new String[] {"recipe"}));
        assertEquals(LlamaCommand.Sub.DISMISS, LlamaCommand.route(new String[] {"dismiss"}));
        assertEquals(LlamaCommand.Sub.GIVE, LlamaCommand.route(new String[] {"give", "Steve"}));
    }

    @Test
    void emptyArgsIsHelp() {
        assertEquals(LlamaCommand.Sub.HELP, LlamaCommand.route(new String[] {}));
    }

    @Test
    void unknownIsUnknown() {
        assertEquals(LlamaCommand.Sub.UNKNOWN, LlamaCommand.route(new String[] {"frobnicate"}));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=LlamaCommandTest`
Expected: FAIL — `LlamaCommand` does not exist.

- [ ] **Step 3: Implement**

```java
package org.xpfarm.ollama.companion;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** /llama ask|recipe|dismiss|give. Never executes suggested commands; give is op-gated. */
public final class LlamaCommand implements CommandExecutor, TabCompleter {

    public enum Sub { ASK, RECIPE, DISMISS, GIVE, HELP, UNKNOWN }

    private final Plugin plugin;
    private final CompanionConversation conversation;
    private final CompanionRegistry registry;
    private final CompanionItem item;

    public LlamaCommand(Plugin plugin, CompanionConversation conversation, CompanionRegistry registry,
            CompanionItem item) {
        this.plugin = plugin;
        this.conversation = conversation;
        this.registry = registry;
        this.item = item;
    }

    public static Sub route(String[] args) {
        if (args.length == 0) {
            return Sub.HELP;
        }
        return switch (args[0].toLowerCase()) {
            case "ask" -> Sub.ASK;
            case "recipe" -> Sub.RECIPE;
            case "dismiss" -> Sub.DISMISS;
            case "give" -> Sub.GIVE;
            case "help" -> Sub.HELP;
            default -> Sub.UNKNOWN;
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (route(args)) {
            case ASK -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Only players can talk to a llama.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /llama ask <message>", NamedTextColor.YELLOW));
                    return true;
                }
                conversation.ask(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case RECIPE -> sender.sendMessage(Component.text(
                    "Llama Companion recipe:\n W L W   (W=White Wool, L=Lead)\n H G H   (H=Hay Bale, G=Gold Ingot)\n W L W",
                    NamedTextColor.AQUA));
            case DISMISS -> {
                if (!(sender instanceof Player player)) {
                    return true;
                }
                dismiss(player);
            }
            case GIVE -> {
                if (!sender.hasPermission("ollama.llama.give")) {
                    sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                target.getInventory().addItem(item.create(1));
                sender.sendMessage(Component.text("Gave a companion charm to " + target.getName(), NamedTextColor.GREEN));
            }
            case HELP -> sender.sendMessage(Component.text(
                    "/llama ask <message> | /llama recipe | /llama dismiss | /llama give [player]",
                    NamedTextColor.GOLD));
            case UNKNOWN -> sender.sendMessage(Component.text("Unknown subcommand. Try /llama help", NamedTextColor.RED));
        }
        return true;
    }

    private void dismiss(Player player) {
        UUID id = registry.companionOf(player);
        if (id != null) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Llama llama) {
                llama.remove();
            }
            registry.unbind(player);
        }
        conversation.forget(player);
        player.getInventory().addItem(item.create(1));
        player.sendMessage(Component.text("Your llama returns to its charm.", NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("ask", "recipe", "dismiss", "give")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=LlamaCommandTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/LlamaCommand.java src/test/java/org/xpfarm/ollama/companion/LlamaCommandTest.java
git commit -m "feat(companion): /llama ask|recipe|dismiss|give command"
```

**Gate 7a runtime check:** `/llama give` (op) yields a summon item; a non-op is refused; `/llama recipe` prints the shape; `/llama dismiss` removes the companion and returns the item.

---

## Task 18: CompanionManager and OllamaPlugin wiring — decouple from the API master switch

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/companion/CompanionManager.java`
- Modify: `src/main/java/org/xpfarm/ollama/OllamaPlugin.java`
- Test: `src/test/java/org/xpfarm/ollama/companion/CompanionManagerConfigTest.java`

**Interfaces:**
- Consumes: every companion class above; `OllamaPlugin`.
- Produces:
  - `new CompanionManager(OllamaPlugin)`.
  - `void enable()` — build registry/item/recipe/entity/conversation/tasks/listeners, register the `llama` command, register listeners, start tasks.
  - `void disable()` — cancel tasks.
  - `static Set<String> parseRules(List<String> configured)` — pure; drops unknown rule names. **Unit-tested.**
  - `CompanionConversation getConversation()` (for the interaction/command wiring).

**The load-bearing change to `OllamaPlugin.onEnable`:** today it returns early when `enabled: false`, which would leave the companion dead. The companion must run whenever `companion.enabled` is true, *independently* of the API master switch. Conversation degrades (Task 14) when the API is absent.

- [ ] **Step 1: Write the failing test (pure rule parsing)**

```java
package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CompanionManagerConfigTest {

    @Test
    void keepsKnownRulesAndDropsUnknown() {
        Set<String> rules = CompanionManager.parseRules(List.of("shield", "food", "bogus"));
        assertTrue(rules.contains("shield"));
        assertTrue(rules.contains("food"));
        assertFalse(rules.contains("bogus"));
        assertEquals(2, rules.size());
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionManagerConfigTest`
Expected: FAIL — `CompanionManager` does not exist.

- [ ] **Step 3: Implement CompanionManager**

```java
package org.xpfarm.ollama.companion;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.PluginManager;
import org.xpfarm.ollama.OllamaPlugin;

/**
 * Owns the companion subsystem. Enabled by {@code companion.enabled} independently of the Ollama
 * {@code enabled} master switch — crafting, following, downing, and nudges do not need Ollama; only
 * conversation does, and it degrades when the API is absent.
 */
public final class CompanionManager {

    private static final Set<String> KNOWN_RULES = Set.of("shield", "food", "tool_durability", "torches");

    private final OllamaPlugin plugin;
    private FollowTask followTask;
    private NudgeTask nudgeTask;
    private CompanionConversation conversation;

    public CompanionManager(OllamaPlugin plugin) {
        this.plugin = plugin;
    }

    public static Set<String> parseRules(List<String> configured) {
        Set<String> out = new LinkedHashSet<>();
        for (String rule : configured) {
            if (KNOWN_RULES.contains(rule)) {
                out.add(rule);
            }
        }
        return out;
    }

    public CompanionConversation getConversation() {
        return conversation;
    }

    public void enable() {
        var config = plugin.getConfig();
        int followInterval = config.getInt("companion.follow_interval", 10);
        int teleportDistance = config.getInt("companion.teleport_distance", 24);
        boolean invulnerable = config.getBoolean("companion.invulnerable", true);
        long nudgeCooldown = config.getLong("companion.nudges.cooldown", 300);
        boolean nudgesEnabled = config.getBoolean("companion.nudges.enabled", true);
        Set<String> rules = parseRules(config.getStringList("companion.nudges.rules"));

        CompanionItem item = new CompanionItem(plugin);
        CompanionRegistry registry = new CompanionRegistry(plugin);
        CompanionEntity entity = new CompanionEntity(plugin, registry);
        CompanionRecipe recipe = new CompanionRecipe(plugin, item);
        recipe.register(plugin.getServer());

        this.conversation = new CompanionConversation(plugin, loadPersonality());
        CompanionDialog dialog = new CompanionDialog(conversation);

        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(new CompanionPlaceListener(plugin, item, entity, registry), plugin);
        pm.registerEvents(new CompanionInteractionListener(plugin, registry, dialog), plugin);
        pm.registerEvents(new CompanionPortalListener(plugin, registry), plugin);
        pm.registerEvents(new DownedStateListener(plugin, registry, entity, item, invulnerable), plugin);

        LlamaCommand command = new LlamaCommand(plugin, conversation, registry, item);
        plugin.getCommand("llama").setExecutor(command);
        plugin.getCommand("llama").setTabCompleter(command);

        this.followTask = new FollowTask(plugin, registry, entity, teleportDistance);
        this.followTask.start(followInterval);

        if (nudgesEnabled) {
            this.nudgeTask = new NudgeTask(plugin, registry, new InventoryAdvisor(rules), nudgeCooldown);
            // Check roughly every 5 seconds; the per-player cooldown does the real gating.
            this.nudgeTask.start(100);
        }

        plugin.getLogger().info("Llama companion enabled"
                + (plugin.getOllamaAPI() == null ? " (conversation dormant — Ollama disabled)" : ""));
    }

    public void disable() {
        if (followTask != null) {
            followTask.cancel();
        }
        if (nudgeTask != null) {
            nudgeTask.cancel();
        }
    }

    private String loadPersonality() {
        try (InputStream in = plugin.getResource("llama-companion.md")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load llama-companion.md: " + e.getMessage());
        }
        return "You are a friendly llama companion.";
    }
}
```

- [ ] **Step 4: Rewire `OllamaPlugin.onEnable` and `onDisable`**

Replace the `onEnable` body (currently lines 34-57) so the companion is initialized independently of `enabled`:

```java
    private CompanionManager companionManager; // add field near the other managers

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        boolean apiEnabled = getConfig().getBoolean("enabled", false);
        if (apiEnabled) {
            initializeComponents();
            registerCommands();
            registerEvents();
            getLogger().info("Ollama Plugin v" + getDescription().getVersion() + " enabled!");
            logStartupInfo();
        } else {
            getLogger().info("Ollama integration is disabled; no API client or listeners were started.");
        }

        // The companion is gated by its own switch, not the API master switch: crafting, following,
        // downing, and nudges work with Ollama off; only conversation needs the API and it degrades.
        if (getConfig().getBoolean("companion.enabled", true)) {
            companionManager = new CompanionManager(this);
            companionManager.enable();
        }
    }
```

In `onDisable`, add before the existing cleanup:

```java
        if (companionManager != null) {
            companionManager.disable();
        }
```

Add the import `import org.xpfarm.ollama.companion.CompanionManager;` and a getter:

```java
    public CompanionManager getCompanionManager() {
        return companionManager;
    }
```

- [ ] **Step 5: Run the config test, verify it passes**

Run: `mvn --batch-mode --no-transfer-progress test -Dtest=CompanionManagerConfigTest`
Expected: PASS.

- [ ] **Step 6: Full build**

Run: `mvn --batch-mode --no-transfer-progress clean verify`
Expected: `BUILD SUCCESS`, all tests pass (baseline 67 + the new companion tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/companion/CompanionManager.java src/main/java/org/xpfarm/ollama/OllamaPlugin.java src/test/java/org/xpfarm/ollama/companion/CompanionManagerConfigTest.java
git commit -m "feat(companion): wire the subsystem, decoupled from the API master switch"
```

**Gate 7a runtime check (the decoupling — critical):** set top-level `enabled: false`, `companion.enabled: true`. The plugin still enables the companion; `/llama give` + place still works; the companion follows and nudges; `/llama ask` returns the degraded line. Then set `enabled: true` with a reachable Ollama and confirm `/llama ask` returns a real reply.

---

## Task 19: JAR inspection, full gate verification, and checklist evidence

**Files:**
- Modify: `docs/PLUGIN_CHECKLIST.md` (gates 4, 6, 7a evidence)
- Modify: `CHANGELOG.md`
- Modify: `README.md` (a short "Companion" section)

- [ ] **Step 1: Full verify**

Run: `export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH" && mvn --batch-mode --no-transfer-progress clean verify`
Expected: `BUILD SUCCESS`, test count ≥ 67 baseline + all new companion tests, 0 failures.

- [ ] **Step 2: Inspect the shaded JAR**

```bash
cd target && unzip -p ollama-0.4.0.jar plugin.yml | grep -E "version|api-version|llama"
unzip -l ollama-0.4.0.jar | grep -E "companion/|libs/gson" | head
unzip -l ollama-0.4.0.jar | grep -c "org/apache/http" # expect 0
```
Expected: `version: 0.4.0`, `api-version: '26.1'`, the `llama` command present; companion classes present; Gson relocated; zero Apache HTTP classes.

- [ ] **Step 3: Gate 7a runtime verification on the shared rig**

Boot the rig from the repo root with the Ollama sidecar overlay:
```bash
../xpfarm-plugin-toolkit/bin/xpfarm-test-stack up --with scripts/extra-services.yml
```
(Use a dot-free worktree name if running from a worktree — the rig rejects dotted Compose project names; see the memory note.) With `enabled: true` and `companion.enabled: true`, exercise via RCON and, where a client is needed, record what could and could not be driven headlessly. Walk the full acceptance list from `docs/PLUGIN_CHECKLIST.md` §1 checks 9–18. Capture verbatim: the enable log line, `/llama give` output, and one `/llama ask` reply. Then tear down.

- [ ] **Step 4: Record evidence in the checklist**

In `docs/PLUGIN_CHECKLIST.md`, tick gates 4 (Bedrock review now matches shipped code), 6 (unit tests), and the 7a boxes the rig genuinely covered — with quoted output. Leave client-only checks (Bedrock dialog render, in-game `/llama ask` from a real client) unchecked, attributed to the gate-12 play-test obligation, exactly as 0.3.0 did. Record the two deliberate deviations: the separate companion session store (Task 14), and any Dialog API symbol reconciliation (Task 15).

- [ ] **Step 5: Update CHANGELOG and README**

Add a `0.4.0` CHANGELOG entry summarizing the companion. Add a short README "Llama Companion" section: how to craft it, that it follows and talks, that conversation needs Ollama but everything else does not, and the Bedrock recipe-book caveat.

- [ ] **Step 6: Commit**

```bash
git add docs/PLUGIN_CHECKLIST.md CHANGELOG.md README.md
git commit -m "docs(companion): record 0.4.0 gate evidence, changelog, and readme"
```

---

## Self-review

**1. Spec coverage** — every design-doc component maps to a task:

| Design component | Task |
| --- | --- |
| `CompanionItem` | 2 |
| `CompanionRecipe` | 3 |
| `CompanionRegistry` | 4 |
| `CompanionEntity` | 5 |
| Summon on place | 6 |
| `FollowTask` | 7 |
| Portal follow | 8 |
| `DownedState` | 9 |
| `InventoryAdvisor` + snapshot | 10, 11 |
| `CompanionContext` | 12 |
| `CommandSuggester` | 13 |
| `CompanionConversation` | 14 |
| `CompanionDialog` + interaction | 15 |
| `NudgeTask` | 16 |
| Commands `/llama …` | 17 |
| Wiring + degrade-when-off | 18 |
| Config, persistence, acceptance checks 9–18 | 0, 18, 19 |

Known limitations and Bedrock caveats are carried in Global Constraints and recorded at gate 19; no design section is unimplemented.

**2. Deliberate deviations** (recorded, not silent):
- Separate companion conversation history rather than reusing `ChatSessionManager` (Task 14) — persona separation.
- `CompanionContext` / `InventorySnapshot` replace `SystemPromptManager` for the companion — the generic templates leak nearby-players and logs, which the approved scope forbids.

**3. Version consistency** — method names are stable across tasks: `create(int)`, `isSummonItem`, `bind/companionOf/ownerOf/isOwner/unbind`, `summon/isDowned/setDowned`, `shouldTeleport`, `classify`, `advise`, `describe`, `parse`, `buildRequest/canConverse/degradedMessage/ask/forget`, `route`, `parseRules/enable/disable`. Interfaces blocks match their implementations.

**4. Placeholder scan** — no `TBD`/`TODO`/"add handling"; every code step carries real code. The one version-sensitive surface (Paper Dialog API package, Task 15) is explicitly flagged as verify-against-jar rather than assumed, which is honesty, not a placeholder.

**5. Test-count floor** — baseline is 67; this plan adds ~14 new test classes. Task 18 Step 6 and Task 19 Step 1 both assert the full suite stays green and above baseline.
