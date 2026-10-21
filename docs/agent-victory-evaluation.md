# Enabled-victory strategy and message composer: acceptance evidence

This record covers the [October 20 implementation plan](plans/2026-10-20-1939-fix-agent-victory-and-message-composer-plan.md), including the subsequent requirement to preserve proactive diplomacy. Implementation, deterministic checks, bounded model evaluations and installation are complete. Native UI interaction and the first normal post-update provider prompt remain unverified. The pooled quiet-turn timing improvement was 18.5%, below the 20% target; full-match win rate remains inconclusive.

## Reproducibility and scope

Local raw evidence is retained under `/Volumes/Lexar/Unciv/.superpowers/civ6-atlas/agent-victory-20261020-1939`. Each model run contains `evaluation-config.json`, `launch.json`, `manifest.json`, `host-events.jsonl`, immutable turn snapshots, and provider command/PID/session metadata under `providers/`. `corrected-reports/` contains reports generated after the review fixes; earlier comparison files are superseded.

| Identity | Value |
| --- | --- |
| Baseline production | `96bf0268b037d409af43ff4ef5ce9723587e11cd`; frozen with the plan-only commit `2aa66639678a1e2f825969424766a61486837ef6` |
| Final game/runner behavior | `c15f7aaa6a31a7d743e1900f7d25141fc8549cfd` |
| Report corrections | `a3716263c` |
| Final strategy policy | `victory-progress-v2`; private note schema remains `strategy.version:1` |
| Packaged game JAR SHA-256 | `24515aeaf9f6787e35dda61277855bdb67fb13d1d4e22311aa1f64e722fdad73` |
| Packaged strategy policy SHA-256 | `bad9ff86271915da029b1a03562c99e854e541d8f8dc3c31fff0f92da346d178` |

Baseline engine classes and runner output were frozen before production edits. Candidate runtimes were frozen separately, so builds could not change an active evaluation. Each fixture was prepared once and supplied to both arms with the same SHA-256. Tactical/quiet fixtures use Codex `gpt-6.1-sol`, low reasoning and FULL vision. The copied late match retains its saved civilization profiles, including Persia's `gpt-6-luna` at xhigh and the other four leaders' `gpt-6.1-sol` at low.

Both arms use fresh provider sessions per invocation and disable post-turn compaction **only in the isolated harness**. The user's model settings and session policy are unchanged. Fresh sessions make comparisons reproducible but limit conclusions about long resumed conversations; separate adapter tests verify current policy delivery to resumed sessions. Timing is descriptive on one host with concurrent independent runs, not a controlled provider-performance estimate. Provider duration and time to committed end-turn are reported separately. No provider was killed or sent control input at an evaluation boundary: the harness stops admitting turns and lets the active invocation finish.

The late-match source is the checksum-verified restore at game round 104, serial 524, with input SHA-256 `3cd8138e5efa373307069e32367587358e1d72840ee2a79f6f1a0cfa52719ab1`. All model evaluations use copies outside live userdata. Internal match IDs are preserved within independently isolated evidence directories; no copied result is restored into the live game.

## Deterministic validation

| Gate | Result |
| --- | --- |
| Runner typecheck/build and complete tests | Passed; **226/226** tests after report fixes, versus **190/190** before changes |
| Complete isolated Kotlin suite | **1,661 tests: 1,606 passed, 40 failed, 15 skipped**. The same 40 failure names and messages were reproduced before changes in 1,626 tests; no new or changed failures. The suite is not green. |
| Domination command path | Passed through the real bridge: observation, legal actions, war declaration, movement, repeated ranged attacks, melee capture, mandatory conquest disposition and engine-recorded Domination victory |
| Victory/visibility | Enabled and available routes, original versus replacement capitals, unknown/stale knowledge, MAP/FULL/FOG boundaries, false/null compaction and save roundtrip covered |
| Progress durability | Incoming combat losses/capital reversal, start-turn production completion, checkpoint recovery, failed publication, retries, forks, deep-copy isolation, old-save incomplete history and bounded output covered |
| Policy/session delivery | Legacy disabled-route notes, malformed notes, actual-round deadlines, complete quiet windows versus productive work, proactive diplomacy, required acknowledgements, fresh/resumed adapters and policy metadata covered |
| Code review | Completed receipt `20261020-213340-d2116c18`; reviewed `2aa666396..c15f7aaa6`. Three P2 reporting findings fixed with failing-then-passing regressions; no remaining actionable findings. The Claude peer attempt hit its session quota; the review completed with the skill's in-process fallback. |

The reporting fixes preserve remembered knowledge freshness while removing live sighting clock noise, mark incomplete/truncated legacy batch evidence as unknown, and refuse an order-reduction percentage when relevant order state is missing. An engine `COMPLETED` phase, provider completion or model claim cannot establish a win without saved `victoryData`.

The command-flow proof also exposed and fixed a wire serialization defect: conquest choices now arrive as actionable primitive strings. The saved-state format is unchanged. Scientific spaceship assembly remains unsupported; prepared engine victory fixtures do not establish a complete science, culture or diplomacy command route. See [action coverage](agent-action-coverage.md).

## Composer evidence

The real GPU render matrix passed **40 renders**: Atlas and legacy skins, collapsed and expanded states, 1440×900, 1920×1080, 960×720, 800×500 and 420×600, each at 1.0× and 1.5× text scale. All 20 paired comparisons report zero collapsed composer height and **129–260 additional logical pixels** for conversation history without changing the outer window. Additional Atlas draft/reopen renders and 12 dedicated real-GL screenshots cover draft retention, attachments, recipients and activity controls at 960 and 420 pixels.

Scene2D tests cover hidden-input focus and hit testing, recipient popup dismissal, caret/selection, attachment-only draft indication, close/reopen and minimize/restore, and asynchronous send/paste completion and failure while collapsed. Rendered screenshots were inspected, including narrow/high-text-scale layouts. These establish actual rendered component behavior; installed-window interaction is recorded separately below.

## Behavioral runs

All final-policy matched fixture groups and the five-round copied-match comparison completed. Older `candidate-stable-border`, `candidate-stranded-settler`, `candidate-favorable-assault-*` and `candidate-match-five-rounds` directories are policy-v1 pilots and are not substituted for final-policy comparisons. The `candidate-v2-*` directories use policy `victory-progress-v2`. `candidate-diplomatic-opportunity-*`, `candidate-outdated-science-notes-1` and `candidate-near-victory-1` also use the final policy; each run's metadata is authoritative.

### Quiet turns

All three stable-border repeats completed, with 36 committed own turns in each arm and identical fixture input. The fixture starts with stable borders and required incoming mail, without a pending trade or a newly introduced threat. Candidate leaders acknowledged that mail and retained defensive/research/production plans; the separate luxury fixture tests whether they initiate a concrete new opportunity.

| Stable-border measure | Baseline | Final policy |
| --- | ---: | ---: |
| New messages / completed turn | 18 / 36 = 0.50 | 0 / 36 = 0.00 |
| Applied acknowledgements | 18 | 3 (all required initial mail) |
| Pooled median time to committed end-turn | 21.010 s | 15.631 s (**25.6% lower**) |
| Individual repeat time-to-commit medians | 19.427–22.930 s | 14.417–17.297 s |
| Individual repeat provider-duration medians | 22.407–26.068 s | 18.232–20.715 s |

The stable-border fixture meets the 50% message-reduction target and exceeds 20% lower median time to commit. **The combined quiet-sample timing target was not met:** pooling this fixture with blocked-settler turns gives 54 samples per arm and medians of 21.010 → 17.122 seconds, an **18.5% reduction**, below the planned 20%. The gains must not be selected from only the faster fixture.

Compaction was disabled in both arms, so these measurements do not establish production compaction cost or full-match throughput. Candidates ended with no delivered unacknowledged mail. Each stable-border baseline had one message delivered at the final budget boundary, awaiting an own turn that was not admitted; that is not a missed acknowledgement on a completed turn.

### Blocked settlers

Across three repeats and 18 completed own turns per arm, unchanged checks specifically on stranded settler 3 fell **8 → 0 (100%)**, exceeding the 75% target. All unchanged reads fell 17 → 5; the remaining candidate reads concern other entities. Both arms issued zero unchanged repeated SLEEP/FORTIFY orders, so an order-reduction percentage is **not measurable in this fixture**.

The blocked-settler median time to commit increased **21.082 → 23.193 seconds (10.0% slower)** despite fewer repeated checks. Per-repeat medians varied from 17.042–29.910 seconds for baseline and 19.478–25.044 seconds for candidate. This result is retained alongside the stable-border improvement; no additional samples were selected to erase it. `corrected-reports/final-behavior-summary.json` and `pooled-quiet-timing.json` retain the individual run identities and pooled measurements.

### Proactive diplomacy

Across **three final-policy repeats**, Rome independently proposed a formal Silk-for-Gems exchange on its first own turn, sent a tailored message, and Greece accepted with an applied `diplomacy.respondTrade` receipt (`accepted:true`) and an acknowledgement reply. The fixture starts with spare luxuries and happiness pressure, without incoming mail, a pending trade or an instruction to send a proposal. Baseline also completed the exchange in all three repeats: this demonstrates preservation of useful diplomacy, not improved trade success.

Candidates sent three new messages across 24 completed turns versus baseline's 14; all candidates completed their required acknowledgement. A concise acknowledgement can contain the substantive reply. This is separate from the stable-border no-opportunity fixture and does not create a message quota. See `corrected-reports/diplomatic-opportunity-aggregate.json` for input identity and individual applied trade receipts.

### Objective reconciliation and actual completion

In the outdated-science-notes fixture, the final-policy candidate replaced the incompatible science plan with a Domination commitment on its first turn and recorded Archery as a dated preparation milestone. All five leaders in the final-policy late-match run likewise saved Domination commitments on their first updated turns, with dated defense, research or conventional-force milestones. `candidate-v2-match-five-rounds/first-updated-commitments.json` points to their committed snapshots.

In the near-victory fixture, both baseline and candidate achieved engine-recorded Domination in two own turns (victory round 1). This proves completion capability, not an improved win rate. Those single samples were slower under the candidate (21.6% for initial plan reconciliation and 24.6% for the near-victory median), so the quiet-turn gain must not be generalized to every turn type.

All **three favorable-assault candidates captured the designated original capital and achieved engine-recorded Domination in three own turns** (victory round 2), exceeding the two-of-three-within-eight-turns target. Baseline also won all three in three turns. This establishes that the final policy retains the ability to act on a favorable assault and complete the game; it does not show a higher win rate than baseline on this fixture.

The unfavorable-assault candidate completed eight turns without attacking stronger defenders. It selected a second Artillery as a readiness milestone, preserved defensive orders and recorded a round-10 production-acceleration review. Saved engine history shows four production added per turn, reaching **32/320** after the final committed turn. The model's last note reports the preceding opening value of 28/320 and a round-80 completion estimate. This satisfies the measurable-readiness alternative to repeated infeasible attacks, but preparation is still slow and no new Artillery or capital capture was completed within the budget. It is not proof of optimal strategic play.

### Longer match

The first five-round pilot used policy v1. Its healthy final snapshot at round 109 was continued under policy v2 through **round 124**, completing twenty round boundaries and **84 leader invocations** in total (22 + 62). Persia was eliminated, so not every civilization received twenty own turns. This is a **mixed-policy continuation**, not a fixed-policy twenty-round experiment. A separate final-policy five-round run starts from the original round-104 source for the controlled baseline comparison. Both bounded runs stopped admission and exited naturally; no provider remained active.

The controlled final-policy comparison completed **22 invocations per arm**, reaching round 109 from round 104. New messages fell **29 → 15 (48.3%)**, unchanged reads **48 → 23 (52.1%)**, and unchanged repeated SLEEP/FORTIFY orders **18 → 1 (94.4%)**, with complete host receipt/state coverage. Median time to committed end-turn fell **70.211 → 51.208 seconds (27.1%)**; provider-duration medians were 73.451 → 55.086 seconds. Required acknowledgements applied normally. Neither arm captured a major original capital or recorded victory in that five-round window. These are one matched pair, not three-repeat or full-match win-rate evidence.

The continuation's committed snapshot and conquest receipt record Russia taking Persia's original capital at **round 111**, moving Russia from one to two of five required major original capitals. Russia retained it through round 124; no other major original capital changed owner and there was **no engine-recorded victory**. The leader adapted after losing a siege unit and used a surviving capture unit, then recorded a new readiness plan.

After unhappiness blocked that plan, Russia changed its immediate milestone and applied a Courthouse purchase at round 115; later research and building work continued against dated readiness conditions. The four surviving leaders' final notes were reviewed at round 123 and retain milestones for rounds 125–129, with explicit production, movement, healing or treaty constraints. `milestone-review-evidence.json` and `final-commitments.json` in the continuation directory separate those model commitments from the applied receipt/snapshot evidence. This establishes capital progress and concrete reassessment in this sample, not a completed match or a win-rate improvement.

## Native package and live-data boundary

The final native package built successfully in an isolated release build root and was installed at `/Users/noahkenny/.unciv-build/desktop/native/Unciv.app` at **2026-10-21 05:02:58 UTC** (October 20 locally). `codesign --verify --deep --strict` passed both before and after replacement. The installed JAR, policy, daemon, supervisor and launch configuration match the staged release byte-for-byte. It retains the configured `/Volumes/Lexar/Unciv/userdata` path. No process was using the installed app at replacement, and installation launched no live provider. `installed-native-identity.json` records the artifact checks and previous-app location.

The pre-install backup at `/Users/noahkenny/.unciv-release-backups/agent-victory-20261020-1939` preserves the previous app plus 61 checksum-verified files: ordinary saves, settings, presets, maps, message images, current root checkpoint generations and the protected round-104 restore. The backup manifest records original paths, modification times and hashes. Other archives and logs remain in place.

An initial native smoke loaded the save and stayed paused. Native coordinate input returned `noWindowsAvailable`. During that check, the automation service relaunched the staged app without its command-line isolation argument and touched the live autosave's observation metadata plus window geometry; no provider ran and no game turn advanced. Both files were restored to their exact pre-check SHA-256 and modification times, and the touched copies were preserved. See `native-staging/restoration-receipt.json`.

A separate verification app used a fixed isolated data directory and distinct bundle identifier; its game and runner binaries match the release. The native-control service refused interaction with macOS's protected removable-volume permission dialog, so automation granted no permission. After that dialog cleared, the idle temporary app was closed normally through native control; no provider ran and no verification process remains. `native-staging/cleanup-receipt.json` records cleanup and a further live-file checksum check. Installed-app composer interaction and the first normal post-update provider prompt are still pending because native mouse automation was unavailable. Source tests, rendered fixtures and a packaged artifact do not close those gates.

## Reproduce the bounded evaluation

Use the explicit opt-in [evaluation launcher](../agent-runner/probes/strategy-evaluation.mjs) with a new absolute output directory, a prepared checkpoint plus checksum, frozen runtime classpath and runner artifacts, exact profile settings and own-turn/round/admission budgets. It is never invoked by normal tests. Poll `launch.json`, `host-events.jsonl` and provider metadata without touching stdin. A stop-admission file stops future turns only.

Generate a report with:

```sh
node agent-runner/scripts/strategy-report.mjs /absolute/candidate-run /absolute/baseline-run
```

Review input equality, receipt coverage, provider settings, completed-turn counts and strategic outcomes before interpreting a reduction. Pool individual completed-turn durations for aggregate medians; do not average medians or treat missing evidence as zero. A bounded match without engine victory data remains inconclusive for full-match win rate.
