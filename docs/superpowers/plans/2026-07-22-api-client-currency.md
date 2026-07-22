# Ollama 0.3.0 — API Client Currency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct and modernize the Ollama HTTP client so the 0.4.0 companion can be built on a client whose system prompt actually arrives, whose timeouts actually fire, and whose overload behavior is bounded.

**Architecture:** Split the current Bukkit-entangled `OllamaAPI` into a Bukkit-free transport (`OllamaHttp`, built on `java.net.http.HttpClient`) plus a thin Bukkit wiring layer that keeps the existing public API shape. The transport owns timeouts, the concurrency gate, retries, and status dispatch — all of which become unit-testable against a loopback `com.sun.net.httpserver.HttpServer` with no Bukkit and no new dependency. Apache HttpClient 4.5.14 is removed entirely.

**Tech Stack:** Java 25 (Temurin 25.0.3), Maven 3.9.16, Paper API 26.1.2.build.74-stable (`provided`), Gson 2.14.0 (shaded + relocated), JUnit Jupiter 5.10.0, Mockito 5.20.0.

## Global Constraints

- **`artifactId` stays `ollama`.** The releasable JAR name (`ollama-<version>.jar`) and the updater destination (`ollama.jar`) must not change, or the updater manifest breaks. Only `groupId` moves.
- **No new HTTP or LLM client dependency.** The net dependency count must go *down* by one (Apache HttpClient removed). `java.net.http` and `com.sun.net.httpserver` are JDK built-ins and are not dependencies.
- **Java 25 / Paper 26.1.2 build 74.** `api-version: '26.1'`, quoted, a YAML `String`.
- **`dependency-reduced-pom.xml` stays gitignored.** Never commit it.
- **Build command:** every shell needs `export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"` first, then `mvn --batch-mode --no-transfer-progress clean verify`.
- **Baseline is 12 passing tests** (6 `PluginDescriptorTest`, 6 `OllamaAPITest`) at `ae09bbb`. The final count must exceed 12 with 0 failures.
- **Forbidden files:** `docs/PLUGIN_CHECKLIST-0.2.1-backfill.md`, `docs/superpowers/specs/**`, `server/`, `releases/`, any `org.xpfarm.ollama.companion` package, the separate updater repo.
- **Do not push, tag, or release.** Local commits on `feat/api-currency` only.
- **Divergence policy:** any departure from the design doc's 0.3.0 table gets a dated note under the relevant gate in `docs/PLUGIN_CHECKLIST.md` plus a callout in the final report. Never deviate silently.

---

## Resolved open questions

These three were left open by the delegation and are settled here. Each is recorded in
`docs/PLUGIN_CHECKLIST.md` in Task B6.

### 1. `performance.max_concurrent_requests` — change the default to `1`

**Decision:** default becomes `1`, and this key becomes the semaphore permit count.

`OLLAMA_NUM_PARALLEL` defaults to `1`, so a shipped default of `5` is wrong against a stock Ollama
in a specific and undiagnosable way: per Finding 3, requests 2–5 do not fail fast, they block on the
server's `semaphore.Acquire` indefinitely. Our own `api.timeout` then fires and reports a timeout,
which is indistinguishable from a dead endpoint. Setting the default to `1` makes the client's
concurrency match the server's actual capacity, so the 2nd concurrent request is refused
*by us*, instantly, with a message that names the real reason.

Operators who raised `OLLAMA_NUM_PARALLEL` raise this key to match; the `config.yml` comment says so
explicitly. This is "change the default to 1" **and** "document that operators must raise
`OLLAMA_NUM_PARALLEL`" — the two options were never exclusive. Adaptive sizing was rejected: there
is no signal to adapt on, precisely because a warm model never pushes back.

### 2. `/api/show` capability probe — lazy per model, warmed at enable

**Decision:** the probe is lazy and cached per model in a `ConcurrentHashMap`; the enable-time
preload additionally warms the entry for the configured model.

Enable-time-only is wrong because the model is not fixed: `chatWithRequest`/`generateWithRequest` are
public API (`integration.expose_api: true`), any caller may set a different model, and `/ollama
reload` can change `api.model` under a running server. A cache keyed by model name is the only shape
that stays correct across all three. Warming the configured model during the preload means the
common path pays no probe latency, so lazy costs nothing in practice — this is strictly better than
either option alone, for the price of one map.

**Failure handling:** a failed probe caches nothing and returns "unknown", and unknown means the
`think` field is **omitted**. Omitting is the pre-0.3.0 behavior, so a probe outage can only make
things no worse, whereas guessing `think: false` on a non-capable model risks the hard 400 of
Finding 2.

### 3. Retry on 500 — `max_retries` is the ceiling, the per-status policy is the cap

**Decision:** effective retries = `min(api.max_retries, policyCapForStatus)`. The cap is `1` for 500
and `max_retries` for 503.

The design doc says "retry once" for 500 because Ollama self-heals by evicting models on OOM — the
*second* retry is not a different experiment, and hammering an out-of-memory box three times only
multiplies the latency a player waits before seeing the failure. But `api.max_retries` is named in
the release table as a key that must stop being decorative, so it cannot be ignored either. Making
it a ceiling honors both: 503 (queue-full on the load path, genuinely transient) uses the full
budget, 500 uses at most one, and `max_retries: 0` disables retries everywhere — the operator keeps
a real override in both directions.

---

## File Structure

**Part A — metadata**

| File | Responsibility | Change |
| --- | --- | --- |
| `pom.xml` | Maven coordinates, deps, build | `groupId`, `version`, `<url>`, **add `<resources>` filtering**, drop dead compiler `source`/`target` |
| `src/main/resources/plugin.yml` | Paper descriptor | `version`, `api-version`, `website` |
| `src/test/java/org/xpfarm/ollama/PluginDescriptorTest.java` | Descriptor gate | Assert new values |

**Part B — client**

| File | Responsibility |
| --- | --- |
| `api/StatusPolicy.java` (new) | Pure mapping: HTTP status → action + retry budget. Nested `Action` enum. |
| `api/OllamaHttpException.java` (new) | Carries an `Action` and a player-facing message. |
| `api/OllamaHttp.java` (new) | **Bukkit-free** transport: `HttpClient`, per-request timeout, concurrency gate, retry loop, status dispatch. Gated and ungated entry points. |
| `api/ModelCapabilities.java` (new) | `/api/show` probe, cached per model. Ungated. |
| `api/models/ChatRequest.java` | **Delete `system`**, add `think`, `format` → `JsonElement`, defensive-copy messages, `setSystemPrompt` |
| `api/models/GenerateRequest.java` | Keep `system` (valid here), add `think`, `format` → `JsonElement` |
| `api/models/ChatMessage.java` | Add `thinking` (parsed, unused) |
| `api/models/ChatResponse.java` | Add `done_reason` |
| `api/models/GenerateResponse.java` | Add `done_reason`, `thinking` |
| `api/OllamaAPI.java` | Bukkit wiring only; delegates to `OllamaHttp`; applies `think` gating |
| `OllamaPlugin.java` | Model preload + capability warm on enable |
| `src/main/resources/config.yml` | `max_concurrent_requests` default → 1, documented |
| `README.md`, `CHANGELOG.md`, `docs/PLUGIN_CHECKLIST.md` | Docs, ViaVersion as hard requirement, gate evidence |

**Tests (new):** `api/StatusPolicyTest.java`, `api/OllamaHttpTest.java`, `api/ModelCapabilitiesTest.java`, `api/models/ChatRequestSerializationTest.java`, `api/models/GenerateRequestSerializationTest.java`.

### Acceptance check → test map

| Check | Covered by |
| --- | --- |
| 1. System prompt is a `role: "system"` message, no top-level `system` | `ChatRequestSerializationTest` |
| 2. `api.timeout` aborts the request | `OllamaHttpTest.timeoutAbortsRatherThanPinningTheThread` |
| 3. `think` present in chat and generate bodies | `ChatRequestSerializationTest`, `GenerateRequestSerializationTest` (see divergence note, Task B4) |
| 4. Non-thinking model never sent `think: true` | `ModelCapabilitiesTest` |
| 5. `format` as JSON Schema serializes as an object | `ChatRequestSerializationTest` |
| 6. 400/404/499/500/503/timeout map to distinct actions | `StatusPolicyTest` |
| 7. Gate at 1 rejects a 2nd concurrent request | `OllamaHttpTest.secondConcurrentRequestIsRejectedNotQueued` |
| 8. Real `/ollama` generation in-game | Gate 7a rig run (Task B7) |

---

# PART A — Gate 3 metadata

Commit Part A **separately** from Part B.

### Task A1: Maven coordinates, resource filtering, and descriptor metadata

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/plugin.yml`
- Modify: `README.md`
- Test: `src/test/java/org/xpfarm/ollama/PluginDescriptorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `${project.version}` substitution in `target/classes/plugin.yml`; Maven group `org.xpfarm`.

**The `minecraft-plugin-scaffold` skill governs this task** (gates 2 and 3). Three of its
requirements are not in the design doc's 0.3.0 table and are folded in here:

1. `pom.xml` must name Carmelo Santana as author/developer — there is currently no `<developers>`
   block at all.
2. `play.xpfarm.org` must be documented "wherever this repository records server identity for a
   player or operator — typically the `README.md`'s join/connection instructions." Verified
   2026-07-22 that it appears nowhere in this repository outside checklist prose.
3. Maven license metadata must be consistent with `LICENSE`. Verified 2026-07-22: `LICENSE` is
   AGPL-3.0 full text and `pom.xml` already declares AGPL-3.0-or-later. No change needed — record
   it, do not edit it.

The skill's `herobrinesystems` scan was run 2026-07-22 and is clean: the only hits are the checklist
lines that *describe* the check. Do not "fix" those.

**Critical context — read before starting.** `pom.xml` currently has **no `<resources>` block**, so
Maven resource filtering is OFF. Writing `version: '${project.version}'` into `plugin.yml` without
turning filtering on ships the literal string `${project.version}` in the JAR, and Paper rejects the
descriptor with `InvalidDescriptionException` — the plugin does not appear in `/plugins` at all.
Verified 2026-07-22: `grep -n "resources\|filtering" pom.xml` returns nothing. The `<resources>`
block below is not optional polish; it is what makes step 3 safe.

Filtering the whole `src/main/resources` directory is safe here: verified 2026-07-22 that no
resource contains `${` or `@token@` delimiters (`ollama-*.md` use `<ANGLE_BRACKET>` placeholders).

- [ ] **Step 1: Write the failing descriptor assertions**

In `PluginDescriptorTest.java`, replace the `assertEquals("1.21", parsed.get("api-version"));` line
and extend `pluginYmlDeclaresTheFieldsPaperRequires`:

```java
    @Test
    void pluginYmlDeclaresTheFieldsPaperRequires() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);

        assertEquals("Ollama", parsed.get("name"));
        assertEquals("org.xpfarm.ollama.OllamaPlugin", parsed.get("main"));
        assertInstanceOf(String.class, parsed.get("api-version"),
                "api-version must be quoted; unquoted it parses as a double and 1.20 becomes 1.2");
        assertEquals("26.1", parsed.get("api-version"),
                "paper-api 26.1.2 is Minecraft Java 26.1; a lower value opts the JAR into "
                        + "Paper's Commodore bytecode rewrites");
        assertNotNull(parsed.get("description"), "description is required");

        Object version = parsed.get("version");
        assertNotNull(version, "version is required");
        assertFalse(version.toString().contains("${"),
                "version still holds an unresolved Maven property: " + version
                        + " -- pom.xml needs <resources><resource><filtering>true");
    }
```

Add a new test to the same class:

```java
    @Test
    void pluginYmlPointsAtTheProjectWebsite() throws IOException {
        Object website = parse(PLUGIN_YML).get("website");
        assertNotNull(website, "website is required");
        assertEquals("https://xpfarm.org", website);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
mvn --batch-mode --no-transfer-progress test -Dtest=PluginDescriptorTest
```

Expected: FAIL — 2 failures. `api-version` is `1.21` not `26.1`; `website` is the GitHub URL.

- [ ] **Step 3: Update `plugin.yml`**

Replace lines 3, 4, and 7 of `src/main/resources/plugin.yml`:

```yaml
version: '${project.version}'
api-version: '26.1'
```

and

```yaml
website: https://xpfarm.org
```

Leave every other line — `name`, `main`, `author`, `description`, `softdepend`, `commands`,
`permissions` — exactly as it is.

- [ ] **Step 4: Update `pom.xml`**

Change `<groupId>` (line 7) and `<version>` (line 9):

```xml
    <groupId>org.xpfarm</groupId>
    <artifactId>ollama</artifactId>
    <version>0.3.0</version>
```

Change `<url>` (line 22):

```xml
    <url>https://xpfarm.org</url>
```

Add a `<developers>` block immediately after `<url>` — `minecraft-plugin-scaffold` gate 3 requires
author metadata and the POM currently has none:

```xml
    <developers>
        <developer>
            <name>Carmelo Santana</name>
            <url>https://xpfarm.org</url>
        </developer>
    </developers>
```

Add a `<resources>` block as the **first** child of `<build>`, before `<plugins>`:

```xml
    <build>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
            </resource>
        </resources>
        <plugins>
```

Remove the dead `<configuration>` from `maven-compiler-plugin` — `maven.compiler.release=25`
already takes precedence over `<source>`/`<target>`, verified 2026-07-22 by `javap`: the compiled
`OllamaPlugin.class` reports `major version: 69` (Java 25), not 65 (Java 21). Leaving `21` in the
POM is a false claim about the build. The element becomes:

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.14.1</version>
            </plugin>
```

- [ ] **Step 4b: Record `play.xpfarm.org` in `README.md`**

`minecraft-plugin-scaffold` gate 3 requires the public server hostname to be documented where the
repository records server identity for a player or operator. This repository has no such section
today, so add one immediately after the top-of-file description paragraph, before `## Features`:

```markdown
## Server

The public xpfarm.org Minecraft server is `play.xpfarm.org` — Java Edition and, via Geyser and
Floodgate, Bedrock Edition. Whether this plugin is enabled there is a deployment question, not a
property of this repository: it ships `enabled: false` and does nothing until an operator turns it
on and points it at a reachable Ollama endpoint.

Project home: <https://xpfarm.org>
```

The second sentence is load-bearing. This plugin is enrolled in the updater but ships disabled, so
a bare "our server is play.xpfarm.org" line in a plugin README would imply a live integration that
has never been confirmed to exist.

- [ ] **Step 5: Run the tests to verify they pass, against the filtered copy**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
mvn --batch-mode --no-transfer-progress clean test -Dtest=PluginDescriptorTest
```

Expected: PASS, 7 tests. `PluginDescriptorTest.descriptor()` prefers `target/classes/plugin.yml`,
so a passing `version` assertion here *is* the proof that filtering substituted.

- [ ] **Step 6: Prove the substitution directly, not just via the test**

```bash
grep -E "^(version|api-version|website):" target/classes/plugin.yml
```

Expected verbatim:

```
version: '0.3.0'
api-version: '26.1'
website: https://xpfarm.org
```

If `version` still reads `${project.version}`, the `<resources>` block is wrong — stop and fix it
before committing.

- [ ] **Step 7: Full verify and commit**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
mvn --batch-mode --no-transfer-progress clean verify
git add pom.xml src/main/resources/plugin.yml README.md \
        src/test/java/org/xpfarm/ollama/PluginDescriptorTest.java
git commit -m "chore: move to org.xpfarm group and correct plugin descriptor metadata

groupId com.carmelosantana -> org.xpfarm, closing the group/package split the
0.2.1 backfill recorded as follow-up 3. artifactId, JAR name, and updater
destination are unchanged, so the updater manifest needs no edit.

api-version '1.21' -> '26.1': paper-api 26.1.2 is Minecraft Java 26.1, and a
lower value opts the JAR into Paper's Commodore bytecode rewrites.

version is now '\${project.version}'. This required turning Maven resource
filtering ON -- there was no <resources> block, so the property would have
shipped as a literal and Paper would have rejected the descriptor.

Drop the compiler plugin's source/target 21: maven.compiler.release=25 already
wins, and the compiled output is major version 69."
```

Expected: `BUILD SUCCESS`, 13 tests.

---

# PART B — the client rewrite

### Task B1: `StatusPolicy` — status-code dispatch

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/api/StatusPolicy.java`
- Test: `src/test/java/org/xpfarm/ollama/api/StatusPolicyTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `StatusPolicy.Action` enum; `StatusPolicy.forStatus(int) -> Action`;
  `StatusPolicy.retryBudget(Action, int configuredMaxRetries) -> int`;
  `StatusPolicy.playerMessage(Action, int status) -> String`.

Covers acceptance check 6. Match on status codes only, never on message text — Ollama emits two
different 404 body formats (`'%s'` and `%q`) depending on which internal path failed.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/xpfarm/ollama/api/StatusPolicyTest.java`:

```java
package org.xpfarm.ollama.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.xpfarm.ollama.api.StatusPolicy.Action;

/** Acceptance check 6: each documented status maps to its own distinct action. */
final class StatusPolicyTest {

    @Test
    void eachDocumentedStatusMapsToItsDocumentedAction() {
        assertEquals(Action.MALFORMED_REQUEST, StatusPolicy.forStatus(400));
        assertEquals(Action.MODEL_MISSING, StatusPolicy.forStatus(404));
        assertEquals(Action.CANCELLED, StatusPolicy.forStatus(499));
        assertEquals(Action.SERVER_ERROR, StatusPolicy.forStatus(500));
        assertEquals(Action.QUEUE_FULL, StatusPolicy.forStatus(503));
    }

    @Test
    void theSixDocumentedOutcomesAreAllDistinct() {
        Action[] actions = {
            StatusPolicy.forStatus(400), StatusPolicy.forStatus(404), StatusPolicy.forStatus(499),
            StatusPolicy.forStatus(500), StatusPolicy.forStatus(503), Action.BACKPRESSURE,
        };
        for (int i = 0; i < actions.length; i++) {
            for (int j = i + 1; j < actions.length; j++) {
                assertNotEquals(actions[i], actions[j],
                        "actions " + i + " and " + j + " collapsed to the same outcome");
            }
        }
    }

    @Test
    void serverErrorRetriesAtMostOnceEvenWhenMaxRetriesIsHigher() {
        assertEquals(1, StatusPolicy.retryBudget(Action.SERVER_ERROR, 3),
                "500 may be OOM; Ollama self-heals by evicting a model, so a 2nd retry is not a "
                        + "different experiment");
        assertEquals(1, StatusPolicy.retryBudget(Action.SERVER_ERROR, 99));
    }

    @Test
    void queueFullUsesTheFullConfiguredBudget() {
        assertEquals(3, StatusPolicy.retryBudget(Action.QUEUE_FULL, 3));
        assertEquals(1, StatusPolicy.retryBudget(Action.QUEUE_FULL, 1));
    }

    @Test
    void maxRetriesZeroDisablesEveryRetry() {
        for (Action action : Action.values()) {
            assertEquals(0, StatusPolicy.retryBudget(action, 0),
                    action + " retried despite api.max_retries: 0");
        }
    }

    @Test
    void nonRetryableActionsAreNeverRetried() {
        assertEquals(0, StatusPolicy.retryBudget(Action.MALFORMED_REQUEST, 3));
        assertEquals(0, StatusPolicy.retryBudget(Action.MODEL_MISSING, 3));
        assertEquals(0, StatusPolicy.retryBudget(Action.CANCELLED, 3));
        assertEquals(0, StatusPolicy.retryBudget(Action.BACKPRESSURE, 3),
                "a semaphore stall must shed load, not add more of it");
    }

    @Test
    void unmappedStatusesFallBackToServerError() {
        assertEquals(Action.SERVER_ERROR, StatusPolicy.forStatus(502));
        assertEquals(Action.SERVER_ERROR, StatusPolicy.forStatus(418));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
mvn --batch-mode --no-transfer-progress test -Dtest=StatusPolicyTest
```

Expected: FAIL — compilation error, `package org.xpfarm.ollama.api does not exist` / cannot find
symbol `StatusPolicy`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/org/xpfarm/ollama/api/StatusPolicy.java`:

```java
package org.xpfarm.ollama.api;

/**
 * Maps an Ollama HTTP status to what the client should do about it.
 *
 * <p>Dispatch is on the status code and never on the response body. Ollama emits two different
 * 404 body formats — {@code '%s'} and {@code %q} — depending on which internal path failed, so
 * any text match here would be correct against one of them and silently wrong against the other.
 *
 * <p>Before 0.3.0 a non-200 was logged and then the body was parsed and delivered to the caller
 * anyway, so an error response reached the player as if it were an answer.
 */
public final class StatusPolicy {

    /** What the client does about a given outcome. One constant per documented row. */
    public enum Action {
        /** 400 — our request is malformed, or a capability is missing. Log loudly, never retry. */
        MALFORMED_REQUEST,
        /** 404 — the model is not installed. Tell the player, never retry. */
        MODEL_MISSING,
        /** 499 — we cancelled it. Ignore. */
        CANCELLED,
        /** 500 — possibly OOM. Ollama self-heals by evicting models, so back off and retry once. */
        SERVER_ERROR,
        /** 503 — queue full; only reachable on the model-load path. Back off and retry. */
        QUEUE_FULL,
        /** Timeout with no status: the semaphore stall of Finding 3. Shed load, never retry. */
        BACKPRESSURE,
    }

    private StatusPolicy() {}

    public static Action forStatus(int status) {
        return switch (status) {
            case 400 -> Action.MALFORMED_REQUEST;
            case 404 -> Action.MODEL_MISSING;
            case 499 -> Action.CANCELLED;
            case 503 -> Action.QUEUE_FULL;
            // Anything else non-200 is treated as a server-side fault. 500 is the documented
            // case; 502 and friends behave the same way from our side.
            default -> Action.SERVER_ERROR;
        };
    }

    /**
     * How many retries this outcome may use.
     *
     * <p>{@code api.max_retries} is a ceiling, not a target: the per-action cap decides how much
     * of that budget is actually sensible. A 500 may be an out-of-memory Ollama, which self-heals
     * by evicting a model — the second retry is not a different experiment, it just multiplies the
     * latency the player waits before being told it failed. A 503 is queue pressure on the load
     * path and is genuinely transient, so it gets the whole budget. {@code max_retries: 0}
     * disables retries everywhere.
     */
    public static int retryBudget(Action action, int configuredMaxRetries) {
        int ceiling = Math.max(0, configuredMaxRetries);
        return switch (action) {
            case SERVER_ERROR -> Math.min(1, ceiling);
            case QUEUE_FULL -> ceiling;
            case MALFORMED_REQUEST, MODEL_MISSING, CANCELLED, BACKPRESSURE -> 0;
        };
    }

    /** A message safe to show a player: no endpoint, no body, no stack trace. */
    public static String playerMessage(Action action, int status) {
        return switch (action) {
            case MALFORMED_REQUEST -> "The AI request was rejected as malformed (HTTP 400).";
            case MODEL_MISSING -> "That model is not installed on the Ollama server (HTTP 404).";
            case CANCELLED -> "The AI request was cancelled.";
            case SERVER_ERROR -> "The Ollama server hit an error (HTTP " + status + ").";
            case QUEUE_FULL -> "The Ollama server is overloaded (HTTP 503). Try again shortly.";
            case BACKPRESSURE -> "The AI is busy right now. Try again shortly.";
        };
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=StatusPolicyTest
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/api/StatusPolicy.java \
        src/test/java/org/xpfarm/ollama/api/StatusPolicyTest.java
git commit -m "feat(api): add status-code dispatch policy

Acceptance check 6. Dispatch on status only, never on body text: Ollama emits
two different 404 body formats depending on which internal path failed.

api.max_retries becomes a ceiling and the per-action cap decides the rest --
500 retries at most once because Ollama self-heals OOM by evicting models,
503 gets the full budget, and max_retries: 0 disables retries everywhere."
```

---

### Task B2: request/response models — kill the top-level `system`

**Files:**
- Modify: `src/main/java/org/xpfarm/ollama/api/models/ChatRequest.java`
- Modify: `src/main/java/org/xpfarm/ollama/api/models/GenerateRequest.java`
- Modify: `src/main/java/org/xpfarm/ollama/api/models/ChatMessage.java`
- Modify: `src/main/java/org/xpfarm/ollama/api/models/ChatResponse.java`
- Modify: `src/main/java/org/xpfarm/ollama/api/models/GenerateResponse.java`
- Test: `src/test/java/org/xpfarm/ollama/api/models/ChatRequestSerializationTest.java` (create)
- Test: `src/test/java/org/xpfarm/ollama/api/models/GenerateRequestSerializationTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `ChatRequest.setSystemPrompt(String)`; `ChatRequest.setThink(Boolean)`;
  `ChatRequest.setFormat(JsonElement)`; `GenerateRequest.setThink(Boolean)`;
  `GenerateRequest.setFormat(JsonElement)`; `ChatResponse.getDone_reason()`;
  `GenerateResponse.getDone_reason()`.

**This is the highest-risk item in the release.** `/api/chat` has never had a top-level `system`
field at any Ollama version. Gin binds with `ShouldBindJSON` and `DisallowUnknownFields` appears
nowhere in `routes.go`, so the key is silently dropped and the request returns `200 OK` with the
prompt discarded. `OllamaAPI.chatWithSystemPrompt()` has never once worked. `/api/generate` **does**
accept a top-level `system` — that asymmetry is the trap, and it is why `GenerateRequest.system`
stays and only `ChatRequest.system` goes.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/xpfarm/ollama/api/models/ChatRequestSerializationTest.java`:

```java
package org.xpfarm.ollama.api.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the bug that motivated release 0.3.0.
 *
 * <p>{@code /api/chat} has no top-level {@code system} field and never has. Gin binds with
 * {@code ShouldBindJSON} and {@code DisallowUnknownFields} appears nowhere in {@code routes.go},
 * so Go's {@code encoding/json} drops the key and the request returns {@code 200 OK} with the
 * system prompt silently discarded. This is the class of bug that looks healthy in every log.
 */
final class ChatRequestSerializationTest {

    private static final Gson GSON = new Gson();

    private static JsonObject serialize(ChatRequest request) {
        return JsonParser.parseString(GSON.toJson(request)).getAsJsonObject();
    }

    /** Acceptance check 1. */
    @Test
    void systemPromptBecomesARoleSystemMessageAndNeverATopLevelField() {
        ChatRequest request = new ChatRequest();
        request.setModel("llama3.2");
        request.setMessages(List.of(ChatMessage.user("How do I build a house?")));
        request.setSystemPrompt("You are a helpful Minecraft assistant.");

        JsonObject json = serialize(request);

        assertFalse(json.has("system"),
                "a top-level 'system' field is silently dropped by Ollama -- the prompt would be "
                        + "discarded and the request would still return 200");

        var messages = json.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("You are a helpful Minecraft assistant.",
                messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    }

    /** No accidental reintroduction via a leftover setter. */
    @Test
    void chatRequestExposesNoSystemAccessorAtAll() {
        for (var method : ChatRequest.class.getMethods()) {
            assertFalse(method.getName().equals("setSystem") || method.getName().equals("getSystem"),
                    "ChatRequest." + method.getName() + " reintroduces the top-level system field");
        }
        for (var field : ChatRequest.class.getDeclaredFields()) {
            assertFalse(field.getName().equals("system"),
                    "ChatRequest still declares a 'system' field; Gson will serialize it");
        }
    }

    @Test
    void applyingASystemPromptTwiceReplacesRatherThanStacks() {
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.user("hi")));
        request.setSystemPrompt("first");
        request.setSystemPrompt("second");

        var messages = serialize(request).getAsJsonArray("messages");
        assertEquals(2, messages.size(), "the system message stacked instead of being replaced");
        assertEquals("second", messages.get(0).getAsJsonObject().get("content").getAsString());
    }

    /**
     * The chat session hands its own live message list to every request. Mutating it in place
     * would append a system message to the player's stored history on every single turn.
     */
    @Test
    void setMessagesDefensivelyCopiesSoSessionHistoryIsNotMutated() {
        List<ChatMessage> sessionHistory = new ArrayList<>();
        sessionHistory.add(ChatMessage.user("hi"));

        ChatRequest request = new ChatRequest();
        request.setMessages(sessionHistory);
        request.setSystemPrompt("You are a llama.");

        assertEquals(1, sessionHistory.size(),
                "the caller's list grew a system message -- chat history is now corrupted");
        assertEquals("user", sessionHistory.get(0).getRole());
    }

    /** Acceptance check 3. */
    @Test
    void thinkIsSerializedWhenSetAndOmittedWhenUnknown() {
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.user("hi")));

        request.setThink(false);
        JsonObject withThink = serialize(request);
        assertTrue(withThink.has("think"), "think must be explicit: omitting it means TRUE "
                + "on a thinking-capable model since Ollama v0.12.4");
        assertFalse(withThink.get("think").getAsBoolean());

        request.setThink(null);
        assertFalse(serialize(request).has("think"),
                "an unknown capability must omit think entirely; sending it to a model without "
                        + "the capability is a hard 400");
    }

    /** Acceptance check 5. */
    @Test
    void formatSerializesAsAJsonSchemaObjectNotAString() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.user("hi")));
        request.setFormat(schema);

        JsonObject json = serialize(request);
        assertTrue(json.get("format").isJsonObject(),
                "format typed as String can only ever send \"json\"; a JSON Schema is impossible");
        assertEquals("object", json.getAsJsonObject("format").get("type").getAsString());
    }

    @Test
    void aThinkingResponseParsesAndTheThinkingTextIsNotMistakenForContent() {
        ChatResponse response = GSON.fromJson(
                "{\"message\":{\"role\":\"assistant\",\"thinking\":\"hmm\",\"content\":\"Hi!\"},"
                        + "\"done\":true,\"done_reason\":\"stop\"}",
                ChatResponse.class);

        assertEquals("Hi!", response.getContent());
        assertEquals("hmm", response.getMessage().getThinking());
        assertEquals("stop", response.getDone_reason());
    }

    @Test
    void doneReasonDistinguishesATruncatedAnswerFromACompleteOne() {
        ChatResponse truncated = GSON.fromJson(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"partial\"},"
                        + "\"done\":true,\"done_reason\":\"length\"}",
                ChatResponse.class);

        assertTrue(truncated.isDone());
        assertEquals("length", truncated.getDone_reason(),
                "without done_reason a reply truncated at num_predict is indistinguishable "
                        + "from a complete one");
    }

    @Test
    void nullMessagesAreToleratedWhenASystemPromptIsApplied() {
        ChatRequest request = new ChatRequest();
        request.setSystemPrompt("You are a llama.");

        var messages = serialize(request).getAsJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void messagesAccessorReturnsTheStoredCopyNotTheCallersList() {
        List<ChatMessage> original = new ArrayList<>(List.of(ChatMessage.user("hi")));
        ChatRequest request = new ChatRequest();
        request.setMessages(original);

        assertFalse(original == request.getMessages(), "setMessages did not defensively copy");
        assertSame(original.get(0), request.getMessages().get(0), "elements need not be cloned");
    }
}
```

Create `src/test/java/org/xpfarm/ollama/api/models/GenerateRequestSerializationTest.java`:

```java
package org.xpfarm.ollama.api.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * {@code /api/generate} <em>does</em> accept a top-level {@code system}, unlike {@code /api/chat}.
 * The asymmetry between the two endpoints is the trap that made the chat bug invisible, so it is
 * pinned here deliberately rather than left to be "cleaned up" later.
 */
final class GenerateRequestSerializationTest {

    private static final Gson GSON = new Gson();

    private static JsonObject serialize(GenerateRequest request) {
        return JsonParser.parseString(GSON.toJson(request)).getAsJsonObject();
    }

    @Test
    void generateKeepsItsTopLevelSystemFieldBecauseThatEndpointSupportsIt() {
        GenerateRequest request = new GenerateRequest();
        request.setModel("llama3.2");
        request.setPrompt("hello");
        request.setSystem("You are a helpful Minecraft assistant.");

        JsonObject json = serialize(request);
        assertTrue(json.has("system"),
                "/api/generate accepts top-level system; removing it here would break /ollama say");
        assertEquals("You are a helpful Minecraft assistant.", json.get("system").getAsString());
    }

    /** Acceptance check 3. */
    @Test
    void thinkIsSerializedWhenSetAndOmittedWhenUnknown() {
        GenerateRequest request = new GenerateRequest();
        request.setPrompt("hello");

        request.setThink(false);
        JsonObject withThink = serialize(request);
        assertTrue(withThink.has("think"));
        assertFalse(withThink.get("think").getAsBoolean());

        request.setThink(null);
        assertFalse(serialize(request).has("think"));
    }

    /** Acceptance check 5. */
    @Test
    void formatSerializesAsAJsonSchemaObjectNotAString() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        GenerateRequest request = new GenerateRequest();
        request.setPrompt("hello");
        request.setFormat(schema);

        assertTrue(serialize(request).get("format").isJsonObject());
    }

    @Test
    void doneReasonAndThinkingAreParsed() {
        GenerateResponse response = GSON.fromJson(
                "{\"response\":\"Hi!\",\"thinking\":\"hmm\",\"done\":true,\"done_reason\":\"stop\"}",
                GenerateResponse.class);

        assertEquals("Hi!", response.getResponse());
        assertEquals("hmm", response.getThinking());
        assertEquals("stop", response.getDone_reason());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
mvn --batch-mode --no-transfer-progress test -Dtest='ChatRequestSerializationTest+GenerateRequestSerializationTest'
```

Expected: FAIL — compilation errors, `cannot find symbol: method setSystemPrompt(String)`,
`setThink(Boolean)`, `getDone_reason()`, `getThinking()`. This is the required failing state for the
system-prompt bug before the fix lands.

- [ ] **Step 3: Rewrite `ChatRequest.java`**

Replace `src/main/java/org/xpfarm/ollama/api/models/ChatRequest.java` entirely:

```java
package org.xpfarm.ollama.api.models;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request model for the Ollama {@code /api/chat} endpoint.
 *
 * <p><strong>There is deliberately no top-level {@code system} field.</strong> {@code /api/chat}
 * has never had one, at any Ollama version. The server binds request JSON with Gin's
 * {@code ShouldBindJSON} and {@code DisallowUnknownFields} appears nowhere in {@code routes.go},
 * so an unknown {@code system} key is dropped by Go's {@code encoding/json} and the request still
 * returns {@code 200 OK} — with the system prompt discarded and nothing anywhere saying so.
 * A system prompt belongs in {@link #setSystemPrompt(String)}, as a {@code role: "system"} message.
 *
 * <p>{@link GenerateRequest} <em>does</em> keep a top-level {@code system}, because
 * {@code /api/generate} genuinely accepts one. Do not "make these consistent".
 */
public class ChatRequest {
    private String model;
    private List<ChatMessage> messages;
    private boolean stream = false;
    /**
     * Boxed so {@code null} omits the key entirely. Since Ollama v0.12.4, omitting {@code think}
     * on a thinking-capable model means {@code think: true} — but sending the field to a model
     * without the capability is a hard 400. Null therefore means "capability unknown, say nothing".
     */
    private Boolean think;
    /** {@link JsonElement}, not {@link String}: as a String this could only ever send "json". */
    private JsonElement format;
    private Map<String, Object> options;
    private String keep_alive;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<ChatMessage> getMessages() { return messages; }

    /**
     * Stores a defensive copy. The chat session hands its own live history list here, and
     * {@link #setSystemPrompt(String)} mutates the stored list — without the copy, every turn
     * would append another system message to the player's saved history.
     */
    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages == null ? null : new ArrayList<>(messages);
    }

    /**
     * Places {@code systemPrompt} as the leading {@code role: "system"} message, replacing an
     * existing leading system message rather than stacking a second one.
     *
     * <p>Call this <em>after</em> {@link #setMessages(List)}.
     */
    public void setSystemPrompt(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return;
        }
        if (messages == null) {
            messages = new ArrayList<>();
        }
        if (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) {
            messages.set(0, ChatMessage.system(systemPrompt));
        } else {
            messages.add(0, ChatMessage.system(systemPrompt));
        }
    }

    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }

    public Boolean getThink() { return think; }
    public void setThink(Boolean think) { this.think = think; }

    public JsonElement getFormat() { return format; }
    public void setFormat(JsonElement format) { this.format = format; }

    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }

    public String getKeep_alive() { return keep_alive; }
    public void setKeep_alive(String keep_alive) { this.keep_alive = keep_alive; }
}
```

- [ ] **Step 4: Update `GenerateRequest.java`**

Keep every existing field including `system`. Change the `format` field and accessors from `String`
to `com.google.gson.JsonElement`, and add a boxed `think`:

```java
    /** Boxed: null omits the key. See ChatRequest#think for why that matters. */
    private Boolean think;
    /** JsonElement, not String: as a String this could only ever send "json". */
    private JsonElement format;
```

```java
    public Boolean getThink() { return think; }
    public void setThink(Boolean think) { this.think = think; }

    public JsonElement getFormat() { return format; }
    public void setFormat(JsonElement format) { this.format = format; }
```

Add `import com.google.gson.JsonElement;`. Delete the old `String format` field and its two
accessors. Add a class Javadoc line: `/** Unlike ChatRequest, /api/generate genuinely accepts a
top-level system field, so this one keeps it. */`

- [ ] **Step 5: Add `thinking` to `ChatMessage.java`**

Add the field and accessors, leaving the three static factories untouched:

```java
    /**
     * Populated by thinking-capable models. Parsed so it is never mistaken for {@code content};
     * the plugin does not use it. Never send this field — it is response-only.
     */
    private String thinking;
```

```java
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }
```

Note: `ChatMessage` is used for both request and response. A `thinking` field would serialize into
outgoing messages if set — it never is, because nothing calls `setThinking`, and Gson omits nulls.

- [ ] **Step 6: Add `done_reason` to `ChatResponse.java` and `done_reason` + `thinking` to `GenerateResponse.java`**

In both classes add:

```java
    /**
     * Why generation stopped — "stop" for a complete answer, "length" for one truncated at
     * num_predict. Without it the two are indistinguishable.
     */
    private String done_reason;
```

```java
    public String getDone_reason() { return done_reason; }
    public void setDone_reason(String done_reason) { this.done_reason = done_reason; }
```

In `GenerateResponse.java` only, additionally add the `thinking` field and accessors with the same
Javadoc as `ChatMessage.thinking` (on `/api/generate` the thinking text is top-level, not nested in
a message).

- [ ] **Step 7: Fix the two call sites that no longer compile**

`OllamaAPI.chatWithSystemPrompt()` at line ~344 calls `request.setSystem(systemPrompt)`. Change it
to `request.setSystemPrompt(systemPrompt)`. Leave
`OllamaAPI.generateWithSystemPrompt()`'s `request.setSystem(...)` alone — that is `/api/generate`
and is correct.

- [ ] **Step 8: Run the tests to verify they pass**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest='ChatRequestSerializationTest+GenerateRequestSerializationTest'
```

Expected: PASS, 14 tests.

- [ ] **Step 9: Full verify and commit**

```bash
mvn --batch-mode --no-transfer-progress clean verify
git add src/main/java/org/xpfarm/ollama/api/models src/main/java/org/xpfarm/ollama/api/OllamaAPI.java \
        src/test/java/org/xpfarm/ollama/api/models
git commit -m "fix(api): send the system prompt as a role:system message

/api/chat has never had a top-level 'system' field at any Ollama version. Gin
binds with ShouldBindJSON and DisallowUnknownFields appears nowhere in
routes.go, so the key was dropped, the request returned 200, and the prompt was
discarded. chatWithSystemPrompt() has never once worked.

GenerateRequest KEEPS its top-level system field -- /api/generate does accept
one. The asymmetry is the trap and is pinned by a test.

setMessages now defensively copies: setSystemPrompt mutates the stored list,
and the chat session passes its own live history in.

Also: think as a boxed Boolean (null omits the key), format as JsonElement so a
JSON Schema is expressible, and done_reason plus thinking on the responses."
```

Expected: `BUILD SUCCESS`, 27 tests.

---

### Task B3: `OllamaHttp` — the transport, with real timeouts and a real gate

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/api/OllamaHttpException.java`
- Create: `src/main/java/org/xpfarm/ollama/api/OllamaHttp.java`
- Test: `src/test/java/org/xpfarm/ollama/api/OllamaHttpTest.java`

**Interfaces:**
- Consumes: `StatusPolicy.Action`, `StatusPolicy.forStatus`, `StatusPolicy.retryBudget`,
  `StatusPolicy.playerMessage` from Task B1.
- Produces:
  - `new OllamaHttp(int permits, java.util.logging.Logger logger)`
  - `void configure(String endpoint, int timeoutSeconds, int maxRetries)`
  - `String get(String path) throws OllamaHttpException` — ungated
  - `String post(String path, String jsonBody) throws OllamaHttpException` — ungated
  - `String postGated(String path, String jsonBody) throws OllamaHttpException` — acquires the gate
  - `void close()`
  - `OllamaHttpException.getAction()`, `.getPlayerMessage()`

**Two invariants a reviewer must confirm here.**

1. **Every request path sets a timeout.** This is structural, not conventional: the only way to
   build a request is `newRequest(path)`, which always calls `.timeout(...)`. There is no builder
   escape hatch. `api.timeout` was read at `OllamaAPI:77` and then never applied, so a stalled
   Ollama pinned an executor thread forever.
2. **The gate must never be acquired around a call that itself makes a gated call.** With
   `permits == 1` that is an instant self-deadlock. `/api/version` and `/api/show` are metadata
   calls that do not occupy an inference slot on the server, so they use the **ungated** entry
   points. Only `/api/chat` and `/api/generate` are gated.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/xpfarm/ollama/api/OllamaHttpTest.java`:

```java
package org.xpfarm.ollama.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the transport against a real loopback HTTP server.
 *
 * <p>{@code com.sun.net.httpserver} is a JDK built-in, so this adds no dependency. The transport
 * is deliberately Bukkit-free precisely so these paths — timeout, gate, retry, status dispatch —
 * can be tested at all; before 0.3.0 none of them could be reached without a running server.
 */
final class OllamaHttpTest {

    private HttpServer server;
    private String endpoint;
    private OllamaHttp http;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (http != null) {
            http.close();
        }
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private OllamaHttp transport(int permits, int timeoutSeconds, int maxRetries) {
        OllamaHttp created = new OllamaHttp(permits, Logger.getAnonymousLogger());
        created.configure(endpoint, timeoutSeconds, maxRetries);
        return created;
    }

    @Test
    void aSuccessfulPostReturnsTheBody() throws Exception {
        respond("/api/chat", 200, "{\"done\":true}");
        http = transport(1, 5, 0);

        assertEquals("{\"done\":true}", http.postGated("/api/chat", "{}"));
    }

    /** Acceptance check 2. */
    @Test
    void timeoutAbortsRatherThanPinningTheThread() {
        server.createContext("/api/chat", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        http = transport(1, 1, 0);

        long started = System.nanoTime();
        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < 5_000,
                "api.timeout did not fire; the call took " + elapsedMs + "ms against a 1s timeout");
        assertEquals(StatusPolicy.Action.BACKPRESSURE, thrown.getAction());
    }

    /** A timed-out request must release the gate, or the plugin wedges after one stall. */
    @Test
    void aTimedOutRequestReleasesTheGate() {
        server.createContext("/api/slow", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        respond("/api/chat", 200, "ok");
        http = transport(1, 1, 0);

        assertThrows(OllamaHttpException.class, () -> http.postGated("/api/slow", "{}"));
        assertEquals("ok", assertDoesNotThrowValue(() -> http.postGated("/api/chat", "{}")));
    }

    private static <T> T assertDoesNotThrowValue(
            org.junit.jupiter.api.function.ThrowingSupplier<T> supplier) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(supplier);
    }

    /** Acceptance check 7. */
    @Test
    void secondConcurrentRequestIsRejectedNotQueued() throws Exception {
        CountDownLatch firstIsInFlight = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        server.createContext("/api/chat", exchange -> {
            firstIsInFlight.countDown();
            try {
                releaseFirst.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"done\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        http = transport(1, 30, 0);

        Thread first = new Thread(() -> {
            try {
                http.postGated("/api/chat", "{}");
            } catch (OllamaHttpException ignored) {
                // not the subject of this test
            }
        });
        first.start();
        assertTrue(firstIsInFlight.await(5, TimeUnit.SECONDS), "the first request never started");

        long started = System.nanoTime();
        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(StatusPolicy.Action.BACKPRESSURE, thrown.getAction());
        assertTrue(elapsedMs < 2_000,
                "the 2nd request queued for " + elapsedMs + "ms instead of being refused; a warm "
                        + "Ollama never 503s, so a queued request stalls until our timeout fires");

        releaseFirst.countDown();
        first.join(10_000);
    }

    @Test
    void theGateIsReleasedAfterASuccessfulRequest() throws Exception {
        respond("/api/chat", 200, "ok");
        http = transport(1, 5, 0);

        for (int i = 0; i < 3; i++) {
            assertEquals("ok", http.postGated("/api/chat", "{}"));
        }
    }

    @Test
    void metadataCallsAreUngatedSoTheyCannotDeadlockAgainstOneInFlightGeneration() throws Exception {
        CountDownLatch generationInFlight = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        server.createContext("/api/chat", exchange -> {
            generationInFlight.countDown();
            try {
                releaseGeneration.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("ok".getBytes(StandardCharsets.UTF_8));
            }
        });
        respond("/api/show", 200, "{\"capabilities\":[\"thinking\"]}");
        http = transport(1, 30, 0);

        Thread generation = new Thread(() -> {
            try {
                http.postGated("/api/chat", "{}");
            } catch (OllamaHttpException ignored) {
                // not the subject
            }
        });
        generation.start();
        assertTrue(generationInFlight.await(5, TimeUnit.SECONDS));

        assertEquals("{\"capabilities\":[\"thinking\"]}", http.post("/api/show", "{}"),
                "the capability probe blocked on the generation gate -- at permits=1 that is a "
                        + "self-deadlock whenever a probe is triggered by a generation");

        releaseGeneration.countDown();
        generation.join(10_000);
    }

    @Test
    void aNonTwoHundredIsThrownRatherThanParsedAndDelivered() {
        respond("/api/chat", 404, "{\"error\":\"model 'nope' not found\"}");
        http = transport(1, 5, 0);

        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(StatusPolicy.Action.MODEL_MISSING, thrown.getAction());
        assertTrue(thrown.getPlayerMessage().contains("not installed"));
    }

    @Test
    void aFiveHundredIsRetriedExactlyOnceRegardlessOfMaxRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        http = transport(1, 5, 5);

        assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(2, attempts.get(),
                "expected 1 initial attempt + 1 retry; api.max_retries is a ceiling, and 500 caps "
                        + "at one retry because Ollama self-heals OOM by evicting models");
    }

    @Test
    void aFiveHundredThatRecoversOnRetryReturnsTheBody() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] bytes = "recovered".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        http = transport(1, 5, 3);

        assertEquals("recovered", http.postGated("/api/chat", "{}"));
        assertEquals(2, attempts.get());
    }

    @Test
    void aFourHundredIsNeverRetried() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        http = transport(1, 5, 5);

        assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(1, attempts.get(), "a malformed request retried; it will fail identically");
    }

    @Test
    void aRefusedConnectionSurfacesAsBackpressureRatherThanHanging() {
        http = new OllamaHttp(1, Logger.getAnonymousLogger());
        // Port 1 on loopback refuses immediately.
        http.configure("http://127.0.0.1:1", 5, 0);

        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(StatusPolicy.Action.BACKPRESSURE, thrown.getAction());
    }

    @Test
    void aGetCarriesTheTimeoutToo() {
        server.createContext("/api/version", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        http = transport(1, 1, 0);

        long started = System.nanoTime();
        assertThrows(OllamaHttpException.class, () -> http.get("/api/version"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < 5_000,
                "GET ignored api.timeout; every request path must carry it, not just POST");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
mvn --batch-mode --no-transfer-progress test -Dtest=OllamaHttpTest
```

Expected: FAIL — `cannot find symbol: class OllamaHttp`.

- [ ] **Step 3: Write `OllamaHttpException`**

Create `src/main/java/org/xpfarm/ollama/api/OllamaHttpException.java`:

```java
package org.xpfarm.ollama.api;

/**
 * A failed Ollama exchange, carrying the {@link StatusPolicy.Action} the caller should take and a
 * message that is safe to show a player — no endpoint, no response body, no stack trace.
 */
public class OllamaHttpException extends Exception {

    private static final long serialVersionUID = 1L;

    private final StatusPolicy.Action action;
    private final String playerMessage;

    public OllamaHttpException(StatusPolicy.Action action, String playerMessage, String detail) {
        super(detail);
        this.action = action;
        this.playerMessage = playerMessage;
    }

    public StatusPolicy.Action getAction() { return action; }

    public String getPlayerMessage() { return playerMessage; }
}
```

- [ ] **Step 4: Write `OllamaHttp`**

Create `src/main/java/org/xpfarm/ollama/api/OllamaHttp.java`:

```java
package org.xpfarm.ollama.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ollama HTTP transport. Deliberately free of any Bukkit reference so that timeouts, the
 * concurrency gate, retries, and status dispatch are unit-testable against a loopback server.
 *
 * <p>Built on {@link java.net.http.HttpClient}, which ships with the JDK. This replaced Apache
 * HttpClient 4.5.14 in 0.3.0 — a removed shaded dependency rather than an added one, and the
 * terminal 4.x release besides.
 *
 * <h2>Two invariants</h2>
 *
 * <p><strong>Every request carries {@code api.timeout}.</strong> The only way to build a request
 * here is {@link #newRequest(String)}, which always sets it. Before 0.3.0 the value was read from
 * config and then never applied, so a stalled Ollama pinned an executor thread forever.
 *
 * <p><strong>Metadata calls are ungated.</strong> {@code /api/version} and {@code /api/show} do
 * not occupy an inference slot on the server, and — more importantly — a probe triggered from
 * inside a gated generation would deadlock instantly at the default of one permit. Only
 * {@code /api/chat} and {@code /api/generate} go through {@link #postGated}.
 */
public final class OllamaHttp {

    /** Base for the linear backoff between retries. Kept small: the player is waiting. */
    private static final long RETRY_BACKOFF_MS = 250L;

    private final Semaphore gate;
    private final Logger logger;

    private volatile HttpClient httpClient;
    private volatile String endpoint = "http://localhost:11434";
    private volatile int timeoutSeconds = 30;
    private volatile int maxRetries = 3;

    /**
     * @param permits concurrent generations allowed. Defaults to 1 because
     *     {@code OLLAMA_NUM_PARALLEL} defaults to 1 and, per Finding 3, a warm model does not
     *     reject surplus requests — it blocks them on an internal semaphore indefinitely, so
     *     overload arrives as unbounded latency and never as an error. Fixed for the lifetime of
     *     this object; changing {@code performance.max_concurrent_requests} needs a restart.
     */
    public OllamaHttp(int permits, Logger logger) {
        this.gate = new Semaphore(Math.max(1, permits));
        this.logger = logger;
        this.httpClient = buildClient(this.timeoutSeconds);
    }

    /** Applies current config. Rebuilds the client only when the connect timeout actually moves. */
    public void configure(String endpoint, int timeoutSeconds, int maxRetries) {
        this.endpoint = endpoint;
        this.maxRetries = Math.max(0, maxRetries);
        int resolved = Math.max(1, timeoutSeconds);
        if (resolved != this.timeoutSeconds || this.httpClient == null) {
            HttpClient previous = this.httpClient;
            this.httpClient = buildClient(resolved);
            this.timeoutSeconds = resolved;
            if (previous != null) {
                // shutdownNow(), never close(): close() blocks until in-flight exchanges finish,
                // and reloadConfig() runs on the main thread. A /ollama reload during a slow
                // generation would freeze the server for up to api.timeout seconds.
                previous.shutdownNow();
            }
        }
    }

    private static HttpClient buildClient(int timeoutSeconds) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * The single request factory. Every path goes through here, which is what makes "the timeout
     * is always applied" a structural property rather than a convention someone can forget.
     */
    private HttpRequest.Builder newRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json");
    }

    /** Ungated GET. Used by the {@code /api/version} connection test. */
    public String get(String path) throws OllamaHttpException {
        return execute(newRequest(path).GET().build());
    }

    /** Ungated POST. Used by the {@code /api/show} capability probe. */
    public String post(String path, String jsonBody) throws OllamaHttpException {
        return execute(postRequest(path, jsonBody));
    }

    /**
     * POST through the concurrency gate. Refuses immediately rather than queueing: a queued
     * request would stall on Ollama's own semaphore until our timeout fired, which the player
     * experiences as a 30-second hang and the log records as a timeout indistinguishable from a
     * dead endpoint.
     */
    public String postGated(String path, String jsonBody) throws OllamaHttpException {
        if (!gate.tryAcquire()) {
            throw new OllamaHttpException(StatusPolicy.Action.BACKPRESSURE,
                    StatusPolicy.playerMessage(StatusPolicy.Action.BACKPRESSURE, 0),
                    "concurrency gate full (" + gate.availablePermits() + " permits free)");
        }
        try {
            return execute(postRequest(path, jsonBody));
        } finally {
            gate.release();
        }
    }

    private HttpRequest postRequest(String path, String jsonBody) {
        return newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    private String execute(HttpRequest request) throws OllamaHttpException {
        int attempt = 0;
        while (true) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                // Finding 3: on a warm model this is what overload looks like. Never retry it --
                // adding load to a stalled server is the one thing guaranteed not to help.
                throw new OllamaHttpException(StatusPolicy.Action.BACKPRESSURE,
                        StatusPolicy.playerMessage(StatusPolicy.Action.BACKPRESSURE, 0),
                        "timed out after " + timeoutSeconds + "s: " + request.uri());
            } catch (IOException e) {
                throw new OllamaHttpException(StatusPolicy.Action.BACKPRESSURE,
                        StatusPolicy.playerMessage(StatusPolicy.Action.BACKPRESSURE, 0),
                        "could not reach Ollama at " + endpoint + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OllamaHttpException(StatusPolicy.Action.CANCELLED,
                        StatusPolicy.playerMessage(StatusPolicy.Action.CANCELLED, 499),
                        "interrupted while awaiting Ollama");
            }

            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }

            StatusPolicy.Action action = StatusPolicy.forStatus(status);
            if (attempt < StatusPolicy.retryBudget(action, maxRetries)) {
                attempt++;
                logger.log(Level.WARNING, "Ollama returned HTTP {0}; retry {1} of {2}",
                        new Object[] {status, attempt,
                                StatusPolicy.retryBudget(action, maxRetries)});
                backoff(attempt);
                continue;
            }
            throw new OllamaHttpException(action, StatusPolicy.playerMessage(action, status),
                    "HTTP " + status + " from " + request.uri() + ": " + truncate(response.body()));
        }
    }

    private void backoff(int attempt) throws OllamaHttpException {
        try {
            Thread.sleep(RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaHttpException(StatusPolicy.Action.CANCELLED,
                    StatusPolicy.playerMessage(StatusPolicy.Action.CANCELLED, 499),
                    "interrupted while backing off");
        }
    }

    /** Error bodies go to the server log, not to a player, and are bounded. */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }

    /**
     * Uses {@code shutdownNow()} rather than {@code close()} on purpose: {@code close()} waits for
     * in-flight exchanges, and this runs from {@code onDisable()}. A pending generation would
     * otherwise stall Paper's shutdown for up to {@code api.timeout} seconds.
     */
    public void close() {
        HttpClient current = this.httpClient;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=OllamaHttpTest
```

Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/api/OllamaHttp.java \
        src/main/java/org/xpfarm/ollama/api/OllamaHttpException.java \
        src/test/java/org/xpfarm/ollama/api/OllamaHttpTest.java
git commit -m "feat(api): add a Bukkit-free transport on java.net.http.HttpClient

Acceptance checks 2 and 7. api.timeout is now structurally unavoidable -- the
single newRequest() factory always sets it, and there is no other way to build
a request. It was previously read from config at OllamaAPI:77 and never applied,
so a stalled Ollama pinned an executor thread forever.

The concurrency gate refuses rather than queues. A warm Ollama never 503s; a
queued request blocks on the server's own semaphore until our timeout fires,
which reads to the player as a 30-second hang.

Metadata calls (/api/version, /api/show) are ungated on purpose: a probe made
from inside a gated generation would self-deadlock at the default of 1 permit."
```

---

### Task B4: `ModelCapabilities` — the `/api/show` probe

**Files:**
- Create: `src/main/java/org/xpfarm/ollama/api/ModelCapabilities.java`
- Test: `src/test/java/org/xpfarm/ollama/api/ModelCapabilitiesTest.java`

**Interfaces:**
- Consumes: `OllamaHttp.post(String, String)` (ungated) and `OllamaHttpException` from Task B3.
- Produces: `new ModelCapabilities(OllamaHttp http, Logger logger)`;
  `Boolean supportsThinking(String model)` — `TRUE` capable, `FALSE` not capable, `null` unknown;
  `void forget(String model)`.

**Divergence to record (goes into the checklist in Task B6).** Acceptance check 3 as literally
worded says "`think` is present in **every** chat and generate request body". That cannot hold
alongside check 4 and Finding 2: sending the `think` field to a model without the thinking
capability is a hard 400, so the field must be *absent* for a non-capable model. Check 3 is
implemented as "present whenever the model is known to be thinking-capable", which is what the
probe exists to determine. When the probe fails, the field is omitted — that is exactly the
pre-0.3.0 wire format, so a probe outage cannot make anything worse.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/xpfarm/ollama/api/ModelCapabilitiesTest.java`:

```java
package org.xpfarm.ollama.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Acceptance check 4: a model without the thinking capability is never sent {@code think}. */
final class ModelCapabilitiesTest {

    private HttpServer server;
    private OllamaHttp http;
    private final AtomicInteger probes = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        http = new OllamaHttp(1, Logger.getAnonymousLogger());
        http.configure("http://127.0.0.1:" + server.getAddress().getPort(), 5, 0);
    }

    @AfterEach
    void stopServer() {
        http.close();
        server.stop(0);
    }

    private void showReturns(int status, String body) {
        server.createContext("/api/show", exchange -> {
            probes.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    void aThinkingCapableModelIsReportedCapable() {
        showReturns(200, "{\"capabilities\":[\"completion\",\"thinking\"]}");
        assertEquals(Boolean.TRUE,
                new ModelCapabilities(http, Logger.getAnonymousLogger()).supportsThinking("qwen3"));
    }

    @Test
    void aModelWithoutTheCapabilityIsReportedNotCapable() {
        showReturns(200, "{\"capabilities\":[\"completion\"]}");
        assertEquals(Boolean.FALSE,
                new ModelCapabilities(http, Logger.getAnonymousLogger())
                        .supportsThinking("llama3.2"));
    }

    @Test
    void aResponseWithNoCapabilitiesArrayIsReportedNotCapable() {
        showReturns(200, "{\"details\":{}}");
        assertEquals(Boolean.FALSE,
                new ModelCapabilities(http, Logger.getAnonymousLogger())
                        .supportsThinking("llama3.2"));
    }

    @Test
    void theResultIsCachedPerModelSoTheProbeRunsOnce() {
        showReturns(200, "{\"capabilities\":[\"thinking\"]}");
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        capabilities.supportsThinking("qwen3");
        capabilities.supportsThinking("qwen3");
        capabilities.supportsThinking("qwen3");

        assertEquals(1, probes.get(), "the capability probe is not cached");
    }

    @Test
    void differentModelsAreProbedIndependently() {
        server.createContext("/api/show", exchange -> {
            probes.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = body.contains("qwen3")
                    ? "{\"capabilities\":[\"thinking\"]}"
                    : "{\"capabilities\":[\"completion\"]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        assertEquals(Boolean.TRUE, capabilities.supportsThinking("qwen3"));
        assertEquals(Boolean.FALSE, capabilities.supportsThinking("llama3.2"));
        assertEquals(2, probes.get(),
                "a per-model cache is required: api.model can change under /ollama reload and any "
                        + "API consumer may set its own model");
    }

    @Test
    void aFailedProbeReportsUnknownAndIsNotCachedAsAnAnswer() {
        showReturns(500, "boom");
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        assertNull(capabilities.supportsThinking("qwen3"),
                "a failed probe must be 'unknown', which omits think entirely -- guessing false "
                        + "risks the hard 400 of sending think to a non-capable model");
        capabilities.supportsThinking("qwen3");
        assertTrue(probes.get() >= 2, "a failed probe was cached as a negative answer");
    }

    @Test
    void aProbeAgainstAnUnreachableEndpointReportsUnknownRatherThanThrowing() {
        OllamaHttp unreachable = new OllamaHttp(1, Logger.getAnonymousLogger());
        unreachable.configure("http://127.0.0.1:1", 2, 0);
        try {
            assertNull(new ModelCapabilities(unreachable, Logger.getAnonymousLogger())
                    .supportsThinking("qwen3"));
        } finally {
            unreachable.close();
        }
    }

    @Test
    void forgetDropsTheCachedAnswerSoAReloadCanReprobe() {
        showReturns(200, "{\"capabilities\":[\"thinking\"]}");
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        capabilities.supportsThinking("qwen3");
        capabilities.forget("qwen3");
        capabilities.supportsThinking("qwen3");

        assertEquals(2, probes.get());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=ModelCapabilitiesTest
```

Expected: FAIL — `cannot find symbol: class ModelCapabilities`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/org/xpfarm/ollama/api/ModelCapabilities.java`:

```java
package org.xpfarm.ollama.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Caches per-model {@code /api/show} capability answers.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Since Ollama v0.12.4, omitting {@code think} on a thinking-capable model means
 * {@code think: true}. Against qwen3, gpt-oss, or deepseek-r1 every request therefore silently
 * pays a reasoning pass whose output lands in {@code message.thinking} and is discarded — pure
 * invisible latency. But sending {@code think} to a model <em>without</em> the capability is a
 * hard 400, so the field cannot simply always be set. It has to be gated on a probe.
 *
 * <h2>Why the cache is per model rather than a single enable-time answer</h2>
 *
 * <p>The model is not fixed. {@code api.model} can change under {@code /ollama reload},
 * {@code integration.expose_api} lets any other plugin submit a request with its own model, and
 * 0.4.0 will add a second caller. A single answer captured at enable would be silently wrong for
 * every model but one. The enable-time preload warms the entry for the configured model, so the
 * common path still pays no probe latency.
 *
 * <h2>Unknown is not "no"</h2>
 *
 * <p>A failed probe returns {@code null} and is not cached. Null means the {@code think} field is
 * omitted entirely, which is exactly the pre-0.3.0 wire format — so an Ollama that is up enough to
 * generate but flaky on {@code /api/show} degrades to old behavior instead of to a 400 storm.
 */
public final class ModelCapabilities {

    private static final String THINKING = "thinking";

    private final OllamaHttp http;
    private final Logger logger;
    private final Map<String, Boolean> thinkingByModel = new ConcurrentHashMap<>();

    public ModelCapabilities(OllamaHttp http, Logger logger) {
        this.http = http;
        this.logger = logger;
    }

    /**
     * @return {@code TRUE} if the model advertises the thinking capability, {@code FALSE} if it
     *     does not, {@code null} if the probe could not be completed.
     */
    public Boolean supportsThinking(String model) {
        if (model == null || model.isEmpty()) {
            return null;
        }
        Boolean cached = thinkingByModel.get(model);
        if (cached != null) {
            return cached;
        }
        Boolean probed = probe(model);
        if (probed != null) {
            thinkingByModel.put(model, probed);
        }
        return probed;
    }

    /** Drops a cached answer so the next call re-probes. Called on config reload. */
    public void forget(String model) {
        if (model != null) {
            thinkingByModel.remove(model);
        }
    }

    /** Drops every cached answer. */
    public void clear() {
        thinkingByModel.clear();
    }

    private Boolean probe(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        try {
            // Ungated on purpose: /api/show occupies no inference slot, and a gated probe called
            // from inside a gated generation would deadlock at the default of one permit.
            String response = http.post("/api/show", body.toString());
            return hasThinkingCapability(response);
        } catch (OllamaHttpException e) {
            logger.log(Level.FINE,
                    "Could not probe capabilities for model {0}: {1}. Sending no 'think' field, "
                            + "which is the pre-0.3.0 behavior.",
                    new Object[] {model, e.getMessage()});
            return null;
        }
    }

    private static Boolean hasThinkingCapability(String responseBody) {
        JsonElement parsed = JsonParser.parseString(responseBody);
        if (!parsed.isJsonObject()) {
            return Boolean.FALSE;
        }
        JsonElement capabilities = parsed.getAsJsonObject().get("capabilities");
        if (capabilities == null || !capabilities.isJsonArray()) {
            return Boolean.FALSE;
        }
        JsonArray array = capabilities.getAsJsonArray();
        for (JsonElement entry : array) {
            if (entry.isJsonPrimitive() && THINKING.equals(entry.getAsString())) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn --batch-mode --no-transfer-progress test -Dtest=ModelCapabilitiesTest
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/xpfarm/ollama/api/ModelCapabilities.java \
        src/test/java/org/xpfarm/ollama/api/ModelCapabilitiesTest.java
git commit -m "feat(api): probe /api/show for thinking capability, cached per model

Acceptance check 4. Since Ollama v0.12.4 omitting 'think' means think:true on a
capable model, so every request against qwen3/gpt-oss/deepseek-r1 silently paid
a reasoning pass whose output Gson discarded. Sending 'think' to a model without
the capability is a hard 400, so the field has to be gated on a probe.

Cached per model, not once at enable: api.model changes under /ollama reload,
expose_api lets other plugins pick their own model, and 0.4.0 adds a caller.

A failed probe returns unknown and is not cached; unknown omits the field, which
is the pre-0.3.0 wire format, so a flaky /api/show degrades to old behavior."
```

---

### Task B5: rewrite `OllamaAPI` onto the new transport, drop Apache HttpClient

**Files:**
- Modify: `src/main/java/org/xpfarm/ollama/api/OllamaAPI.java`
- Modify: `pom.xml`
- Modify: `src/main/java/org/xpfarm/ollama/OllamaPlugin.java`
- Modify: `src/main/resources/config.yml`
- Test: `src/test/java/org/xpfarm/ollama/api/OllamaAPITest.java`

**Interfaces:**
- Consumes: `OllamaHttp`, `OllamaHttpException`, `StatusPolicy`, `ModelCapabilities` from B1/B3/B4;
  the model changes from B2.
- Produces: `OllamaAPI.preload()` for `OllamaPlugin` to call on enable. Every existing public
  method keeps its exact signature — `OllamaCommand`, `OllamaEventListener`, and 0.4.0 all depend
  on them.

This is the largest diff and the one that must be atomic: removing the Apache dependency breaks
`OllamaAPI` until the rewrite lands, so both happen in one commit.

- [ ] **Step 1: Extend `OllamaAPITest` with the config assertions that must survive**

In `src/test/java/org/xpfarm/ollama/api/OllamaAPITest.java`, **replace** the existing
`max_concurrent_requests` stub — the default argument changes from `5` to `1`, and a Mockito stub
registered against the old default no longer matches:

```java
        // was: when(mockPlugin.getConfig().getInt("performance.max_concurrent_requests", 5))
        when(mockPlugin.getConfig().getInt("performance.max_concurrent_requests", 1))
                .thenReturn(1);
        when(mockPlugin.getConfig().getInt("api.max_retries", 3)).thenReturn(3);
        // Unstubbed, getLogger() returns null and OllamaHttp/ModelCapabilities NPE on first log.
        when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
```

```java
    @Test
    void theConcurrencyGateDefaultsToOneToMatchOllamaNumParallel() {
        // The default passed to getInt() is the contract: OLLAMA_NUM_PARALLEL defaults to 1, and a
        // warm model does not 503 when overloaded -- it stalls indefinitely. A client-side default
        // of 5 would therefore stall 4 requests rather than refuse them.
        verify(mockPlugin.getConfig()).getInt("performance.max_concurrent_requests", 1);
    }

    @Test
    void maxRetriesIsActuallyReadFromConfig() {
        verify(mockPlugin.getConfig()).getInt("api.max_retries", 3);
    }
```

- [ ] **Step 2: Run to verify the new tests fail**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
mvn --batch-mode --no-transfer-progress test -Dtest=OllamaAPITest
```

Expected: FAIL — `performance.max_concurrent_requests` is still requested with a default of `5`.

- [ ] **Step 3: Remove Apache HttpClient from `pom.xml`**

Delete the whole dependency block:

```xml
        <dependency>
            <groupId>org.apache.httpcomponents</groupId>
            <artifactId>httpclient</artifactId>
            <version>4.5.14</version>
        </dependency>
```

Delete the now-dead relocation from the shade plugin — nothing under `org.apache.http` remains to
relocate. **Keep the Gson relocation.** The `<relocations>` element becomes:

```xml
                            <relocations>
                                <relocation>
                                    <pattern>com.google.gson</pattern>
                                    <shadedPattern>org.xpfarm.ollama.libs.gson</shadedPattern>
                                </relocation>
                            </relocations>
```

Bump Gson from `2.10.1` to `2.14.0`:

```xml
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.14.0</version>
        </dependency>
```

- [ ] **Step 4: Rewrite `OllamaAPI.java`**

The class keeps every public signature. Replace the imports, fields, constructor, and the four
request methods. Full replacement for the head of the file:

```java
package org.xpfarm.ollama.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.xpfarm.ollama.OllamaPlugin;
import org.xpfarm.ollama.api.models.GenerateRequest;
import org.xpfarm.ollama.api.models.GenerateResponse;
import org.xpfarm.ollama.api.models.ChatRequest;
import org.xpfarm.ollama.api.models.ChatResponse;
import org.xpfarm.ollama.api.models.ChatMessage;
import org.xpfarm.ollama.events.OllamaRequestEvent;
import org.xpfarm.ollama.events.OllamaResponseEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Bukkit-facing Ollama client.
 *
 * <p>All HTTP concerns — timeouts, the concurrency gate, retries, status dispatch — live in
 * {@link OllamaHttp}, which has no Bukkit reference and is therefore testable. This class is the
 * wiring: config, events, the async-to-main-thread hop, and {@code think} gating.
 *
 * @author Carmelo Santana
 */
public class OllamaAPI {

    private final OllamaPlugin plugin;
    private final Gson gson;
    private final ExecutorService executorService;
    private final OllamaHttp http;
    private final ModelCapabilities capabilities;

    private String endpoint;
    private String defaultModel;
    private int timeout;
    private int maxRetries;
    private double defaultTemperature;

    public OllamaAPI(OllamaPlugin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();

        // Default 1, matching OLLAMA_NUM_PARALLEL. Per Finding 3 a warm model does not reject
        // surplus requests, it blocks them indefinitely, so a higher client-side default would
        // stall requests rather than refuse them. Operators who raised OLLAMA_NUM_PARALLEL raise
        // this to match; see config.yml. Changing it needs a restart, not a /ollama reload.
        int permits = plugin.getConfig().getInt("performance.max_concurrent_requests", 1);

        // Headroom above the gate so an ungated metadata probe or a refused request still gets a
        // thread promptly instead of queueing behind in-flight generations.
        this.executorService = Executors.newFixedThreadPool(Math.max(1, permits) + 2);

        this.http = new OllamaHttp(permits, plugin.getLogger());
        this.capabilities = new ModelCapabilities(this.http, plugin.getLogger());

        loadConfig();
    }

    private void loadConfig() {
        this.endpoint = plugin.getConfig().getString("api.endpoint", "http://localhost:11434");
        this.defaultModel = plugin.getConfig().getString("api.model", "llama3.2");
        this.timeout = plugin.getConfig().getInt("api.timeout", 30);
        this.maxRetries = plugin.getConfig().getInt("api.max_retries", 3);
        this.defaultTemperature = plugin.getConfig().getDouble("api.temperature", 0.7);
        // Both values were read and then never used before 0.3.0. This line is what makes them real.
        this.http.configure(this.endpoint, this.timeout, this.maxRetries);
    }

    public void reloadConfig() {
        loadConfig();
        // api.model may have changed, and a stale capability answer would gate 'think' wrongly.
        this.capabilities.clear();
        plugin.debugLog("API configuration reloaded");
    }
```

Replace `testConnection` with:

```java
    public void testConnection(BiConsumer<Boolean, String> callback) {
        CompletableFuture.runAsync(() -> {
            String message;
            boolean ok;
            try {
                JsonObject json = JsonParser.parseString(http.get("/api/version")).getAsJsonObject();
                ok = true;
                message = "Connected to Ollama v" + json.get("version").getAsString();
            } catch (OllamaHttpException e) {
                ok = false;
                message = e.getPlayerMessage();
            } catch (RuntimeException e) {
                ok = false;
                message = "Unexpected response from Ollama: " + e.getMessage();
            }
            final boolean success = ok;
            final String result = message;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(success, result));
        }, executorService);
    }
```

Replace `generateWithRequest` with:

```java
    public void generateWithRequest(GenerateRequest request, Player player,
            Consumer<GenerateResponse> callback) {
        OllamaRequestEvent requestEvent = new OllamaRequestEvent(player, request);
        Bukkit.getPluginManager().callEvent(requestEvent);
        if (requestEvent.isCancelled()) {
            plugin.debugLog("Request cancelled by event");
            return;
        }

        if (request.getModel() == null) {
            request.setModel(defaultModel);
        }
        applyThinkGating(request.getModel(), request::setThink);

        CompletableFuture.runAsync(() -> {
            GenerateResponse result;
            try {
                String json = gson.toJson(request);
                plugin.debugLog("Sending generate request: " + json);
                String body = http.postGated("/api/generate", json);
                plugin.debugLog("Received response: " + body);
                result = gson.fromJson(body, GenerateResponse.class);
            } catch (OllamaHttpException e) {
                logFailure("generate", e);
                result = new GenerateResponse();
                result.setError(e.getPlayerMessage());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Error generating text", e);
                result = new GenerateResponse();
                result.setError("Failed to generate text: " + e.getMessage());
            }
            final GenerateResponse delivered = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.getPluginManager().callEvent(new OllamaResponseEvent(player, delivered));
                callback.accept(delivered);
            });
        }, executorService);
    }
```

Replace `chatWithRequest` with the identical shape, using `/api/chat`, `ChatRequest`,
`ChatResponse`, and `"chat"` in the log label.

Add these three helpers:

```java
    /**
     * Sets {@code think} only when the model is known to be thinking-capable.
     *
     * <p>Capable → {@code false} explicitly, because since Ollama v0.12.4 omitting the field means
     * {@code true} and we do not want to pay for reasoning tokens we discard. Not capable, or
     * unknown → the field is left null and Gson omits it, because sending {@code think} to a model
     * without the capability is a hard 400.
     */
    private void applyThinkGating(String model, Consumer<Boolean> setThink) {
        setThink.accept(Boolean.TRUE.equals(capabilities.supportsThinking(model)) ? Boolean.FALSE
                : null);
    }

    private void logFailure(String label, OllamaHttpException e) {
        switch (e.getAction()) {
            case MALFORMED_REQUEST ->
                    plugin.getLogger().log(Level.SEVERE,
                            "Ollama rejected the {0} request as malformed. This is a client bug, "
                                    + "not an operator problem: {1}",
                            new Object[] {label, e.getMessage()});
            case MODEL_MISSING ->
                    plugin.getLogger().log(Level.WARNING,
                            "Model not installed on the Ollama server ({0}): {1}",
                            new Object[] {label, e.getMessage()});
            case CANCELLED -> plugin.debugLog(label + " request cancelled: " + e.getMessage());
            default -> plugin.getLogger().log(Level.WARNING,
                    "Ollama {0} request failed: {1}", new Object[] {label, e.getMessage()});
        }
    }

    /**
     * Loads the configured model and warms its capability answer, so a player's first message
     * does not pay the cold-load cost. Never throws and never blocks the main thread; an
     * unreachable endpoint here is a logged line and nothing more.
     */
    public void preload() {
        CompletableFuture.runAsync(() -> {
            capabilities.supportsThinking(defaultModel);
            GenerateRequest request = new GenerateRequest();
            request.setModel(defaultModel);
            request.setPrompt("");
            request.setStream(false);
            try {
                http.postGated("/api/generate", gson.toJson(request));
                plugin.getLogger().info("Preloaded Ollama model " + defaultModel);
            } catch (OllamaHttpException e) {
                plugin.getLogger().info("Could not preload model " + defaultModel + ": "
                        + e.getPlayerMessage() + " The first request will pay the load cost.");
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "Preload failed", e);
            }
        }, executorService);
    }
```

Replace `shutdown()`:

```java
    public void shutdown() {
        executorService.shutdownNow();
        http.close();
    }
```

Leave `generate`, `chat`, `generateWithSystemPrompt`, `generateWithContext`, `getDefaultModel`, and
`getEndpoint` as they are, except that `chatWithSystemPrompt` already calls `setSystemPrompt` from
Task B2.

- [ ] **Step 5: Call `preload()` from `OllamaPlugin`**

In `OllamaPlugin.logStartupInfo()`, after `testAPIConnection();`, add:

```java
        // Pay the cold-model-load cost at startup rather than on a player's first message.
        ollamaAPI.preload();
```

- [ ] **Step 6: Update `config.yml`**

Change the `performance` block:

```yaml
# Performance Settings
performance:
  # Maximum concurrent Ollama requests this plugin will have in flight.
  #
  # Keep this equal to the server's OLLAMA_NUM_PARALLEL, which itself defaults to 1. Raising this
  # without raising OLLAMA_NUM_PARALLEL does not increase throughput: an already-loaded model does
  # not reject surplus requests, it blocks them on an internal semaphore with no timeout, so the
  # extra requests simply stall until this plugin's api.timeout fires. Requests beyond this limit
  # are refused immediately with a message, which is the honest outcome.
  #
  # Changing this takes effect on server restart, not on /ollama reload.
  max_concurrent_requests: 1
```

Leave `rate_limit`, `cache_responses`, and `cache_ttl` unchanged.

- [ ] **Step 7: Verify and prove Apache is gone**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
mvn --batch-mode --no-transfer-progress clean verify
grep -rn "org\.apache\.http" src/ ; echo "grep exit: $? (1 == clean)"
unzip -l target/ollama-0.3.0.jar | grep -c "org/apache/http" ; echo "should print 0"
unzip -l target/ollama-0.3.0.jar | grep -c "org/xpfarm/ollama/libs/gson" ; echo "should be > 0"
```

Expected: `BUILD SUCCESS`; grep exit 1; zero `org/apache/http` entries; a non-zero relocated Gson
count.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/java/org/xpfarm/ollama/api/OllamaAPI.java \
        src/main/java/org/xpfarm/ollama/OllamaPlugin.java \
        src/main/resources/config.yml src/test/java/org/xpfarm/ollama/api/OllamaAPITest.java
git commit -m "refactor(api): move OllamaAPI onto java.net.http and drop Apache HttpClient

Removes a shaded dependency instead of adding one; 4.5.14 was the terminal 4.x
release. The org.apache.http shade relocation is deleted with it -- the Gson
relocation stays, and Gson moves 2.10.1 -> 2.14.0.

api.timeout and api.max_retries are now genuinely applied on every request path,
via OllamaHttp.configure(). Both were read at OllamaAPI:77-78 and never used, so
a stalled Ollama pinned an executor thread forever.

A non-200 is no longer parsed and delivered as if it were an answer; it maps
through StatusPolicy to a distinct logged action and a player-safe message.

think is set to false only for models the /api/show probe reports as capable,
and omitted otherwise. The model is preloaded on enable so the first player
message does not pay the cold-load cost.

performance.max_concurrent_requests defaults to 1, matching OLLAMA_NUM_PARALLEL."
```

---

### Task B6: documentation and checklist evidence

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/PLUGIN_CHECKLIST.md`

**Interfaces:** consumes the outcomes of A1 and B1–B5. Produces no code.

- [ ] **Step 1: `README.md` — ViaVersion as a hard runtime requirement**

Under **Prerequisites**, replace the list with:

```markdown
### Prerequisites
- Java 25+
- Minecraft Paper 26.1.2+ (this is Minecraft Java **26.1** — Mojang moved to `YY.D[.H]`
  versioning in 2026 and 26.1 succeeded the 1.21 line)
- **ViaVersion — required, not optional.** Geyser emulates a Java 26.2 client against a 26.1.2
  server, and ViaVersion is what bridges that gap. It is declared as `softdepend` for load
  ordering only; Bedrock players cannot connect without it installed.
- [Ollama](https://ollama.com) running and reachable, if you want generation
```

Add a **Concurrency** subsection to the configuration documentation:

```markdown
### Concurrency

`performance.max_concurrent_requests` defaults to `1`, matching Ollama's own `OLLAMA_NUM_PARALLEL`
default. Raise both together or neither: an already-loaded Ollama model does not reject surplus
requests, it blocks them on an internal semaphore indefinitely, so a higher client-side limit
converts "refused immediately" into "stalled until `api.timeout` fires". Requests beyond the limit
get an immediate message instead of a hang.
```

- [ ] **Step 2: `CHANGELOG.md` — add the 0.3.0 entry above `## 0.2.1`**

```markdown
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
```

- [ ] **Step 3: `docs/PLUGIN_CHECKLIST.md` — tick gates with quoted evidence**

Update gates 2, 3, 4, 6, and 7a. Every ticked box carries a quoted command output, not an
assertion. Every box that cannot be honestly ticked stays unticked **with a stated reason**. Use
the evidence actually produced by the runs in this branch — do not copy expected values from this
plan. Record under gate 3 (or the nearest relevant gate) these dated notes:

```markdown
**2026-07-22 — divergences and findings from the 0.3.0 implementation**

- **Maven resource filtering had to be turned on.** `pom.xml` had no `<resources>` block, so
  `version: '${project.version}'` would have shipped as a literal string and Paper would have
  rejected the descriptor with `InvalidDescriptionException` — the plugin would have been absent
  from `/plugins` rather than present-and-disabled. Not in the design doc's 0.3.0 table; it is a
  prerequisite the table's `version: '${project.version}'` row silently depended on.
- **`play.xpfarm.org` is now recorded** in a new `## Server` section of `README.md`, per
  `minecraft-plugin-scaffold` gate 3, which requires the hostname wherever a repository documents
  server identity. It had appeared nowhere in this repository outside checklist prose. The section
  states explicitly that the plugin ships `enabled: false`, so the entry documents the server
  without implying a live integration that has never been confirmed.
- **`pom.xml` gained a `<developers>` block.** Gate 3 requires author metadata and the POM had
  none. Not in the design doc's 0.3.0 table.
- **Acceptance check 3 is implemented conditionally.** As worded it says `think` is present in
  *every* chat and generate body, which cannot hold alongside check 4 and Finding 2 — sending the
  field to a model without the thinking capability is a hard 400. It is implemented as "present
  whenever `/api/show` reports the model thinking-capable", and omitted otherwise, including when
  the probe fails. Omission is the pre-0.3.0 wire format, so a probe outage cannot regress it.
- **The compiler plugin's `<source>21</source><target>21</target>` was removed.** It was dead:
  `maven.compiler.release=25` already took precedence, confirmed by `javap` reporting
  `major version: 69` on the pre-change build. Leaving it would have left a false claim about the
  build in the POM. Not in the design doc's table.
- **`performance.max_concurrent_requests` changed default `5` → `1`** and now drives the
  concurrency gate. Reasoning recorded in `docs/superpowers/plans/2026-07-22-api-client-currency.md`.
- **Retry reconciliation:** `api.max_retries` is a ceiling; the per-status cap decides the rest.
  500 retries at most once, 503 uses the full budget, `0` disables retries everywhere.
```

- [ ] **Step 4: Commit**

```bash
git add README.md CHANGELOG.md docs/PLUGIN_CHECKLIST.md
git commit -m "docs: record 0.3.0 changes, gate evidence, and divergences

ViaVersion documented as a hard runtime requirement rather than an optional
soft dependency. Gates 2, 3, 4, 6 and 7a updated with quoted evidence; the
play.xpfarm.org box stays unticked with a stated reason."
```

---

### Task B7: gate 7a — a real generation in-game

**Files:** none. This produces evidence for `docs/PLUGIN_CHECKLIST.md` (committed in a follow-up).

Acceptance check 8, and the discharge of follow-up 1 from the 0.2.1 backfill. **This has never been
done for this plugin** — every prior runtime record proves only that a dormant `onEnable()` does not
throw, because the plugin ships `enabled: false` and no `/ollama` subcommand has ever been
dispatched on a real stack.

**Invoke the `minecraft-plugin-dev` skill for this task** — it owns gates 4, 5, 6, and 7a and knows
the rig contract.

- [ ] **Step 1: Build the JAR from the worktree**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
cd /home/carmelo/Projects/Minecraft/Plugins/ollama-0.3.0
mvn --batch-mode --no-transfer-progress clean verify
ls -la target/ollama-0.3.0.jar
```

The rig mounts the newest `target/*.jar` **from the directory it is invoked in**. Invoke it from
this worktree, never from the main checkout at `.../Plugins/ollama`.

- [ ] **Step 2: Boot the stack**

```bash
cd /home/carmelo/Projects/Minecraft/Plugins/ollama-0.3.0
./scripts/test-stack.sh up
```

The `scripts/extra-services.yml` overlay adds the Ollama sidecar and makes `minecraft` wait on its
healthcheck. Note that the healthcheck is `ollama list`, which passes with **zero models
installed** — a healthy sidecar does not mean a usable model.

- [ ] **Step 3: Pull a model into the sidecar**

The plugin's default is `llama3.2`. Pull the smallest model that will actually answer, and record
which one:

```bash
docker compose -p "$(cat /tmp/xpfarm-test-stack/ollama-0.3.0/env | grep COMPOSE_PROJECT_NAME | cut -d= -f2)" \
  exec ollama ollama pull llama3.2
```

If that project-name lookup does not work, find the container with `docker ps --format '{{.Names}}' | grep ollama`
and `docker exec <name> ollama pull llama3.2`. If the pull fails (no network, disk), **stop and
report it** — do not fake the evidence.

- [ ] **Step 4: Enable the plugin and point it at the sidecar**

The plugin ships `enabled: false`, so a default boot proves nothing. Edit the deployed
`plugins/Ollama/config.yml` inside the container to set `enabled: true` and
`api.endpoint: "http://ollama:11434"` (the compose service name — port 11434 is deliberately not
published to the host), then restart the plugin by restarting the server via RCON:

```bash
./scripts/test-stack.sh rcon "plugins"
```

- [ ] **Step 5: Exercise `/ollama` for real**

```bash
./scripts/test-stack.sh rcon "ollama version"
./scripts/test-stack.sh rcon "ollama status"
./scripts/test-stack.sh rcon "ollama test"
./scripts/test-stack.sh rcon "ollama say What is a creeper?"
```

Capture the **verbatim** output of each. `ollama say` returning a real generated sentence is
acceptance check 8. `ollama chat` requires a `Player` and cannot be driven from RCON console —
record that limitation rather than claiming the check passed by proxy.

- [ ] **Step 6: Verify graceful degradation**

Stop the sidecar and confirm the server stays up and the command reports an error rather than
hanging:

```bash
docker stop <ollama-container>
./scripts/test-stack.sh rcon "ollama say hello"
./scripts/test-stack.sh rcon "list"
```

Expected: an error message within roughly `api.timeout` seconds, and `list` still answering.

- [ ] **Step 7: Tear down — always, even on failure**

```bash
./scripts/test-stack.sh down
```

Leaving the stack up holds the slot lease and collides with the next run.

- [ ] **Step 8: Record the evidence in `docs/PLUGIN_CHECKLIST.md` gate 7a and commit**

Quote the actual RCON output. If any step failed, record the failure verbatim and leave the box
unticked — a papered-over gate 7a is worse than an honest unticked one, because the whole point of
this release is that a 200-shaped failure is invisible.

---

### Task B8: final whole-branch verification

- [ ] **Step 1: Clean verify from scratch**

```bash
export PATH="$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"
cd /home/carmelo/Projects/Minecraft/Plugins/ollama-0.3.0
mvn --batch-mode --no-transfer-progress clean verify 2>&1 | tee /tmp/verify.log
grep -E "Tests run:.*Failures" /tmp/verify.log | tail -3
grep "BUILD SUCCESS" /tmp/verify.log
```

Expected: >12 tests, 0 failures, 0 errors, `BUILD SUCCESS`.

- [ ] **Step 2: Inspect the shaded JAR — this is the gate, not the source files**

```bash
cd /home/carmelo/Projects/Minecraft/Plugins/ollama-0.3.0
unzip -p target/ollama-0.3.0.jar plugin.yml
echo "--- apache classes (must be 0) ---"
unzip -l target/ollama-0.3.0.jar | grep -c "org/apache/http" || true
echo "--- relocated gson (must be > 0) ---"
unzip -l target/ollama-0.3.0.jar | grep -c "org/xpfarm/ollama/libs/gson"
echo "--- unrelocated gson (must be 0) ---"
unzip -l target/ollama-0.3.0.jar | grep -c "^.*com/google/gson" || true
echo "--- original-* jars that must never ship ---"
ls target/original-*.jar 2>/dev/null || echo "none in target"
echo "--- dependency-reduced-pom must stay untracked ---"
git status --porcelain dependency-reduced-pom.xml
git check-ignore -v dependency-reduced-pom.xml
```

The embedded `plugin.yml` must show `version: '0.3.0'` fully substituted and `api-version: '26.1'`.
Reading the source file instead of the JAR is exactly the mistake this step exists to prevent.

- [ ] **Step 3: Confirm the main checkout was never touched**

```bash
cd /home/carmelo/Projects/Minecraft/Plugins/ollama
git status --porcelain
git rev-parse HEAD
```

Expected: clean, still `ae09bbb0211ffa4ebbd30ad2ec9aa9bd3420ce36`.

- [ ] **Step 4: Review the whole branch diff**

```bash
cd /home/carmelo/Projects/Minecraft/Plugins/ollama-0.3.0
git log --oneline feat/llama-companion..feat/api-currency
git diff feat/llama-companion..feat/api-currency --stat
```

Confirm no forbidden path appears: `docs/PLUGIN_CHECKLIST-0.2.1-backfill.md`,
`docs/superpowers/specs/`, `server/`, `releases/`, any `companion` package.
