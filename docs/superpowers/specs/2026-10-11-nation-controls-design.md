# Mid-game nation controls

The spectator may play any living major nation for as long as desired, explicitly hand it to an external agent, and edit an agent's provider, model and reasoning during the match. A modeless Nation settings panel must keep the route back to spectating available while playing. Ordinary local human players manage only their own nation. This first implementation supports offline agent-enabled matches; online matches fail closed.

## Control and save contract

Local management identity is distinct from the displayed civilization. Save a nullable `localControlCivId` and `localViewingCivId` on AgentMatchState, plus deep-copied `retainedAgentProfiles` keyed by nation ID. A spectator principal retains any-nation management while the actual nation view uses ordinary fog, private mail/activity and human-input restrictions. Initialize absent old-save identities deterministically for offline saves: spectator when present; otherwise one eligible human controller, otherwise the attached eligible viewer. Ambiguous saves remain unmanaged. Validate saved identities and retained profiles before enabling controls. The new message format 3 is unreleased on this branch; retain that version and the existing GameInfo protection floor 5.

Only living major nations are editable. Taking over a native game-AI nation atomically converts its PlayerType to Human with the controller assignment so native automation cannot run alongside player/agent control. Taking over preserves the last committed agent profile; changing saved agent settings while a nation is Human keeps it Human. Switching to another human nation does not implicitly hand the prior nation back. Spectate changes only the displayed view; a Human assignment remains until explicitly handed back, and its turn may wait for the player.

Controller/profile/view changes commit through a trusted host-only coordinator API, never a provider MCP action. Preserve all committed moves, map memory, mail, notes and decisions. A controller/profile commit advances revision at a safe boundary. Local identity/view initialization may use metadata-only commits. Invalidate provider sessions and advance authority only after a successful durable controller/profile change. Failed checkpoints leave the previous configuration authoritative and show an error.

## Running agents

Accept at most one pending local change. Copy its inputs and show a pending label; it is not saved until durably applied. If a provider turn is admitted, pause scheduling and let it finish normally. Apply only after loop ownership is released, then leave the match paused. Requests during the selected-but-not-admitted dispatch gap prevent admission. Pending requests cancelled by leaving/closing the match report an error; never kill or interrupt a provider for configuration changes. Human input and Next Turn require the actual viewer to equal the active Human controller. Normal Resume/Retry remain explicit.

## Presentation

Add a Nation settings button to the compact match controls without clipping at narrow widths. The floating panel includes nation selection with controller/active indicators, provider/model/reasoning, display name/personality, Save agent settings, Play as, Hand to agent and Spectate. Preserve custom model IDs and supported effort choices using the existing catalog; copied drafts and cancel/hide never mutate committed profiles. No starting bonuses or map edits during a match.

Pending and failure states are visible. Applying takeover rebinds the existing WorldScreen to the committed selected viewer on GL, clears stale selections/private panels, resets fog to normal before leaving spectator mode, and updates Next Turn. Ordinary load chooses the saved local viewer before WorldScreen construction; restore starts paused. Messages/Overview/Nation settings share the right-hand panel area; Activity remains independent.

## Acceptance

Deterministic tests must cover spectator-origin authority after takeover, own-nation restrictions, online and dead/non-major rejection, native-AI conversion, no overlapping/admitted provider interruption, boundary application, persistence, failed commits, retained personalities, fresh provider sessions and wrong-viewer human-action rejection. UI tests cover saved/custom models and effort levels, pending/errors/cancel isolation, hotkey isolation, view/fog/privacy rebinding and narrow layout. Run isolated real GPU renders at 960x720 and 420x600; do not operate or restart the user's running game until the complete update is ready and safe closure is confirmed.
