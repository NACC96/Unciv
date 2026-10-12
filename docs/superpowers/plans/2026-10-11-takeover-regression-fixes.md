# Takeover integration review fixes

**Goal:** Ensure the new Play as feature uses the authoritative game state and normal human decisions, rather than exposing existing snapshot-only interactions.

**Context:** Final branch review found missing citizen/founding routes, stale secondary screens and a spectator fog regression. A focused normal-play audit additionally found research queue, construction context-menu, city-state improvement gift and mandatory vote routing gaps. These legacy dependencies now fail the new takeover contract; fix them before delivery. No live match or installed app is touched.

## Task 1: Human mutation parity

Route city citizens and the reachable construction context-menu helpers, city founding after normal confirmation, complete staged research queues and city-state improvement gifts through existing typed coordinator transactions. Add minimal trusted typed commands/production operations where necessary, validating current ownership, legality and actor. Keep ordinary session-less behavior. New save fields are unnecessary. Cover durability, rejection and human-versus-agent input isolation with focused tests; root alone runs Gradle.

Owned paths: CityView, CivView as needed, UnitActionsFromUniques, TechPickerScreen, CityStateDiplomacyTable, AgentCommand, MatchCoordinator, new focused tests and audit corrections. No WorldScreen/CityScreen/DiplomacyScreen/PolicyPickerScreen/PromotionPickerScreen edits.

## Task 2: Fresh secondary presentation

Rebind or safely recreate an open CityScreen, DiplomacyScreen, PolicyPickerScreen and PromotionPickerScreen from the committed WorldScreen snapshot after successful human commands. Resolve entities by stable identity, preserve relevant selection where valid, avoid a queued old snapshot reopening a closed screen or exposing a foreign private view. Clear invalid/removed handles. Maintain the existing screen stack and privacy constraints. Cover real asynchronous publication/secondary UI refresh behavior, not only a mock callback.

Owned paths: WorldScreen and the four secondary screen classes plus a narrowly scoped reusable interface/helper if needed, new focused tests. No AgentCommand/MatchCoordinator or Task 1 paths.

## Root integration fixes

Authorize/show fog using the actual spectator viewer, retaining selected-nation tech/policy display. Human agent-session Next Turn may open ordinary mandatory choices (particularly diplomatic votes) before invoking the session end-turn fallback; spectator/external turns stay status-only and no legacy turn advance occurs. Keep unsupported legacy automation routes disabled. Add regressions for spectator selection and UN vote reachability.

## Validation and review

Capture meaningful failures before fixes where executable fixtures permit, then focused GREEN. Each worker owns disjoint paths and commits only its files. Complete one bounded final fix wave, provide immutable deltas to the final reviewer, perform one scoped rereview, run full Kotlin/Node checks and native packaging, and update ready PR4 with aggregate findings. Installation waits for user-confirmed safe closure.
