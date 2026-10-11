# Unciv UI and graphics exploration

Prepared October 10, 2026 from the current desktop fork, the tested floating activity feed, and the supplied screenshots. These are candidate ideas, not a selected implementation scope.

## Recommended direction

A modern strategy interface around a readable, polished hex map. Keep the floating agent feed, make the map the main attraction, and give the interface a consistent visual language. Support both watching agents and playing alongside them.

The highest-impact early work is hierarchy, density, and interaction. The current screenshot has several competing focal points: saturated terrain, many circular resource badges, large blue buttons, a large status block, and a tall text feed. Calming the interface and making map overlays contextual should help before new terrain art is commissioned.

| Direction | Appearance | Tradeoff |
| --- | --- | --- |
| Modern controls with polished pixel art | Quiet charcoal panels, crisp text, restrained civilization colors, improved existing terrain | Recommended first; preserves the game's identity and supports incremental changes |
| Illustrated strategy atlas | Painted terrain, richer coastlines and cities, subtle paper/cartographic character | Larger asset effort; requires a consistent art style across terrain combinations and zoom levels |
| Clean strategic map | Simplified terrain shapes, strong borders, restrained symbols, data overlays | Best for comparing agent decisions; less atmospheric than an illustrated world |

A useful eventual combination is a detailed map and a switchable strategic view. Full 3D would be a separate renderer project and should be judged against the actual value it adds to watching and playing.

## 60 candidate improvements

### 1. Visual foundations

1. Use neutral panel colors with civilization colors reserved for identity, selection, and ownership.
2. Replace inconsistent rounded pills and rectangular blocks with one small set of panel and button shapes.
3. Define a consistent spacing scale, aligned edges, padding, and compact/comfortable density modes.
4. Establish clear typography for titles, body text, secondary information, and numerical data.
5. Make text and icons crisp at Retina resolution; tune map-art scaling separately from text rendering.
6. Give controls consistent hover, pressed, focused, disabled, and selected states, with readable contrast.

### 2. World-screen layout

7. Make the top bar slimmer, with turn/year, viewing perspective, and selected civilization clearly separated.
8. Give spectators useful match information and chosen-civilization statistics, avoiding ambiguous spectator zero totals.
9. Combine agent identity, state, and Pause/Resume into a compact control strip; put technical model IDs in expanded details.
10. Make side panels draggable, resizable, dockable, and collapsible, saving their arrangement.
11. Use responsive layouts at small window sizes, with compact controls and expandable details.
12. Add a clean-map presentation mode that hides optional panels and restores the previous arrangement on exit.

### 3. Map clarity

13. Expose the existing resource/yield/movement toggles in a clearer, labeled map-layer palette.
14. Make overlay detail respond to zoom: small distant markers, fuller information when inspecting a tile.
15. Show relevant resources strongly and less relevant badges quietly, with an option to show everything.
16. Use a consistent icon family and badge size, with distinct treatment for resources, units, cities, and warnings.
17. Make selection, reachable tiles, planned paths, and attacks visually distinct and explain them with a small legend.
18. Improve terrain and ownership tooltips, including yields, movement cost, and why an action is unavailable.

### 4. Terrain graphics

19. Harmonize the terrain palette so grassland, plains, hills, forests, and desert are distinct without all competing for attention.
20. Add terrain variations to reduce repeated patterns while keeping each terrain immediately recognizable.
21. Improve transitions between coast and ocean, beaches and land, forest and open ground, and adjoining mountains.
22. Add restrained depth through consistent shadows, shoreline treatment, and mountain/forest silhouettes.
23. Redesign fog and unexplored regions with clearly different appearances and more attractive boundaries.
24. Explore a complete illustrated tileset after testing a small representative scene across multiple zoom levels.

### 5. Units and cities

25. Improve unit silhouettes so military and civilian roles read without relying entirely on flag badges.
26. Coordinate civilization tint layers, selection outlines, and shadows to keep units visible over every terrain.
27. Use compact health, movement, and order indicators that become more detailed when selected.
28. Improve city banners with consistent names, population, production progress, and ownership treatment.
29. Expand era-specific city and unit artwork so visual development reflects game progression.
30. Add short movement, damage, founding, and completion animations tied to committed engine events.

### 6. Agent feed

31. Refine the feed's typography, paragraph spacing, turn separators, and civilization identity markers.
32. Resize and reposition the floating feed independently of the game window, with optional docking.
33. Group repeated observations and inspections into expandable summaries without losing individual calls or results.
34. Link unit, city, and coordinate references to map highlights or an explicit camera jump.
35. Add filters by civilization, turn, and event type, with clear unread activity counts while reading earlier entries.
36. Visually distinguish thinking summaries, proposed actions, calls in progress, committed actions, and errors; only use reasoning actually exposed by the provider.

### 7. Watching a match

37. Add optional Follow action camera behavior; manual map panning suspends it until explicitly re-enabled.
38. Show a subtle map highlight for the agent's latest committed action, synchronized with its feed entry.
39. Introduce event cards for founding a city, first contact, research completion, declarations of war, and captured cities.
40. Add a compact civilization scoreboard with expandable economy, research, territory, and victory progress.
41. Offer round summaries and trend charts using real game statistics, separate from an agent's own claims.
42. Explore turn stepping and visual replay from committed events/checkpoints; playback speed controls presentation, not how long a model is allowed to think.

### 8. City, research, and diplomacy screens

43. Reorganize city management around a readable summary, construction queue, citizen controls, and map inspection.
44. Present construction choices as consistent cards with costs, turns, yields, and requirements.
45. Make production queue editing clearer with explicit reorder controls and visible current versus queued production.
46. Improve research-tree spacing, zoom/pan behavior, and current/queued/researched state styling.
47. Present diplomatic relationships in clearer civilization cards and a relationship matrix, with reasons available on demand.
48. Modernize empire overview tables with sortable columns, filters, concise summaries, and map navigation from each row.

### 9. Setup and onboarding

49. Lead setup with clear Play, Watch agents, and Mixed match choices.
50. Use compact player cards showing civilization, controller, friendly model name, reasoning level, and edit controls.
51. Make personality instructions easy to inspect and edit, with reusable local profiles and clear defaults.
52. Provide useful presets for quick tests, longer matches, and model comparisons while showing all chosen settings.
53. Explain pending startup/authentication errors beside the affected agent rather than through a generic failure message.
54. Refresh the main menu and load screen with a consistent theme, useful match thumbnails, and clear resume information where supported.

### 10. Accessibility, motion, and responsiveness

55. Offer readable UI/text scaling with independent map zoom and optional compact density.
56. Pair color with icons, shapes, and labels; provide civilization/border palettes suitable for color-vision differences.
57. Add clear keyboard focus, tooltips with shortcut hints, and consistent navigation between panels.
58. Use brief purposeful transitions and restrained sounds for major events, with reduced-motion and sound controls.
59. Keep rendering responsive as feeds grow: update changed rows, limit visible detail, and avoid rebuilding expensive components for text-only events.
60. Provide a battery-conscious mode and graphics options; animate only when needed and preserve fluid map pan/zoom on larger maps.

## Suggested order

1. **Trustworthy live display:** map, minimap, active-agent label, and action feed must track committed state. This is a prerequisite for attractive spectator controls.
2. **Interface polish:** shared colors, type, icons, spacing, compact HUD, and a more flexible feed. These should produce the fastest visible improvement.
3. **Readable action presentation:** contextual map layers, improved unit/city markers, feed-to-map links, optional camera following, and event highlights.
4. **Graphics sample:** one small scene containing coast, water, mountains, forest, open terrain, cities, and several unit types. Compare polished pixel art with an illustrated alternative before replacing all assets.
5. **Broader screens:** city, research, diplomacy, setup, and overview improvements using the same components.
6. **Larger spectator features:** statistics, round recaps, saved layouts, and replay after their event/state requirements are established.

## Decisions to settle before substantial work

- Pixel-art refinement or illustrated map overhaul: choose through actual sample scenes, because producing the wrong asset style creates significant rework.
- Desktop-first polish or a shared desktop/mobile redesign: layouts and interaction density differ, while the underlying game screens are shared.
- Customizable floating panels or a fixed simple layout: test whether added flexibility is worth the control complexity.
- Live view only or replay/scrubbing: replay needs explicit historical state and event storage; an activity transcript alone is insufficient.

Small defaults such as corner radius, accent color, feed padding, and icon spacing can be chosen and adjusted during a prototype.

## Implementation fit with the current fork

The checked-in UI-skin system can replace many backgrounds, tints, and shapes. The documented skin system does not by itself replace fonts/icons or every layout; those require application changes. See [Creating a UI skin](Modders/Creating-a-UI-skin.md).

The existing tileset system supports layered terrain assets, era variants, civilization tint layers, attack frames, fog artwork, and neighbor-edge images. These are useful starting points for graphics improvements. See [Creating a custom tileset](Modders/Creating-a-custom-tileset.md).

Resource, yield, movement, and worked-tile toggles already exist. Some proposals improve their discovery and presentation rather than introducing entirely new features. Agent-enabled gameplay currently has its own restrictions on mods, so enabling a new visual pack for those matches needs validation of that path; it should not be assumed to work merely because ordinary games can load a tileset.

Rendering uses LibGDX and Scene2D. A coherent interface and improved 2D artwork can be built within the existing renderer. Blur, shaders, animation-heavy scenes, and a 3D conversion have different performance and engineering costs and should be evaluated separately.
