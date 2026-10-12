# Atlas: full interface, map art and animated units

Status: proposed design for user review. Product implementation has not started.

## Objective and agreed scope

The objective is to make Unciv's UI on par with, or close to, Civilization VI. The user selected **full UI plus improved 2D map art** and explicitly added **new unit models and animations**. This is a whole-game presentation project, not a recoloring pass. A polished HUD alone does not complete it.

The reference is Civilization VI's hierarchy, readable map, illustrated detail, contextual controls and feedback. Unciv retains its existing game rules, hex coordinates, providers and human/agent/spectator behavior. New unit models mean original illustrated designs rendered as animated 2D sprites in the selected 2D architecture. We are not introducing a 3D camera or engine.

The running app, live matches, provider processes, saves and presets remain untouched during source work. Native installation is a separate step after validation and a safe user-approved restart. Design artifacts, captures and performance reports are kept on Lexar; isolated compiler scratch may use APFS as the current build workflow requires.

## Current evidence

Source audited at `7fced2becf07f6fa211a98e49c89659a3c23523a` (merged PRs 4, 5 and 6).

- `GameUiStyle` and `WorldScreenStyle` already provide scoped colors, button states, type sizes and spacing. They cover selected world-screen/setup surfaces rather than the full app.
- `docs/world-screen-ui.md` explicitly leaves city management, research, diplomacy and other screens in their original style. The preceding polish specification explicitly deferred replacement terrain and unit art.
- The default map uses HexaRealm and AbsoluteUnits. `TileGroup` separates terrain, features, borders, resources, improvements, overlays, unit art, flags and city labels. `TileSetStrings` already supports era/civilization variants and fallbacks.
- `TileLayerTerrain` supports layered artwork and neighbor-edge images. Only explored neighbors participate in edge rendering: new art must retain that information boundary.
- `WorldMapHolder.animateMovement` currently interpolates a unit sprite group for a local movement path. `BattleTableHelpers` supports attack image sequences, red damage flashes and actor displacement. These are useful foundations, but not a complete shared animation system for agent snapshot publications.
- `Fonts` currently uses a single global, platform-backed bitmap font with embedded ruleset symbols. A second decorative font cannot simply replace it without checking international text and symbols.
- Floating panel backgrounds currently use existing drawables, not map blur or framebuffer readback. Blur is not being reintroduced.
- Existing GPU fixtures prove selected bounds/privacy/culling cases. Their synthetic flat-grass map and plain panel backgrounds do not establish whole-game visual quality. Earlier captures are references, not evidence for the new target.
- Ruleset inventory: Vanilla has 100 units, 29 terrain/features, 30 resources and 34 improvements; Gods & Kings has 127 units, 33 terrain/features, 35 resources and 35 improvements. The union contains 127 named unit types. The source-derived inventory is saved on Lexar at `.superpowers/civ6-atlas/asset-inventory.json`.

## Approaches considered

1. **Extend the current charcoal skin only.** Lowest effort, but leaves the map, unit art and management screens too far from the objective. Insufficient as the final solution.
2. **Illustrated atlas interface and 2D art.** Recommended and consistent with the user's scope selection. Build a complete common visual language, original terrain/units and a visibility-safe presentation layer on existing Scene2D and map layers.
3. **Replace the world with 3D rendering.** Much larger engine and asset pipeline change. The user selected the 2D approach instead.

## Visual direction

### Surfaces and typography

Use midnight navy surfaces, ivory body text and restrained antique-brass structure. Brass marks primary actions and key framing; nation colors continue to identify ownership and speakers. Warnings, errors and confirmations also have icons and text, rather than relying on color.

Default controls have small corner radii and clear hover, pressed, focused, disabled and selected states. Avoid oversized pill buttons and uniform rows of equally prominent actions. Panels use readable filled/translucent surfaces with fine rims and modest shadows. No live blur.

Use a readable sans-serif body face, tabular numeric alignment and a restrained display face for large screen/city titles if its font pipeline passes localization checks. The generated concept uses more serif text than the intended live activity body. Preserve user font scaling, international glyph fallback and inline resource symbols. Missing glyphs are a release failure.

Initial semantic tokens: navy `#10232F`, inset `#0B1821`, raised `#213744`, ivory `#F2EBDD`, muted `#B8C4C8`, brass `#D5B776`. Contrast tests govern final values; the exact color values are easy to adjust.

### Map and terrain

Build an original illustrated tileset with natural greens, ochres, stone and deep-to-shallow water. Terrain detail should describe shape and height rather than repeat high-contrast pixel noise. Forests, mountains, coastlines and riverbanks need consistent lighting and scale. Roads and improvements must remain visible over the art.

Use layered assets and neighbor transitions, not one huge map image. The result must work on generated maps and both installed Earth maps, including horizontal wrap. Repeated hex edges should be quiet; selected movement ranges and borders remain legible. Unexplored land has a cartographic treatment that reveals no actual terrain; remembered land stays visually distinct from live vision.

Art coverage includes every terrain, feature, resource and improvement in the two bundled Civ V rulesets, city appearances across eras, and recognizable natural wonders. Original art provenance, prompts and licenses are recorded with assets. Third-party rulesets retain their own assets/fallbacks; a missing mod sprite never becomes a missing actor or white rectangle.

### New unit models

Replace the default pixel-unit presentation with a coherent illustrated unit set. Infantry, mounted units, armor, siege, naval units, aircraft, civilian workers/settlers and great people must have distinct silhouettes at actual play zoom. Unique units need their own recognizable designs, not merely a nation-color swap. Cover both bundled rulesets and meaningful era changes; do not call a handful of demo units a completed set.

Separate base art from nation-color masks and stable shadows. Keep consistent foot/base anchors, facing, proportions and lighting. Artwork must fit the layered map's draw order: a tall sprite should not disappear through incorrect culling or obscure its flag/city plaque. Selected units receive a clear ring and health/movement context. Stacked civilian/military units and aircraft remain separately selectable.

Authoring sheets contain aligned frames with manifests declaring dimensions, anchors, facing and state durations. Packing validates frame bounds, missing frames and tint masks. New atlases have their own filtering policy so smooth art does not blur existing pixel-art mods.

The art pipeline must separate small always-available static/preview images from animation clips loaded on demand. The current `ImageGetter.loadModAtlases` eagerly loads every atlas listed in `Atlases.json`; listing the full animated unit library there is not acceptable. A bounded clip cache owns graphics resources, loads only relevant visible unit states, pins clips currently playing, and disposes unused clips safely on the rendering thread. Its allocation ceiling is explicit, measured and tested; cache pressure may fall back to the correct static sprite while a clip loads, but may not remove or misidentify a unit. This is a necessary architectural addition, not an excuse to omit new unit designs or animations from final coverage.

## Whole-game interface

### World screen

The map is the primary surface. A compact top strip groups civilization/view identity, economy, turn/year and navigation. Spectating, playing a nation, and whose turn is active must remain visibly distinct; selecting a portrait cannot silently grant control or change fog authority.

Use an ordered nation ribbon to show turn order, current nation, previous nation and next nation. Mark both emblem and name; scroll the ribbon for large rosters. Retain the equivalent nation-order selector in Activity. Human games expose only permitted identities/information.

Research/policy progress becomes a compact tracker below the header. A contextual unit/city command dock groups identity, health, movement, primary action and secondary actions. Existing commands and their conditions remain authoritative. Spectator inspection shows read-only information and an explicit existing takeover entry point rather than enabled gameplay commands.

Keep the minimap in its familiar lower-right region with compact map-layer controls. Notification stacks remain separate from the primary turn action. The latter describes the actual next task (choose production, research, next unit, end turn) rather than a generic action.

Default layout avoids collisions between activity, tracker, unit dock, minimap and right-side panels. At smaller sizes, reduce optional expanded regions and use tabs/scrolling before shrinking text. Keep at least 45% of the map unobstructed in the default 1440×900 desktop layout with Activity open; deliberately opened detailed panels can use more space.

### Agent activity

Keep the floating, non-modal feed. It gets a stable nation header, turn-order filter, elapsed time and repeated colored agent identity before every message. Consecutive tools remain one expandable group showing the latest completed/pending call and count; errors remain noticeable. Show only the reasoning/status content the provider actually supplies.

Live remains immediate, not a slow scrolling animation. Reading earlier entries preserves the nation and visible anchor; incoming content increments an unobtrusive count until Live is chosen. Streaming text does not move focus. Tool details are on demand and full history remains reachable. Model/provider/reasoning details live in secondary text or a disclosure, leaving leader speech easy to read.

### Messages, overview and nation control

Messages uses a clear conversation list, a reading pane and a stable composer/footer. Preserve image attachment previews, unsupported-model warnings, private-message visibility, queued/read/acknowledged distinctions and ordering by receipt. Repeated leader identity and nation color stay visible in long exchanges. Drafts survive layout/snapshot refresh; typing never fires world shortcuts.

Overview becomes a compact dashboard within the user's chosen floating-panel format: sortable comparisons, selected-nation details, resources and relevant progress. Full statistics remain spectator-only where current rules require it. Messages and Overview remain mutually exclusive; Activity remains independent.

Nation Settings clearly separates viewing from controlling. Show current controller, pending takeover/handback, model and reasoning. Returning to spectate remains discoverable. Existing takeover boundaries and saved controller state remain authoritative. Avoid reintroducing recurring hotseat introduction popups.

### City management

Retain the map context, with a clear city header, population/growth, yields, production and queue. Put available constructions into searchable/filterable cards with cost, completion time, requirements and purchase actions. Keep citizen focus, specialists, tile purchases and buildings accessible through grouped sections. Distinguish inspection from action permissions and maintain draft selection across live snapshot updates.

### Research, policies, religion and promotions

Use coherent tree/card styling with strong locked/available/current/completed states, visible prerequisites, accurate costs and a stable details pane. Keep context while panning the technology/promotion tree. Policy and belief choices use readable grouped cards. These are presentations of Unciv's rules; Civ VI districts, housing or a separate civic tree are not added by the visual redesign.

### Diplomacy and trade

Use recognizable civilization identity, relationship/status summary, conversation and actionable proposal sections. Separate proposed terms, pending offers and completed agreements. Trade items group by category and show legal availability, quantity, duration and the two sides clearly. Preserve the just-merged recipient-consent behavior; presentation never declares an agreement before the engine confirms acceptance.

### Setup, menus and supporting screens

Carry the common design through main menu, loading, new-game/map selection, nation editors, presets, save/load, options, Civilopedia, empire overviews, victory, multiplayer, mod browser and map editor chrome. Use consistent navigation, search, fields, tabs, dialogs and fixed footers. Preserve map preview, TSL selection, bonuses and agent settings. Destructive actions stay visually distinct and require their existing confirmation.

Rare tools may reuse common components rather than receive bespoke illustrations, but no shipped first-party screen may remain with accidentally mismatched legacy controls, unreadable contrast or inaccessible primary actions.

## Animation and feedback

### Visible behavior

- Movement: directional walking/riding/rolling/sailing/flying cycles along the real committed path, with wrap-aware travel and correct destination.
- Combat: appropriate melee motion, bow/projectile or firearm effect, artillery recoil, naval fire and aircraft attack passes; visible damage, defeat and city capture feedback reflect actual results.
- Work/founding: brief worker activity, improvement completion and city-founding transitions.
- Idle/selection: restrained flag/water/unit motion, limited to relevant visible actors; no whole-map perpetual redraw requirement.
- UI: short focus/hover/selection responses, panel entry/exit and progress transitions. No animated auto-scroll in Live, no moving text during reading, and no camera motion that fights user pan/zoom.

Provide Normal, Fast and Reduced/off animation choices. Frame-sequence cadence and total action durations are data-driven and tuned from captures. Reduced motion uses stable changes with short opacity/color feedback and retains every outcome. Optional follow-action camera is off by default and immediately yields to user navigation.

### State and presentation boundary

The game commits actions exactly as today. Animation cannot execute an action, alter damage, delay checkpoint correctness or hold a provider turn open.

Introduce a small transient presentation timeline keyed by game identity, committed revision and event identity. The timeline carries only visual facts needed for a transition, not cloned game states, full receipts or image bytes. Replays of the same action do not animate twice. Save/load begins from the authoritative final state; it does not replay a saved movie. This avoids the earlier map-memory/checkpoint storage problem.

Build events through the viewer's existing `GameView` and visibility rules at the appropriate before/after commit states. Do not expose a hidden route, defender, city or attack source through motion, sound, tooltips or camera movement. Spectators may observe all events their current spectator view allows. A switch to human control immediately clears/revalidates presentation data from the spectator view.

Use one shared presentation path for human commands, agent publications and built-in AI where their committed transition is available. If an intermediate path/event was not retained, display the confirmed current state; never invent a route or combat event from prose in Activity.

The existing human-command route inside an agent match returns before `WorldMapHolder.animateMovement`. Therefore both spectator agent actions and human takeover actions need the shared committed-event integration. A successful legacy human move animation is not evidence that the agent path works. Both paths are separate required verification cases.

Bound the visual queue. When events arrive faster than playback, accelerate/coalesce eligible movement and skip stale cosmetic work, with a visible catch-up affordance. Preserve important outcomes in the activity/notification record. Clear actors/timers and reconcile final positions on load, screen close, nation switch, resize, settings change, skipped animation and resource disposal. The unit dock and hit targets always resolve against the current committed state; ghost actors never accept gameplay clicks.

Render on demand while animations are active. A static screen must return to its current low-work idle behavior. Existing request-rendering/Scene2D infrastructure is preferred over a new always-running render loop.

## Architecture and integration

1. Expand the existing scoped `GameUiStyle` into consistent semantic theme/components, retaining `WorldScreenStyle` compatibility during migration. Apply copied widget styles or a correctly initialized built-in skin; do not mutate a shared skin midway through drawing. All first-party screens migrate deliberately.
2. Add reusable headers, stat cells, tabs, fields, disclosure groups, tooltips, footers and bounded panels. Separate display data from controls' existing authoritative callbacks.
3. Add the illustrated terrain/unit set through current tileset mechanisms, with static atlas discovery and a bounded on-demand animation-clip cache. Keep legacy art selectable and honor explicit third-party art selections. New style and animation settings have backward-compatible defaults.
4. Add the transient timeline and sprite animation abstraction at a narrow map/presentation seam. Extend existing movement and battle helpers where appropriate; avoid independent overlapping playback implementations.
5. Extend GPU fixtures to real mixed-biome scenes and all major screens. Headless tests check behavior; rendered captures and interaction traces check actual presentation.

## Delivery stages (all required for the full goal)

1. Visual design/coverage manifest and representative unit/terrain art boards; approve the target and write the implementation plan.
2. Theme/component foundation and a complete world-screen scene with usable activity, navigation and unit commands.
3. Terrain transitions, city/improvement/resource art and full unit asset coverage, integrated through the existing layer pipeline.
4. Shared committed-action playback and animated unit states, including agent turns and reduced-motion behavior.
5. City, research, policy/religion/promotion, diplomacy/trade and the three agent management panels.
6. Main menu/setup/presets/save/options and remaining first-party screens; localization, accessibility and narrow-layout completion.
7. Whole-game visual, functional and performance review, exact-source PR validation, separate native review build, safe installation and actual desktop acceptance.

Stages may overlap after contracts are fixed. Partial stages remain work in progress; a green unit suite or a beautiful concept does not satisfy the overall goal.

## Verification and completion gates

### Appearance and coverage

- Maintain a checked screen inventory and an asset manifest generated from both bundled rulesets. Every built-in unit has the correct new static design, applicable animation states and a deliberate unique-unit treatment. All required terrain/resource/improvement/city assets resolve.
- Compare actual rendered screens with the approved concept at 1440×900 and 1920×1080, plus 960×720, 800×500 and 420×600 stress layouts. Check 1.0× and 1.5× text scaling, long localized labels and actual Retina/native resize behavior.
- Normal text meets 4.5:1 contrast against its composited surface, large text and essential graphical controls at least 3:1. Inspect screenshots over bright desert, snow, coast and dark forest; nominal token values alone are not evidence.
- Check small/medium/strategic zoom for legible units, distinct terrain, city and flag culling, seam-free world wrap, rivers/coasts/roads, improvement ownership and fog boundaries.
- Test all complete common workflows: create/load preset, start/load, inspect and move unit, choose research/policy, manage city/queue, propose/respond to trade/peace, message with image, read activity, overview, takeover/handback and return to spectate.

### Animation correctness

- Record real GPU frames for movement, ranged/melee combat, destruction, founding, improvement and turn handoff; test paths across wrap boundaries and actors entering/leaving visibility.
- Verify final actor positions, health and ownership equal committed state at completion and after skip/catch-up. Test duplicate receipts, rapidly published actions, consumed units, source screen disposal, camera pan, resizing, load and takeover mid-animation.
- Hidden-event fixtures prove that identity, coordinates and terrain are not exposed through any art, sound or camera effect. No provider calls are made by synthetic fixtures.

### Performance and lifecycle

- Measure baseline and changed builds with the same synthetic large Earth-size scene (164×80, 15 civilizations), viewport, camera path and action stream. Record render/CPU frame timings, draw calls, texture memory, heap and idle behavior. Do not infer performance from asset dimensions alone.
- Desktop target: smooth 60 Hz interaction on this Mac, median frame time no more than 16.7 ms and p95 no more than 33.3 ms during the representative pan/action trace after warm-up. Establish baseline first; if the existing renderer misses this target, report the measured bottleneck and improve it rather than silently weakening acceptance.
- No sustained memory growth after repeated open/close/resize/view-switch cycles. No framebuffer readback for panel appearance and no continuous rendering on static, hidden or reduced-motion screens. Asset loading/upload is outside per-frame draw paths.
- Record graphics allocation separately from compressed PNG/download size. Validate animation-cache eviction, pinning, static fallback, atlas-filter isolation and device texture-size limits. Existing packing defaults use 2048-pixel pages; increasing the default for unrelated assets is not part of this design.
- Run appropriate focused regressions through development, then the complete Gradle check and agent-runner tests after integration. Record exact source tree and capture provenance. Source and native interaction checks are distinct.
- Review the separate native build while leaving the running app untouched. The final goal is complete only after the full screen/art/animation inventory, performance checks and actual native workflows are verified. Any missing coverage remains explicit unfinished work.

## Design concept and reference

The generated world-screen image at `/Volumes/Lexar/Unciv/.superpowers/civ6-atlas/concept/world-screen-atlas-v1.png` is an art/layout target, not an app screenshot. The prompt is stored beside it. Its flags, generic portraits, sample statistics and mixed-nation feed content are illustrative artifacts: shipped controls must use real game identity, the selected filter and correct control permissions. The live body font will prioritize readability over the concept's decorative typography.

The companion unit board at `/Volumes/Lexar/Unciv/.superpowers/civ6-atlas/concept/unit-art-direction-v1.png` establishes detail, materials and silhouettes. Its side-on poses are not production-ready map sprites or a verified walking cycle. Production frames must use the chosen map projection, aligned anchors, meaningful pose changes and correct nation-color masks, and pass in-game motion inspection. Both concepts were created with the built-in image-generation tool; their exact prompts are saved alongside the PNGs.

The official Civilization VI manual's main-screen/interface section is the external hierarchy reference: https://cdn.cloudflare.steamstatic.com/steam/apps/289070/manuals/CIV_VI_25TH_ONLINE_MANUAL_ENG.pdf?t=1663263035 . Original artwork is created for Unciv; the concept is not a source of gameplay data or a promise of a 3D renderer.
