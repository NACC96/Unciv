# Atlas overhaul implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved whole-game atlas interface, illustrated terrain and complete animated unit set while preserving Unciv's rules and agent/spectator features.

**Architecture:** Retain Kotlin/LibGDX, Scene2D and the layered hex map. Extend the common theme, supply original static and animated assets, and route committed engine events into a bounded, viewer-filtered transient playback layer. Split delivery into five linked subsystem plans with explicit integration gates.

**Tech Stack:** Existing Kotlin/JVM, LibGDX Scene2D/TextureAtlas, Gradle/JUnit/Mockito, desktop LWJGL, existing TypeScript runner tests; built-in image generation for original raster artwork.

**Spec:** [Approved design](../specs/2026-10-12-civ6-atlas-ui-design.md); [source readiness audit](../specs/2026-10-12-civ6-atlas-readiness-audit.md).

Status: approved for subagent implementation and independent task reviews on 2026-10-12. Execution has started; no implementation task is yet complete. Design approval and implementation-plan approval are both recorded; this document does not claim the new UI exists.

## Global Constraints

- Full UI plus improved **2D** map art and new animated unit designs; all stages are required. No gameplay or 3D-engine conversion.
- Preserve providers, turn authority, diplomacy consent, save recovery, message privacy, takeover/handback and explicit mod selections.
- No live blur or framebuffer readback for panel appearance; no permanently enabled rendering loop.
- Normal text contrast **4.5:1**; large text and essential graphics **3:1**, measured on composited surfaces.
- Capture **1440×900, 1920×1080, 960×720, 800×500 and 420×600**, at **1.0× and 1.5×** text scaling. Default 1440×900 layout with Activity open leaves **at least 45%** of the map unobstructed.
- Verify the **164×80, 15-civilization** benchmark; warmed median frame time **≤16.7 ms**, p95 **≤33.3 ms** on this Mac. A slow baseline calls for measured optimization, not an unreported target reduction.
- Cover **127 distinct built-in unit names** across Vanilla/Gods & Kings plus all terrain, resources, improvements, natural wonders and era city designs. Unique units need distinct designs.
- Animation is transient presentation, never gameplay authority or saved history. No new durable action snapshots, image duplication or full-library eager animation loading.
- Leave the installed/running app, provider processes and live data untouched. Artifacts on Lexar; isolated APFS compiler scratch only. A native review build and later safe installation are separate steps.

## Review Focus

1. Spectator → human during an animation must immediately discard hidden event details; tested in V2/V3 and E1.
2. Rapid agent publications and duplicate receipts must not replay actions, leave ghosts or stall turns; tested in V1–V3.
3. Long localized labels at 420×600/1.5× must leave controls and composer footers reachable; tested in F2/W1/W2/M1–M4.
4. Missing mod art, cache pressure or atlas reload must retain correct selectable units; tested in A1/A3/V3.
5. Snapshot refresh must preserve drafts, reading anchors, selected construction and controller permissions; tested in W2/M1/M3/E1.

---

## Source and ownership

Start from `7fced2becf07f6fa211a98e49c89659a3c23523a`, with approved documentation commits `e110d2d09` and `aa81bdd15`. Worktree: `/Users/noahkenny/.codex/worktrees/ui-graphics-polish/Unciv`, branch `codex/civ6-atlas-ui`. Recheck status and attached worktrees before execution; preserve others' changes.

Keep subsystem ownership narrow: foundation owns theme/fixture contracts, art owns manifests/packing/cache, animation owns engine capture/publication/playback, management owns its screens. WorldScreen, GameSettings, Constants and the build files are shared integration files: assign one writer at a time. Do not run two Gradle builds against the same scratch directory.

## Execution map

| Order | Plan | Task IDs | Working deliverable |
| --- | --- | --- | --- |
| 1 | [Foundation and world UI](2026-10-12-civ6-atlas-foundation-world.md) | F1, F2, W1, W2 | Reproducible baseline, shared atlas controls, complete world HUD and agent panels |
| 2 | [Artwork and asset delivery](2026-10-12-civ6-atlas-artwork.md) | A1–A4 | Validated asset pipeline, illustrated terrain, all 127 unit designs and bounded clip loading |
| 3 | [Committed-action animation](2026-10-12-civ6-atlas-animation.md) | V1–V3 | Correct human/agent/AI action playback with visibility and lifecycle safeguards |
| 4 | [Management and supporting screens](2026-10-12-civ6-atlas-management.md) | M1–M4 | Full city, choice, diplomacy, setup and supporting UI migration |
| 5 | [Integration and acceptance](2026-10-12-civ6-atlas-acceptance.md) | E1, E2 | Performance evidence, full workflows, reviewed PR and separately validated native build |

After F1/F2, M1–M4 and A1/A2 can progress independently from W1/W2. Fix the A1 clip contract before V3. V1/V2 do not need finished artwork; use explicit test fixtures, never present placeholders as shipped art. Finish A4 against real playback. E1/E2 require every other task. Parallelism is an execution option, not permission to create additional user-owned chats.

Each task includes a focused regression check, a visual/behavioral deliverable and a commit. Asset authoring is divided into bounded batches inside A2/A4; no task is finished while coverage rows remain provisional. Do not use a placeholder asset to satisfy a final coverage check.

## Decisions fixed by this plan

- Continue the existing platform-backed body font, with stronger size/weight hierarchy. A second display font is optional refinement only after glyph tests; the approved spec does not require a risky font-system replacement.
- Add built-in `Atlas`, `AtlasTerrain` and `AtlasUnits` selections. New installations default to them only at E2, after coverage passes. Existing explicit skin/tile/unit choices remain; a reversible “Use Atlas appearance” action lets existing users opt in. Original skins and mod selections stay available.
- Use shared widget styles initialized at skin creation, plus screen-specific layout changes. Do not traverse/recolor the scene on every frame or overwrite semantic nation/error colors.
- Animation gets one engine-to-view stream, not separate spectator and human implementations. Only viewer-filtered immutable facts cross publication; the provider protocol and durable save schema stay unchanged.
- Start the clip cache at **128 MiB on desktop / 32 MiB elsewhere**, with separate decoded staging accounting and at most **8 MiB** pending uploads. These are reversible, measured defaults; the A1/V3 acceptance tests enforce their actual allocation, including mipmaps and masks. A clip larger than budget falls back to static art without throwing.
- Six hex directions use authored frames or manifest-declared safe mirroring; asymmetric unit details are not mirrored accidentally. Default art canvas **128×128**, up to **192×192** for tall units; trimmed frames retain a common anchor. Tune frame count per state from actual motion, not a universal oversized sheet.
- Visual journal cap **256 events**, active playback cap **64**, movement playback **180 ms/hex Normal / 70 ms/hex Fast**, maximum **900/300 ms** per move. These are initial adjustable constants. Catch-up reconciles cosmetic backlog to committed state and keeps results in the existing logs.

## Verification entry points

F1 creates `scripts/atlas/check.sh` and `scripts/atlas/render.sh`; later task commands depend on that deliverable. `check.sh` invokes the existing wrapper/JUnit suite using isolated scratch, never packages or launches the user app. `render.sh <fixture> --output <path>` launches only a synthetic LWJGL fixture with no provider access or live data.

Evidence root: `/Volumes/Lexar/Unciv/.superpowers/civ6-atlas/`. Source manifests and compact reports are committed; generated captures/raw timing traces remain on Lexar. Every report records source commit/tree, runtime, viewport, scale, fixture seed, graphics device and command. Native UI acceptance cannot be inferred from headless tests or a generated concept.

## Coverage and self-review

| Design requirement | Owning tasks |
| --- | --- |
| Semantic theme, fonts, focus, contrast, small screens | F1/F2, each screen task, E1 |
| World HUD, ordered nations, contextual commands, minimap | W1 |
| Activity/tools/live reading, images/messages, overview/control | W2 |
| All terrain/feature/wonder/resource/improvement/era-city art | A1/A2 |
| All unique units, masks/anchors/facings, clip residency | A1/A3/A4 |
| Movement/combat/founding/work/selection and all controllers | V1–V3, A4 |
| City, research/policy/religion/promotions | M1/M2 |
| Diplomacy, trade consent and empire information | M3 |
| Menus/setup/presets/save/options/encyclopedia/tools | M4 |
| Fog/wrap/lifecycle/privacy/reduced motion | A2/V2/V3/E1 |
| Benchmarks, complete workflows, PR/native acceptance | E1/E2 |

Self-review completed against the approved spec: every section has an owner; shared contracts are defined once and consumed by task ID; each Review Focus item has an explicit test; source tests and native acceptance are separate. Unavailable platform/tool checks remain pending, never marked passed by assumption. Execution should report progress against this table rather than calling an early stage the finished redesign.
