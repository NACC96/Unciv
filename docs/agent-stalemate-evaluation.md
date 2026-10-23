# Policy v3: copied stalled-match comparison

This follow-up tests the smaller repair for a three-player Domination match that had reached round 139 without a major war or an original-capital transfer. It supplements the earlier [victory and composer evaluation](agent-victory-evaluation.md); those earlier fixtures and results remain separate evidence.

## Change and expected behavior

Leaders already understood that legal attack previews were unavailable during peace. Their private plans nevertheless kept requiring favorable legal previews before launching a campaign. The repair removes that impossible prerequisite, asks for readiness achievable under the leader's own agreements, and requires a concrete response to recurring blockers. Respecting treaties, nuclear deterrence and third-party incentives remains valid; the policy imposes no attack quota or new end-turn gate.

The host now retains factual objective progress separately from private notes. Original-capital ownership and fully known completed victory milestones have bounded change clocks. Old saves begin tracking at the loaded round and report earlier change times as unknown. Reads, notes and preparation do not reset the clocks; actual gains or losses do. Unknown mod routes remain untracked. These facts are saved in the existing checkpoint transaction and ordinary saves.

Full prewar battle forecasting and host-owned campaign records were deferred. Every fresh or resumed invocation receives `victory-progress-v3`. Provider models, reasoning settings and production compaction defaults are unchanged.

## Matched comparison

One control/patched pair was authorized, with a maximum of 20 game rounds, 20 own turns per civilization and 60 provider invocations per arm. Both start from the same round-139 checkpoint of `match-6149aa4a-8b06-4147-92a5-c35d72ce60d7`:

```text
input SHA-256: 8c85fdb31a4999cb9953c8ee0e1268eedf2af4ccfb63f11efde56e595bdebc7e
control: victory-progress-v2, production source at 5292dc004
patched: victory-progress-v3, production changes through ed2850c1e
```

Later commits remove comments, add review regression tests and update documentation. The frozen patched runtime and staged native product have matching relevant engine classes and runner modules.

Russia and America use `gpt-6-luna` with `low` reasoning; China uses `gpt-6-luna` with `xhigh`. Both arms use Codex CLI `0.163.0`. Each civilization starts with a native fork of its existing source session, then resumes only its new session. All six new IDs are distinct. The original session histories and checkpoint are checked independently for changes.

The opt-in evaluation driver imports each arm's production Supervisor, MCP adapter and prompt. It waits for natural provider and compaction exit after every turn. Budgets stop admission of another turn; they do not cancel a running provider. This serial wait removes normal desktop compaction overlap. The arms ran at different times, so timing differences are descriptive and include service variability. First-fork token deltas are unknown because native cumulative usage includes inherited history; this is not an inference-cost comparison.

The assessment rubric was frozen before model admission. Applied staging, access changes, feasible preparation and specific evidence-backed infeasibility count as operational evidence. Reworded deadlines alone do not. Engine snapshots establish capital ownership and victory; attack counts and model narratives do not establish a sound campaign.

## Results

Both arms completed all 60 turns and advanced from round 139 to 159. Each stopped admission at its budget and exited naturally, including its supervisor. Host command coverage is complete, with no unknown receipts, malformed records or incomplete calls. All six session IDs stayed distinct and stable; the original three histories and source checkpoint remained byte-identical.

| Measure | Control v2 | Patched v3 |
| --- | ---: | ---: |
| Engine victories | 0 | 0 |
| Major original-capital ownership changes | 0 | 0 |
| Applied commands | 108 | 147 |
| Applied movement commands | 10 | 49 |
| Existing major units ending on a different tile | 3 | 22 |
| Attacks against major civilizations | 0 | 0 |
| New diplomatic messages | 29 | 24 |
| Applied acknowledgements | 30 | 25 |
| Unchanged reads / eligible reads | 135 / 489 | 218 / 786 |
| Unchanged orders | 0 | 0 |
| Median time to committed end-turn | 32.908 s | 60.363 s |
| Median invocation duration, including compaction | 43.081 s | 73.013 s |
| Total evaluation duration | 73.45 min | 138.30 min |

The control's sole attack targeted Barbarians, not another major civilization. Unchanged-order reduction is undefined because both counts are zero. The patched median time to commit was **83.4% higher**, and unchanged reads rose **61.5%**. These results provide no performance improvement claim. The arms ran at different times, with no overlap, and the evaluation waits serially for compaction.

The patched leaders removed the peacetime-preview prerequisite from their first revised plans. Russia repositioned nine existing units. China moved Artillery 121 onto American land, later confirmed its withdrawal to Chinese land, landed Rocket Artillery 49 on Monaco, and assembled a nine-siege line on Chinese land. Those are committed board changes. The five capture units cited in China's Monaco staging plan were already there at the start, so they are not newly completed staging.

China's proposed eight-siege deployment on allied land did not complete. It identified a mountain and occupied American corridor tiles, revised its immediate milestone to a reachable Chinese-side line, and deferred a joint operation review to round 165. America likewise retained a round-165 siege/route review; Russia retained a round-161 production milestone. Route access, a survivable firing approach and fallback remained unresolved. More movement and more specific blockers did **not** produce a capital gain or break the stalemate within this window.

At the final round-159 snapshot, every leader still owned one of three required original capitals. Each factual counter remained unchanged since tracking began at round 139, with `lastChangedGameTurn: null`. Changed notes and preparation did not reset that interval. Nuclear retaliation and three-player incentives remain plausible reasons for caution. This pair tests the policy and factual-history changes together and cannot isolate their individual effects or establish better strategic play.

## Verification and review

The runner's 236 tests, typecheck and build pass. Focused engine tests cover actual capital gains and losses, failed checkpoint writes, legacy saves, note/read purity, peaceful Time progress and unknown mod routes. The full Kotlin suite reports 1,665 tests, 40 failures and 15 skips; all 40 failure identities and messages match the reproduced baseline. There are no new, resolved or changed failures. Detekt's CI error rules pass for the nine changed Kotlin files; its warning run reports 30 advisory findings.

Eight local review lenses, including adversarial review, completed on `5292dc004..bee718d76`. The single P2 finding was missing changed-session/cross-civilization emitted-ID regression coverage; existing guards were retained and the tests were added in `6b41a418c`. Additional peaceful/unknown-route characterizations were added in `c6e98e486`. No findings remain unresolved. The external peer did not run: its launcher rejected a timeout option before source egress. This is not external model approval of the implementation.

## Native activation

After both runs finished, the reviewed native bundle was installed at `/Users/noahkenny/.unciv-build/desktop/native/Unciv.app` on October 23, 2026 at 17:11:59 UTC. Strict signature verification passed before and after installation. The installed JAR, policy, daemon, supervisor and launch configuration match the staged release hashes. Its production source is `bee718d76`; subsequent commits change tests and documentation only.

The current ordinary autosave already contained the original round-139 game when activation began. The reviewed engine's official loader accepted it and round-tripped it without changes. That autosave was retained, with an additional named recovery save, rather than replaced with an older export. The evaluated round-159 game was never copied into live userdata. The previous app and both earlier/current save backups remain available.

Native CUA reopened the installed app and resumed the save. A full native capture visibly showed **T139**, **Paused · Russia**, **GPT-6 Luna · Low**, and the **Resume** button. No provider ran, no game turn advanced, and the autosave, private notes, profiles and old provider histories remained unchanged. The app was left open and paused. As chosen by the user, the next Resume creates fresh provider bindings; the first desktop provider invocation remains unobserved. Copied-match provider prompts and packaged-file identity are separate evidence. Installed composer interaction also remains outside this activation check.

## Evidence and reproduction

The local evidence root is `/Volumes/Lexar/Unciv/.superpowers/match-6149-policy-v3-20261022`. It contains the frozen rubric, runtime manifests and snapshots, launch receipts, host command logs, provider streams, engine snapshots, source-history hashes, review receipt and validation reports. Local absolute paths are evidence locations, not hosted attachments.

`candidate-report.json` includes the paired quantitative comparison. `baseline-operational-outcome.json` and `candidate-operational-outcome.json` retain final commitments and before/after unit facts. `session-fork-resume-audit.json`, `source-integrity-final.json` and `installed-native-identity.json` record session isolation, source integrity and native activation.

Use the explicit [evaluation launcher](../agent-runner/probes/strategy-evaluation.mjs) and [persistent-session driver](../agent-runner/probes/strategy-evaluation-driver.mjs) with new output directories, frozen runtimes, the same source checkpoint and exact profiles. Normal tests never invoke these paid model runs. Generate the paired report with:

```sh
node agent-runner/scripts/strategy-report.mjs /absolute/patched-run /absolute/control-run
```

One matched pair describes this continuation. It cannot establish improved full-match win rate or optimal strategic play.
