# Agent sessions and turn speed

Almost all of an agent turn is model time: in a 65-turn, two-leader Codex match, tool execution was 2.6% of 10,055 seconds of turns. A turn is a series of model round trips, each re-reading the whole context, so turns get faster by cutting round trips and context size.

## Session policy

The desktop reads `<userdata>/.agent-data/agent-settings.json` before every agent turn:

```json
{"sessionRotationTurns": 0, "sessionAbTest": false, "postTurnCompaction": true}
```

These are the defaults. Each leader resumes its own provider session every turn, and the session is compacted after each turn (arm `compact`). Without compaction a resumed session keeps every earlier prompt and tool result: in the match above the resumed leader's turns grew from about 140 s to 300–500 s as its session aged, while leaders on fresh sessions held steady. Compaction keeps the session's continuity and bounds its size.

- `postTurnCompaction: false` with `sessionRotationTurns: 0` resumes uncompacted sessions forever (arm `resume`).
- `sessionRotationTurns: N` starts a fresh session after N turns (arm `rotate-N`). A fresh session's continuity comes from private notes, the prompt's recent correspondence and the previous closing summary. A session that the next turn will rotate out is not compacted.
- `sessionAbTest: true` alternates agent nations (in turn order) between `rotate-N` and the never-rotating arm. `npm run ab-report -- <match log dir>` in `agent-runner` compares the arms.

Missing, malformed or out-of-range values fall back to the defaults. The file is read every turn, so changes apply without restarting the app.

## Strategy updates in ongoing sessions

Every turn prompt carries the current strategy policy (`victory-progress-v3`), including resumed OpenCode sessions whose earlier static prompt remains in history. Codex and Claude continue to receive the full game instructions through their existing developer/system instruction arguments. The supervisor supplies the current engine observation's `gameTurn`, victory routes and strategic progress to the prompt; old private notes remain beliefs that must be reconciled with these facts.

Policy updates take effect at the next normal turn invocation after the updated runner is loaded. They do not require session rotation, compaction changes or clearing saved notes and correspondence. Existing civilization, timeline and exact provider session bindings remain in force.

Each run's `meta.json` and lifecycle activity entries record `strategyPolicyVersion`, the match/timeline/civilization identity and the observed `gameTurn` separately from `turnSerial`. Older engines that omit `gameTurn` leave it absent in metadata; the runner never estimates it from turn serials. These fields persist when a turn is released and its provider finishes compacting in the background. The daemon's `run_started` event also reports the policy version. A completed invocation or compaction is not evidence of an engine victory.

Source changes and prompt tests alone do not establish that an already-running desktop daemon uses the update. Verify the packaged runner and the first normal post-update run's policy metadata before treating an active match as updated.

The [strategy/composer acceptance record](agent-victory-evaluation.md) separates adapter tests, isolated real-provider samples, packaged artifacts and installed-app verification. Its fresh-session/no-compaction evaluation settings are confined to the harness and do not change this production session policy.

Copied-match evaluations can opt in to `sessionPolicy: {mode: "forked-persistent", forkFromSessionIds: {"civ-id": "source-session-id"}}`. Each leader's first turn uses native Codex history fork; later turns resume only the newly returned session ID. The driver and host reject original, changed and cross-civilization resume IDs. Live source sessions are never resumed by an evaluation. The default fixture mode remains fresh sessions without compaction.

The persistent probe uses the production Supervisor, prompts, tools, map images and post-turn compaction, but waits for the entire provider process to exit naturally before admitting another turn. It disables early release and the existing tool-call cap for this probe, avoiding timed compaction cancellation. Budgets stop new admissions only. Invocation timing includes compaction and omits desktop overlap; time-to-commit is recorded separately. First-fork cumulative token usage is unknown when inherited from the source; later known deltas on the new session can be counted. Production daemon timeouts and session defaults are unchanged.

## Post-turn compaction

Codex supports compaction after a turn (`model_post_turn_compact_threshold_percent`). When the desktop sends `compactAfterTurn`, the supervisor asks Codex to compact once the turn's closing message is written. The supervisor does not wait for it:

1. The leader commits `game.end_turn` and writes its closing summary.
2. The supervisor releases the turn as soon as both have happened (or 30 s after the commit if no summary arrives). It revokes the run's game tools at once, including kept-alive connections, so the compacting process can never act for the next nation, and reports the completed turn.
3. The next leader plays while the first one's provider compacts its session in the background.
4. Before that session is resumed, the supervisor waits for its compaction to finish. A lone leader with nothing to hide the compaction behind waits up to four minutes, then resumes the session uncompacted.

The run log keeps the turn's own duration as its run-end entry; the compaction is logged afterwards as `Session compacted in mm:ss`. `meta.json` records `turnDurationMs` at release and `durationMs` when the process exits. Claude and OpenCode sessions resume without compaction.

## Fewer round trips, smaller context

- **Opening observation in the prompt.** The supervisor already observes before each turn, so the turn prompt carries that observation (without the events, decisions and mail stated elsewhere in the prompt). Leaders observe again only for state their own actions changed.
- **Compact results.** Model-facing entity reads omit redundant empty/default fields; ordinary numeric zero values remain, while zero tile yields and resource amounts are omitted. Original-capital false/null metadata stays explicit, and the victory, military and strategic-progress blocks remain intact. `game.observe` returns `diplomaticHistory` only with `includeHistory:true`, because the turn prompt already lists recent correspondence; in the match above that history was 17% of all tool output.
- **Map pictures.** `game.map_image` and the turn-start picture (Codex) replace most `game.map` paging; see [map pictures](agent-map-tools.md#map-pictures).
- **Long moves.** `unit.move` accepts any known destination and continues on later turns; in the match above 65% of moves stepped a unit one tile per call.
- **Fewer per-unit checks.** `game.observe` flags units with attack targets, promotions or upgrades available, and attack targets carry the engine's battle preview, so leaders query `game.legal_actions` only for units that need it.
- **End-turn timeout.** Ending a turn runs the other nations' turns and can take more than 30 s; `game.end_turn` now allows 180 s instead of failing and being retried.
