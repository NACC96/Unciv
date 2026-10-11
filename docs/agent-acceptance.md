# Agent Match — Release Acceptance Evidence (plan §13)

Status as of CP-034 on `agent-match-foundation`. Commands run with the
standing environment (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`,
`GRADLE_USER_HOME=/Users/noahkenny/.gradle-unciv`, `COPYFILE_DISABLE=1`,
build dirs relocated to `~/.unciv-build/` by the local-only init script —
never committed).

## 13.1 Automated checks — all green

| Check | Result |
| --- | --- |
| `./gradlew :tests:test` | **975 tests, 0 failures, 0 errors** (100 suites; 127 agent tests across 23 agent suites, incl. AgentOrchestrationLoopTest 8/8 stable over 3 runs), per-file XML presence verified after `cleanTest` |
| `./gradlew :desktop:dist` | BUILD SUCCESSFUL — runnable jar at `~/.unciv-build/desktop/libs/Unciv.jar` (57 MB; contains all agent classes — spot-checked `DemandCommand`) |
| `./gradlew check` | BUILD SUCCESSFUL |
| `npm run typecheck` (agent-runner) | clean (`tsc --noEmit`) |
| `npm test` (agent-runner) | **34/34** (7 files) |
| `npm run build` (agent-runner) | clean (`tsc -p tsconfig.build.json` → `dist/`) |

`npm ci` lockfile install was performed at setup (W5); node_modules is
present and current, so a plain test/build re-run is equivalent evidence.

## 13.2 Scenario matrix — evidence pointers

| Area | Evidence |
| --- | --- |
| Controller/rule parity | AgentMatchSetupTest (slot validation, nation-name binding); AgentSlotLogicTest (human rule parity, cycling); built-in AI stays BUILTIN_AI in controllers (all fixtures) |
| Turn ordering | TurnLifecycleTest (begin once, cycle, rollover wrap, exactly-once end effects, replay/stale fences); AgentMatchRunnerTest (round wrap fix); daemon end-turn census in DaemonLoopTest |
| Isolation | MatchCoordinatorTest (candidate cannot mutate published state, failed persist never acknowledges); FileCheckpointStoreTest (atomic generations, torn-write recovery on the real Lexar FS); suppression of settings-driven automation (CP-027, DomesticOrdersTest auto-production fence) |
| Command contract | AgentCommandsTest + suite-wide: illegal owner/phase/action, stale revision (`STALE_STATE`), reused actionId same/different payload (replay vs `ACTION_ID_CONFLICT`), pending-result lookup, removed entity fences |
| Visibility | ObservationVisibilityTest (hidden units, movement zeroing, no queue oracle, differential-ish gates); CP-018 fixture lessons |
| Decisions | Conquest decisions (CombatConquestTest/EndgameDynamicsTest: MANDATORY, `DECISIONS_PENDING` block, resolve via command); demand-response decisions (TurnLifecycleTest: raised at beginTurn, block end turn, resolved by `diplomacy.respondDemand`); free tech/policy via research/civics commands |
| Provider parsing | W1 sanitized fixtures + probes (split/malformed/truncated JSONL, unknown events, duplicate usage, session mismatch — `W1-COMPATIBILITY.md`); daemon second-turn concurrency rejected |
| Authority/lifecycle | AgentRunControlsTest (pause/step/takeover/stop state machine); AgentOrchestrationLoopTest (scripted-supervisor full loop: sequential rotation, single-invocation invariant, pause-drain, resume without duplicates, failure→retry, human handoff); supervisorFactory injection + run-state observers on DesktopAgentMatch; daemon shutdown/EOF drain; SetControllerCommand rev discipline |
| Storage | FileCheckpointStoreTest on the actual ExFAT volume: atomic move, interrupted-write recovery, corruption fallback, missing-volume error surfaced |
| UI (automated part) | Session gating: `canChangeState` degrades all compliant surfaces to read-only; popup block now requires `canChangeState` (CP-030); DevConsole forbidden; AutoPlay gated (audit doc). Interactive GL-thread checks remain manual (see below) |
| Victories | EndgameDynamicsTest: **all five** live — Domination (capital capture), Time (max turns), Scientific (Apollo + all parts in capital), Cultural (5 branches + Utopia Project), Diplomatic (UN + won vote). No further agent work after COMPLETED phase |
| Compatibility | Old saves without runner: legacy `nextTurn` path untouched when no session; unsupported-format refusals in AgentMatchSetup/Session attach (logged, session stays null) |

## 13.3 Deliberate manual acceptance — needs a human (checklist below)

**Terminology correction (scope prompt):** a 30–40-turn smoke test is NOT a
completed match — it proves the loop, not acceptance. A "completed match"
means a game played to a victory/defeat/elimination outcome (or an explicit
Time-victory turn limit) without human intervention in agent decisions.
Likewise, the three-provider match requires **at least three major
civilizations** controlled by three DIFFERENT provider CLIs — two civs plus a
spectator does not qualify.

These require the real provider plans and an interactive desktop session;
the plan explicitly forbids running them as a side effect of ordinary work.
Provider accounts to be used (record per match: CLI version, exact model,
outcome, interventions):

| Provider CLI | Auth | Version pin | Notes |
| --- | --- | --- | --- |
| `codex` | Noah's OpenAI/ChatGPT plan | 0.154.0 | W1/W3-proven; `danger-full-access` required on macOS (seatbelt blocks loopback MCP) |
| `claude` | Noah's Anthropic/Claude plan | 2.1.270 | per-invocation temp config, session-id verified in W1 |
| `opencode` | Noah's z.ai plan; also has free models | 1.18.31 | free models usable for the shakedown run |

Minimal-burn run order (each match: small Vanilla duel, 30–40 turns,
record outcome + interventions per §13.3):

Minimum QUALIFYING runs (full matches, not smoke tests):

1. **Shakedown (free, may stay short)**: opencode with a free model through
   the real desktop host — proves spawn, loop, checkpoints, and UI refresh
   at zero quota. Expected issues surface here, not on paid plans.
2. **Codex completed match** (ChatGPT plan): one small Vanilla match played
   to an outcome, agent decisions untouched.
3. **Claude completed match** (Anthropic plan): same bar.
4. **Three-provider match**: at least THREE major civs, one per CLI —
   codex + claude + opencode — played to an outcome.
5. Human+agent match: diplomacy, handoffs, pause/retry, save/reopen, both
   inspection settings; human actions through the real UI.
6. Later-game fixture (many cities/units) for observation size and UI
   rebinding; Finder launch with bundled Node, path-with-spaces and
   missing-drive error paths; GL-thread stall sampling while providers think.

See docs/manual-test-checklist.md for the step-by-step script to execute.

Performance expectations to sample: checkpoint duration, tool-response size,
and the GL thread never waiting on provider/network/disk work.

## Packaging: implemented vs assembled (artifact-inspected)

- **Runtime discovery (code)**: implemented — explicit `-Dunciv.node.path`,
  then `<appDir>/node/{bin/node,node.exe,node}`, then PATH; supervisor-bundle
  discovery mirrors that layout; failures name every searched location.
- **Assembled distributable**: implemented this increment —
  `./gradlew :desktop:agentPackage [-Dunciv.node.home=<node install>]`
  produces `~/.unciv-build/desktop/agentPackage/` containing **Unciv.jar** +
  **agent-runner/dist** (daemon.js + JS outputs) + **agent-runner/node_modules**
  + **node/** (bundled runtime when the property is given). Artifact
  inspection of the built package confirmed all four parts present, and the
  packaged node + daemon.js were launched from a path WITH SPACES emitting a
  valid protocol `ready` frame. The plain `:desktop:dist` fat jar contains
  NO runner or Node (verified: zero matches for daemon.js/node_modules in
  the jar listing) — use `agentPackage` for agent matches.
- **Native macOS .app bundle**: `./gradlew :desktop:macApp` now produces
  `~/.unciv-build/desktop/native/Unciv.app` with the local build relocation.
  It uses JDK `jpackage`, a native launcher, and a private Java runtime.
  The former shell launcher became generic Java at runtime, preventing
  Computer Use from resolving the running application by its bundle ID.
  - `Contents/MacOS/Unciv`: native launcher (`com.unciv.app`).
  - `Contents/runtime/`: bundled Java runtime; no installed Java needed to run.
  - `Contents/app/Unciv.jar` and `Contents/app/agent-runner/`: game and runner.
  - `-Dunciv.node.home=<dir>` bundles Node beside the jar in `Contents/app/node/`.
  - Packaged launches store data in `~/Library/Application Support/Unciv`;
    explicit `--data-dir=...` takes precedence. Existing legacy bundle data
    is left untouched and is not automatically migrated.
  - Earlier native builds are preserved beside the output with a timestamp.
  - This is a local app image, not a notarized distribution release.
  - Verified 2026-09-15: `:desktop:macApp` built successfully; Computer Use
    attached to the native bundle, captured screenshots, selected English
    through mouse input, and reached the normal main menu. This verifies
    launch/control only, not agent gameplay. The first prototype exposed
    `user.dir=/` and failed writing `/GameSettings.json`; the packaged data
    directory fix above resolved that startup failure.

## Deliverable reconciliation (CP-034): which .app to test

- **Test this bundle:** `~/.unciv-build/desktop/native/Unciv.app`, built
  reproducibly by `./gradlew :desktop:macApp
  -Dunciv.node.home=/Users/noahkenny/.nvm/versions/node/v24.13.1` from current
  sources (rebuilt 2026-09-15 with the run-control + routing code in this
  report). Artifact-inspected: bundle id `com.unciv.app`, executable native
  launcher, private jlink runtime, `Contents/app/Unciv.jar` (57 MB, contains
  DesktopAgentMatch/MatchCoordinator/SupervisorRunner classes), `Contents/app/
  agent-runner/dist/daemon.js`, `node_modules` (incl. @modelcontextprotocol/sdk),
  bundled `node/` answering `v24.13.1`. Discovery paths align: the jar's
  directory is `Contents/app`, so `agent-runner/dist` and `node/bin/node`
  resolve exactly where `findSupervisorDist`/`findNode` look.
- **Do NOT test this one:** `~/.unciv-build/desktop/Unciv.app` (Resources
  layout) is a stale manual assembly from 2026-09-15 00:54, built by no
  committed task, with an older jar and NO bundled Node. It is superseded by
  the jpackage bundle above.

## Known limitations (honest record)

- All Vanilla human gameplay mutations (unit orders incl. explore/automate/
  guard/escort/connect-road/sweep, unit swap, unit rename, gifts, tribute,
  protection, marriage, production, research, policies, diplomacy incl. trade
  propose/retract, demands, protector-alert and friendship-offer answers,
  conquest dispositions, diplomatic votes) route authoritatively through the
  MatchCoordinator during agent matches — see the audit; no gameplay control
  bypasses it.
- Non-gameplay / presentation controls (MultiplayerStatus preview popup,
  Spectator Mode view) are verified presentation-only (no match state mutation).
- Mod-only mechanics (such as UnitActionType.Transform) are out of Vanilla scope.
- Research agreements and G&K-specific air sweep mechanics are out of Vanilla scope.
- Real-provider matches (plan §13.3) are intentionally deferred to the manual phase
  using user auth plans.

## 2026-10-11 structured map implementation — current acceptance scope

The CP-034 counts above are historical evidence. They do not certify the new observation-v2 map memory, bounded catalogs or worker controls.

Current runner validation covers typecheck/build, ten-tool discovery and provider configuration, actor binding, typed production stdio errors, full escaped UTF-8 response bounds, bounded IDs, fake map pagination, prompt guidance and activity summaries. The runner suite passed 64 tests across 14 files after adding the China/scout workflow fixture; the commands and scope are recorded in the implementation report. Root records the final Kotlin and combined automated checks separately; no full-release-pass claim is made here.

The automated China/scout workflow uses deterministic service fixtures through stdio and authenticated MCP. It exercises the information available to a leader and revision-bound re-querying; it does not demonstrate a real model's strategic choices. Kotlin visibility/memory, checkpoint rollback, Earth wrapping, worker parity and migration tests supply production-engine evidence, subject to root's final results.

Still pending for this change: packaging/install into the running app, real-provider access (including OpenCode Go), a naturally safe live match boundary, and interactive China/worker/scout geography checks. No provider invocation, app restart or current-game control was performed as part of these automated checks. Existing full-match and human/UI acceptance gates remain applicable. See [Structured map and worker tools](agent-map-tools.md) for incomplete legacy history, historical enemy intelligence and null worker yield-delta limitations.

## Approved storage policy

The Earth checkpoint measurement (roughly 147 MB and 1.65 s; about 1.47 TB for 10,000 retained generations) triggered the storage-design gate. The user explicitly approved compact map memory plus compressed managed recovery retaining only the latest three verified checkpoints. Old archive files remain untouched, and manual saves are not part of that managed recovery window.

Approved game-data location: `/Volumes/Lexar/Unciv/userdata` for manual saves, settings, presets/maps, game logs and recovery. Profile migration copies data and preserves the original. The app must fail closed if Lexar is unavailable, including before every checkpoint, with no internal game-data fallback. Existing supported legacy representations remain readable; missing historical terrain and debug-contaminated exploration still cannot be reconstructed. Developer build-cache paths in historical commands are separate from game-data storage.

Automated checks verified compact serialization, compressed writes, the three-checkpoint window, interrupted/corrupt-write recovery, old-archive preservation and unavailable-volume handling. Native installation/startup passed; live-provider acceptance remains pending. Runner evidence remains 64 passing tests across 14 files; no new runner test/build was performed for this documentation-only storage update.

The feed-blur benchmark on local M4 Pro/2x Retina measured median refresh costs near 4.97 ms with blur and 0.46 ms plain; feed blur was removed. The numbers describe that UI refresh benchmark, not whole-game FPS. Root records final performance and release acceptance separately.

## 2026-10-11 final automated and measured record

This record supersedes the pending automated-storage status above. The final relevant Kotlin agent/map/worker/turn/HUD/storage suite passed **323 checks with zero failures**. Runner validation remains **64 tests across 14 files**, with typecheck and build passing and runner sources unchanged. Eight emitted map/tile output fixtures passed the Draft 2020-12 JSON Schema. These automated results do not certify a completed live-provider match or the installed native app.

Read-only headless compatibility checks restored the latest checksum-verified copied version-1 checkpoint, generation **252**, state revision 251. Migration and explicit version-2 roundtrip preserved all 15 external agent profiles, diplomatic mail and receipts, current-turn receipts, game identity/turn, units/actions/charges, cities, economy/resources and difficulty/bonus configuration. The normal `Autosave` passed its normal loader and serialization roundtrip; it has no paired agent metadata to check. There were **zero source-data or checkpoint writes**, with source bytes and modification times unchanged. Across controlled civilizations, **97 hidden explored legacy tiles remained incomplete**, with no invented historical details. This establishes read/roundtrip compatibility, not a simulated turn, UI restoration or every archived generation. See the [compatibility harness](../tests/src/com/unciv/dev/AgentMapSaveCompatibilityCheck.kt).

The compact/GZIP benchmark ran on **Lexar ExFAT**, using a synthetic 164×80 rectangle (13,120 tiles), 15 civilizations, no live cities and one unit per civilization. Each case used two warmups and five measured samples and retained exactly three managed generations.

| Initial map memory | Median mutation including fsynced checkpoint |
| --- | ---: |
| Empty | 98.6 ms |
| Partial (25%) | 153.9 ms |
| Full, uniform | 414.8 ms |
| Full, diverse historical facts | 613.8 ms |

The full uniform case used **0.549 MB per checkpoint / 1.647 MB for the three-checkpoint window**; the synthetic diverse case used **1.218 MB / 3.653 MB**. MB here is decimal logical file size; window totals include checksum sidecars, exclude AppleDouble companions and omit allocation rounding/filesystem overhead. The diverse-case isolated metadata-copy and serialization medians were about **53 ms** and **181 ms**. See [compact benchmark harness](../tests/src/com/unciv/dev/AgentMapPerformanceCheck.kt). Existing old archive files remain untouched and outside the managed window.

These samples measure the current pipeline, including candidate copy, sight capture, metadata serialization and checkpoint writing. The empty-memory baseline also includes the new visibility pipeline; it is not a pre-feature build. Synthetic historical variation, sequential case order, JVM/GC and filesystem cache effects limit generalization to a played Earth match. They do not measure whole-game FPS. A separate JFR run completed with 592 execution samples. Copy and palette serialization dominate the sampled map-memory work; sighting collection is retained on gameplay changes for conservative visibility/resource capture. Notes, mail and controller metadata commits already skip terrain rescans. Overlapping sample stacks are not wall-time percentages. No provider or game UI was exercised by these headless gates. Live-provider acceptance, including OpenCode Go authentication and interactive geography/worker checks, remains pending.


## Installed artifact and startup

Native packaging succeeded with `-Dunciv.data.dir=/Volumes/Lexar/Unciv/userdata`. The installed JAR SHA256 matches the build; all 226 packaged agent class files and seven read-tool/instruction/runner modules match their tested outputs. The runtime data-directory property confirms Lexar. Unciv opens its main menu using the copied profile without starting a provider. The previous app bundle and original internal profile are preserved. Native UI clicks return `noWindowsAvailable`. The user confirmed that the DeepSeek V4.1 Flash choice is available and survives Save/reopen; actual provider gameplay remains unverified.


## 2026-10-11 message acknowledgement and publication checks

Delivered mail now requires an explicit, recipient-bound `diplomacy.acknowledgeMessage` action before an external agent can end its turn. The saved receipt text appears as a standalone Activity entry and as a separate **Acknowledged** status in Messages. Receipt confirms that the message was received, not agreement with its contents. Older delivered-but-unacknowledged messages remain in the inbox. Durable mail action IDs remain reserved across receipt rotation and reload; recovered receipts are bound to the authenticated recipient before activity is emitted. Provider stream text cannot forge the saved acknowledgement entry.

The complete Kotlin suite completed **1,195 tests: 1,180 passed, 15 skipped, zero failures/errors**. Final `./gradlew check` succeeded across the configured modules. The runner passed **72 tests across 15 files**, plus typecheck and build. Targeted tests cover acknowledgement validation, end-turn gating, save/reload, duplicate/reused IDs, lost-response recovery, sender/recipient attribution, forged provider stream rejection, and Messages refresh from Queued to Delivered to Acknowledged. Protocol schemas parse and `git diff --check` is clean.

The user confirmed the live acceptance check after installation: a message sent to an agent was acknowledged in Activity, and Messages showed Acknowledged with the reply. This verifies the visible receipt path in the installed app; automated engine/MCP/UI tests cover the separate retry and persistence cases. Native packaging succeeded with the Lexar data-directory property. The installed JAR SHA256 matches the build and contains the acknowledgement command/result and Messages panel; seven acknowledgement/runner modules match the tested build. The previous app bundle is preserved. No provider was started by packaging.

## 2026-10-11 floating-panel and Live follow regressions

Opening Messages hides the civilization overview, and opening Overview hides Messages. Activity remains independent. Live now stays at the newest entry after transcript growth, bursts of calls and resizing; manual scrolling still holds the reader's position until Live is selected again. Tests exercise the actual Scene2D controls and world-screen panel transitions. Discovered Codex models and their supported reasoning levels also take precedence when choosing a new profile's default.

`./gradlew check --offline` succeeded with **1,198 Kotlin tests: 1,183 passed, 15 skipped, zero failures/errors**. The targeted panel/Live suite passed 36 tests, and the model catalog passed 10 tests after both new regressions were demonstrated failing against the prior implementation. The new panel and Live behavior still requires a visible check in the packaged app.
