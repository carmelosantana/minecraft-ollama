# New or Edited Plugin Checklist

- Plugin name: `Ollama`
- Slug: `ollama`
- Repository: `carmelosantana/minecraft-ollama`
- Owner: `Carmelo Santana`
- Target version: `0.2.1` (released; no new version is proposed by this file)
- Paper version: `26.1.2 build 74`
- Java version: `25`
- Updater destination: `ollama.jar`
- External services: `Ollama HTTP API` — opt-in, `enabled: false` by default
- Status: `active`

Maven `groupId`/`artifactId`: `com.carmelosantana` / `ollama`. `plugin.yml` name: `Ollama`
(main class `org.xpfarm.ollama.OllamaPlugin`). Releasable JAR: `ollama-<version>.jar`.
Latest tag in this clone: `v0.2.1`.

---

## READ THIS FIRST — this is a backfill, written 2026-07-21

This plugin **predates the checklist process**. It has never had a `docs/PLUGIN_CHECKLIST.md`,
and gates 1–6 and 8–12 were **never formally run or recorded** for it. This file is written after
the fact to record what is *actually known*, not to reconstruct a history that does not exist.

Accordingly:

- **Gate 7a carries real evidence**, produced by the docker-rig-consolidation effort's shared test
  rig, quoted verbatim below from `minecraft-plugin-docs/.superpowers/sdd/task-5-report.md`. That
  report does not state its own date; the sibling `task-4-report.md` dates the same effort
  **2026-07-20**.
- **Gate 7b cites a real recorded run** (the 2026-07-19 ecosystem matrix) in
  `minecraft-plugin-docs/CURRENT_STATE.md`. It was not re-run for this backfill.
- Everything else is either an **observed fact** (something readable in the repo or manifest today,
  with the place it was read stated) or is marked **NOT RECORDED / NOT RUN / UNKNOWN**.
- An **observed fact is not a passed gate**. A checkbox is ticked only where a gate criterion is
  genuinely satisfied by evidence quoted here.

**This plugin's runtime history is the worst of the four backfilled repos** — see gate 7a. Its old
`docker-compose.yml` never delivered the plugin to the stack *at all*, for two independent reasons.
The 2026-07-20 rig boot is, together with the 2026-07-19 matrix run, the only trustworthy evidence
this plugin has ever run.

---

## 1. Scope — NOT RECORDED

- [ ] Status is explicitly recorded as active, experimental, or excluded. **NOT RECORDED at the
      time.** `active` is asserted here from the plugin's presence in
      `minecraft-plugin-updater/plugins.json` and in the Active Plugin Releases table of
      `minecraft-plugin-docs/CURRENT_STATE.md`. No scoping decision was ever written down —
      notable for a plugin that pipes player chat to an LLM and can execute server commands.
- [ ] Purpose, commands, events, permissions, configuration, persistence, and acceptance checks are
      defined. **NOT RECORDED — predates the checklist process.** No requirements interview was
      run; no acceptance checks were ever defined. `docs/API.md` exists and documents the
      integration surface, but it is developer documentation, not a scoping record with acceptance
      criteria. The surface below is **read out of `src/main/resources/plugin.yml` today**.
- [ ] Known limitations and any intentionally withheld gates are recorded. **NOT RECORDED** for any
      released version. Gaps known *as of this backfill* are under Known gaps.

### Commands and permissions — observed from `src/main/resources/plugin.yml`

| Command | Usage | Permission |
| --- | --- | --- |
| `/ollama` | `/ollama <subcommand>` | `ollama.use` |

Permissions declared, with their **shipped defaults**:

| Permission | Default |
| --- | --- |
| `ollama.use` | `true` (everyone) |
| `ollama.generate` | `true` (everyone) |
| `ollama.code` | `true` (everyone) |
| `ollama.chat` | `true` (everyone) |
| `ollama.admin` | `op` |
| `ollama.run` | `op` |
| `ollama.debug` | `op` |

Four of seven permissions default to **every player**. That is a deliberate-looking choice with no
recorded rationale; it is recorded here as an observation, not endorsed. `ollama.run` (command
execution) correctly defaults to `op`, and `config.yml` additionally ships
`commands.enable_execution: false`.

Subcommand names are not enumerated in `plugin.yml` (`usage` is generic) and were not extracted
from source for this backfill.

### Known gaps (as of this backfill)

- One test class exists (`src/test/java/org/xpfarm/ollama/api/OllamaAPITest.java`). The report for
  the 2026-07-20 build **did not quote a test count**, so no test-count figure is claimed here.
- No acceptance criteria exist against which any release could be judged.
- Runtime evidence covers **load and enable** plus the sidecar wiring — no `/ollama` subcommand has
  ever been dispatched on a stack, and no generation request has ever been observed succeeding
  against a real Ollama endpoint from inside Minecraft.

## 2. Repository — PARTIAL (observed)

- [x] Repository is `carmelosantana/minecraft-ollama` with an SSH `origin` and `main` branch.
      **Observed** via `git remote -v`: `origin git@github.com:carmelosantana/minecraft-ollama.git`.
      This file is committed on `test/docker-rig-consolidation`, branched off `main`.
- [ ] Existing user-owned worktree changes were identified and preserved. **NOT RECORDED as a
      gate.** Observed today: clean tree on `test/docker-rig-consolidation`. The rig-migration
      report states the repo was `## main...origin/main` with no local changes before branching.
- [ ] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or
      documentation. **PARTIALLY CHECKED, not a formal audit.** A case-insensitive grep of the
      working tree run on 2026-07-21 returned **zero hits**. Scope limits: it excluded `target/`,
      `.git/`, `releases/`, and `server/`, and therefore says **nothing about git history**.

## 3. Metadata — PARTIAL (observed)

- [x] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent.
      **Observed:** `LICENSE` begins "GNU AFFERO GENERAL PUBLIC LICENSE / Version 3, 19 November
      2007"; `pom.xml` declares `<name>GNU Affero General Public License v3.0 or later</name>`.
- [ ] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present. **HALF
      PRESENT.** Author metadata is there — `plugin.yml` `author: Carmelo Santana`. The
      `xpfarm.org` URL is **not**: `pom.xml` `<url>` and `plugin.yml` `website:` both point at
      `https://github.com/carmelosantana/minecraft-ollama`. A grep of the working tree found the
      string `xpfarm` only as the Java package `org.xpfarm.ollama` and the shade relocation targets
      — **never as an `xpfarm.org` URL**. Left unchecked; this backfill must not edit `pom.xml` or
      `plugin.yml`.
- [ ] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity
      is documented. **NOT PRESENT.** A grep of the working tree on 2026-07-21 found **no**
      `play.xpfarm.org` reference anywhere in this repository.
- [ ] New work uses the `org.xpfarm` Maven group, or an existing-coordinate compatibility decision
      is documented. **NOT SATISFIED.** `pom.xml` still declares
      `<groupId>com.carmelosantana</groupId>` — the only one of the four backfilled repos that has
      not been moved to `org.xpfarm`. The Java package **is** `org.xpfarm.ollama`, so group and
      package disagree. **No compatibility decision documenting this is recorded anywhere.** This
      is an open inconsistency, not a resolved choice.
- [x] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are
      consistent. **Observed:** slug `ollama`, artifact `ollama`, JAR `ollama-0.2.1.jar`, updater
      destination `ollama.jar`, `plugin.yml` name `Ollama`. The manifest `asset_regex`
      `^ollama-[0-9].*\.jar$` matches the JAR name.
- [ ] No secrets committed in source, defaults, tests, logs, history, or documentation.
      **NOT AUDITED.** No secret scan was run for this backfill. Observed only: `config.yml` ships
      a localhost endpoint (`http://localhost:11434`) and **no credential field at all**, so there
      is no API key in defaults to leak. That is an observation about the default file, not a scan
      of source, tests, or history.

**Observed version-drift hazard.** `plugin.yml` hardcodes `version: 0.2.1` rather than using
`version: '${project.version}'` as the sibling repos do. It happens to match `pom.xml` today, so
gate 9's consistency check passes — but nothing enforces that, and a POM bump without a matching
`plugin.yml` edit would ship a JAR that lies about its own version. Recorded, not fixed (this
backfill must not touch `plugin.yml`).

**Observed drift note:** `pom.xml` sets `<maven.compiler.release>25</maven.compiler.release>` while
the `maven-compiler-plugin` block also carries `<source>21</source>`. Which wins was **not
investigated**.

## 4. Compatibility — PARTIAL

- [x] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '1.21'`.
      **Real evidence** from `task-5-report.md`:

      ```
      [INFO] Replacing original artifact with shaded artifact.
      [INFO] BUILD SUCCESS
      ```

      (jar: `target/ollama-0.2.1.jar`). `pom.xml` depends on
      `io.papermc.paper:paper-api:26.1.2.build.74-stable` (`provided`). `plugin.yml` declares
      `api-version: 1.21` — **unquoted**, unlike the sibling repos' `'1.21'`. YAML parses that as a
      float; Paper accepted it on the 2026-07-20 boot, so it works in practice, but it is a
      deviation from the house form and is recorded as such.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and
      declared. **Observed:** `plugin.yml` declares `softdepend: [ViaVersion]` and no hard
      `depend`. Runtime-scoped POM dependencies are `com.google.code.gson:gson:2.10.1` and
      `org.apache.httpcomponents:httpclient:4.5.14`, both **shaded with relocations** —
      `com.google.gson` → `org.xpfarm.ollama.libs.gson`, `org.apache.http` →
      `org.xpfarm.ollama.libs.http` — so neither can collide with another plugin's copy.
      `paper-api` is `provided`; `junit-jupiter` and `mockito-core` are `test`.
- [ ] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and
      protocol behavior. **NEVER PERFORMED — NOT RECORDED.** The `softdepend: [ViaVersion]` line is
      a load-order declaration, not a review. This plugin's surface is chat and command text, which
      is exactly where Bedrock input differs (no tab completion via Geyser, `.`-prefixed Floodgate
      usernames, different character handling). Whether any of that was considered is **UNKNOWN**.
      The gate 7a boot shows coexistence with Geyser/Floodgate/ViaVersion; that is not a review.

## 5. External services — PARTIAL (defaults observed; failure path evidenced elsewhere)

- [x] External integrations are disabled by default or require explicit configuration and have
      bounded timeouts. **Observed** in `src/main/resources/config.yml`:

      ```yaml
      # Ollama integration is opt-in. When false, no API client or listeners are started.
      enabled: false
      ...
        endpoint: "http://localhost:11434"
        timeout: 30
        max_retries: 3
      ```

      Off by default; the endpoint is localhost; the timeout is bounded at 30 seconds with
      `max_retries: 3`. Also observed: `commands.enable_execution: false` with a
      `blocked_commands` list (`stop`, `restart`, `op`, `deop`, `ban`, `kick`) and
      `require_confirmation: true`, and `performance.rate_limit: 10` per player per minute.
      **These are the shipped values, read today. That the code honours them was not verified
      here.**
- [x] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable.
      **Evidence exists, but it is the matrix run's, not this gate's.**
      `minecraft-plugin-docs/CURRENT_STATE.md` records that on 2026-07-19 the plugin was
      deliberately pointed at TEST-NET-2 `198.51.100.9` to exercise the real failure path: "Ollama
      caught `java.net.SocketException: Network is unreachable`, retried once and stopped (bounded,
      no loop)" and "Server stayed available. No credential-shaped strings in logs." That is a real
      negative-path observation for this plugin. It is cited from that document; it was **not
      re-run** here.
- [x] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets. Same
      2026-07-19 evidence: the server stayed available and no credential-shaped strings appeared.
      Note `config.yml` defines **no credential field**, so "redaction" is largely untested by
      construction — there was nothing to redact.

## 6. Tests and build — PARTIAL

- [ ] Unit tests cover separable logic, configuration, serialization, permissions, and failure
      paths. **THIN AND UNQUANTIFIED.** Exactly one test class exists,
      `src/test/java/org/xpfarm/ollama/api/OllamaAPITest.java` (JUnit 5 + Mockito). The 2026-07-20
      build report quoted only `BUILD SUCCESS` and **did not record a test count**, so no number is
      claimed. Nothing is known about coverage of config parsing, the permission defaults, the
      command allow/block list, or the rate limiter.
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds. **Real evidence**, quoted
      above from `task-5-report.md`.
- [ ] The releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded.
      **NOT RECORDED.** The shaded JAR has never been unzipped and inspected — which matters more
      here than in the non-shading repos, since the relocations above are unverified at the
      bytecode level. Observed instead: `.github/workflows/build.yml` filters `! -name 'original-*'`
      on the checksum, artifact-upload, and release-upload steps, so an `original-*` JAR cannot
      reach a release. `dependency-reduced-pom.xml` is produced by the shade plugin locally and is
      **git-ignored** here (`.gitignore:3`).

## 7. Matrix

### 7a — single-plugin runtime verification — PARTIAL (real evidence, narrow scope)

Evidence source: **this effort's shared test rig** (`minecraft-plugin-docs/bin/xpfarm-test-stack`)
plus this repo's new `scripts/extra-services.yml` sidecar overlay, on a disposable fresh-volume
Legendary stack, recorded verbatim in `minecraft-plugin-docs/.superpowers/sdd/task-5-report.md`.

#### The old compose file never ran this plugin — two independent, confirmed defects

Both were **confirmed present in the deleted `docker-compose.yml`** during the 2026-07-20
migration, and both are recorded here as genuine history rather than as gate evidence:

1. **The plugin was never delivered to the stack.** The compose file set **both** an exec-form
   `entrypoint:` **and** a `command:` block on the `minecraft` service. Docker passes `command` as
   *arguments to the entrypoint*, so the entire `bash -c` readiness-wait-plus-plugin-copy script
   was handed to `start.sh` as positional junk and **never ran**. The stack came up; the plugin was
   simply not there.
2. **The healthcheck could never have passed.** It ran
   `curl -f http://localhost:11434/api/version`, and the `ollama/ollama` image **ships no `curl`**
   — verified during the migration ("`curl absent`"). `ollama list` does work inside the image and
   is what the replacement healthcheck uses.

Two further problems in the same file: the sidecar published `11434:11434` on the host
unconditionally, outside the slot lease, and its `command:` ran `ollama pull llama3.2` on every
boot.

**Both defects are now fixed** by `scripts/test-stack.sh` (the shared rig, which mounts the newest
`target/*.jar` and *asserts* the plugin is enabled) plus `scripts/extra-services.yml`, in which the
base compose owns the entrypoint, the overlay sets **neither** `command:` nor `entrypoint:`,
readiness comes from `depends_on: ollama: condition: service_healthy` rather than a shell loop,
`11434` is not host-published, and there is no `ollama pull`.

The consequence for this checklist is blunt: **no runtime claim about this plugin made before
2026-07-19 can be trusted**, because the local harness that would have produced it was incapable
of loading the plugin.

#### What was actually observed on 2026-07-20

- [x] Paper, Geyser, Floodgate, and ViaVersion start successfully together, with the plugin loaded
      **and enabled**. **VERIFIED.**

      The sidecar reached healthy *before* Paper started — the ordering the old compose faked with
      a shell loop:

      ```
       Container xpfarm-plugin-test-ollama-bae39558-ollama-1 Started
       Container xpfarm-plugin-test-ollama-bae39558-ollama-1 Waiting
       Container xpfarm-plugin-test-ollama-bae39558-ollama-1 Healthy
       Container xpfarm-plugin-test-ollama-bae39558-minecraft-1 Starting
       Container xpfarm-plugin-test-ollama-bae39558-minecraft-1 Started
      ```

      Paper's own completion line:

      ```
      minecraft-1  | >....[K[16:54:14 INFO]: Done (16.066s)! For help, type "help"
      ```

      A **real Minecraft protocol handshake** against the Java port — not a bare TCP connect:

      ```
      MOTD: "A Minecraft Server"
      VERSION: Paper 26.1.2 | protocol 775
      PLAYERS: 0 / 20
      ```

      RCON `plugins`, captured raw with `cat -v` so the `§` colour bytes are visible as `M-BM-'`:

      ```
      AUTH OK
      $ plugins
      M-BM-'xM-BM-'3M-BM-'4M-BM-'9M-BM-'fM-BM-'dM-BM-'aM-bM-^DM-9 M-BM-'fServer Plugins (4):
      M-BM-'xM-BM-'eM-BM-'dM-BM-'8M-BM-'1M-BM-'0M-BM-'6Bukkit Plugins:
       M-BM-'8- M-BM-'afloodgateM-BM-'r, M-BM-'aGeyser-SpigotM-BM-'r, M-BM-'aOllamaM-BM-'r, M-BM-'aViaVersion
      ```

      `Ollama` is prefixed `M-BM-'a` = `§a` = **green = enabled**, not merely listed. The header
      count `(4)` matches the four names listed. No Geyser/Floodgate/ViaVersion **version strings**
      were recorded for this run — only presence and green state.

      Port containment was also verified, three ways:

      ```
      NAME                                             SERVICE     STATUS                    PORTS
      xpfarm-plugin-test-ollama-bae39558-ollama-1      ollama      Up 46 seconds (healthy)   11434/tcp
      ```

      ```
      === any host binding to 11434? ===       -> no host binding to 11434 (GOOD)
      === docker port ollama container ===     -> ollama publishes nothing to host (GOOD)
      === docker inspect health ===            -> ...-ollama-1 => healthy
      ```

- [ ] Java and Bedrock smoke tests cover joins plus commands, events, permissions, persistence, and
      reloads. **NOT DONE — neither side.** No client joined; **no `/ollama` subcommand was ever
      dispatched**; no generation request was made from inside Minecraft. Note that the plugin
      ships `enabled: false`, so on this boot it would have started **dormant** — the sidecar was
      healthy and reachable, but nothing exercised the API path. Load-and-enable is the entire
      behavioral claim.
- [ ] Public deployment smoke tests verify `play.xpfarm.org` reaches the intended entry points.
      Belongs to gate 11; **NOT DONE**.
- [x] Ollama and Umami unavailable-endpoint tests keep the server and plugins available.
      **Evidence exists but belongs to the 2026-07-19 matrix run** — see gate 5. Not exercised on
      the 2026-07-20 rig boot, where the endpoint was reachable and the plugin dormant.

### 7b — ten-plugin ecosystem matrix — PASSED, but recorded elsewhere and not re-run here

Not run by this backfill. `minecraft-plugin-docs/CURRENT_STATE.md` records an
**Ecosystem Matrix Run (2026-07-19) — PASSED 11/11** on a fresh-volume Legendary stack, installing
every plugin through the one-shot updater from published release assets. Its row for this plugin:

| Plugin | Installed | Enabled in log | Result |
|---|---|---|---|
| Ollama | 0.2.1 | Ollama | PASS |

That run reported `Done (18.076s)`, zero SEVERE or exception lines, and that each installed JAR's
SHA-256 matched its published `SHA256SUMS.txt` digest. It also carried the negative-path evidence
quoted under gate 5, and it explicitly notes that with no endpoint configured the default run
"only proved dormancy". Cited, not reproduced. No client join was performed in that run either.

## 8. CI/CD — PARTIAL (observed)

- [x] Identical standard plugin Actions workflow is installed. **Observed:**
      `.github/workflows/build.yml` is **byte-identical** to the workflow in `copper-kingdom`,
      `death-depot`, `umami`, and `curse` — md5 `df37a4e433a45b4cc999e14bb5997184` on all five,
      checked 2026-07-21. It triggers on `push` to `main`, `push` of `v*` tags, `pull_request` to
      `main`, and `workflow_dispatch`; builds with `temurin` Java `25`; runs
      `mvn --batch-mode --no-transfer-progress clean verify`; writes bare-filename `SHA256SUMS.txt`;
      and uploads release assets only for `refs/tags/v`.
- [ ] Successful main Actions run is recorded before tagging. **NOT RECORDED per release in this
      repository.** `CURRENT_STATE.md` states the tag and `main`-branch runs observed on
      `2026-07-19` were successful for all ten repositories, covering this repo at `v0.2.1` — but
      that is an ecosystem-wide observation of *outcome*, not a record that a green `main` run
      *preceded* each tag.
- [x] Workflow permissions contain no broader access than the documented contract. **Observed:**
      exactly `permissions: contents: write` at the top level, no job-level escalation, and the
      only token used is `GH_TOKEN: ${{ github.token }}` for `gh release`.

## 9. Release — `v0.2.1` published; asset verification NOT RE-DONE here

- [x] Semantic version matches the POM, plugin metadata, and `v<version>` tag. **Observed today:**
      `pom.xml` `<version>0.2.1</version>`, `plugin.yml` `version: 0.2.1`, newest tag `v0.2.1` —
      all three agree. But see the version-drift hazard in gate 3: `plugin.yml` hardcodes the
      value instead of interpolating `${project.version}`, so this agreement is coincidental
      maintenance, not a structural guarantee. Tags present: `v0.2.0`, `v0.2.1`.
- [x] Successful tag Actions run and GitHub release are recorded. **Cited, not re-verified.**
      `CURRENT_STATE.md` lists Ollama at release `v0.2.1` and records successful tag and `main`
      runs observed on 2026-07-19. GitHub was not queried for this backfill.
- [ ] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*`
      JAR. **NOT VERIFIED here.** Published assets were not downloaded or listed. Indirect support
      only: the workflow's `! -name 'original-*'` filters, and the matrix run's checksum match.
- [ ] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`. **NOT RUN here.**

## 10. Updater — enrolled (observed); behaviors NOT RUN

- [x] Updater manifest covers repository, destination, anchored asset regex, legacy globs, enabled
      state, and optional pin. **Observed** in `minecraft-plugin-updater/plugins.json`:

      ```json
      {"name": "Ollama", "repo": "carmelosantana/minecraft-ollama", "destination": "ollama.jar", "asset_regex": "^ollama-[0-9].*\\.jar$", "legacy_globs": ["ollama-[0-9]*.jar"]}
      ```

      The regex is anchored at both ends. `enabled` is **absent, which means true**. There is **no
      version pin**. So this plugin **does install and enable on every fresh volume**, even though
      it is an external-service integration that ships dormant — `CURRENT_STATE.md` makes the same
      point ("nothing ships disabled"). No manifest change is proposed by this backfill.
- [ ] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure
      behaviors pass. **NOT RUN for this plugin.** The 2026-07-19 matrix exercised *fresh install*
      of this entry as a side effect; the other five behaviors were never tested per-plugin.
- [ ] Updater dry-run uses a disposable directory and never a production plugin directory.
      **NOT RUN.**
- [ ] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.
      **NOT RUN for this plugin.**

## 11. Deployment — NOT RECORDED

- [ ] Dokploy redeployment notes identify the full recreation used to rerun the one-shot updater.
      **NOT RECORDED.**
- [ ] Updater completion, Minecraft startup, destination JAR, and stack/plugin logs were inspected.
      **NOT RECORDED.**
- [ ] No production plugin hot reload was used. **UNKNOWN** — no deployment record exists for this
      plugin at any version.

No deployment was performed by this backfill, and this workstation has no Dokploy access, so no
production log could be inspected even in principle. **Whether a production Ollama endpoint is
configured on `play.xpfarm.org`, and therefore whether this plugin is dormant or live there, is
completely unknown from this repository.**

**Rollback:** untested. The prior tag is `v0.2.0`. Note the cheapest mitigation for this specific
plugin is not a rollback at all — setting `enabled: false` returns it to dormancy — but that has
not been rehearsed in production either.

## 12. Handoff — PARTIAL

- [ ] Current-state documentation refreshed with release, CI, updater, deployment, and local
      pending state. **NOT DONE by this backfill** — `minecraft-plugin-docs/CURRENT_STATE.md` was
      deliberately left untouched. It already flags this repo as one of four carrying no gate 7a
      checklist record; that flag is now stale in this repo's favour.
- [x] Known limitations, skipped checks, migration notes, rollback guidance, and follow-up owner
      are recorded. This file is that record. Owner: Carmelo Santana.
- [x] Evidence distinguishes source commit, published tag/release, updater state, and deployed
      state without exposing secrets. Source: `test/docker-rig-consolidation`, **local and
      unpushed** — including the `scripts/extra-services.yml` overlay that fixes the compose
      defects above. Published: `v0.2.1`. Updater: enrolled, unpinned, enabled. Deployed:
      **unknown**.

**Follow-ups, in priority order:**

1. Exercise `/ollama` end to end against the sidecar with `enabled: true` — generation, chat, the
   blocked-command list, and the rate limiter. Gate 7a currently proves only that a **dormant**
   plugin's `onEnable()` does not throw.
2. Review the four permissions that default to `true` for every player (`ollama.use`,
   `ollama.generate`, `ollama.code`, `ollama.chat`) and record a rationale or tighten them.
3. Resolve the `com.carmelosantana` vs `org.xpfarm` coordinate split, or document the compatibility
   decision (gate 3).
4. Change `plugin.yml` to `version: '${project.version}'` to remove the drift hazard (gate 3).
5. Perform the Geyser/Floodgate/ViaVersion Bedrock-safety review for chat and command input
   (gate 4).
6. Inspect the shaded release JAR, confirm the relocations landed, and run a secrets scan
   (gates 3 and 6).
7. Establish and record the deployed state, including whether an endpoint is configured (gate 11).
