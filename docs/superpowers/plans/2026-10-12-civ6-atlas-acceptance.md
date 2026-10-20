# Atlas integration and acceptance implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the complete redesign works and performs in real workflows, then deliver a reviewed source change and verified native app safely.

**Architecture:** Reuse the exact F1 fixtures/trace against baseline and integrated source. Gate default activation on complete screen/art coverage and correctness/performance evidence. Keep source review, synthetic rendering, native review build, installation and user-data acceptance as distinct recorded states.

**Tech Stack:** Existing Gradle/JUnit and Node/Vitest checks, LWJGL captures/performance metrics, macOS jpackage, Computer tools, GitHub CLI.

**Spec:** [Approved design](../specs/2026-10-12-civ6-atlas-ui-design.md), [master plan](2026-10-12-civ6-atlas-implementation.md).

## Global Constraints

- Every preceding task must be complete; no unfinished coverage hidden behind fallback images or passing unit tests.
- Capture all five sizes at both font scales, plus actual Retina/native resizing. Body contrast ≥4.5:1, essential controls ≥3:1, default Activity map coverage ≥45%.
- Benchmark 164×80/15-civ scene: warm median ≤16.7 ms, p95 ≤33.3 ms, no sustained lifecycle memory growth or hidden/idle continuous animation.
- Preserve game state, three-checkpoint retention, agents, messages, permissions and explicit legacy/mod choices.
- Do not install/restart over a running match or terminate a provider process. A separate review build uses isolated data and synthetic/stubbed providers.
- PRs are ready for review, never drafts. Post reviewer findings/no-findings to GitHub as well as chat, per standing authorization.

## Review Focus

1. Passing headless tests do not prove actual GPU layout/animation or native interaction (E1/E2).
2. Visual backlog/cache spikes on a crowded late-game map must not stall the game or grow indefinitely (E1).
3. Real text/image messaging and takeover must retain focus, privacy and pending-state semantics after the redesign (E1/E2).
4. An appearance upgrade must not overwrite explicit mod selections or disable old saves (E2).
5. Installed artifacts must match reviewed source and not overwrite live data or leave ambiguous build copies (E2).

---

## Task 1: E1: Whole-game regression, visual review and measured optimization

**Files:** Complete `docs/atlas/screen-coverage.json`, `art/atlas/coverage.json`; create `docs/atlas/acceptance.md`, `tests/src/com/unciv/ui/components/AtlasEndToEndTest.kt`, `scripts/atlas/report.py`, `scripts/atlas/test_report.py` (Python standard-library unittest, no extra dependency). Optimize only source proven by benchmark evidence. Raw evidence root `/Volumes/Lexar/Unciv/.superpowers/civ6-atlas/acceptance/`.

**Interfaces:** `report.py --evidence <path> --require-complete` reads fixture reports/capture manifests and emits a compact JSON/Markdown report with source hashes, completed/pending gates and links. Missing/unavailable timings or captures remain explicit failures/pending; no implied passes. Test fixture scripts drive real controls/commands with isolated test saves and scripted providers.

- [ ] Write `atlasWorkflowTracePreservesAuthoritativeResults`: create/load preset → start/load → choose research/policy → found/move/work/attack → city queue/purchase → propose/respond to peace → text/image message → Activity scroll/Live → Overview → takeover/handback/spectate. Assert exact engine state, recipient consent, final message statuses and controller/view IDs at each checkpoint. Test captures/checkpoints contain no new animation payload or duplicated images.
- [ ] Write `test_acceptance_report_rejects_missing_evidence` in `scripts/atlas/test_report.py`: `self.assertFalse(report["complete"])` when any unit, screen, render size, benchmark metric or workflow is absent; an unavailable GPU timer is labeled unavailable rather than invented. Expected CPU/frame/texture/heap/idle metrics must still be present. The report library function is `build_report(evidence_root: Path) -> dict`, shared by the CLI and this test.
- [ ] Run `scripts/atlas/check.sh '*AtlasEndToEndTest'` and `python3 -m unittest discover -s scripts/atlas -p test_report.py`; confirm intended failures before completing the workflow/report implementation, then require both commands to pass.
- [ ] Run all focused tests, `scripts/atlas/assets.sh coverage --require-complete`, then the complete repository Gradle check using the isolated F1 wrapper's Gradle mode: `scripts/atlas/check.sh --gradle check`. Run `npm --prefix agent-runner run typecheck`, `npm --prefix agent-runner test`, `npm --prefix agent-runner run build`. Expected `BUILD SUCCESSFUL` and zero test/type/build failures; report skips with reasons.
- [ ] Capture every registered fixture/variant at all target sizes/scales. Inspect against approved concept for hierarchy, map readability, clear ownership, spacing, composited contrast, motion, tool grouping and context. Fix real defects, rerun affected captures and their tests; don't repeatedly run unrelated suites without a change or concern.
- [ ] Benchmark the exact baseline seed/camera/action stream three times on the same device/runtime. Include a crowded mixed-unit action phase, menu idle, minimized/hidden world, repeated view switches and 100 cache/panel resize/open/close cycles. Report median/p95 and CPU/GPU distinction. Record texture allocation plateau and post-GC heap slope after warm-up; increasing retained counts/bytes are a failure even if peak memory fits the device.
- [ ] If thresholds miss, profile before changing anything. Address measured sources such as per-frame allocations, overdraw, atlas switches, snapshot rebind or unnecessary actor rebuilds; preserve privacy and complete art. Rerun the identical trace. Never secretly turn off required animations/art to meet the target.
- [ ] Produce `docs/atlas/acceptance.md` with complete coverage tables, before/after evidence and remaining native checks. Commit `test: verify complete atlas workflows rendering and performance` only when synthetic/source gates pass; native gates remain unchecked.

## Task 2: E2: Defaults, independent review, PR and native acceptance

**Files:** Modify `core/src/com/unciv/Constants.kt` defaults and `models/metadata/GameSettings.kt` only after E1; complete `ui/popups/options/DisplayTab.kt` reversible appearance action. Create `tests/src/com/unciv/ui/components/AtlasAppearanceMigrationTest.kt`; update `docs/atlas/acceptance.md`, `docs/world-screen-ui.md` and user-facing appearance/motion documentation.

**Interfaces:** New-install defaults `skin=Atlas`, `tileSet=AtlasTerrain`, `unitSet=AtlasUnits`; existing serialized explicit fields retain values. `GameSettings.useAtlasAppearance(): Unit` changes only the three appearance selections plus version marker, using the existing skin/atlas reload lifecycle. Motion settings remain independently selectable. Review artifact manifest includes source tree hash, JAR hash, app path, build command and validation status.

- [ ] Write `existingSettingsRetainExplicitAppearance`, `newSettingsUseAtlasDefaults`, `optInDoesNotTouchGameOrProviderSettings`: `assertEquals("CustomModUnits", loaded.unitSet)` for old settings; blank new settings resolve new selections; language, window size, provider/model config and data roots unchanged by opt-in. Existing saves load before and after changing appearance.
- [ ] Run `scripts/atlas/check.sh '*AtlasAppearanceMigrationTest'`; expect new-default assertions to fail, then implement narrowly and verify passing. Re-run affected source/asset checks and default-appearance captures.
- [ ] Run the final independent review required by the selected execution method against the exact integrated diff, focusing on privacy, event timing, clip lifecycle, setup persistence and screen coverage. Validate findings against source and fix them with focused regressions; post findings/no-findings on the PR once created. Do not claim an independent review if only self-review occurred.
- [ ] Commit, push `codex/civ6-atlas-ui` and create a ready-for-review PR describing the final behavior, complete scope, before/after captures, measured performance, tests and pending native checks. Use a body file for multiline descriptions. Attach every created PR with the Codex artifact tool. If size calls for multiple PRs, split along the linked plans with explicit dependencies, keep the whole objective open and avoid enabling unfinished defaults.
- [ ] Build a **separate** native review artifact using the isolated scratch destination, copy it to `/Volumes/Lexar/Unciv/.superpowers/civ6-atlas/native-review/`, and record hashes. Do not overwrite `/Users/noahkenny/.unciv-build/desktop/native/Unciv.app`. Before launching, verify its data root is isolated and all provider launching is stubbed/disabled; run fixture game data, not the user's live match. If desktop launcher lacks a supported isolated data-root option, add and test a development-only launch option before this step.
- [ ] Use Computer/native input to exercise the full common workflow and resize at Retina scale, including text/image paste, modal focus, map pan/zoom and animation settings. Check 60 seconds of idle main menu for recurrence of the earlier native crash, then sustained synthetic action playback. Record actual observed results. If Java-window automation still fails, state that limit and request only the specific remaining human check; do not mark it passed from a screenshot.
- [ ] Once validated source is reviewed and user-authorized merge conditions are satisfied, merge the relevant PR(s). Install only after the user has safely saved/closed the app or expressly authorized that restart; never terminate a quiet provider run. Verify installed hash/source and data roots on Lexar, preserve rollback information, and reopen the verified app without auto-starting an agent match.
- [ ] Re-run the essential native workflows against the installed artifact and update acceptance status. Record any user-assisted checks separately. Keep goal incomplete for missing assets/screens, failed performance, unverified native behavior or pending installation. Commit final evidence/docs; only then report the whole redesign complete.
