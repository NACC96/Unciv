# UI and graphics polish

## Purpose and visual authority

Make watching and playing an agent match clearer, calmer and more consistent, and make setup readable in a small desktop window. This refines the charcoal/teal world-screen design and existing HexaRealm/AbsoluteUnits pixel art established in `docs/world-screen-ui.md`; it does not replace the terrain art or rendering architecture. The user requested a plan followed by subagent implementation. Optional questions about broader art and screen scope remain steerable; the implementation defaults to the established art direction and world/map, floating panels and setup.

## Selected implementation

1. A small shared styling object supplies the current charcoal surfaces, off-white type, restrained teal actions, spacing and button states to the selected screens. Keep `WorldScreenStyle` as a compatible facade. Do not change the global skin. Floating panels use a readable translucent surface with a fine rim, without framebuffer blur/readback.
2. Refine top-bar hierarchy and panel content. Civilization identity remains prominent; economy, turn and resource information stay aligned. Activity keeps agent names before each message, collapsed consecutive tool groups, nation filters, immediate Live following and manual reading hold. Messages make existing receipt statuses easy to scan. Overview makes comparison and selected-nation details distinct.
3. Refine map annotations around the existing terrain. City plaques use a calm surface, legible type and restrained ownership accents; defence, population and aircraft remain distinct. Minimap framing, controls and layer affordances match the HUD. Do not automatically hide map information, change fog/ownership rules, recolor mod assets or add zoom-dependent information policies.
4. Modernize setup cards and editors with the same palette. Nation identity and actions occupy separate rows; long model/bonus text wraps. Use width-aware layout and footer actions. Keep Save/Cancel outside scrolling forms, and bind the visible Start action to existing validation/working state.

## Behavioral constraints

- Game rules, agent prompts/tools, provider processes, save schemas, checkpoint retention, preset contents and bonus delivery stay unchanged.
- Human fog, met-nation restrictions and private messages remain authoritative. Spectator visibility stays as it is.
- Messages and Overview remain mutually exclusive; Activity is independent. Map input outside panel rectangles stays usable.
- No new dependencies, fonts, external art, shaders, continuous animations or global skin mutation.
- Use current translated game labels and existing icons. Preserve keyboard activation and disabled-state input consumption.
- Keep actual running Unciv, providers and real userdata untouched. Tests use synthetic fixtures and isolated build directories; game data continues to live on Lexar.
- Desktop is the primary acceptance platform, with a narrow logical viewport checked because shared Scene2D code must still fit.

## Acceptance

- Inspect real GPU captures at 960×720 and 420×600, plus setup at 800×500 and 520×360. Labels, controls, dropdowns, footers and scroll panes stay inside their owning bounds.
- Long nation/agent names, many bonuses, expanded tool groups, empty/error panels and receipt states remain readable and actionable.
- Existing live-follow, manual-hold, filter, message privacy, acknowledgement, preset/model/bonus persistence and map snapshot/camera tests pass.
- City interaction/visibility and minimap wrap geometry remain unchanged. No floating panel owns blur resources after this pass.
- Full isolated Gradle `check` passes. Package a separate reviewable native build; installing it over the live app and merging a new PR require their normal explicit authorization.

## Larger work deferred

Illustrated terrain replacement, new unit art, 3D rendering, action-camera/replay, automated zoom detail and city/research/diplomacy screen redesign need separate scopes. This pass establishes compatible visual components and improves the selected existing surfaces.
