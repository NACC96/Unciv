# Agent Run Timing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the elapsed time of each active agent run and retain its final duration in the floating activity feed.

**Architecture:** The desktop host exposes a transient monotonic timer around its admitted provider invocation. The Node runner records monotonic provider duration in the existing per-run Lexar journal; the feed displays a distinct run-duration row. Match metadata and recovery formats remain unchanged.

**Tech Stack:** Kotlin/libGDX host and widgets, TypeScript Node runner, deterministic clocks and scripted test providers.

**Spec:** This plan is also the bounded feature contract: live elapsed time appears beside the active agent, updates once per second even when Activity is hidden, continues during a requested pause, stops at invocation completion, and resets for the next run/retry. Final completed/failed durations remain visible per agent/run in Activity and in Lexar logs. Timers are monotonic and do not reset on UI hide/reopen. No run or model is launched, interrupted or changed by timing.

## Global Constraints

- Only admitted provider invocations are timed; human turns and pre-admission pause gaps have no active timer.
- Capture transient host run identity and elapsed milliseconds; do not save wall-clock-derived active timers in checkpoints.
- Record finite nonnegative durationMs plus a readable duration on each Node provider exit, including failed exits, in existing Lexar metadata/activity files.
- Retain image redaction, log privacy and agent-scoped feed filtering. Never expose a foreign agent timer to an ordinary human viewer.
- Root alone runs Gradle. Timers must never control provider lifecycle or touch the live installed app.

## Review Focus

- Pausing with an admitted provider keeps the timer running until natural completion.
- Retry/new-turn invocation resets rather than accumulating the previous elapsed time.
- Closing Activity cannot freeze timing; disposing its controls cancels only UI refresh tasks.
- Wall-clock jumps cannot produce negative or backwards elapsed time.
- Long agent identities and hours-long durations fit the existing compact/wide controls.

### Task 1: Persisted runner durations

**Files:** agent-runner/src/supervisor.ts, activity.ts as needed; focused runner tests. Produce `kind: "run"` final activity entries with `durationMs`, status completed/failed, readable title/summary and unchanged metadata/details, and `durationMs` in meta.json. No core/desktop/UI files owned.

- [ ] Add meaningful RED tests with an injected monotonic clock and a harmless scripted process for successful and failed completion; verify duration belongs to the actual run, current privacy/redaction remains, and a wall-clock shift does not alter duration.
- [ ] Record duration from spawn admission to child close using monotonic time; preserve process safety, existing stream handling and log structure.
- [ ] Run focused tests, full runner tests/typecheck/build; commit only owned files and report `/Volumes/Lexar/Unciv/.superpowers/diplomatic-messages/timing-runner-report.md`.

### Task 2: Live host timer and display

**Files:** Create core/src/com/unciv/logic/agents/AgentRunTiming.kt; extend AgentMatchSession.kt and DesktopAgentMatch.kt; modify AgentMatchControlTable.kt and AgentActivityFeed.kt; focused core/UI tests and existing GPU harness. Start only after nation-host/UI commits release these paths.

**Interfaces:** `data class AgentRunTiming(val runId: String, val civId: String, val elapsedMillis: Long)`; session `getActiveRunTiming(): AgentRunTiming? = null`. Timer uses an injected monotonic clock in its focused utility tests, with production System.nanoTime; fixed duration formatting supports mm:ss and h:mm:ss.

- [ ] Add failing clock/lifecycle and UI tests for pause/drain/reset/error, own-view privacy, hidden feed refresh and narrow bounds. Ask root for focused RED/GREEN; no worker Gradle.
- [ ] Start host timing immediately before proc.runTurn(control), clear in its finally; expose copied timing only to spectator or that nation. Show live elapsed beside agent status with a one-second UI refresh cancelled on disposal.
- [ ] Display kind run as a separate colored, named final-duration row, included in nation filters and outside collapsed tool groups. Update GPU fixture with a long-duration live timer and final run entry.
- [ ] Root runs GREEN/GPU checks; commit owned files and report `/Volumes/Lexar/Unciv/.superpowers/diplomatic-messages/timing-display-report.md`.

### Task 3: Combined review and delivery

- [ ] Review timing and nation/messaging branch together, run full repository checks and package the app, update ready PR4 and post GitHub findings.
- [ ] Install only after safe closure of the existing app; report live validation separately from deterministic tests.
