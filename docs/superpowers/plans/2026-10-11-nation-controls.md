# Mid-game Nation Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a spectator play any living major nation, explicitly hand it back to an agent, and change agent configuration mid-game.

**Architecture:** A trusted, viewer-authorized host API queues one copied change until the provider/loop finishes, then commits it before revoking old session authority. Saved management/view identities and retained profiles preserve control and personality across reload; a modeless world panel consumes copied DTOs and publishes the selected committed view on GL.

**Tech Stack:** Kotlin, libGDX Scene2D, existing desktop supervisor/coordinator/checkpoint pipeline and JUnit/GPU fixtures.

**Spec:** docs/superpowers/specs/2026-10-11-nation-controls-design.md

## Global Constraints

- Offline agent-enabled matches only; spectators manage any living major nation, ordinary humans only their own.
- Actual nation view uses ordinary fog, private mail/activity and viewer-equals-active-Human input restrictions; management identity is separate.
- No kill, cancellation or stdin input to running providers. One transient pending change; only durable application counts as success.
- Preserve moves, map memory, mail, notes, decisions and saved personalities. Epoch/session invalidation happens after durable success.
- Changing settings while Human keeps Human. Spectate never implicitly hands a nation back. Native-AI takeover converts PlayerType atomically.
- New unreleased match format stays 3; existing GameInfo protection floor stays 5. Old nullable fields initialize deterministically or fail closed on ambiguity.
- Root alone runs Gradle. No live-app operation, installation or restart during implementation.

## Review Focus

- Selected-but-not-admitted run gap: a queued control change prevents starting the provider.
- Storage failure: previous controller/profile/view remains authoritative; no premature epoch/session invalidation.
- Reload after spectator takeover: management authority remains spectator-wide while private nation view stays bounded.
- Multiple Human nations: only the actual active viewed Human can issue moves or Next Turn.
- Long/custom identities at 420x600: form and action buttons remain scrollable/in bounds; drafts survive periodic updates.

### Task 1: Durable host boundary and local authority

**Files:** Create core/src/com/unciv/logic/agents/NationControl.kt and focused coordinator/desktop tests under tests/src/com/unciv/logic/agents/. Modify AgentMatchSession.kt, AgentMatchState.kt, AgentOrdinarySave.kt, MatchCoordinator.kt and desktop/src/com/unciv/app/desktop/agents/DesktopAgentMatch.kt. Keep WorldScreen/UncivGame changes with Task 2.

**Interfaces:** Produce `enum NationControlChange { SAVE_AGENT_SETTINGS, PLAY_AS, HAND_TO_AGENT, SPECTATE }`; `data class NationSetting(val civId: String, val nationName: String, val kind: ControllerKind, val profile: AgentProfile?, val isActive: Boolean)`; session `getNationSettings(): List<NationSetting>`, `getPendingNationChange(): String?`, `canSpectate(): Boolean`, and `requestNationChange(civId: String?, change: NationControlChange, profile: AgentProfile?, onComplete: (String?) -> Unit)`. Interface defaults return empty/unavailable so unrelated hosts/mocks remain compatible. Produce a pure saved-view lookup helper in NationControl for Task 2, named `NationControl.savedViewerCivId(game: GameInfo, match: AgentMatchState): String?`. Consume Task 2 `WorldScreen.installAgentSnapshot(..., viewerCivId: String? = null)` as optional final parameter, passing the committed requested viewer from publication.

- [ ] Write failing tests: spectator takes current/inactive/native-AI major; handback retains personality; human cannot manage another nation; online/dead/barbarian/city-state reject; wrong viewer cannot mutate/end turn; reload retains identities/profile; injected failed checkpoint leaves assignment/epoch/session unchanged.
- [ ] Add deterministic latch/fake-runner tests for pending-after-admission and dispatch-gap rejection, with no kill/overlap, exactly one apply/callback and close/navigation cancellation. Ask root to run selectors and record actual RED evidence.
- [ ] Implement copied DTOs/nullable saved fields/deep copies/validation and host-only coordinator commit. Initialize old identities without granting online/ambiguous control. Add bounded profile validation via catalog.
- [ ] Implement host queue/PAUSING/boundary drain and actual-viewer gating. Publish only committed viewer. Keep controller changes and profile retention atomic; leave scheduling paused after application, clear only the affected provider resume session after success.
- [ ] Ask root for focused tests, self-review and commit only owned files; report evidence and exact commit to /Volumes/Lexar/Unciv/.superpowers/diplomatic-messages/nation-host-report.md.

### Task 2: Floating settings and actual play view

**Files:** Create core/src/com/unciv/ui/screens/worldscreen/status/AgentNationSettingsPanel.kt and tests/src/com/unciv/ui/components/AgentNationSettingsPanelTest.kt. Modify WorldScreen.kt, UncivGame.kt, status/AgentMatchControlTable.kt, AgentMessagesPanel.kt only as needed for mutual hiding, and tests/dev/AgentNationSettingsRenderCheck.kt. May create one reusable profile-form component when it avoids duplicating setup selector logic; preserve existing setup behavior and tests if refactored.

**Interfaces:** Consume Task 1 DTO/session API and `NationControl.savedViewerCivId`. Extend `WorldScreen.installAgentSnapshot(snapshot, selection, publicationGeneration = 0, viewerCivId: String? = null)` with the optional final viewer. Panel `toggle()`, `hide()`, `dispose()` match other modeless panels.

- [ ] Write failing UI tests for nation selection/profile drafts/custom model and reasoning preservation, pending/errors, explicit handback, Spectate while playing, compact bounds and keyboard typing isolation. Write snapshot/view tests for normal fog, cleared stale selection/private panels and load choosing saved viewer. Ask root to run selectors and record RED.
- [ ] Implement styled scrollable panel, catalog-based provider/model/effort fields, copied drafts, explicit actions and periodic status updates that do not reset unsaved form. Pending disables duplicate requests and remains visible; errors retain draft.
- [ ] Add Settings button with narrow wrapping; coordinate right-side panel visibility. Publish actual committed viewer on GL, reset fog before non-spectator view, clear selections/privacy-sensitive panels, restore saved viewer before construction. Do not reconstruct/close the live host.
- [ ] Ask root for focused GREEN/GPU checks at 960x720 and 420x600 with deterministic session mocks. Commit only owned files and report /Volumes/Lexar/Unciv/.superpowers/diplomatic-messages/nation-ui-report.md.

### Task 3: Review and combined deliverable

- [ ] Independently review each task and the complete PR4 branch; fix confirmed findings with covering regression checks and scoped re-review.
- [ ] Run runner tests/typecheck/build and repository check plus macApp; count tests accurately and verify code signature. GPU fixtures may not launch real providers.
- [ ] Update ready-for-review PR4 title/body to the complete implementation, push tested commits and post findings/validation as a GitHub comment. No merge in this task.
- [ ] Preserve the user's running match. Once the full candidate is reviewable, ask for save/quit only if installation needs it; install/reopen safely and report remaining live validation honestly.
