# Ordinary agent saves and restore

Manual saves, clipboard/custom-location exports, and default/numbered autosaves contain the game plus one `GameInfo.agentMatchStateJson` string. That string is the matching committed `AgentMatchState`, including controllers, profile/model/reasoning/personality, notes, pending decisions, guidance, diplomatic mail and acknowledgement receipts, compact map knowledge, phase, serial and revision. It contains no nested world, provider session bindings, credentials, process handles or machine-local storage paths.

Publication captures immutable game and match JSON under the desktop host lock. The match JSON is reused from the successful paired checkpoint commit; metadata-only commits publish a new pair even when gameplay revision does not advance. The world screen and the active application's autosave pointer both adopt that snapshot. Screen/global-object ownership and a monotonic publication-generation fence reject replaced screens and older queued frames, including changes at the same gameplay revision. Cloning preserves the immutable metadata string, so an already captured older save never reads newer session/checkpoint state.

The coordinator's canonical/candidate `GameInfo` does not carry this envelope. Recovery still uses the existing paired `AgentBundle` / `FileCheckpointStore` format: one plain inner game JSON plus one match JSON, without recursion. Ordinary save export does not decode another copy of the match state.

## Reopening

Both ordinary file and string loaders validate embedded metadata, suppress unsolicited production choices, and enable normal civilization visibility before their first transient-restoration pass. Constructor-visible sightings are captured before caching the initial committed metadata; both create and restore factories attach that post-construction cache before exposing the host. The GUI uses the restore factory rather than new-game setup, preserving saved IDs, revisions, work-in-progress, receipts and controllers. It publishes the world only after successful host attachment; a failed attachment disposes the world and clears the Resume target. The factory only constructs a host: reopening performs no begin/end turn, automatic play, Node startup or provider invocation. Provider session bindings begin empty.

Ordinary active phases reopen **PAUSED**. A completed match remains **FINISHED**. A persisted coordinator failure-pause opens as **NEEDS_ATTENTION**, keeps its reason, and requires explicit retry; clearing that failure must first commit a replacement checkpoint successfully. Human/hotseat routing follows saved controller assignments.

Each reopened ordinary save receives a fresh recovery directory under `.agent-data/checkpoints/<matchId>/restores/<unique-lineage>/` within the same configured project data directory. Saved match/timeline IDs remain unchanged. Resuming an older save cannot overwrite the original newer recovery lineage; load never chooses a newest checkpoint or combines independent metadata with the selected game.

## Compatibility

- Agent-bearing ordinary saves use GameInfo compatibility **5**, so older number-4 readers reject them before silently ignoring agent metadata.
- Plain ordinary saves retain compatibility **4**.
- Match format **2** preserves per-civilization map knowledge with compact encoding version **1**. Existing plain format-2 map snapshots remain readable and rewrite compactly on publication.
- Format fields are written explicitly. Historical version-1 metadata that omitted its default version is recognized from raw JSON and migrated to format 2 with empty historical map knowledge. Current permitted sightings are captured normally; unseen terrain history is never invented.
- Unknown match or compact-map encoding versions are rejected. Ordinary saves and recovery bundles share the same explicit migration path.
- Blank, malformed, missing protected metadata, inconsistent controller identities and invalid receipt timelines fail closed. Platforms without a restore host fail before replacing screens.
- Old ordinary saves with no agent metadata remain ordinary games. Lost agent profiles/notes/receipts cannot be reconstructed from player types; there is no legacy importer or automatic recovery guessing.

Map knowledge retains independent full-sighting and resource timestamps and provenance. Viewer/provider queries continue to enforce ordinary visibility and private mail/receipt access after restore. Recovery retains the existing latest-three verified compressed checkpoint policy; it never replaces the ordinary save selected for loading.

## Verification

`AgentOrdinarySaveTest` exercises genuine version-1 migration, compact and legacy-v2 memories with hidden facts/resource provenance, initial canonical capture, early normal visibility despite debug reveal, frozen map/mail/acknowledgement save A against newer checkpoint B; file/string/compressed/clone/default and numbered autosaves; saved profiles, notes, receipts, canonical worker progress, health, movement and gold; corruption/version checks; paused/terminal/failure restore; factory ownership; no recursive checkpoints; metadata-only guidance and controller changes; fresh recovery lineages and empty provider bindings. Headless tests call the actual snapshot-install method with mocked rendering widgets to check same-ID replacement and newer-then-older frame ordering, and exercise the GUI's restore-attachment seam on success/failure. Failed guidance persistence preserves queued guidance, pauses with attention, and never invokes the runner. Explicit Resume uses an injected scripted runner, with no external provider process.

Nearby coordinator, publication, orchestration, session-ownership, turn-lifecycle, domestic-order and game-serialization tests cover the affected integration contracts. Native GUI save/reopen acceptance remains separate; the installed app and live user data are untouched by these tests.


## Combined format-2 profiling

A synthetic 164×80 Vanilla rectangle with 15 civilizations, one Warrior per civilization and 196,800 diverse historical tile records produced **4,396,928 bytes of raw ordinary JSON** and **1,772,832 bytes of application gzip/base64 text**. The initial save and three repeated load/save cycles were byte-identical. Complete map-memory digests and acknowledged mail survived each cycle; canonical inner worlds contained no embedded match metadata. Normal sight stayed limited to 11–37 tiles per civilization with debug reveal enabled during load.

Across the three repeated cycles, median raw export was 17.1 ms, compressed export 200.9 ms, raw load with first transients 198.0 ms, compressed load 216.0 ms, coordinator restoration and sight capture 371.8 ms, publication payload capture 6.5 ms and presentation-world restoration 17.3 ms. These are sequential headless measurements with no independent warmup or pre-feature baseline. They exclude fsync, native GUI, played-map performance and peak-heap measurement.

The separate existing checkpoint benchmark ran on Lexar ExFAT with one warmup and three measured samples per case. Diverse full memory used a median **1,217,633-byte checkpoint** and **3,653,084 bytes for three retained checkpoints plus checksums**; median mutation including fsynced persistence was 591.9 ms. The initially empty-memory case took 111.4 ms. The baseline still runs the current visibility pipeline; it is not a pre-feature comparison. No user save or archive was read or changed by either benchmark.

Persistence failures publish their pause metadata into the ordinary-save pair even when gameplay revision is unchanged. Retry and controller recovery wait for an admitted provider worker to finish naturally. Completed outcomes and leaving/reopening the match cannot clear a persisted failure or automatically schedule another agent. Scripted lifecycle regressions cover these boundaries without external provider processes.
