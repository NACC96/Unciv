package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord

/**
 * Snapshot publication contract (AI_AGENT_PLAN.md §9, §11.1).
 *
 * The coordinator serializes its COMMITTED game (off the GL thread); the
 * desktop host posts the payload to the presentation layer, which installs
 * it — swapping the rendered game and view model, rebinding the map, and
 * re-resolving selections by stable IDs. Removed entities resolve to null
 * so the UI can clear selections instead of holding orphans.
 */
class PublicationPayload(
    var serializedGame: String = "",
    var revision: Int = 0,
    var turnSerial: Int = 0,
    var phase: TurnPhase = TurnPhase.READY_TO_START,
    var currentCivId: String? = null,
    var viewerCivId: String = "",
    /** Last-known UI selection handles, re-resolved against the snapshot. */
    var selectedUnitId: Int? = null,
    var selectedCityId: String? = null,
    var selectedTile: HexCoord? = null
)

/** Resolution outcome for one selection handle against a new snapshot. */
class ResolvedSelection(
    val unitId: Int?,
    val cityId: String?,
    val tile: HexCoord?
)

object AgentSnapshotPublication {

    /** Serializes the committed game plus publication metadata, coordinator-side. */
    fun buildPayload(
        coordinator: MatchCoordinator,
        viewerCivId: String,
        selectedUnitId: Int? = null,
        selectedCityId: String? = null,
        selectedTile: HexCoord? = null
    ): PublicationPayload {
        val serialized = coordinator.serializeCommittedGameForPublication()
        return PublicationPayload(
            serializedGame = serialized,
            revision = coordinator.getStateRevision(),
            turnSerial = coordinator.getTurnSerial(),
            phase = coordinator.getTurnPhase(),
            currentCivId = if (coordinator.getTurnPhase() == TurnPhase.AWAITING_DECISIONS)
                coordinator.getCurrentCivId() else null,
            viewerCivId = viewerCivId,
            selectedUnitId = selectedUnitId,
            selectedCityId = selectedCityId,
            selectedTile = selectedTile
        )
    }

    /** Restores a payload into a fresh [GameInfo] with transients bound. */
    fun deserializeGame(payload: PublicationPayload): GameInfo =
        MatchCoordinator.deserializeForPublication(payload.serializedGame)

    /**
     * Re-resolves UI selection handles against the NEW object graph by
     * stable ID. Entities that no longer exist (killed units, razed or
     * conquered cities) resolve to null — never to a same-index lookalike.
     */
    fun resolveSelection(gameInfo: GameInfo, payload: PublicationPayload): ResolvedSelection {
        val unit = payload.selectedUnitId?.let { id ->
            gameInfo.civilizations.asSequence()
                .flatMap { it.units.getCivUnits() }
                .firstOrNull { it.id == id }
        }
        val city = payload.selectedCityId?.let { id ->
            gameInfo.getCities().firstOrNull { it.id == id }
        }
        val tile = payload.selectedTile?.let { pos ->
            gameInfo.tileMap.values.firstOrNull { it.position == pos }
        }
        return ResolvedSelection(
            unitId = unit?.id,
            cityId = city?.id,
            tile = tile?.position
        )
    }
}
