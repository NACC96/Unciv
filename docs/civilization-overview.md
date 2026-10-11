# Civilization overview

Implemented October 10, 2026 as a local extension of the [world-screen HUD](world-screen-ui.md).

## Overview

Compare civilizations without leaving the live map: filter or sort the roster, select a nation, then inspect its details. The user selected a floating panel and full opponent statistics for spectators. Human games retain the viewer's own details and preserve opponent information limits.

The panel inherits the HUD's translucent charcoal surface, off-white text, restrained teal control states and stable agent accents. The October 11 polish pass removes its map blur; existing rounded skin textures draw the fill and rim without framebuffer resources. `WorldScreenStyle.kt` delegates to the scoped `GameUiStyle.kt`; the global skin remains unchanged.

## Typography

The panel title leads, followed by colored civilization names and a larger selected-nation heading. Controller labels, helper copy and metric labels recede in muted text; current values use the HUD's brighter text. Long roster names truncate, while the selected name and details wrap.

## Layout

The first viewport places a movable panel on the right, with comparison rows above a separate scrolling detail pane. Drag the header to move it; its geometry stays within the stage after dragging or resizing the window. Only the panel's rectangle captures input, leaving the map and match controls usable outside it.

Wide layouts show score, cities, population, gold and science. Narrow layouts retain score and science, with the remaining available statistics in selected-nation details. The detail metrics change from three columns to two. Individual panel resizing is deferred.

## Components

- **Open and close:** Overview and the existing Empire Overview shortcut toggle the panel. The close control hides it and releases its keyboard/scroll focus. Reopening refreshes its data.
- **Find and compare:** Nations is the initial filter; City-states and All are also available. Search matches civilization or configured agent names. Score starts in descending order; clicking a visible column selects its descending sort, then toggles direction. Hidden values display an em dash and sort after visible values. An empty result has explicit helper copy.
- **Inspect:** Selecting a row starts its detail pane at the top. Available details cover economy, research progress, era, strength, technologies, resources, city population/production, unit counts and wars. Empire details opens the original civilization tabs.
- **Refresh:** The panel reads the published GL-thread snapshot twice per second while visible and on world updates. Selection, filters, search, sorting and scroll positions survive refreshes within the remaining content bounds. If the selected nation leaves the filtered roster, the first visible nation becomes selected. Its timer is disposed with the world screen.

| Viewer | Roster | Detail visibility |
| --- | --- | --- |
| Spectator | Nations and city-states, including defeated civilizations | Full statistics and details |
| Human | Own civilization and met civilizations | Own details remain available; opponent score, population, strength and technology count obey existing ranking visibility |

Private opponent economy, research queues, era, resources, city production, unit lists and wars remain hidden. Ranking visibility does not authorize these details; the data layer removes them before building UI rows. Inspecting a nation never changes the map's viewing civilization or grants gameplay authority.

## Verification

Wide (960-pixel) and narrow (420-pixel) Scene2D/GPU renders were reviewed for composition, hierarchy and HUD material continuity. Both are explicitly labeled test fixtures, not live-match evidence. The local automated suite passed; interaction verification in the installed live panel remains pending.
