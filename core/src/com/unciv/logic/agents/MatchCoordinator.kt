package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat

/**
 * Single-writer owner of one agent-enabled match (AI_AGENT_PLAN.md §3.2).
 *
 * All mutations — human commands, agent commands, turn transitions,
 * controller changes — enter through [execute], which is `@Synchronized`:
 * one logical mutation at a time, no interleaving. (The desktop will call
 * this from a dedicated serial executor; the synchronization is the
 * contract, the thread is a deployment detail.)
 *
 * Per operation:
 * 1. Validate authority, phase, pause state, and expected revision.
 * 2. Build an unpublished candidate via serialization roundtrip, with the
 *    [AgentExecutionPolicy] installed before transient restoration. The
 *    candidate never touches `UncivGame.Current` or any visible screen.
 * 3. Execute and validate entirely on the candidate.
 * 4. Serialize a new generation and hand it to [CheckpointWriter]. Only
 *    after durable publication, replace the committed state and acknowledge.
 * 5. On any exception or persistence failure: discard the candidate,
 *    preserve the prior committed state, pause with a reason, and throw
 *    [AgentCommandException]. No success receipt or gameplay effect escapes.
 *
 * Idempotency: `actionId -> (request fingerprint, receipt)` is rehydrated
 * from the active turn's receipts, so recovery from a checkpoint keeps the
 * same deduplication scope. Same ID + same request replays the original
 * receipt (including its recorded result); same ID + different request is
 * [AgentCommandError.ACTION_ID_CONFLICT].
 */
class MatchCoordinator(
    game: GameInfo,
    match: AgentMatchState,
    val writer: CheckpointWriter = InMemoryCheckpointWriter(),
    val policy: AgentExecutionPolicy = AgentExecutionPolicy()
) {
    private val json = json()
    private var committedGame: GameInfo
    private var committedMatch: AgentMatchState
    private val actionReceipts = HashMap<String, ActionReceipt>()
    private var mutationInFlight: String? = null

    init {
        match.validateFormat()
        // Deep-copy inputs so the caller retains no alias into committed state.
        committedGame = deserializeGame(json.toJson(game))
        committedMatch = match.deepCopy()
        rebuildHotIndex()
    }

    //region Reads (no mutation)

    fun getStateRevision(): Int = committedMatch.stateRevision
    fun getTurnPhase(): TurnPhase = committedMatch.turnPhase
    fun isPaused(): Boolean = committedMatch.matchPaused
    fun getPausedReason(): String? = committedMatch.matchPausedReason

    /** Position of a unit in COMMITTED state, or null if absent/destroyed. */
    fun getUnitPosition(civId: String, unitId: Int): HexCoord? {
        val unit = committedGame.getCivilizationOrNull(civId)
            ?.units?.getCivUnits()?.firstOrNull { it.id == unitId }
            ?: return null
        return if (unit.isDestroyed) null else unit.getTile().position
    }

    /** (automated flag, action) of a unit in COMMITTED state; nulls when absent. */
    fun getUnitOrder(civId: String, unitId: Int): Pair<Boolean, String?> {
        val unit = committedGame.getCivilizationOrNull(civId)
            ?.units?.getCivUnits()?.firstOrNull { it.id == unitId }
            ?: return false to null
        return unit.isAutomated() to unit.action
    }

    fun getNotes(civId: String): Pair<String, Int> {
        requireController(civId)
        return (committedMatch.privateNotes[civId] ?: "") to
            (committedMatch.notesRevisions[civId] ?: 0)
    }

    /** Committed pending decisions for one civ (copies; mutating them affects nothing). */
    fun getPendingDecisions(civId: String): List<PendingDecision> {
        requireController(civId)
        return committedMatch.pendingDecisions.filter { it.owningCivId == civId }.map { it.copy() }
    }

    /** Committed receipt by action ID (active-turn scope), for result lookup. */
    fun getReceipt(actionId: String): ActionReceipt? {
        return actionReceipts[actionId]?.copy()
    }

    /** Single-unit inspection with visibility policy (null = hidden/nonexistent). */
    fun inspectUnit(actorCivId: String, unitId: Int): ObservedUnit? {
        requireController(actorCivId)
        return buildUnitInspection(committedGame, actorCivId, unitId)
    }

    /** Owner civ ID of a city in COMMITTED state, null when the city is gone. */
    fun getCityOwner(cityId: String): String? =
        committedGame.getCities().firstOrNull { it.id == cityId }?.civ?.civID

    fun isCityPuppet(cityId: String): Boolean =
        committedGame.getCities().firstOrNull { it.id == cityId }?.isPuppet == true

    fun isCityBeingRazed(cityId: String): Boolean =
        committedGame.getCities().firstOrNull { it.id == cityId }?.isBeingRazed == true

    /** Diplomatic status name toward another civ, null without contact. */
    fun getDiplomaticStatus(civId: String, otherCivId: String): String? {
        requireController(civId)
        val civ = committedGame.getCivilizationOrNull(civId) ?: return null
        return civ.getDiplomacyManager(otherCivId)?.diplomaticStatus?.name
    }

    /** Civ IDs with a queued proposal awaiting this civ's answer. */
    fun getIncomingTradeRequests(civId: String): List<String> {
        requireController(civId)
        return committedGame.getCivilizationOrNull(civId)
            ?.tradeRequests?.map { it.requestingCiv } ?: emptyList()
    }

    fun getGold(civId: String): Int {
        requireController(civId)
        return committedGame.getCivilizationOrNull(civId)?.gold ?: 0
    }

    /**
     * Permitted overview for one civ (game.observe). Pure read of committed
     * state: allowed in any phase, never mutates, never touches candidates.
     */
    fun observe(actorCivId: String): ObservationDto {
        requireController(actorCivId)
        return buildObservation(committedGame, committedMatch, actorCivId)
    }

    /** Gated per-entity actions (game.legal_actions). Pure read, same guarantees. */
    fun legalActions(actorCivId: String, unitId: Int? = null, cityId: String? = null): LegalActionsDto {
        requireController(actorCivId)
        return buildLegalActions(committedGame, actorCivId, unitId, cityId)
    }

    /** Static rules summary (game.rules). Safe to cache by ruleset identity. */
    fun rules(): RulesSummaryDto = buildRulesSummary(committedGame)

    /** Adopted policy names in COMMITTED state. */
    fun getAdoptedPolicies(civId: String): List<String> {
        requireController(civId)
        return committedGame.getCivilizationOrNull(civId)
            ?.policies?.getAdoptedPolicies()?.toList() ?: emptyList()
    }

    /** Researched technology names in COMMITTED state. */
    fun getResearchedTechs(civId: String): List<String> {
        requireController(civId)
        return committedGame.getCivilizationOrNull(civId)
            ?.tech?.techsResearched?.toList() ?: emptyList()
    }

    /** Owner civ ID of a map tile in COMMITTED state, null when unowned/absent. */
    fun getTileOwnerCiv(x: Int, y: Int): String? {
        return try {
            committedGame.tileMap[x, y].getOwner()?.civID
        } catch (ex: Exception) {
            null
        }
    }

    /** Worked tile positions of a city in COMMITTED state. */
    fun getWorkedTiles(cityId: String): List<HexCoord> {
        return committedGame.getCities().firstOrNull { it.id == cityId }
            ?.getWorkedTiles()?.map { it.position }?.toList() ?: emptyList()
    }

    /** Achieved victory type in COMMITTED state, null while undecided. */
    fun getVictoryType(): String? = committedGame.victoryData?.victoryType

    /** Whether two civs currently share open borders. */
    fun hasOpenBorders(civId: String, otherCivId: String): Boolean {
        val civ = committedGame.getCivilizationOrNull(civId) ?: return false
        return civ.getDiplomacyManager(otherCivId)?.hasOpenBorders == true
    }

    /** Improvement name on a tile in COMMITTED state, null when none. */
    fun getTileImprovement(x: Int, y: Int): String? {
        return try {
            committedGame.tileMap[x, y].improvement
        } catch (ex: Exception) {
            null
        }
    }

    /** Whether a tile's improvement/road is pillaged in COMMITTED state. */
    fun isTilePillaged(x: Int, y: Int): Boolean {
        return try {
            committedGame.tileMap[x, y].isPillaged()
        } catch (ex: Exception) {
            false
        }
    }

    /** Improvement under construction on a tile, null when idle. */
    fun getImprovementInProgress(x: Int, y: Int): String? {
        return try {
            committedGame.tileMap[x, y].improvementInProgress
        } catch (ex: Exception) {
            null
        }
    }

    /** Unit health in COMMITTED state, null when absent/destroyed. */
    fun getUnitHealth(civId: String, unitId: Int): Int? {
        val unit = committedGame.getCivilizationOrNull(civId)
            ?.units?.getCivUnits()?.firstOrNull { it.id == unitId }
            ?: return null
        return if (unit.isDestroyed) null else unit.health
    }

    /** City health in COMMITTED state, null when the city is gone. */
    fun getCityHealth(cityId: String): Int? =
        committedGame.getCities().firstOrNull { it.id == cityId }?.health

    /** Civ ID whose turn is currently awaiting decisions (empty unless AWAITING). */
    fun getCurrentCivId(): String = committedGame.currentPlayer

    fun getGameTurns(): Int = committedGame.turns
    fun getTurnSerial(): Int = committedMatch.turnSerial

    //endregion
    //region Execution

    @Synchronized
    fun execute(command: AgentCommand): CommandResult {
        if (committedMatch.matchPaused)
            throw AgentCommandException(
                AgentCommandError.MATCH_PAUSED,
                "Match is paused: ${committedMatch.matchPausedReason ?: "no reason recorded"}"
            )
        val controller = requireController(command.actorCivId)
        if (controller.kind != ControllerKind.HUMAN && controller.kind != ControllerKind.EXTERNAL_AGENT)
            throw AgentCommandException(
                AgentCommandError.AUTHORITY_REVOKED,
                "Civ ${command.actorCivId} is ${controller.kind} and takes no coordinator commands"
            )
        if (committedMatch.turnPhase != TurnPhase.AWAITING_DECISIONS)
            throw AgentCommandException(
                AgentCommandError.NOT_YOUR_TURN,
                "Commands require phase AWAITING_DECISIONS, current is ${committedMatch.turnPhase}"
            )
        if (mutationInFlight != null)
            throw AgentCommandException(
                AgentCommandError.MUTATION_IN_PROGRESS,
                "Mutation $mutationInFlight is still in flight"
            )

        return when (command) {
            is UpdateNotesCommand -> executeNotes(command)
            is MoveUnitCommand -> executeMove(command)
            is EndTurnCommand -> executeEndTurn(command)
            is SelectResearchCommand -> executeResearch(command)
            is CityProductionCommand -> executeProduction(command)
            is FoundCityCommand -> executeFound(command)
            is UnitOrderCommand -> executeOrder(command)
            is AttackCommand -> executeAttack(command)
            is ResolveConquestCommand -> executeResolveConquest(command)
            is DeclareWarCommand -> executeDeclareWar(command)
            is MakePeaceCommand -> executeMakePeace(command)
            is ProposeTradeCommand -> executeProposeTrade(command)
            is RespondTradeCommand -> executeRespondTrade(command)
            is AdoptPolicyCommand -> executeAdoptPolicy(command)
            is CityCitizensCommand -> executeCitizens(command)
            is BuyTileCommand -> executeBuyTile(command)
            is BuildImprovementCommand -> executeBuildImprovement(command)
            is PromoteUnitCommand -> executePromote(command)
            is UpgradeUnitCommand -> executeUpgrade(command)
            is GreatPersonCommand -> executeGreatPerson(command)
            is GiftGoldToCityStateCommand -> executeGiftGold(command)
            is PillageCommand -> executePillage(command)
            is RepairCommand -> executeRepair(command)
            is FriendshipCommand -> executeFriendship(command)
            is SetControllerCommand -> executeSetController(command)
        }
    }

    private fun executeNotes(command: UpdateNotesCommand): NotesUpdatedResult {
        val controller = requireController(command.actorCivId)
        if (controller.kind != ControllerKind.EXTERNAL_AGENT && controller.kind != ControllerKind.HUMAN)
            throw AgentCommandException(AgentCommandError.AUTHORITY_REVOKED, "No notes for $controller.kind")
        if (command.notes.toByteArray(Charsets.UTF_8).size > 16 * 1024)
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Notes exceed 16 KiB UTF-8")

        val candidate = committedMatch.deepCopy()
        val current = candidate.notesRevisions[command.actorCivId] ?: 0
        if (command.expectedNotesRevision != current)
            throw AgentCommandException(
                AgentCommandError.STALE_STATE,
                "Notes changed; re-read (expected $current)"
            )
        candidate.notesRevisions[command.actorCivId] = current + 1
        candidate.privateNotes[command.actorCivId] = command.notes

        // Notes still commit through the checkpoint: game snapshot and notes
        // must always restore as a pair, never mixed across generations.
        commit(candidateGame = null, candidateMatch = candidate)
        return NotesUpdatedResult(command.actorCivId, current + 1)
    }

    /**
     * Shared gameplay-mutation path: duplicate/revision gates, unpublished
     * candidate, [apply] on the candidate returning the sanitized result
     * payload, atomic commit, hot-index registration. Replays return the
     * ORIGINAL receipt (with its recorded result) without touching state.
     */
    private fun runGameplayMutation(
        actorCivId: String,
        actionId: String,
        expectedRevision: Int,
        fingerprint: String,
        apply: (candidateGame: GameInfo, candidateMatch: AgentMatchState) -> String
    ): ActionReceipt {
        actionReceipts[actionId]?.let { prior ->
            if (prior.requestFingerprint == fingerprint) return prior
            throw AgentCommandException(
                AgentCommandError.ACTION_ID_CONFLICT,
                "actionId $actionId reused with a different request"
            )
        }
        if (expectedRevision != committedMatch.stateRevision)
            throw AgentCommandException(
                AgentCommandError.STALE_STATE,
                "Expected revision $expectedRevision but committed is ${committedMatch.stateRevision}",
                currentRevision = committedMatch.stateRevision
            )
        mutationInFlight = actionId
        try {
            val candidateGame = deserializeGame(json.toJson(committedGame))
            val candidateMatch = committedMatch.deepCopy()
            val resultJson = apply(candidateGame, candidateMatch)
            val before = committedMatch.stateRevision
            candidateMatch.stateRevision = before + 1
            val receipt = ActionReceipt(
                actionId = actionId,
                actorCivId = actorCivId,
                timelineId = committedMatch.timelineId,
                requestFingerprint = fingerprint,
                beforeRevision = before,
                afterRevision = before + 1,
                outcome = "applied",
                committedEventIds = ArrayList(),
                resultJson = resultJson
            )
            candidateMatch.currentTurnReceipts.add(receipt)
            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            actionReceipts[actionId] = receipt
            return receipt
        } finally {
            mutationInFlight = null
        }
    }

    /** Hidden and nonexistent units share one externally safe response. */
    private fun requireOwnUnit(candidateGame: GameInfo, actorCivId: String, unitId: Int): MapUnit {
        val civ = candidateGame.getCivilizationOrNull(actorCivId)
            ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization $actorCivId")
        val unit = civ.units.getCivUnits().firstOrNull { it.id == unitId }
        if (unit == null || unit.owner != actorCivId || unit.isDestroyed)
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such visible unit")
        return unit
    }

    /** Opponent cities must not be enumerable through IDs: one safe response. */
    private fun requireOwnCity(candidateGame: GameInfo, actorCivId: String, cityId: String): City {
        val city = candidateGame.getCities().firstOrNull { it.id == cityId }
        if (city == null || city.civ.civID != actorCivId)
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such city under your control")
        return city
    }

    private fun resolveTile(candidateGame: GameInfo, pos: HexCoord): Tile {
        return try {
            candidateGame.tileMap[pos]
        } catch (ex: Exception) {
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Destination is not a tile of this map")
        }
    }

    private fun executeMove(command: MoveUnitCommand): UnitMovedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.move|${command.unitId}|${command.destination.x},${command.destination.y}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val tile = resolveTile(candidateGame, command.destination)
            val start = unit.getTile().position
            if (start == command.destination)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit is already there")
            if (!unit.hasMovement())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit has no movement left")

            @Suppress("UNUSED_VARIABLE") // policy hook: automation flags are read by rule code from here in W4
            val executionPolicy = policy
            // Like a human map click, the engine walks as far as legally
            // possible within this turn; hidden blockage is resolved by the
            // engine mid-path, never by a global destination oracle.
            // getTileToMoveToThisTurn is the engine's own resolver (far goals
            // stage through the closest reachable step; blocked goals stage
            // through an adjacent tile); multi-turn treks are re-issued per
            // turn, one mutation at a time.
            val step = try {
                unit.movement.getTileToMoveToThisTurn(tile)
            } catch (ex: Exception) {
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION,
                    "Cannot make progress toward destination: ${ex.message}"
                )
            }
            if (step.position == start)
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION, "Cannot make progress toward destination"
                )
            unit.movement.moveToTile(step)
            val finalPosition = unit.getTile().position
            if (finalPosition == start)
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION, "Cannot make progress toward destination"
                )
            "${finalPosition.x},${finalPosition.y}"
        }
        val (x, y) = receipt.resultJson.split(",")
        return UnitMovedResult(command.actorCivId, receipt, command.unitId, HexCoord(x.toInt(), y.toInt()))
    }

    private fun executeResearch(command: SelectResearchCommand): ResearchSelectedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "research.select|${command.techName}|${command.queue}|${command.useFreeTech}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val tech = candidateGame.ruleset.technologies[command.techName]
                ?: throw AgentCommandException(
                    AgentCommandError.INVALID_ARGUMENT, "Unknown technology ${command.techName}"
                )
            if (command.useFreeTech) {
                if (civ.tech.freeTechs <= 0)
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No free technologies available")
                if (!civ.tech.canBeResearched(command.techName))
                    throw AgentCommandException(
                        AgentCommandError.ILLEGAL_ACTION, "${command.techName} cannot be researched now"
                    )
                civ.tech.getFreeTechnology(command.techName)
            } else {
                val path = civ.tech.getRequiredTechsToDestination(tech)
                if (path.isEmpty())
                    throw AgentCommandException(
                        AgentCommandError.ILLEGAL_ACTION, "${command.techName} cannot be researched now"
                    )
                if (command.queue) {
                    for (step in path.map { it.name })
                        if (step !in civ.tech.techsToResearch) civ.tech.techsToResearch.add(step)
                } else {
                    civ.tech.techsToResearch = ArrayList(path.map { it.name })
                }
                civ.tech.updateResearchProgress()
            }
            civ.tech.currentTechnologyName() ?: ""
        }
        val current = receipt.resultJson.ifEmpty { null }
        return ResearchSelectedResult(command.actorCivId, receipt, current)
    }

    private fun executeProduction(command: CityProductionCommand): CityProductionResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "city.produce|${command.cityId}|${command.op}|${command.name}|${command.index}|${command.tilePos}"
        ) { candidateGame, _ ->
            val city = requireOwnCity(candidateGame, command.actorCivId, command.cityId)
            val constructions = city.cityConstructions
            when (command.op) {
                ProductionOp.ADD_TOP, ProductionOp.ADD_BOTTOM -> {
                    val construction = try {
                        constructions.getConstruction(command.name)
                    } catch (ex: Exception) {
                        throw AgentCommandException(
                            AgentCommandError.INVALID_ARGUMENT, "Unknown construction ${command.name}"
                        )
                    }
                    val tile = ((construction as? Building)
                        ?.getImprovementToCreate(city.getRuleset(), city.civ))
                        ?.let {
                            val pos = command.tilePos
                                ?: throw AgentCommandException(
                                    AgentCommandError.INVALID_ARGUMENT,
                                    "${command.name} needs a target tile"
                                )
                            resolveTile(candidateGame, pos)
                        }
                    if (!constructions.canAddToQueue(construction))
                        throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "${command.name} cannot be queued now"
                        )
                    constructions.addToQueue(construction, addToTop = command.op == ProductionOp.ADD_TOP, tile = tile)
                }
                ProductionOp.REMOVE -> {
                    if (command.index !in 0 until constructions.constructionQueue.size)
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Queue index out of range")
                    constructions.removeFromQueue(command.index, automatic = false)
                }
                ProductionOp.RAISE -> {
                    if (command.index !in 0 until constructions.constructionQueue.size)
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Queue index out of range")
                    constructions.raisePriority(command.index)
                }
                ProductionOp.LOWER -> {
                    if (command.index !in 0 until constructions.constructionQueue.size)
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Queue index out of range")
                    constructions.lowerPriority(command.index)
                }
                ProductionOp.PURCHASE -> {
                    val construction = try {
                        constructions.getConstruction(command.name)
                    } catch (ex: Exception) {
                        throw AgentCommandException(
                            AgentCommandError.INVALID_ARGUMENT, "Unknown construction ${command.name}"
                        )
                    } as? INonPerpetualConstruction
                        ?: throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "${command.name} cannot be purchased"
                        )
                    val cost = construction.getStatBuyCost(city, Stat.Gold)
                        ?: throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "${command.name} cannot be purchased"
                        )
                    if (!constructions.isConstructionPurchaseAllowed(construction, Stat.Gold, cost))
                        throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "${command.name} cannot be purchased now"
                        )
                    val tile = (construction as? Building)
                        ?.getImprovementToCreate(city.getRuleset(), city.civ)
                        ?.let { command.tilePos?.let { resolveTile(candidateGame, it) } }
                    if (!constructions.purchaseConstruction(
                            construction, command.index, automatic = true, stat = Stat.Gold, tile = tile
                        )
                    )
                        throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "${command.name} purchase failed"
                        )
                }
            }
            constructions.constructionQueue.joinToString("")
        }
        return CityProductionResult(command.actorCivId, receipt, receipt.resultJson.split(""))
    }

    private fun executeFound(command: FoundCityCommand): CityFoundedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.found|${command.unitId}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val civ = candidateGame.getCivilization(command.actorCivId)
            val tile = unit.getTile()
            val unique = unit.getMatchingUniques(UniqueType.FoundCity).firstOrNull()
                ?: unit.getMatchingUniques(UniqueType.FoundPuppetCity).firstOrNull()
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot found cities")
            if (unique.modifiers.any {
                    it.type?.targetTypes?.contains(UniqueTarget.UnitActionModifier) == true
                        && it.type != UniqueType.UnitActionConsumeUnit
                })
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION, "Complex founding rites are unsupported in v1"
                )
            if (tile.isWater || tile.isImpassible())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot found a city here")
            if (civ.isOneCityChallenger() && civ.hasEverOwnedOriginalCapital)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot found another city")
            if (!unit.hasMovement())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit has no movement left")
            if (!tile.canBeSettled(civ))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Too close to another city")
            // Engine path mirrors UnitActionsFromUniques without its tutorial/
            // render side effects (those belong to snapshot publication).
            // "By consuming this unit" is the standard modifier and maps to
            // consume(); anything else was rejected above.
            val city = civ.addCity(tile.position, unit)
            if (unique.type == UniqueType.FoundPuppetCity) city.isPuppet = true
            unit.consume()
            city.id
        }
        return CityFoundedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeOrder(command: UnitOrderCommand): UnitOrderResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.order|${command.unitId}|${command.order}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            var disbanded = false
            when (command.order) {
                UnitOrder.FORTIFY -> {
                    if (!unit.canFortify() || !unit.hasMovement())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot fortify now")
                    unit.fortify()
                }
                UnitOrder.FORTIFY_UNTIL_HEALED -> {
                    if (unit.isFortifyingUntilHealed() || !unit.canHealInCurrentTile())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot fortify-heal now")
                    unit.fortifyUntilHealed()
                }
                UnitOrder.SLEEP -> {
                    if (unit.isFortified() || unit.canFortify() || unit.isGuarding() || !unit.hasMovement())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot sleep now")
                    unit.action = UnitActionType.Sleep.value
                }
                UnitOrder.SLEEP_UNTIL_HEALED -> {
                    if (unit.isSleepingUntilHealed() || !unit.canHealInCurrentTile())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot sleep-heal now")
                    unit.action = UnitActionType.SleepUntilHealed.value
                }
                UnitOrder.WAKE -> {
                    unit.action = null
                }
                UnitOrder.DISBAND -> {
                    unit.disband()
                    disbanded = true
                }
            }
            "${unit.action ?: ""}|$disbanded"
        }
        val (action, disbanded) = receipt.resultJson.split("|")
        return UnitOrderResult(
            command.actorCivId, receipt,
            action = action.ifEmpty { null }, disbanded = disbanded.toBoolean()
        )
    }

    private fun executeAttack(command: AttackCommand): AttackResult {
        if ((command.attackerUnitId == null) == (command.attackerCityId == null))
            throw AgentCommandException(
                AgentCommandError.INVALID_ARGUMENT, "Exactly one attacker (unit or city) is required"
            )
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.attack|${command.attackerUnitId}|${command.attackerCityId}|${command.target.x},${command.target.y}"
        ) { candidateGame, candidateMatch ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val targetTile = resolveTile(candidateGame, command.target)
            // Safe response whether the target hides units or nothing at all.
            val defenderBefore = Battle.getMapCombatantOfTile(targetTile)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No attackable target there")

            val knownAlerts = civ.popupAlerts
                .filter { it.type == AlertType.CityConquered }.map { it.value }.toSet()

            val attackerAlive: Boolean
            val damage: Battle.DamageDealt
            if (command.attackerUnitId != null) {
                val unit = requireOwnUnit(candidateGame, command.actorCivId, command.attackerUnitId)
                if (!unit.canAttack())
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot attack now")
                val candidate = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
                    .firstOrNull { it.tileToAttack.position == targetTile.position }
                    ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No legal attack on that tile")
                val attacker = MapUnitCombatant(unit)
                if (!Battle.movePreparingAttack(attacker, candidate, tryHealPillage = false))
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot reach attack position")
                damage = Battle.attackOrNuke(attacker, candidate)
                attackerAlive = !unit.isDestroyed
            } else {
                val city = requireOwnCity(candidateGame, command.actorCivId, command.attackerCityId!!)
                if (!city.canBombard())
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City cannot bombard now")
                if (TargetHelper.getBombardableTiles(city).none { it.position == targetTile.position })
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No legal bombardment of that tile")
                damage = Battle.attackOrNuke(
                    CityCombatant(city),
                    AttackableTile(city.getCenterTile(), targetTile, 0f, defenderBefore)
                )
                attackerAlive = candidateGame.getCities().any { it.id == city.id }
            }

            val defenderAlive = when (defenderBefore) {
                is MapUnitCombatant -> !defenderBefore.unit.isDestroyed
                is CityCombatant -> candidateGame.getCities().any { it.id == defenderBefore.city.id }
                else -> true
            }

            // Conquest raises a MANDATORY decision; nothing is auto-selected.
            for (alert in civ.popupAlerts.filter {
                it.type == AlertType.CityConquered && it.value !in knownAlerts
            }) {
                val cityId = alert.value
                val conquered = candidateGame.getCities().firstOrNull { it.id == cityId }
                    ?: continue
                raiseConquestDecision(candidateMatch, civ, conquered)
            }
            "${damage.attackerDealt},${damage.defenderDealt}|$attackerAlive|$defenderAlive"
        }
        val parts = receipt.resultJson.split("|")
        val (attDealt, defDealt) = parts[0].split(",")
        return AttackResult(
            command.actorCivId, receipt,
            parts[1].toBoolean(), parts[2].toBoolean(), attDealt.toInt(), defDealt.toInt()
        )
    }

    /** Computes the UI-equivalent legal dispositions (AlertPopup.addCityConquered). */
    private fun legalDispositions(
        conqueringCiv: Civilization,
        city: City
    ): List<ConquestDisposition> {
        // One-city challengers never reach dispositions: the engine destroys
        // on capture (Battle). Barbarians likewise.
        val options = ArrayList<ConquestDisposition>()
        options.add(ConquestDisposition.PUPPET)
        if (!conqueringCiv.hasUnique(UniqueType.MayNotAnnexCities)) options.add(ConquestDisposition.ANNEX)
        if (city.canBeDestroyed(justCaptured = true)) options.add(ConquestDisposition.RAZE)
        if (city.foundingCivObject != null
            && city.civ != city.foundingCivObject
            && conqueringCiv != city.foundingCivObject
        ) options.add(ConquestDisposition.LIBERATE)
        return options
    }

    private fun raiseConquestDecision(
        candidateMatch: AgentMatchState,
        conqueringCiv: Civilization,
        city: City
    ) {
        val decisionId = "conquest:${city.id}"
        if (candidateMatch.pendingDecisions.any { it.decisionId == decisionId }) return
        val options = legalDispositions(conqueringCiv, city).map {
            hashMapOf("optionId" to it.name, "label" to it.name.lowercase().replaceFirstChar { c -> c.uppercase() })
        }
        candidateMatch.pendingDecisions.add(
            PendingDecision(
                decisionId, "conquest-disposition", conqueringCiv.civID,
                "city:${city.id}", DecisionClassification.MANDATORY, ArrayList(options)
            )
        )
    }

    private fun executeResolveConquest(command: ResolveConquestCommand): ConquestResolvedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "conquest.resolve|${command.decisionId}|${command.disposition}"
        ) { candidateGame, candidateMatch ->
            val decision = candidateMatch.pendingDecisions.firstOrNull { it.decisionId == command.decisionId }
                ?: throw AgentCommandException(
                    AgentCommandError.INVALID_ARGUMENT, "Unknown decision ${command.decisionId}"
                )
            if (decision.owningCivId != command.actorCivId
                || decision.decisionType != "conquest-disposition"
                || decision.classification != DecisionClassification.MANDATORY
            )
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Decision cannot be resolved this way")
            val cityId = decision.sourceRef.removePrefix("city:")
            val city = candidateGame.getCities().firstOrNull { it.id == cityId }
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City no longer available")
            val conqueringCiv = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (city.civ == conqueringCiv)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City is already yours")
            // Revalidate: listing was never a reservation.
            if (command.disposition !in legalDispositions(conqueringCiv, city))
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION, "${command.disposition} is not available for this city"
                )
            when (command.disposition) {
                ConquestDisposition.PUPPET -> city.puppetCity(conqueringCiv)
                ConquestDisposition.ANNEX -> {
                    city.puppetCity(conqueringCiv)
                    city.annexCity()
                }
                ConquestDisposition.RAZE -> {
                    city.puppetCity(conqueringCiv)
                    if (!conqueringCiv.hasUnique(UniqueType.MayNotAnnexCities)) city.annexCity()
                    city.isBeingRazed = true
                }
                ConquestDisposition.LIBERATE -> city.liberateCity(conqueringCiv)
            }
            candidateMatch.pendingDecisions.remove(decision)
            "${city.id}|${command.disposition}"
        }
        val (cityId, disposition) = receipt.resultJson.split("|")
        return ConquestResolvedResult(
            command.actorCivId, receipt, cityId, ConquestDisposition.valueOf(disposition)
        )
    }

    private fun executeDeclareWar(command: DeclareWarCommand): DiplomacyResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.declareWar|${command.targetCivId}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val diplomacy = civ.getDiplomacyManager(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            if (!diplomacy.canDeclareWar())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot declare war now")
            diplomacy.declareWar(DeclareWarReason(WarType.DirectWar))
            diplomacy.diplomaticStatus.name
        }
        return DiplomacyResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeMakePeace(command: MakePeaceCommand): DiplomacyResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.makePeace|${command.targetCivId}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val diplomacy = civ.getDiplomacyManager(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            if (diplomacy.diplomaticStatus != DiplomaticStatus.War)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Not at war")
            // Direct peace without a treaty cooldown (plan §6 diplomacy row):
            // timed treaties arrive through accepted trade, as in human play.
            diplomacy.makePeace()
            diplomacy.diplomaticStatus.name
        }
        return DiplomacyResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun buildTrade(
        candidateGame: GameInfo,
        actor: Civilization,
        command: ProposeTradeCommand
    ): Trade {
        val trade = Trade()
        for (offer in command.ourOffers)
            trade.ourOffers.add(TradeOffer(offer.name, offer.type, offer.amount, candidateGame.speed))
        for (offer in command.theirOffers)
            trade.theirOffers.add(TradeOffer(offer.name, offer.type, offer.amount, candidateGame.speed))
        return trade
    }

    private fun executeProposeTrade(command: ProposeTradeCommand): TradeProposedResult {
        val fingerprint = "diplomacy.proposeTrade|${command.targetCivId}|" +
            command.ourOffers.joinToString(";") { "${it.name}:${it.type}:${it.amount}" } + "|" +
            command.theirOffers.joinToString(";") { "${it.name}:${it.type}:${it.amount}" }
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision, fingerprint
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val target = candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (civ.getDiplomacyManager(command.targetCivId) == null)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            val trade = buildTrade(candidateGame, civ, command)
            if (!TradeEvaluation().isTradeValid(trade, civ, target))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Offer is not valid")
            // Stored recipient-perspective, mirroring TradeView proposals.
            target.tradeRequests.add(TradeRequest(command.actorCivId, trade.reverse()))
            target.tradeRequests.count { it.requestingCiv == command.actorCivId }.toString()
        }
        return TradeProposedResult(command.actorCivId, receipt, receipt.resultJson.toInt())
    }

    private fun executeRespondTrade(command: RespondTradeCommand): TradeRespondedResult {        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.respondTrade|${command.requestingCivId}|${command.accept}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val requester = candidateGame.getCivilizationOrNull(command.requestingCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val request = civ.tradeRequests.firstOrNull { it.requestingCiv == command.requestingCivId }
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "No such trade request")
            if (!command.accept) {
                request.decline(civ)
                civ.tradeRequests.remove(request)
                return@runGameplayMutation "declined"
            }
            // Revalidate immediately before applying: offers expire as the
            // game moves (plan §6.1); never apply a stale proposal.
            if (!TradeEvaluation().isTradeValid(request.trade, civ, requester))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Offer is no longer valid")
            val logic = TradeLogic(civ, requester)
            logic.currentTrade.set(request.trade)
            logic.acceptTrade()
            civ.tradeRequests.remove(request)
            "accepted"
        }
        return TradeRespondedResult(command.actorCivId, receipt, receipt.resultJson == "accepted")
    }

    private fun executeAdoptPolicy(command: AdoptPolicyCommand): PolicyAdoptedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "civics.adopt|${command.policyName}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val policy = candidateGame.ruleset.policies[command.policyName]
                ?: throw AgentCommandException(
                    AgentCommandError.INVALID_ARGUMENT, "Unknown policy ${command.policyName}"
                )
            if (!civ.policies.isAdoptable(policy) || !civ.policies.canAdoptPolicy())
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION, "${command.policyName} cannot be adopted now"
                )
            val before = civ.policies.getAdoptedPolicies().toSet()
            civ.policies.adopt(policy)
            (civ.policies.getAdoptedPolicies().toSet() - before).joinToString("")
        }
        val adopted = receipt.resultJson.split("").filter { it.isNotEmpty() }
        return PolicyAdoptedResult(command.actorCivId, receipt, adopted)
    }

    private inline fun <T> illegalFromEngine(block: () -> T): T {
        try {
            return block()
        } catch (ex: IllegalArgumentException) {
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, ex.message ?: "Illegal city order")
        }
    }

    private fun executeCitizens(command: CityCitizensCommand): CityCitizensResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "city.citizens|${command.cityId}|${command.op}|${command.tilePos}|${command.specialist}|${command.focus}|${command.flag}"
        ) { candidateGame, _ ->
            val city = requireOwnCity(candidateGame, command.actorCivId, command.cityId)
            when (command.op) {
                CitizenOp.WORK_TILE -> {
                    val tile = command.tilePos?.let { resolveTile(candidateGame, it) }
                        ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Tile required")
                    if (city.population.getFreePopulation() <= 0)
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No free population")
                    illegalFromEngine { city.workTile(tile) }
                    city.cityStats.update()
                }
                CitizenOp.STOP_TILE -> {
                    val tile = command.tilePos?.let { resolveTile(candidateGame, it) }
                        ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Tile required")
                    illegalFromEngine { city.stopWorkingTile(tile) }
                    city.cityStats.update()
                }
                CitizenOp.LOCK_TILE -> {
                    val tile = command.tilePos?.let { resolveTile(candidateGame, it) }
                        ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Tile required")
                    illegalFromEngine { city.lockTile(tile) }
                }
                CitizenOp.UNLOCK_TILE -> {
                    val tile = command.tilePos?.let { resolveTile(candidateGame, it) }
                        ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Tile required")
                    illegalFromEngine { city.unlockTile(tile) }
                }
                CitizenOp.ASSIGN_SPECIALIST -> {
                    if (command.specialist.isEmpty())
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Specialist required")
                    if (city.isPuppet)
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Puppet cities assign no specialists")
                    if (city.population.getFreePopulation() <= 0)
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No free population")
                    city.manualSpecialists = true
                    illegalFromEngine {
                        city.population.specialistAllocations.add(command.specialist, 1)
                        if ((city.population.specialistAllocations[command.specialist] ?: 0) >
                            (city.population.getMaxSpecialists()[command.specialist] ?: 0)
                        ) {
                            city.population.specialistAllocations.add(command.specialist, -1)
                            throw IllegalArgumentException("No ${command.specialist} slots available")
                        }
                    }
                    city.cityStats.update()
                }
                CitizenOp.UNASSIGN_SPECIALIST -> {
                    if (command.specialist.isEmpty())
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Specialist required")
                    if ((city.population.specialistAllocations[command.specialist] ?: 0) <= 0)
                        throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "No ${command.specialist} assigned"
                        )
                    city.manualSpecialists = true
                    city.population.specialistAllocations.add(command.specialist, -1)
                    city.cityStats.update()
                }
                CitizenOp.SET_FOCUS -> {
                    val focus = try {
                        CityFocus.valueOf(command.focus)
                    } catch (ex: Exception) {
                        throw AgentCommandException(
                            AgentCommandError.INVALID_ARGUMENT, "Unknown focus ${command.focus}"
                        )
                    }
                    city.setCityFocus(focus)
                    city.reassignPopulation()
                }
                CitizenOp.SET_MANUAL_SPECIALISTS -> {
                    city.manualSpecialists = command.flag
                    city.reassignPopulation()
                }
                CitizenOp.SET_AVOID_GROWTH -> {
                    city.avoidGrowth = command.flag
                    city.reassignPopulation()
                }
                CitizenOp.REASSIGN -> city.reassignPopulation()
            }
            city.population.getFreePopulation().toString()
        }
        return CityCitizensResult(command.actorCivId, receipt, receipt.resultJson.toInt())
    }

    private fun executeBuyTile(command: BuyTileCommand): TileBoughtResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "city.buyTile|${command.cityId}|${command.tilePos.x},${command.tilePos.y}"
        ) { candidateGame, _ ->
            val city = requireOwnCity(candidateGame, command.actorCivId, command.cityId)
            val tile = resolveTile(candidateGame, command.tilePos)
            if (!city.expansion.canBuyTile(tile))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Tile cannot be bought")
            val cost = city.expansion.getGoldCostOfTile(tile)
            if (city.civ.gold < cost)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Insufficient gold")
            illegalFromEngine { city.expansion.buyTile(tile) }
            "${command.tilePos.x},${command.tilePos.y}"
        }
        val (x, y) = receipt.resultJson.split(",")
        return TileBoughtResult(command.actorCivId, receipt, HexCoord(x.toInt(), y.toInt()))
    }

    private fun executeBuildImprovement(command: BuildImprovementCommand): ImprovementStartedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.build|${command.unitId}|${command.improvementName}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val improvement = candidateGame.ruleset.tileImprovements[command.improvementName]
                ?: throw AgentCommandException(
                    AgentCommandError.INVALID_ARGUMENT, "Unknown improvement ${command.improvementName}"
                )
            if (!unit.canBuildImprovement(improvement))
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION, "${command.improvementName} cannot be built here now"
                )
            val tile = unit.getTile()
            tile.startWorkingOnImprovement(improvement, unit.civ, unit)
            improvement.getTurnsToBuild(unit.civ, unit).toString()
        }
        return ImprovementStartedResult(command.actorCivId, receipt, receipt.resultJson.toInt())
    }

    private fun executePromote(command: PromoteUnitCommand): UnitPromotedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.promote|${command.unitId}|${command.promotionName}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            if (!unit.promotions.canBePromoted())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot be promoted now")
            if (unit.promotions.getAvailablePromotions().none { it.name == command.promotionName })
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION, "${command.promotionName} is not available"
                )
            unit.promotions.addPromotion(command.promotionName)
            command.promotionName
        }
        return UnitPromotedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeUpgrade(command: UpgradeUnitCommand): UnitUpgradedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.upgrade|${command.unitId}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val civ = candidateGame.getCivilization(command.actorCivId)
            val upgradesTo = unit.baseUnit.upgradesTo
            if (upgradesTo.isNullOrEmpty())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit has no upgrade path")
            val target = civ.getEquivalentUnit(upgradesTo)
            if (!unit.upgrade.canUpgrade(target))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Upgrade requirements unmet")
            val cost = unit.upgrade.getCostOfUpgrade(target)
            if (civ.gold < cost)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Insufficient gold")
            unit.upgrade.performUpgrade(target, isFree = false)
            target.name
        }
        return UnitUpgradedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeGreatPerson(command: GreatPersonCommand): GreatPersonResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.great|${command.unitId}|${command.action}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            if (!unit.hasMovement())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit has no movement left")
            val civ = candidateGame.getCivilization(command.actorCivId)
            val tile = unit.getTile()
            when (command.action) {
                GreatPersonAction.HURRY_RESEARCH -> {
                    if (!unit.getMatchingUniques(UniqueType.CanHurryResearch).any())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot hurry research")
                    val current = civ.tech.currentTechnology()
                        ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No research in progress")
                    if (current.hasUnique(UniqueType.CannotBeHurried))
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Research cannot be hurried")
                    civ.tech.addScience(civ.tech.getScienceFromGreatScientist())
                    unit.consume()
                    current.name
                }
                GreatPersonAction.HURRY_POLICY -> {
                    if (!unit.getMatchingUniques(UniqueType.CanHurryPolicy).any())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot hurry policies")
                    civ.policies.addCulture(civ.policies.getCultureFromGreatWriter())
                    unit.consume()
                    "culture"
                }
                GreatPersonAction.HURRY_WONDER -> {
                    if (!unit.getMatchingUniques(UniqueType.CanSpeedupWonderConstruction).any())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot hurry wonders")
                    if (!tile.isCityCenter() || !tile.getCity()!!.cityConstructions.isBuildingWonder()
                        || !tile.getCity()!!.cityConstructions.canBeHurried()
                    )
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No hurriable wonder here")
                    tile.getCity()!!.cityConstructions.apply {
                        addProductionPoints(
                            ((300 + 30 * tile.getCity()!!.population.population) *
                                unit.civ.gameInfo.speed.productionCostModifier).toInt()
                        )
                        constructIfEnough()
                    }
                    unit.consume()
                    "wonder"
                }
                GreatPersonAction.HURRY_BUILDING -> {
                    if (!unit.getMatchingUniques(UniqueType.CanSpeedupConstruction).any())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot hurry construction")
                    if (!tile.isCityCenter()) throw AgentCommandException(
                        AgentCommandError.ILLEGAL_ACTION, "Unit must be in a city"
                    )
                    val constructions = tile.getCity()!!.cityConstructions
                    val current = constructions.getCurrentConstruction()
                    if (current !is Building || !constructions.canBeHurried())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No hurriable building here")
                    val points = minOf(
                        (300 + 30 * tile.getCity()!!.population.population) *
                            unit.civ.gameInfo.speed.productionCostModifier,
                        constructions.getRemainingWork(constructions.currentConstructionName()).toFloat() - 1
                    ).toInt()
                    if (points <= 0) throw AgentCommandException(
                        AgentCommandError.ILLEGAL_ACTION, "Nothing worth hurrying here"
                    )
                    constructions.apply {
                        addProductionPoints(points)
                        constructIfEnough()
                    }
                    unit.consume()
                    "building"
                }
                GreatPersonAction.TRADE_MISSION -> {
                    val tradeUnique = unit.getMatchingUniques(
                        UniqueType.CanTradeWithCityStateForGoldAndInfluence
                    ).firstOrNull()
                        ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot trade-mission")
                    val host = tile.owningCity?.civ
                    if (host == null || !host.isCityState || host == civ || host.isAtWarWith(civ))
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No friendly city-state here")
                    var gold = (350 + 50 * civ.getEraNumber()) * civ.gameInfo.speed.goldCostModifier
                    for (goldUnique in unit.getMatchingUniques(
                        UniqueType.PercentGoldFromTradeMissions, checkCivInfoUniques = true
                    )) gold *= 1 + goldUnique.params[0].toFloat() / 100f
                    civ.addGold(gold.toInt())
                    host.getDiplomacyManager(civ)
                        ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
                    host.getDiplomacyManager(civ)!!.addInfluence(tradeUnique.params[0].toFloat())
                    unit.consume()
                    "trade"
                }
            }
        }
        return GreatPersonResult(command.actorCivId, receipt, command.action)
    }

    private fun executeGiftGold(command: GiftGoldToCityStateCommand): GoldGiftedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "citystate.giftGold|${command.cityStateId}|${command.amount}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val cityState = candidateGame.getCivilizationOrNull(command.cityStateId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (!cityState.isCityState)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Target is not a city-state")
            if (command.amount <= 0)
                throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Amount must be positive")
            if (civ.gold < command.amount)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Insufficient gold")
            val influence = cityState.cityStateFunctions.influenceGainedByGift(civ, command.amount)
            cityState.cityStateFunctions.receiveGoldGift(civ, command.amount)
            influence.toString()
        }
        return GoldGiftedResult(command.actorCivId, receipt, receipt.resultJson.toInt())
    }

    private fun executePillage(command: PillageCommand): PillagedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.pillage|${command.unitId}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val tile = unit.getTile()
            // Gates mirror UnitActionsPillage.getPillageAction/canPillage.
            if (!unit.hasMovement())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit has no movement left")
            if (unit.isCivilian())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Civilians cannot pillage")
            val improvementName = tile.getImprovementToPillageName()
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Nothing to pillage here")
            if (tile.getOwner() == unit.civ)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot pillage own lands")
            if (unit.isTransported)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Transported units cannot pillage")
            if (!tile.canPillageTile())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Tile cannot be pillaged")
            if (unit.hasUnique(UniqueType.CannotPillage, checkCivInfoUniques = true))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot pillage")
            val owner = tile.getOwner()
            if (owner != null && !unit.civ.isAtWarWith(owner))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Pillaging neutrals is an act of war")
            owner?.addNotification(
                "An enemy [${unit.baseUnit.name}] has pillaged our [$improvementName]",
                tile.position,
                com.unciv.logic.civilization.NotificationCategory.War,
                "ImprovementIcons/$improvementName",
                com.unciv.logic.civilization.NotificationIcon.War,
                unit.baseUnit.name
            )
            com.unciv.logic.map.tile.TilePillage.pillageLooting(tile, unit)
            val pillagingImprovement = tile.canPillageTileImprovement()
            tile.setPillaged()
            if (tile.resource != null) tile.getOwner()?.cache?.updateCivResources()
            if (!unit.hasUnique(UniqueType.NoMovementToPillage, checkCivInfoUniques = true))
                unit.useMovementPoints(1f)
            if (pillagingImprovement) {
                // Only improvements (not roads) heal the pillager.
                var healAmount = 25f
                for (unique in unit.getMatchingUniques(
                    UniqueType.PercentHealthFromPillaging, checkCivInfoUniques = true
                )) healAmount *= 1 + unique.params[0].toFloat() / 100f
                unit.healBy(healAmount.toInt())
            }
            if (tile.getImprovementToPillage()?.hasUnique(UniqueType.DestroyedWhenPillaged) == true)
                tile.removeImprovement()
            improvementName
        }
        return PillagedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeRepair(command: RepairCommand): RepairStartedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.repair|${command.unitId}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val tile = unit.getTile()
            // Gates mirror UnitActionsFromUniques.getRepairAction.
            if (!tile.ruleset.tileImprovements.containsKey(com.unciv.Constants.repair))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Repair unavailable in this ruleset")
            if (!unit.cache.hasUniqueToBuildImprovements)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot repair")
            if (unit.isEmbarked() || tile.isCityCenter() || !tile.isPillaged())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Nothing to repair here")
            if (!unit.hasMovement() || tile.improvementInProgress == com.unciv.Constants.repair)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Repair cannot start now")
            if (tile.isEnemyTerritory(unit.civ))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot repair in enemy territory")
            val repairTurns = minOf(
                tile.ruleset.tileImprovements[com.unciv.Constants.repair]!!.getTurnsToBuild(unit.civ, unit),
                tile.getImprovementToRepair()!!.getTurnsToBuild(unit.civ, unit)
            )
            tile.queueImprovement(com.unciv.Constants.repair, repairTurns)
            unit.action = null
            repairTurns.toString()
        }
        return RepairStartedResult(command.actorCivId, receipt, receipt.resultJson.toInt())
    }

    private fun executeFriendship(command: FriendshipCommand): DiplomacyResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.friendship|${command.targetCivId}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val diplomacy = civ.getDiplomacyManager(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            if (civ.isDefeated())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Defeated civilizations sign nothing")
            if (diplomacy.diplomaticStatus == DiplomaticStatus.War)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot befriend wartime enemies")
            if (diplomacy.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Already friends")
            diplomacy.signDeclarationOfFriendship()
            diplomacy.diplomaticStatus.name
        }
        return DiplomacyResult(command.actorCivId, receipt, receipt.resultJson)
    }

    /**
     * Controller reassignment through the commit discipline. Game state is
     * untouched; only the controller map changes (plus the revision bump
     * that forces every observer to re-read). Host-side revocation and epoch
     * bumps happen BEFORE this commits.
     */
    private fun executeSetController(command: SetControllerCommand): ControllerSetResult {
        if (command.kind == ControllerKind.EXTERNAL_AGENT && command.profile == null)
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "External controllers need a profile")
        if (command.kind != ControllerKind.EXTERNAL_AGENT && command.profile != null)
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Only external controllers take profiles")
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "controller.set|${command.targetCivId}|${command.kind}|${command.profile?.profileRevision ?: "-"}"
        ) { candidateGame, candidateMatch ->
            candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            candidateMatch.controllers[command.targetCivId] =
                CivilizationController(command.targetCivId, command.kind, command.profile?.copy())
            command.kind.name
        }
        return ControllerSetResult(command.actorCivId, receipt, command.targetCivId, command.kind)
    }

    //endregion
    //region Turn lifecycle

    /**
     * Opens the match: applies start-turn effects exactly once for the first
     * eligible commandable civ and enters AWAITING_DECISIONS. Requires phase
     * READY_TO_START; already-decided games (victory present) COMPLETE
     * immediately with no new work scheduled.
     */
    @Synchronized
    fun beginAwaitingTurn(): TurnEndedResult {
        if (committedMatch.matchPaused)
            throw AgentCommandException(AgentCommandError.MATCH_PAUSED, "Match is paused")
        if (committedMatch.turnPhase != TurnPhase.READY_TO_START)
            throw AgentCommandException(
                AgentCommandError.ILLEGAL_ACTION,
                "beginAwaitingTurn requires phase READY_TO_START, current is ${committedMatch.turnPhase}"
            )

        val candidateGame = deserializeGame(json.toJson(committedGame))
        val candidateMatch = committedMatch.deepCopy()
        if (candidateGame.victoryData != null) {
            candidateMatch.turnPhase = TurnPhase.COMPLETED
            candidateMatch.stateRevision = committedMatch.stateRevision + 1
            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            val receipt = ActionReceipt(
                actionId = "internal:begin-t${candidateMatch.turnSerial}-completed",
                actorCivId = "",
                timelineId = candidateMatch.timelineId,
                requestFingerprint = "internal:begin",
                beforeRevision = committedMatch.stateRevision,
                afterRevision = candidateMatch.stateRevision,
                outcome = "applied",
                committedEventIds = ArrayList(),
                resultJson = ""
            )
            return TurnEndedResult("", receipt, null, false, candidateGame.turns)
        }

        val anchor = candidateGame.civilizations.indexOfFirst { it.civID == candidateGame.currentPlayer }
        val startAfter = if (anchor >= 0) anchor - 1 else -1
        val next = rotateToNextCommandable(candidateGame, candidateMatch, startAfter)
        if (next == null) {
            pauseCommitted("No eligible civilization to open the match")
            throw AgentCommandException(
                AgentCommandError.INTERNAL_ERROR, "No eligible civilization to open the match"
            )
        }
        mutationInFlight = "internal:begin"
        try {
            beginTurnOnCandidate(candidateGame, candidateMatch, next.civID)
            val receipt = candidateMatch.currentTurnReceipts.last()
            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            actionReceipts.clear()
            actionReceipts[receipt.actionId] = receipt
            return TurnEndedResult("", receipt, next.civID, next.wrappedAround, candidateGame.turns)
        } finally {
            mutationInFlight = null
        }
    }

    private fun pauseCommitted(reason: String) {
        committedMatch.matchPaused = true
        committedMatch.matchPausedReason = reason
    }

    private fun executeEndTurn(command: EndTurnCommand): TurnEndedResult {
        val fingerprint = "end-turn"
        actionReceipts[command.actionId]?.let { prior ->
            if (prior.requestFingerprint == fingerprint) {
                val next = if (committedMatch.turnPhase == TurnPhase.AWAITING_DECISIONS)
                    committedMatch.currentTurnReceipts.firstOrNull()?.actorCivId else null
                return TurnEndedResult(command.actorCivId, prior, next, false, committedGame.turns)
            }
            throw AgentCommandException(
                AgentCommandError.ACTION_ID_CONFLICT,
                "actionId ${command.actionId} reused with a different request"
            )
        }
        if (command.expectedRevision != committedMatch.stateRevision)
            throw AgentCommandException(
                AgentCommandError.STALE_STATE,
                "Expected revision ${command.expectedRevision} but committed is ${committedMatch.stateRevision}",
                currentRevision = committedMatch.stateRevision
            )

        val blocking = committedMatch.pendingDecisions
            .filter { it.owningCivId == command.actorCivId && it.classification == DecisionClassification.MANDATORY }
        if (blocking.isNotEmpty())
            throw AgentCommandException(
                AgentCommandError.DECISIONS_PENDING,
                "Resolve mandatory decisions first: ${blocking.joinToString(",") { it.decisionId }}. " +
                    "Missing decisions are never chosen automatically."
            )

        mutationInFlight = command.actionId
        try {
            val candidateGame = deserializeGame(json.toJson(committedGame))
            val candidateMatch = committedMatch.deepCopy()
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(
                    AgentCommandError.INVALID_ARGUMENT, "Unknown civilization ${command.actorCivId}"
                )

            // End-turn effects exactly once for the acting civ. Coordinator-routed
            // civs (human and external) always suppress unsolicited automation.
            TurnManager(civ).endTurn(null, suppressAutomation = true)

            val before = committedMatch.stateRevision
            candidateMatch.stateRevision = before + 1
            val endReceipt = ActionReceipt(
                actionId = command.actionId,
                actorCivId = command.actorCivId,
                timelineId = committedMatch.timelineId,
                requestFingerprint = fingerprint,
                beforeRevision = before,
                afterRevision = before + 1,
                outcome = "applied",
                committedEventIds = ArrayList(),
                resultJson = ""
            )
            candidateMatch.currentTurnReceipts.add(endReceipt)

            if (candidateGame.victoryData != null) {
                // Victory achieved: stop scheduling new work, keep the ending
                // checkpoint inspectable. Existing victory UI reads victoryData.
                candidateMatch.turnPhase = TurnPhase.COMPLETED
                candidateMatch.endTurnsCommitted++
                commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
                actionReceipts[command.actionId] = endReceipt
                return TurnEndedResult(command.actorCivId, endReceipt, null, false, candidateGame.turns)
            }

            val fromIndex = candidateGame.civilizations.indexOfFirst { it.civID == command.actorCivId }
            val next = rotateToNextCommandable(candidateGame, candidateMatch, fromIndex)
            if (next == null) {
                candidateMatch.turnPhase = TurnPhase.COMPLETED
                candidateMatch.endTurnsCommitted++
                candidateMatch.matchPaused = true
                candidateMatch.matchPausedReason = "No eligible civilization to continue the match"
                commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
                actionReceipts[command.actionId] = endReceipt
                return TurnEndedResult(command.actorCivId, endReceipt, null, false, candidateGame.turns)
            }
            beginTurnOnCandidate(candidateGame, candidateMatch, next.civID)
            candidateMatch.endTurnsCommitted++
            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            actionReceipts.clear()
            actionReceipts[command.actionId] = endReceipt
            actionReceipts[candidateMatch.currentTurnReceipts.last().actionId] =
                candidateMatch.currentTurnReceipts.last()
            return TurnEndedResult(
                command.actorCivId, endReceipt, next.civID, next.wrappedAround, candidateGame.turns
            )
        } finally {
            mutationInFlight = null
        }
    }

    private class RotationResult(val civID: String, val wrappedAround: Boolean)

    /**
     * Advances [fromIndex] through the civilization list, running built-in
     * automation inline as one indivisible turn per AI civ (defeated civs and
     * spectators skipped), until the next HUMAN/EXTERNAL_AGENT civ.
     * Returns null when no eligible civ exists (caller terminates or pauses
     * with a reason — never spins). Caps steps well above one full round.
     */
    private fun rotateToNextCommandable(
        candidateGame: GameInfo,
        candidateMatch: AgentMatchState,
        fromIndex: Int
    ): RotationResult? {
        val civs = candidateGame.civilizations
        if (civs.isEmpty()) return null
        var index = fromIndex
        var wrapped = false
        val cap = civs.size * 2 + 8
        repeat(cap) {
            // A wrap crosses the list end (new index at/below the previous
            // one). Landing on index 0 from the virtual pre-start position
            // (fromIndex < 0) is initial placement, not a round rollover.
            val previous = index
            index = (index + 1) % civs.size
            if (index <= previous && previous >= 0) {
                wrapped = true
                // Round rollover, mirroring GameInfo.nextTurn order minus the
                // ranking-history snapshot (deferred to the victory pass).
                candidateGame.turns++
            }
            val civ = civs[index]
            if (civ.isSpectator() || civ.isDefeated()) return@repeat
            val kind = candidateMatch.controllers[civ.civID]?.kind
                ?: throw AgentCommandException(
                    AgentCommandError.INTERNAL_ERROR, "No controller for civilization ${civ.civID}"
                )
            if (kind == ControllerKind.BUILTIN_AI) {
                // One indivisible automated turn (plan §9): existing automation
                // runs internally, never through provider invocations.
                TurnManager(civ).startTurn(null, suppressAutomation = false)
                TurnManager(civ).automateTurn()
                TurnManager(civ).endTurn(null, suppressAutomation = false)
                if (candidateGame.victoryData != null) return null
                return@repeat
            }
            return RotationResult(civ.civID, wrapped)
        }
        return null
    }

    /** Applies start-turn effects for [civId] and opens its decision ledger. */
    private fun beginTurnOnCandidate(
        candidateGame: GameInfo,
        candidateMatch: AgentMatchState,
        civId: String
    ) {
        val civ = candidateGame.getCivilization(civId)
        TurnManager(civ).startTurn(null, suppressAutomation = true)
        candidateGame.currentPlayer = civ.civID
        candidateGame.currentPlayerCiv = civ
        candidateGame.currentTurnStartTime = System.currentTimeMillis()
        candidateMatch.turnPhase = TurnPhase.AWAITING_DECISIONS
        candidateMatch.turnSerial++
        val before = candidateMatch.stateRevision
        candidateMatch.stateRevision = before + 1
        // New turn, new idempotency scope: the hot index is swapped only after
        // a successful commit (see callers), so a failed persist can never
        // strand the committed state without its deduplication scope.
        val beginReceipt = ActionReceipt(
            actionId = "internal:begin-t${candidateMatch.turnSerial}-$civId",
            actorCivId = civId,
            timelineId = candidateMatch.timelineId,
            requestFingerprint = "internal:begin",
            beforeRevision = before,
            afterRevision = before + 1,
            outcome = "applied",
            committedEventIds = ArrayList(),
            resultJson = ""
        )
        candidateMatch.currentTurnReceipts.clear()
        candidateMatch.currentTurnReceipts.add(beginReceipt)
    }

    //endregion
    //region Commit and persistence

    /**
     * Durably publish the candidate, then swap it in. [candidateGame] is null
     * for metadata-only commits (notes), which still rewrite the checkpoint
     * so game and metadata generations never diverge.
     */
    private fun commit(candidateGame: GameInfo?, candidateMatch: AgentMatchState) {
        val gameJson = if (candidateGame != null) json.toJson(candidateGame) else json.toJson(committedGame)
        val matchJson = json.toJson(candidateMatch)
        try {
            writer.write(gameJson, matchJson)
        } catch (ex: Exception) {
            // Discard the candidate, preserve prior committed state, pause
            // with the reason. No success receipt escapes.
            committedMatch.matchPaused = true
            committedMatch.matchPausedReason = "Checkpoint persistence failed: ${ex.message}"
            throw AgentCommandException(AgentCommandError.INTERNAL_ERROR, "Checkpoint persistence failed", null)
        }
        if (candidateGame != null) committedGame = candidateGame
        committedMatch = candidateMatch
    }

    /** Versioned bundle for save/reopen and recovery scans. */
    fun saveToJson(): String {
        val persistedGame = committedGame
        return json.toJson(
            AgentBundle(
                json.toJson(persistedGame),
                json.toJson(committedMatch)
            )
        )
    }

    //endregion
    //region Internals

    private fun requireController(civId: String): CivilizationController =
        committedMatch.controllers[civId]
            ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization $civId")

    private fun deserializeGame(gameJson: String): GameInfo {
        val game = json.fromJson(GameInfo::class.java, gameJson)
        // Controlled transient restoration on the unpublished candidate.
        // Agent policy is installed before restoration (executeMove binds it
        // above); restoration itself must not make unsolicited choices.
        game.setTransients()
        return game
    }

    private fun rebuildHotIndex() {
        actionReceipts.clear()
        for (receipt in committedMatch.currentTurnReceipts)
            actionReceipts[receipt.actionId] = receipt
    }

    companion object {
        /** Reloads a coordinator from [saveToJson]; hot dedup scope is restored with it. */
        fun loadFromJson(
            bundleJson: String,
            writer: CheckpointWriter = InMemoryCheckpointWriter(),
            policy: AgentExecutionPolicy = AgentExecutionPolicy()
        ): MatchCoordinator {
            val json = json()
            val bundle = json.fromJson(AgentBundle::class.java, bundleJson)
            bundle.validateFormat()
            val game = json.fromJson(GameInfo::class.java, bundle.gameJson)
            val match = json.fromJson(AgentMatchState::class.java, bundle.matchJson)
            return MatchCoordinator(game, match, writer, policy)
        }
    }
}

/** Serializable save-bundle envelope (game + match travel as paired JSON). */
class AgentBundle(
    var gameJson: String = "",
    var matchJson: String = "",
    var bundleVersion: Int = AGENT_MATCH_FORMAT_VERSION
) : com.unciv.logic.IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this("", "", AGENT_MATCH_FORMAT_VERSION)

    fun validateFormat() {
        require(bundleVersion == AGENT_MATCH_FORMAT_VERSION) {
            "Unsupported agent bundle version $bundleVersion"
        }
    }
}
