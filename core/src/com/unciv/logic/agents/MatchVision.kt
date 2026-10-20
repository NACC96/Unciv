package com.unciv.logic.agents

/**
 * Match-wide map vision for agents' observations and local human views.
 * Presentation and observation only: game rules (attacks, movement, founding)
 * always use each civilization's real sight.
 */
enum class MatchVision(val label: String) {
    /** Normal fog of war. */
    FOG("Fog of war"),
    /** Every tile's current terrain, borders and cities; units only within real sight. */
    MAP("Map revealed, units fogged"),
    /** Every tile and every unit, live. */
    FULL("Everything");
}
