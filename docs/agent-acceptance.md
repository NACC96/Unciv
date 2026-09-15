# Agent Match — Release Acceptance Evidence (plan §13)

Status as of CP-030 on `agent-match-foundation`. Commands run with the
standing environment (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`,
`GRADLE_USER_HOME=/Users/noahkenny/.gradle-unciv`, `COPYFILE_DISABLE=1`,
build dirs relocated to `~/.unciv-build/` by the local-only init script —
never committed).

## 13.1 Automated checks — all green

| Check | Result |
| --- | --- |
| `./gradlew :tests:test` | **932 tests, 0 failures, 0 errors** (95 suites; 82 agent tests in 18 agent suites), per-file XML presence verified after `cleanTest` (CP-024 rule) |
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
| Authority/lifecycle | AgentRunControlsTest (pause/step/takeover/stop state machine); daemon shutdown/EOF drain; SetControllerCommand rev discipline |
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

## Known limitations (honest record)

- Human civs in an agent match still use legacy engine handlers on their own
  turns (serialized behind the stepper). Full human-through-coordinator
  routing is the documented follow-up in the mutation audit.
- Deferred Vanilla actions: paradrop, rebase/carrier ops, unit gifts to
  major civs, join-war trade offers, denounce, multi-turn goto, city
  sell/rename. Each is listed per-row in the coverage table.
- Air sweep is G&K-only (out of Vanilla scope). Research agreements do not
  exist in the Vanilla ruleset (G&K feature).
- UN election/vote mechanics run through the legacy UI path for humans; the
  agent-side diplomatic victory is exercised at flag level (CP-029 note).
- Real-provider matches (13.3) are deliberately not run in CI or by agents.
