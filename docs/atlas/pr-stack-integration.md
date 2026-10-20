# Agent PR stack in Atlas

PRs #7–#10 are integrated into `codex/civ6-atlas-ui`, preserving their history.
The Atlas UI and artwork are delivered through PR #11.

## Resulting behavior

- Nation switches apply between atomic actions. Taking over the active agent cancels its run, preserves committed moves and gives the human the rest of the turn. Handback preserves the match's running or paused state.
- A spectator retains presentation-only map viewing while playing a taken-over nation. The minimap and roads use explicit presentation visibility even in a match without a spectator civilization.
- Senders may delete their own queued mail with a compact Atlas Delete/Confirm control. Delivered mail cannot be deleted. Notification Hide uses Atlas ink and retains unseen news until the viewer's human turn; spectator and agent views stay quiet.
- Runner transport EOF cancels the orphaned provider; a shutdown command from a connected app still drains its admitted turn. JVM crash reporting writes to the data directory. The underlying native graphics crash is outside this change.
- Agents receive the new expansion/realpolitik instructions, rival intelligence and ranked settlement sites. Explore and settler automation act immediately.
- The Atlas nation settings panel includes the shared FOG/MAP/FULL vision picker. New matches start with FULL; existing saves retain FOG unless changed. Rules and map memory keep real sight.

## Integration corrections

- Provider admission rechecks pause/recovery and pending view refresh after both a pending nation change and guidance publication. Failed persistence or publication cannot start a paid provider.
- Snapshot attachment checks explicitly refer to `this@DesktopAgentMatch` inside coroutines, rather than comparing against the coroutine receiver.
- Animation view authority includes map/unit reveal flags. Vision changes revoke stale capture and playback authority; MAP/FOG do not project unseen or undetected units.
- Map cursors bind vision as well as actor, timeline, query and revision. A vision change requires a fresh query even without a gameplay revision change.
- Runner DTOs and its fake service include REVEALED tiles and the match vision field. The EOF protocol documentation describes immediate cancellation accurately.
- Direct founding and automated founding share the same Atlas settler-removal capture path.

## Validation boundary

Core, desktop and test sources compile. The focused integration checks cover 134 Kotlin tests and 19 runner tests, with no remaining failures; TypeScript typecheck and runner build pass. The affected three lifecycle classes were rerun after correcting the coroutine attachment check. Other focused classes were unchanged after their passing run.

Whole-game review, art acceptance, native GPU/performance checks and packaged-app validation remain pending. Art stays candidate and appearance activation remains release-gated. This integration does not rebuild, install, restart or change the running app, live match, provider configuration or userdata. Check logs and the per-class result inventory are on Lexar under `.superpowers/civ6-atlas/`.
