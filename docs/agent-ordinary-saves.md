# Ordinary agent saves and restore

Manual saves, clipboard/custom-location exports, and default/numbered autosaves contain the game plus one `GameInfo.agentMatchStateJson` string. That string is the matching committed `AgentMatchState`, including controllers, profile/model/reasoning/personality, notes, pending decisions, guidance, receipts, phase, serial and revision. It contains no nested world, provider session bindings, credentials, process handles or machine-local storage paths.

Publication captures immutable game and match JSON under the desktop host lock. The match JSON is reused from the successful paired checkpoint commit; metadata-only commits publish a new pair even when gameplay revision does not advance. The world screen and the active application's autosave pointer both adopt that snapshot. Screen/global-object ownership and a monotonic publication-generation fence reject replaced screens and older queued frames, including changes at the same gameplay revision. Cloning preserves the immutable metadata string, so an already captured older save never reads newer session/checkpoint state.

The coordinator's canonical/candidate `GameInfo` does not carry this envelope. Recovery still uses the existing paired `AgentBundle` / `FileCheckpointStore` format: one plain inner game JSON plus one match JSON, without recursion. Ordinary save export does not decode another copy of the match state.

## Reopening

Both ordinary file and string loaders validate embedded metadata and suppress unsolicited production choices before their first transient-restoration pass. The GUI uses the restore factory rather than new-game setup, preserving saved IDs, revisions, work-in-progress, receipts and controllers. It publishes the world only after successful host attachment; a failed attachment disposes the world and clears the Resume target. The factory only constructs a host: reopening performs no begin/end turn, automatic play, Node startup or provider invocation. Provider session bindings begin empty.

Ordinary active phases reopen **PAUSED**. A completed match remains **FINISHED**. A persisted coordinator failure-pause opens as **NEEDS_ATTENTION**, keeps its reason, and requires explicit retry; clearing that failure must first commit a replacement checkpoint successfully. Human/hotseat routing follows saved controller assignments.

Each reopened ordinary save receives a fresh recovery directory under `.agent-data/checkpoints/<matchId>/restores/<unique-lineage>/` within the same configured project data directory. Saved match/timeline IDs remain unchanged. Resuming an older save cannot overwrite the original newer recovery lineage; load never chooses a newest checkpoint or combines independent metadata with the selected game.

## Compatibility

- Agent-bearing ordinary saves use GameInfo compatibility **5**, so older number-4 readers reject them before silently ignoring agent metadata.
- Plain ordinary saves retain compatibility **4**.
- This foundation branch accepts match format **1** and rejects unsupported versions. Format fields are written explicitly; historical version-1 metadata that omitted its default version remains readable.
- Blank, malformed, missing protected metadata, inconsistent controller identities and invalid receipt timelines fail closed. Platforms without a restore host fail before replacing screens.
- Old ordinary saves with no agent metadata remain ordinary games. Lost agent profiles/notes/receipts cannot be reconstructed from player types; there is no legacy importer or automatic recovery guessing.

The experience branch's format-2 map-memory migration and normal agent visibility must be retained when integrating this fix there. This foundation change does not add map memory or format-2 support.

## Verification

`AgentOrdinarySaveTest` exercises frozen save A against newer checkpoint B; file/string/compressed/clone/default and numbered autosaves; saved profiles, notes, receipts, canonical worker progress, health, movement and gold; corruption/version checks; paused/terminal/failure restore; factory ownership; no recursive checkpoints; metadata-only guidance and controller changes; fresh recovery lineages and empty provider bindings. Headless tests call the actual snapshot-install method with mocked rendering widgets to check same-ID replacement and newer-then-older frame ordering, and exercise the GUI's restore-attachment seam on success/failure. Failed guidance persistence preserves queued guidance, pauses with attention, and never invokes the runner. Explicit Resume uses an injected scripted runner, with no external provider process.

Nearby coordinator, publication, orchestration, session-ownership, turn-lifecycle, domestic-order and game-serialization tests cover the affected integration contracts. Native GUI save/reopen acceptance and large format-2 Earth/map-memory profiling remain separate checks after experience-branch integration; the installed app and live user data are untouched by these tests.
