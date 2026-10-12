# Atlas design readiness audit

Source: `7fced2becf07f6fa211a98e49c89659a3c23523a`. Audit is read-only with respect to product code, live app and game data. Findings refine the proposed [design](2026-10-12-civ6-atlas-ui-design.md); they do not demonstrate implementation or completion.

## Load-bearing findings

| Evidence | Implication for the design |
| --- | --- |
| `WorldMapHolder.moveUnitToTargetTile` returns after routing through `routeThroughAgentSession`; legacy `animateMovement` is later in the other path. | Agent publications and human takeover commands need explicit committed-event animation. Testing only ordinary human moves would miss the user's main workflow. |
| `ImageGetter.loadModAtlases` constructs a `TextureAtlas` for every entry in `Atlases.json` and retains it in an atlas map. | Do not add all high-resolution animation frames to the eager list. Separate static previews from an on-demand bounded animation cache. |
| `AndroidImagePacker` defaults to 2048×2048 maximum pages, padding, mipmaps and per-image-folder settings. | Preserve device limits, isolate new smooth-art filtering, and test edge bleeding. Do not modify all existing pixel-art atlases to suit the new art. |
| `Fonts` uses one globally cached platform-backed bitmap font and custom resource glyphs. | Preserve body text/glyph behavior. Treat a decorative title font as an explicit lifecycle/localization change, not an ad hoc replacement. |
| `BaseScreen.render` runs `stage.act()` then draws; continuous rendering is a user setting. | Active playback must request frames and release the need when idle/hidden/disposed. A permanently enabled render loop is not an acceptable shortcut. |
| `TileLayerTerrain` derives edge images from visible/explored neighbor views. | Better coast/forest edges must never inspect hidden neighbors. This is a privacy requirement, not merely a visual detail. |
| Existing `ActivityGlassPerformanceCheck` explicitly benchmarks a simple textured component scene and states it is not whole-game FPS. | It cannot establish the target map/animation frame budget. Use a mixed-biome, large-map native trace for that claim. |

## Art sizing evidence

- Current AbsoluteUnits source folder: **467 PNGs** including base art, tint layers and variants. Dimensions range across small approximately 64–100 pixel canvases. File count is not the number of game unit types.
- The current `AbsoluteUnits.png` packed page is **2048×2048**. Its uncompressed RGBA8 base level is **16 MiB**, excluding mipmaps, CPU-side data and other textures. Compressed file size is not graphics-memory usage.
- Source inventory contains **127 distinct named unit types** across the two bundled rulesets. Coverage must track types, unique units, era variants and animation states independently.
- Illustrative sizing only: 127 types × 6 facings × 24 frames × 256×256 RGBA8 is **4.46 GiB** before masks, mipmaps, packing effects or shared frames. These are deliberately explicit hypothetical authoring assumptions, not measured usage or a selected asset format. They demonstrate why whole-library eager loading must be excluded. Actual frame counts, trimming, shared effects and page residency need measurements from representative production art.

## UI coverage map

The screen tree currently contains 217 Kotlin source files; that is not 217 distinct screens. Direct references to the scoped polished style appear in six new-game files and seventeen world-screen files. Inheritance and common controls can affect other files, so this is an adoption clue rather than a complete visual audit.

The acceptance inventory must cover these user-facing families:

| Family | Required flows |
| --- | --- |
| World and agent viewing | Header, research/policy tracker, ordered nation ribbon, activity, notifications, map layers, minimap, contextual actions, pause/elapsed status |
| Agent management | Conversations, images, receipts, overview, nation control, model/reasoning changes, takeover, handback, return to spectating |
| City | Production and queue, buying, citizens/specialists, growth/yields, buildings, tile purchases, selected construction details |
| Research and choices | Technology tree, policies, religion/beliefs, promotion tree, great-person selection, improvement selection, diplomatic votes |
| Diplomacy | Met-nation navigation, relationship/status, city-state interactions, terms, pending trade/peace, accept/reject outcomes |
| Empire information | Cities, units, resources, diplomacy, statistics, espionage and other available overview tabs |
| New game | Game/map options, Earth preview and TSL, roster, agent/nation editors, starting bonuses, presets, validation/start footer |
| General navigation | Main menu, loading, save/load, options, language, Civilopedia, victory/results |
| Supporting tools | Multiplayer setup/status, mod browser/management, map editor controls and dialogs, developer tools using shared controls |

## Verification still required

No new theme components, production art, animation cache or event playback has been implemented. No new performance or native interaction result is claimed. The design approval is pending; after approval, the implementation plan must preserve all of the coverage above, and actual rendered/runtime evidence must replace these source-only observations.
