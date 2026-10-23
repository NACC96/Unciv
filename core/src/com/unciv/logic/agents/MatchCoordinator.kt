package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.presentation.CaptureScope
import com.unciv.logic.presentation.VisualCapture
import com.unciv.logic.presentation.VisualProvenance
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.automation.unit.UnitAutomation
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
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceType
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
    /** Explicit versions survive Gdx's otherwise compact/default-skipping saves. */
    private val metadataJson = json().apply { setUsePrototypes(false) }
    private var committedGame: GameInfo
    private var committedMatch: AgentMatchState
    private var committedMatchJson: String
    private var publicationGeneration = 0L
    private val actionReceipts = HashMap<String, ActionReceipt>()
    private var mutationInFlight: String? = null
    /** Local-only opt-in supplied by V2; absent from game/match JSON, receipts and provider methods. */
    private var visualCaptureSession: VisualCapture.Session? = null

    @Synchronized
    fun configureVisualCapture(session: VisualCapture.Session?) {
        if (session != null) {
            require(session.journal.gameId == committedGame.gameId)
            require(session.journal.timelineId == committedMatch.timelineId)
            require(isVisualViewPermitted(session.view))
        }
        visualCaptureSession = session
    }

    private fun isVisualViewPermitted(view: com.unciv.logic.presentation.VisualViewKey): Boolean {
        val local = committedMatch.localViewingCivId ?: return false
        if (committedGame.civilizations.none { it.civID == view.viewerCivId }) return false
        if (isSpectatorViewer(local)) return view.spectatorMode
        if (view.spectatorMode || view.viewerCivId != local) return false
        // A saved spectator principal retains presentation-only full vision while playing.
        // Ordinary human viewers remain bounded by the committed match setting, including
        // between a restrictive vision change and its next UI snapshot installation.
        if (canSpectate()) return true
        if (view.revealMap && committedMatch.vision == MatchVision.FOG) return false
        return !view.revealUnits || committedMatch.vision == MatchVision.FULL
    }

    private fun beginVisualCapture(game: GameInfo, actionId: String): CaptureScope {
        val session = visualCaptureSession?.takeIf { isVisualViewPermitted(it.view) }
        return VisualCapture.begin(game, session, VisualProvenance(actionId = actionId))
    }

    init {
        match.validateFormat()
        // Deep-copy inputs so the caller retains no alias into committed state.
        committedGame = deserializeGame(json.toJson(game))
        committedMatch = match.deepCopy()
        NationControl.initialize(committedGame, committedMatch)
        captureVictoryProgress(committedGame, committedMatch)
        // Initial/load knowledge is populated once, never by a read tool.
        AgentMapSightings().apply {
            captureAll(committedGame, committedMatch)
            flushInto(committedMatch, committedMatch.stateRevision)
        }
        // Historical admitted saves have no progress ledger. Install their current baseline once
        // without replaying start-turn effects or fabricating earlier quiet turns. Reads stay pure.
        if (committedMatch.turnPhase == TurnPhase.AWAITING_DECISIONS &&
            committedGame.currentPlayer in committedMatch.controllers)
            admitStrategicTurn(committedGame, committedMatch, committedGame.currentPlayer)
        committedMatchJson = AgentOrdinarySave.encode(committedMatch)
        rebuildHotIndex()
    }

    fun getLocalControlCivId(): String? = committedMatch.localControlCivId
    fun getLocalViewingCivId(): String? = committedMatch.localViewingCivId

    @Synchronized
    fun getNationSettings(): List<NationSetting> {
        if (committedGame.gameParameters.isOnlineMultiplayer) return emptyList()
        val origin = committedMatch.localControlCivId ?: return emptyList()
        val principal = committedGame.getCivilizationOrNull(origin) ?: return emptyList()
        return committedGame.civilizations.filter { NationControl.isEligible(it, committedMatch) &&
            (principal.isSpectator() || it.civID == origin) }.map { civ ->
            val controller = committedMatch.controllers[civ.civID]!!
            NationSetting(civ.civID, civ.civName, controller.kind,
                (controller.agentProfile ?: committedMatch.retainedAgentProfiles[civ.civID])?.copy(),
                committedGame.currentPlayer == civ.civID)
        }
    }

    fun canSpectate(): Boolean = !committedGame.gameParameters.isOnlineMultiplayer &&
        committedMatch.localControlCivId?.let { committedGame.getCivilizationOrNull(it)?.isSpectator() } == true

    /** The host supplies no gameplay actor: authority comes only from the saved local principal. */
    @Synchronized
    fun validateNationChange(civId: String?, change: NationControlChange, profile: AgentProfile?) {
        require(!committedGame.gameParameters.isOnlineMultiplayer) { "Online agent matches are unsupported" }
        check(committedMatch.turnPhase != TurnPhase.COMPLETED) { "This match has finished" }
        if (change == NationControlChange.SPECTATE) {
            require(canSpectate()) { "Only the local spectator may return to spectating" }
            return
        }
        require(getNationSettings().any { it.civId == civId }) { "This nation is unavailable to the local player" }
        if (change == NationControlChange.SAVE_AGENT_SETTINGS) require(profile != null) { "Choose an agent profile" }
        val desired = profile ?: committedMatch.retainedAgentProfiles[civId]
            ?: committedMatch.controllers[civId]?.agentProfile
        if (change in setOf(NationControlChange.SAVE_AGENT_SETTINGS, NationControlChange.HAND_TO_AGENT)) {
            require(desired != null) { "Choose an agent profile before handing this nation to an agent" }
            NationControl.validateProfile(desired)
        }
    }

    /** Called only at a host-owned idle boundary, never exposed in the provider engine protocol. */
    @Synchronized
    fun applyNationChange(civId: String?, change: NationControlChange, profile: AgentProfile?) {
        check(!committedMatch.matchPaused) { "Resolve the checkpoint failure before changing nation settings" }
        validateNationChange(civId, change, profile)
        val candidate = committedMatch.deepCopy()
        var candidateGame: GameInfo? = null
        if (change == NationControlChange.SPECTATE) {
            candidate.localViewingCivId = candidate.localControlCivId
        } else {
            val controller = candidate.controllers[civId]!!
            if (controller.agentProfile != null) candidate.retainedAgentProfiles[civId!!] = controller.agentProfile!!.copy()
            if (profile != null && change != NationControlChange.PLAY_AS) {
                val previousRevision = candidate.retainedAgentProfiles[civId]?.profileRevision ?: 0
                require(previousRevision < Int.MAX_VALUE) { "Agent profile revision is exhausted" }
                candidate.retainedAgentProfiles[civId!!] = profile.copy().apply {
                    profileRevision = maxOf(profileRevision, previousRevision + 1)
                }
            }
            when (change) {
                NationControlChange.PLAY_AS -> {
                    controller.kind = ControllerKind.HUMAN
                    controller.agentProfile = null
                    candidate.localViewingCivId = civId
                    // Taking control of an existing match is not a new-game introduction.
                    // Consume only this greeting in the same durable controller/view transaction.
                    if (committedGame.getCivilization(civId!!).popupAlerts.any { it.type == AlertType.StartIntro }) {
                        candidateGame = deserializeGame(json.toJson(committedGame))
                        candidateGame.getCivilization(civId).popupAlerts.removeAll { it.type == AlertType.StartIntro }
                    }
                }
                NationControlChange.HAND_TO_AGENT -> {
                    controller.kind = ControllerKind.EXTERNAL_AGENT
                    controller.agentProfile = candidate.retainedAgentProfiles[civId]!!.copy()
                }
                NationControlChange.SAVE_AGENT_SETTINGS -> if (controller.kind == ControllerKind.EXTERNAL_AGENT)
                    controller.agentProfile = candidate.retainedAgentProfiles[civId]!!.copy()
                else -> {}
            }
        }
        check(candidate.stateRevision < Int.MAX_VALUE) { "Match revision is exhausted" }
        candidate.stateRevision++
        commit(candidateGame, candidate)
    }

    //region Reads (no mutation)

    fun getStateRevision(): Int = committedMatch.stateRevision
    fun getTurnPhase(): TurnPhase = committedMatch.turnPhase

    /**
     * Testing seam: overwrite the committed match phase (e.g. simulate
     * COMPLETED without playing out turns). Never used by production paths;
     * receipts, revisions, and game state are untouched.
     */
    fun forcePhaseForTest(phase: TurnPhase) {
        committedMatch.turnPhase = phase
        refreshMatchPublication()
    }
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

    /** Guidance travels with the same durable game/metadata pair, never a provider session. */
    @Synchronized
    fun queueGuidance(civId: String, text: String) {
        requireController(civId)
        val candidate = committedMatch.deepCopy()
        candidate.guidanceQueue.add(GuidanceMessage(
            "guidance-${java.util.UUID.randomUUID()}", civId, System.currentTimeMillis(), GuidanceState.QUEUED, text))
        commit(null, candidate)
    }

    @Synchronized
    fun takeGuidanceForDelivery(civId: String): List<String> {
        requireController(civId)
        val candidate = committedMatch.deepCopy()
        val pending = candidate.guidanceQueue.filter { it.targetCivId == civId && it.state == GuidanceState.QUEUED }
        if (pending.isEmpty()) return emptyList()
        pending.forEach { it.state = GuidanceState.DELIVERED }
        commit(null, candidate)
        return pending.map { it.text }
    }

    /** Explicit retry may clear a persisted failure only after the replacement checkpoint succeeds. */
    @Synchronized
    fun retryPausedMatch() {
        if (!committedMatch.matchPaused) return
        val candidate = committedMatch.deepCopy()
        candidate.matchPaused = false
        candidate.matchPausedReason = null
        commit(null, candidate)
    }

    /** Committed pending decisions for one civ (copies; mutating them affects nothing). */
    fun getPendingDecisions(civId: String): List<PendingDecision> {
        requireController(civId)
        return committedMatch.pendingDecisions.filter { it.owningCivId == civId }.map { it.copy() }
    }

    /** Committed controller assignment, if any. */
    fun getController(civId: String): CivilizationController? =
        committedMatch.controllers[civId]?.copy()

    fun getControllers(): Map<String, CivilizationController> =
        committedMatch.controllers.mapValues { it.value.copy() }

    /** Agent-controlled civs in turn order; session A/B arms stay stable while controllers do not change. */
    @Synchronized
    fun getAgentCivsInTurnOrder(): List<String> = committedGame.civilizations
        .filter { committedMatch.controllers[it.civID]?.kind == ControllerKind.EXTERNAL_AGENT }
        .map { it.civID }

    /** Host visibility depends on the real viewing civilization, never a caller-supplied mode flag. */
    @Synchronized
    fun isSpectatorViewer(viewerCivId: String): Boolean =
        committedGame.getCivilizationOrNull(viewerCivId)?.isSpectator() == true

    /** The host binds the viewing identity; this list never enumerates unmet opponents. */
    @Synchronized
    fun getMessageContacts(viewerCivId: String): List<MessageContact> {
        requireMessageViewer(viewerCivId)
        return buildMessageContacts(committedGame, committedMatch, viewerCivId)
    }

    /** Senders see queued mail; recipients see it after delivery; spectators can inspect all mail. */
    @Synchronized
    fun getMessageHistory(viewerCivId: String): List<DiplomaticMessage> {
        val viewer = requireMessageViewer(viewerCivId)
        return orderedDiplomaticMessages(committedMatch.diplomaticMessages.filter { message ->
            viewer.isSpectator() || message.senderCivId == viewerCivId ||
                message.isDeliveredTo(viewerCivId, committedMatch.turnSerial)
        }).map { it.copy() }
    }

    /** Provider reads can access only that actor's delivered incoming attachments. */
    @Synchronized
    fun getDeliveredMessageImage(actorCivId: String, messageId: String,
                                 attachmentId: String): DiplomaticImageAttachment {
        requireController(actorCivId)
        val message = committedMatch.diplomaticMessages.firstOrNull {
            it.messageId == messageId && it.isDeliveredTo(actorCivId, committedMatch.turnSerial)
        }
        return requireMessageImage(message, attachmentId)
    }

    /** Preview access uses the attached viewer's ordinary private history visibility. */
    @Synchronized
    fun getViewerMessageImage(viewerCivId: String, messageId: String,
                              attachmentId: String): DiplomaticImageAttachment {
        val viewer = requireMessageViewer(viewerCivId)
        val message = committedMatch.diplomaticMessages.firstOrNull {
            it.messageId == messageId && (viewer.isSpectator() || it.senderCivId == viewerCivId ||
                it.isDeliveredTo(viewerCivId, committedMatch.turnSerial))
        }
        return requireMessageImage(message, attachmentId)
    }

    private fun requireMessageImage(message: DiplomaticMessage?, attachmentId: String): DiplomaticImageAttachment =
        message?.attachments?.firstOrNull { it.attachmentId == attachmentId }?.copy()
            ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such accessible message image")

    /**
     * Host-only player/spectator mail may queue between turns, including while play is paused.
     * This metadata commit preserves an active provider's gameplay revision and never invokes it.
     */
    @Synchronized
    fun queuePlayerMessage(
        senderCivId: String,
        messageId: String,
        targetCivId: String,
        text: String,
        attachments: List<DiplomaticImageAttachment> = emptyList()
    ): DiplomaticMessage {
        val validated = validatedPlayerMessage(senderCivId, messageId, targetCivId, text, attachments)
        validated.duplicate?.let { return it.copy() }
        val candidate = committedMatch.deepCopy()
        val message = DiplomaticMessage(messageId, senderCivId, targetCivId, validated.body,
            candidate.turnSerial, null, validated.origin, attachments = ArrayList(validated.images))
        candidate.diplomaticMessages.add(message)
        commit(candidateGame = null, candidateMatch = candidate)
        return message.copy()
    }

    /**
     * The player or spectator who sent mail may delete it until the recipient's turn delivers it.
     * Undelivered mail was never visible to the recipient, so removal leaves no trace there.
     */
    @Synchronized
    fun deletePlayerMessage(senderCivId: String, messageId: String) {
        val sender = requireMessageViewer(senderCivId)
        if (!sender.isSpectator() && committedMatch.controllers[senderCivId]?.kind != ControllerKind.HUMAN)
            throw AgentCommandException(AgentCommandError.AUTHORITY_REVOKED,
                "Only the human player or spectator may delete their messages")
        if (committedMatch.matchPaused)
            throw AgentCommandException(AgentCommandError.MATCH_PAUSED, "Match requires recovery before changing mail")
        if (committedMatch.turnPhase == TurnPhase.COMPLETED)
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot change mail after play has ended")
        val message = committedMatch.diplomaticMessages.firstOrNull {
            it.messageId == messageId && it.senderCivId == senderCivId
        } ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such message of yours")
        if (message.deliveredTurnSerial != null)
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION,
                "This message was already delivered and can no longer be deleted")
        val candidate = committedMatch.deepCopy()
        candidate.diplomaticMessages.removeAll { it.messageId == messageId }
        commit(candidateGame = null, candidateMatch = candidate)
    }

    @Synchronized
    fun getVision(): MatchVision = committedMatch.vision

    /**
     * Match-wide map vision for agent observations and local human views. A metadata commit:
     * no gameplay revision, and game rules keep every civilization's real sight.
     */
    @Synchronized
    fun setVision(vision: MatchVision) {
        if (committedMatch.vision == vision) return
        if (committedMatch.matchPaused)
            throw AgentCommandException(AgentCommandError.MATCH_PAUSED, "Match requires recovery before changing settings")
        val candidate = committedMatch.deepCopy()
        candidate.vision = vision
        commit(candidateGame = null, candidateMatch = candidate)
    }

    /** Cheap read-only preflight before a host writes image bytes. Queueing repeats every check. */
    @Synchronized
    fun validatePlayerMessage(senderCivId: String, messageId: String, targetCivId: String,
                              text: String, attachments: List<DiplomaticImageAttachment> = emptyList()) {
        validatedPlayerMessage(senderCivId, messageId, targetCivId, text, attachments)
    }

    private data class ValidatedPlayerMessage(val body: String, val origin: String,
                                             val images: List<DiplomaticImageAttachment>,
                                             val duplicate: DiplomaticMessage?)

    private fun validatedPlayerMessage(senderCivId: String, messageId: String, targetCivId: String,
                                       text: String, attachments: List<DiplomaticImageAttachment>): ValidatedPlayerMessage {
        val sender = requireMessageViewer(senderCivId)
        if (!sender.isSpectator() && committedMatch.controllers[senderCivId]?.kind != ControllerKind.HUMAN)
            throw AgentCommandException(AgentCommandError.AUTHORITY_REVOKED,
                "Only the human player or spectator may use the player message composer")
        if (committedMatch.matchPaused)
            throw AgentCommandException(AgentCommandError.MATCH_PAUSED, "Match requires recovery before sending mail")
        if (committedMatch.turnPhase == TurnPhase.COMPLETED || sender.isDefeated())
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot send mail after play has ended")
        val origin = if (sender.isSpectator()) "SPECTATOR" else "NATION"
        val images = attachments.map { it.copy() }
        if (images.size > 1)
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "A message may contain one image")
        try { images.forEach { it.validate() } } catch (ex: IllegalArgumentException) {
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, ex.message ?: "Invalid message image")
        }
        val body = validateMessageBody(messageId, text, allowEmpty = images.isNotEmpty())
        findDuplicateMessage(messageId, senderCivId, targetCivId, body, origin, images)?.let {
            return ValidatedPlayerMessage(body, origin, images, it)
        }
        requireMessageTarget(senderCivId, targetCivId)
        requireMessageLimit(senderCivId)
        return ValidatedPlayerMessage(body, origin, images, null)
    }

    fun getTimelineId(): String = committedMatch.timelineId
    fun getMatchId(): String = committedMatch.matchId

    /** Pinned ruleset name of the committed game. */
    fun getRulesetName(): String = committedGame.ruleset.name

    /** Committed receipt by action ID (active-turn scope), for result lookup. */
    fun getReceipt(actionId: String): ActionReceipt? {
        return (actionReceipts[actionId] ?: committedMatch.diplomaticMessageReceipts[actionId])?.copy()
    }

    /** Single-unit inspection with visibility policy (null = hidden/nonexistent). */
    fun inspectUnit(actorCivId: String, unitId: Int): ObservedUnit? {
        requireController(actorCivId)
        return buildUnitInspection(committedGame, actorCivId, unitId, committedMatch.vision)?.also { unit ->
            if (unit.ownerCivId == actorCivId)
                unit.tile = buildPermittedTile(committedGame, committedMatch, actorCivId, HexCoord(unit.x, unit.y))
        }
    }

    /** Own-city detail (game.inspect_city); foreign, hidden and nonexistent cities share null. */
    fun inspectCity(actorCivId: String, cityId: String): CityInspection? {
        requireController(actorCivId)
        return buildCityInspection(committedGame, actorCivId, cityId)
    }

    /** Committed position/movement of an own unit without tile detail; null once consumed or destroyed. */
    fun ownUnitState(actorCivId: String, unitId: Int): ObservedUnit? {
        requireController(actorCivId)
        return buildUnitInspection(committedGame, actorCivId, unitId)?.takeIf { it.ownerCivId == actorCivId }
    }

    fun inspectTile(actorCivId: String, position: HexCoord): ObservedTile? {
        requireController(actorCivId)
        return buildPermittedTile(committedGame, committedMatch, actorCivId, position)
    }

    fun map(actorCivId: String, query: MapQuery): MapPage {
        requireController(actorCivId)
        return buildMapPage(committedGame, committedMatch, actorCivId, query)
    }

    /** Owner civ ID of a city in COMMITTED state, null when the city is gone. */
    fun getCityOwner(cityId: String): String? =
        committedGame.getCities().firstOrNull { it.id == cityId }?.civ?.civID

    fun isCityPuppet(cityId: String): Boolean =
        committedGame.getCities().firstOrNull { it.id == cityId }?.isPuppet == true

    fun isCityBeingRazed(cityId: String): Boolean =
        committedGame.getCities().firstOrNull { it.id == cityId }?.isBeingRazed == true

    /** Whether the civ is in a golden age in COMMITTED state. */
    fun isGoldenAge(civId: String): Boolean {
        requireController(civId)
        return committedGame.getCivilizationOrNull(civId)?.goldenAges?.isGoldenAge() == true
    }

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
        return buildLegalActions(committedGame, actorCivId, unitId, cityId).also {
            it.revision = committedMatch.stateRevision
        }
    }

    /** Static rules summary (game.rules). Safe to cache by ruleset identity. */
    fun rules(): RulesSummaryDto = buildRulesSummary(committedGame)

    fun rules(query: RulesQuery): Any {
        if (query.limit !in 1..128)
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Rules limit must be 1 through 128")
        return if (query.section == null) {
            if (query.name != null || query.cursor != null)
                throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Rules name/cursor requires a section")
            rules()
        } else buildRulesCatalog(committedGame, getRulesetName(), query).toJson()
    }

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
            is SendDiplomaticMessageCommand -> executeDiplomaticMessage(command)
            is AcknowledgeDiplomaticMessageCommand -> executeAcknowledgeMessage(command)
            is MoveUnitCommand -> executeMove(command)
            is EndTurnCommand -> executeEndTurn(command)
            is SelectResearchCommand -> executeResearch(command)
            is CityProductionCommand -> executeProduction(command)
            is CityConstructionSettingsCommand -> executeConstructionSettings(command)
            is SetCityUnitSavedPromotionCommand -> executeSavedPromotionPreference(command)
            is SaveUnitPromotionDefaultsCommand -> executeSavePromotionDefaults(command)
            is FoundCityCommand -> executeFound(command)
            is UnitOrderCommand -> executeOrder(command)
            is AttackCommand -> executeAttack(command)
            is ResolveConquestCommand -> executeResolveConquest(command)
            is DeclareWarCommand -> executeDeclareWar(command)
            is MakePeaceCommand -> executeMakePeace(command)
            is NegotiateCityStatePeaceCommand -> executeNegotiateCityStatePeace(command)
            is ProposeTradeCommand -> executeProposeTrade(command)
            is RespondTradeCommand -> executeRespondTrade(command)
            is AdoptPolicyCommand -> executeAdoptPolicy(command)
            is CityCitizensCommand -> executeCitizens(command)
            is BuyTileCommand -> executeBuyTile(command)
            is BuildImprovementCommand -> executeBuildImprovement(command)
            is QueueWorkerImprovementsCommand -> executeQueueWorkerImprovements(command)
            is PromoteUnitCommand -> executePromote(command)
            is UpgradeUnitCommand -> executeUpgrade(command)
            is GreatPersonCommand -> executeGreatPerson(command)
            is ChooseFreeGreatPersonCommand -> executeChooseFreeGreatPerson(command)
            is DismissAlertCommand -> executeDismissAlert(command)
            is DismissDiplomaticVoteResultsCommand -> executeDismissVoteResults(command)
            is MarkPolicyPickerOpenedCommand -> executeMarkPolicyPickerOpened(command)
            is GiftGoldToCityStateCommand -> executeGiftGold(command)
            is GiftImprovementToCityStateCommand -> executeGiftImprovement(command)
            is PillageCommand -> executePillage(command)
            is RepairCommand -> executeRepair(command)
            is FriendshipCommand -> executeFriendship(command)
            is DenounceCommand -> executeDenounce(command)
            is DemandCommand -> executeDemand(command)
            is RespondDemandCommand -> executeRespondDemand(command)
            is CastVoteCommand -> executeCastVote(command)
            is RenameCityCommand -> executeRenameCity(command)
            is AnnexCityCommand -> executeAnnexCity(command)
            is SetRazingCommand -> executeSetRazing(command)
            is StopImprovementCommand -> executeStopImprovement(command)
            is SwapUnitsCommand -> executeSwapUnits(command)
            is RenameUnitCommand -> executeRenameUnit(command)
            is ConnectRoadCommand -> executeConnectRoad(command)
            is TributeCommand -> executeTribute(command)
            is ProtectionCommand -> executeProtection(command)
            is DiplomaticMarriageCommand -> executeDiplomaticMarriage(command)
            is RetractTradeCommand -> executeRetractTrade(command)
            is RespondProtectorAlertCommand -> executeRespondProtectorAlert(command)
            is RespondFriendshipOfferCommand -> executeRespondFriendshipOffer(command)
            is GiftUnitCommand -> executeGiftUnit(command)
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

    private fun executeDiplomaticMessage(command: SendDiplomaticMessageCommand): DiplomaticMessageSentResult {
        if (command.actorCivId != committedGame.currentPlayer)
            throw AgentCommandException(AgentCommandError.NOT_YOUR_TURN,
                "Diplomatic messages may only be sent by the active civilization")
        val body = validateMessageBody(command.actionId, command.text)
        val duplicate = findDuplicateMessage(command.actionId, command.actorCivId,
            command.targetCivId, body, "NATION")
        val fingerprint = "diplomacy.message|" + json.toJson(arrayListOf(
            command.actorCivId, command.targetCivId, body, "NATION"))
        committedMatch.diplomaticMessageReceipts[command.actionId]?.let { prior ->
            if (prior.requestFingerprint != fingerprint)
                throw AgentCommandException(AgentCommandError.ACTION_ID_CONFLICT,
                    "Action ID was already used for a different command")
        }
        if (duplicate != null) {
            val activeReceipt = actionReceipts[command.actionId]
            if (activeReceipt != null && activeReceipt.requestFingerprint != fingerprint)
                throw AgentCommandException(AgentCommandError.ACTION_ID_CONFLICT,
                    "Action ID was already used for a different command")
            // Older mail may have no retained receipt. Its saved record still proves delivery was
            // queued successfully; acknowledge it at the current revision without another commit.
            val prior = committedMatch.diplomaticMessageReceipts[command.actionId] ?: activeReceipt ?: ActionReceipt(
                actionId = command.actionId, actorCivId = command.actorCivId,
                timelineId = committedMatch.timelineId, requestFingerprint = fingerprint,
                beforeRevision = committedMatch.stateRevision, afterRevision = committedMatch.stateRevision,
                outcome = "applied", resultJson = command.actionId
            )
            return DiplomaticMessageSentResult(command.actorCivId, prior.copy(), duplicate.copy())
        }
        val receipt = runGameplayMutation(command.actorCivId, command.actionId,
            command.expectedRevision, fingerprint,
            collectSightings = false,
            rememberReceipt = { candidateMatch, retained ->
                candidateMatch.diplomaticMessageReceipts[command.actionId] = retained.copy()
            }) { _, candidateMatch ->
            requireMessageTarget(command.actorCivId, command.targetCivId)
            requireMessageLimit(command.actorCivId)
            candidateMatch.diplomaticMessages.add(DiplomaticMessage(command.actionId,
                command.actorCivId, command.targetCivId, body, candidateMatch.turnSerial))
            command.actionId
        }
        val message = committedMatch.diplomaticMessages.first { it.messageId == receipt.resultJson }
        return DiplomaticMessageSentResult(command.actorCivId, receipt, message.copy())
    }

    private fun executeAcknowledgeMessage(command: AcknowledgeDiplomaticMessageCommand): DiplomaticMessageAcknowledgedResult {
        if (command.actorCivId != committedGame.currentPlayer)
            throw AgentCommandException(AgentCommandError.NOT_YOUR_TURN,
                "Messages may only be acknowledged by the active civilization")
        // Resolve access before replay: a receipt must never expose somebody else's private mail.
        val message = committedMatch.diplomaticMessages.firstOrNull {
            it.messageId == command.messageId && it.isDeliveredTo(command.actorCivId, committedMatch.turnSerial)
        } ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such delivered incoming message")
        val body = validateMessageBody(command.actionId, command.text)
        val fingerprint = "diplomacy.acknowledgeMessage|" + json.toJson(arrayListOf(
            command.actorCivId, command.messageId, body))
        val prior = actionReceipts[command.actionId] ?: committedMatch.diplomaticMessageReceipts[command.actionId]
        if (prior != null) {
            if (prior.requestFingerprint != fingerprint)
                throw AgentCommandException(AgentCommandError.ACTION_ID_CONFLICT,
                    "Action ID was already used for a different command")
            return DiplomaticMessageAcknowledgedResult(command.actorCivId, prior.copy(), message.copy())
        }
        val receipt = runGameplayMutation(command.actorCivId, command.actionId,
            command.expectedRevision, fingerprint, collectSightings = false,
            rememberReceipt = { candidate, retained ->
                candidate.diplomaticMessageReceipts[command.actionId] = retained.copy()
                candidate.diplomaticMessages.first { it.messageId == command.messageId }
                    .acknowledgedRevision = retained.afterRevision
            }) { _, candidate ->
            val incoming = candidate.diplomaticMessages.first { it.messageId == command.messageId }
            if (incoming.acknowledgedTurnSerial != null)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Message is already acknowledged")
            incoming.acknowledgedTurnSerial = candidate.turnSerial
            incoming.acknowledgementText = body
            incoming.messageId
        }
        val acknowledged = committedMatch.diplomaticMessages.first { it.messageId == receipt.resultJson }
        return DiplomaticMessageAcknowledgedResult(command.actorCivId, receipt, acknowledged.copy())
    }

    private fun requireMessageViewer(viewerCivId: String): Civilization {
        val viewer = committedGame.getCivilizationOrNull(viewerCivId)
            ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown viewing civilization")
        val kind = committedMatch.controllers[viewerCivId]?.kind
        if (!viewer.isSpectator() && kind != ControllerKind.HUMAN && kind != ControllerKind.EXTERNAL_AGENT)
            throw AgentCommandException(AgentCommandError.AUTHORITY_REVOKED, "This civilization has no message access")
        return viewer
    }

    private fun requireMessageTarget(senderCivId: String, targetCivId: String) {
        if (buildMessageContacts(committedGame, committedMatch, senderCivId).none { it.civId == targetCivId })
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No eligible diplomatic contact")
    }

    private fun validateMessageBody(messageId: String, text: String, allowEmpty: Boolean = false): String {
        val body = text.trim()
        if (messageId.isBlank() || messageId.length > 128)
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Message ID must contain 1–128 characters")
        if ((!allowEmpty && body.isEmpty()) || body.length > DiplomaticMessageLimits.MAX_BODY_CHARS ||
            body.toByteArray(Charsets.UTF_8).size > DiplomaticMessageLimits.MAX_BODY_BYTES)
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT,
                "Message must contain 1–2000 characters and fit within 8 KiB UTF-8")
        return body
    }

    private fun findDuplicateMessage(
        messageId: String,
        senderCivId: String,
        targetCivId: String,
        body: String,
        origin: String,
        attachments: List<DiplomaticImageAttachment> = emptyList()
    ): DiplomaticMessage? {
        val prior = committedMatch.diplomaticMessages.firstOrNull { it.messageId == messageId } ?: return null
        if (prior.senderCivId != senderCivId || prior.targetCivId != targetCivId ||
            prior.text != body || prior.origin != origin ||
            (prior.attachments.size != attachments.size || prior.attachments.zip(attachments).any { (a, b) ->
                a.attachmentId != b.attachmentId || a.mimeType != b.mimeType || a.width != b.width ||
                    a.height != b.height || a.byteLength != b.byteLength
            }))
            throw AgentCommandException(AgentCommandError.ACTION_ID_CONFLICT,
                "Message ID was already used for different mail")
        return prior
    }

    private fun requireMessageLimit(senderCivId: String) {
        if (committedMatch.diplomaticMessages.count {
                it.senderCivId == senderCivId && it.sentTurnSerial == committedMatch.turnSerial
            } >= DiplomaticMessageLimits.MAX_MESSAGES_PER_TURN)
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION,
                "A civilization may send at most 10 messages per turn")
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
        rememberReceipt: ((AgentMatchState, ActionReceipt) -> Unit)? = null,
        collectSightings: Boolean = true,
        requiresActiveActor: Boolean = true,
        snapshotUnitId: Int? = null,
        apply: (candidateGame: GameInfo, candidateMatch: AgentMatchState) -> String
    ): ActionReceipt {
        (actionReceipts[actionId] ?: committedMatch.diplomaticMessageReceipts[actionId])?.let { prior ->
            if (prior.requestFingerprint == fingerprint) return prior
            throw AgentCommandException(
                AgentCommandError.ACTION_ID_CONFLICT,
                "actionId $actionId reused with a different request"
            )
        }
        if (requiresActiveActor) requireActiveActor(actorCivId)
        if (expectedRevision != committedMatch.stateRevision)
            throw AgentCommandException(
                AgentCommandError.STALE_STATE,
                "Expected revision $expectedRevision but committed is ${committedMatch.stateRevision}",
                currentRevision = committedMatch.stateRevision
            )
        mutationInFlight = actionId
        var visualScope: CaptureScope? = null
        try {
            val candidateGame = deserializeGame(json.toJson(committedGame), collectSightings = collectSightings)
            visualScope = beginVisualCapture(candidateGame, actionId)
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
                resultJson = resultJson,
                unitSnapshot = snapshotUnitId?.let { unitId ->
                    val unit = candidateGame.getCivilization(actorCivId).units.getCivUnits()
                        .firstOrNull { it.id == unitId && it.owner == actorCivId && !it.isDestroyed }
                    if (unit == null) ActionUnitSnapshot(unitId = unitId)
                    else ActionUnitSnapshot(unitId, true, unit.getTile().position.x, unit.getTile().position.y,
                        unit.currentMovement, unit.health, unit.action)
                }
            )
            candidateMatch.currentTurnReceipts.add(receipt)
            rememberReceipt?.invoke(candidateMatch, receipt)
            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            actionReceipts[actionId] = receipt
            return receipt
        } finally {
            visualScope?.close()
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
            "unit.move|${command.unitId}|${command.destination.x},${command.destination.y}",
            snapshotUnitId = command.unitId
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
            // Paradrop jumps bypass pathing entirely (engine teleports when
            // preparing); gate on the engine's own canParadropOn.
            if (unit.isPreparingParadrop()) {
                // Validate against the paradrop uniques with the unit's
                // CURRENT tile bound (the cached filters may lack tile
                // context during candidate restoration).
                val distance = unit.getTile().aerialDistanceTo(tile)
                val context = com.unciv.models.ruleset.unique.GameContext(
                    unit = unit, tile = unit.getTile()
                )
                val allowed = unit.getMatchingUniques(UniqueType.MayParadrop, context)
                    .any { distance <= it.params[1].toInt() && tile.matchesFilter(it.params[0], unit.civ) }
                if (!allowed)
                    throw AgentCommandException(
                        AgentCommandError.ILLEGAL_ACTION, "Cannot paradrop to that tile"
                    )
                unit.movement.moveToTile(tile)
                val landed = unit.getTile()
                "${landed.position.x},${landed.position.y}"
            }
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
            // Human-click parity: an unreached destination persists as the
            // engine's own "moveTo" action, resumed at next turn start
            // (mirrors MapUnitView.trySetMoveToAction).
            if (finalPosition != command.destination)
                unit.action = "moveTo ${command.destination.x},${command.destination.y}"
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
            "city.produce|${command.cityId}|${command.op}|${command.name}|${command.index}|${command.tilePos}" +
                if (command.purchaseStat != Stat.Gold) "|purchaseStat=${command.purchaseStat}" else ""
        ) { candidateGame, _ ->
            val city = requireOwnCity(candidateGame, command.actorCivId, command.cityId)
            val constructions = city.cityConstructions
            val previousConstruction = constructions.currentConstructionName()
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
                ProductionOp.MOVE_TOP -> {
                    if (command.index !in 0 until constructions.constructionQueue.size)
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Queue index out of range")
                    constructions.moveEntryToTop(command.index)
                }
                ProductionOp.PURCHASE -> {
                    if (command.purchaseStat !in Stat.statsUsableToBuy)
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Invalid purchase currency")
                    if (command.index < -1 || command.index >= constructions.constructionQueue.size)
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Queue index out of range")
                    if (command.index >= 0 && constructions.constructionQueue[command.index] != command.name)
                        throw AgentCommandException(AgentCommandError.STALE_STATE, "The selected purchase queue entry changed")
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
                    val cost = construction.getStatBuyCost(city, command.purchaseStat)
                        ?: throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "${command.name} cannot be purchased"
                        )
                    if (!constructions.isConstructionPurchaseAllowed(construction, command.purchaseStat, cost))
                        throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "${command.name} cannot be purchased now"
                        )
                    val tile = (construction as? Building)
                        ?.getImprovementToCreate(city.getRuleset(), city.civ)
                        ?.let { command.tilePos?.let { resolveTile(candidateGame, it) } }
                    // automatic=false + suppressed: an emptied queue idles for an
                    // explicit agent choice instead of settings-driven auto-fill.
                    if (!constructions.purchaseConstruction(
                            construction, command.index, automatic = false, stat = command.purchaseStat, tile = tile,
                            suppressAutomation = true
                        )
                    )
                        throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "${command.name} purchase failed"
                        )
                }
                ProductionOp.MOVE_END -> {
                    if (command.index !in constructions.constructionQueue.indices)
                        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Queue index out of range")
                    if (constructions.getConstruction(constructions.constructionQueue[command.index]) is PerpetualConstruction)
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Perpetual construction cannot be moved")
                    constructions.moveEntryToEnd(command.index)
                }
                ProductionOp.REMOVE_ALL -> {
                    requireConstruction(city, command.name)
                    constructions.removeAllByName(command.name)
                }
                ProductionOp.SELL -> {
                    // Gates mirror ConstructionInfoTable's sell button.
                    val building = candidateGame.ruleset.buildings[command.name]
                        ?: throw AgentCommandException(
                            AgentCommandError.INVALID_ARGUMENT, "Unknown building ${command.name}"
                        )
                    if (!building.isSellable())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "${command.name} cannot be sold")
                    if (!city.cityConstructions.isBuilt(command.name))
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "${command.name} is not built here")
                    if (city.isPuppet)
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Puppet cities sell nothing")
                    if (city.hasSoldBuildingThisTurn)
                        throw AgentCommandException(
                            AgentCommandError.ILLEGAL_ACTION, "Already sold a building this turn"
                        )
                    city.sellBuilding(building)
                }
            }
            // Allocate against the committed queue, rather than an old UI snapshot.
            if (constructions.currentConstructionName() != previousConstruction)
                city.reassignPopulation()
            constructions.constructionQueue.joinToString("")
        }
        return CityProductionResult(command.actorCivId, receipt, receipt.resultJson.split(""))
    }

    private fun requireConstruction(city: City, name: String) = try {
        city.cityConstructions.getConstruction(name)
    } catch (ex: Exception) {
        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown construction $name")
    }

    private fun executeConstructionSettings(command: CityConstructionSettingsCommand): CityConstructionSettingsResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "city.constructionSettings|${command.cityId}|${command.constructionName}|${command.disabled}") { game, _ ->
            val civ = game.getCivilization(command.actorCivId)
            val cities = command.cityId?.let { listOf(requireOwnCity(game, command.actorCivId, it)) } ?: civ.cities
            val construction = cities.firstOrNull()?.let { requireConstruction(it, command.constructionName) }
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No city available")
            if (construction == PerpetualConstruction.Idle)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Idle cannot be disabled")
            for (city in cities) {
                if (command.disabled) city.disabledConstructions.add(command.constructionName)
                else city.disabledConstructions.remove(command.constructionName)
            }
            if (command.cityId == null) {
                if (command.disabled) civ.disabledCityConstructions.add(command.constructionName)
                else civ.disabledCityConstructions.remove(command.constructionName)
            }
            ""
        }
        return CityConstructionSettingsResult(command.actorCivId, receipt)
    }

    private fun executeSavedPromotionPreference(command: SetCityUnitSavedPromotionCommand): UnitPromotionDefaultsSavedResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "city.savedPromotion|${command.cityId}|${command.baseUnit}|${command.enabled}") { game, _ ->
            val city = requireOwnCity(game, command.actorCivId, command.cityId)
            if (command.baseUnit !in game.ruleset.units)
                throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown unit ${command.baseUnit}")
            if (city.isPuppet || command.baseUnit !in city.unitShouldUseSavedPromotion)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No editable saved promotions for ${command.baseUnit}")
            city.unitShouldUseSavedPromotion[command.baseUnit] = command.enabled
            city.id
        }
        return UnitPromotionDefaultsSavedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeSavePromotionDefaults(command: SaveUnitPromotionDefaultsCommand): UnitPromotionDefaultsSavedResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "unit.savePromotionDefaults|${command.unitId}") { game, _ ->
            val unit = requireOwnUnit(game, command.actorCivId, command.unitId)
            val city = unit.getTile().getCity()
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit is not in a city territory")
            if (city.civ !== unit.civ || city.isPuppet)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit must be in an owned non-puppet city")
            city.unitShouldUseSavedPromotion[unit.baseUnit.name] = true
            city.unitToPromotions[unit.baseUnit.name] = unit.promotions.clone()
            city.id
        }
        return UnitPromotionDefaultsSavedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeFound(command: FoundCityCommand): CityFoundedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.found|${command.unitId}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val civ = candidateGame.getCivilization(command.actorCivId)
            val tile = unit.getTile()
            // Same checks legal_actions reports as canFound/foundBlockedReason.
            foundCityBlocker(unit)?.let { throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, it) }
            foundCityHere(civ, unit, tile)
        }
        return CityFoundedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    /** Caller has checked [foundCityBlocker]. Returns the new city's ID. */
    private fun foundCityHere(civ: Civilization, unit: MapUnit, tile: Tile = unit.getTile()): String {
        val unique = unit.getMatchingUniques(UniqueType.FoundCity).firstOrNull()
            ?: unit.getMatchingUniques(UniqueType.FoundPuppetCity).first()
        // Engine path mirrors UnitActionsFromUniques without its tutorial/
        // render side effects (those belong to snapshot publication).
        // "By consuming this unit" is the standard modifier and maps to
        // consume(); anything else was rejected by foundCityBlocker.
        val city = civ.addCity(tile.position, unit)
        if (unique.type == UniqueType.FoundPuppetCity) city.isPuppet = true
        unit.consume()
        civ.gameInfo.visualCaptureSink?.let { it.removeUnit(it.unit(unit)) }
        return city.id
    }

    /**
     * Unciv's settler automation choice, without its UI found-city action (which routes through
     * the screen and may ask for confirmation): settle in place when nearly as good as the best
     * ranked reachable site, otherwise head there, founding on arrival. Returns the new city's ID.
     */
    private fun autoSettle(unit: MapUnit): String? {
        if (!unit.hasMovement()) throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit has no movement left")
        val minimum = unit.civ.gameInfo.ruleset.modOptions.constants.minimumCityLocationTileValue
        val ranked = CityLocationTileRanker.getBestTilesToFoundCity(unit, null, minimum)
        val best = ranked.bestTile ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION,
            "No reachable city site in explored land nearby: " + (if (ranked.tileRankMap.isEmpty())
                "no known site within search range ranks high enough. "
            else "${ranked.tileRankMap.size} known site(s) rank high enough but none is reachable. ") +
                "Order EXPLORE to reveal land, unit.move toward unclaimed land" +
                (if (unit.civ.tech.unitsCanEmbark) " (it can embark to cross water)" else "") +
                ", or keep the unit (SLEEP) until borders or exploration change.")
        val here = ranked.tileRankMap[unit.getTile()]
        val site = if (here != null && here >= ranked.bestTileRank - 2) unit.getTile() else best
        if (site != unit.getTile()) unit.movement.headTowards(site)
        if (unit.isDestroyed || unit.getTile() != site || foundCityBlocker(unit) != null) return null
        return foundCityHere(unit.civ, unit)
    }

    private fun executeOrder(command: UnitOrderCommand): UnitOrderResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.order|${command.unitId}|${command.order}",
            snapshotUnitId = command.unitId
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            var disbanded = false
            var foundedCityId: String? = null
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
                UnitOrder.PARADROP -> {
                    // Mirrors UnitActionsFromUniques paradrop toggle: sets the
                    // preparing action; the actual jump is a unit.move.
                    if (!unit.hasUnique(UniqueType.MayParadrop))
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot paradrop")
                    if (unit.hasUnitMovedThisTurn())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit has already moved this turn")
                    unit.action = UnitActionType.Paradrop.value
                }
                UnitOrder.EXPLORE -> {
                    if (unit.baseUnit.isAirUnit() || unit.isExploring())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot explore now")
                    unit.action = UnitActionType.Explore.value
                    // Explores now with the movement left, as the Explore button does. Agent civs'
                    // standing automation clears at their next turn start, so it lasts this turn.
                    if (unit.hasMovement()) UnitAutomation.automatedExplore(unit)
                }
                UnitOrder.STOP_EXPLORATION -> {
                    if (!unit.isExploring())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit is not exploring")
                    unit.action = null
                }
                UnitOrder.AUTOMATE -> {
                    if (unit.isFoundingUnit()) {
                        foundedCityId = autoSettle(unit)
                        disbanded = foundedCityId != null
                    } else {
                        if (unit.isAutomated())
                            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit is already automated")
                        unit.action = UnitActionType.Automate.value
                    }
                }
                UnitOrder.STOP_AUTOMATION -> {
                    if (!unit.isAutomated())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit is not automated")
                    unit.action = null
                    unit.automated = false
                }
                UnitOrder.GUARD -> {
                    if (unit.isGuarding())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit is already guarding")
                    unit.action = UnitActionType.Guard.value
                }
                UnitOrder.SET_UP -> {
                    if (!unit.hasMovement() || unit.isSetUpForSiege())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot set up now")
                    unit.action = UnitActionType.SetUp.value
                }
                UnitOrder.AIR_SWEEP -> {
                    if (!unit.canAttack())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot air-sweep now")
                    unit.action =
                        if (unit.isPreparingAirSweep()) null else UnitActionType.AirSweep.value
                }
                UnitOrder.ESCORT_FORMATION -> {
                    if (unit.isEscorting())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit is already escorting")
                    unit.startEscorting()
                }
                UnitOrder.STOP_ESCORT_FORMATION -> {
                    if (!unit.isEscorting())
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit is not escorting")
                    unit.stopEscorting()
                }
            }
            "${unit.action.takeUnless { disbanded } ?: ""}|$disbanded|${foundedCityId ?: ""}"
        }
        val (action, disbanded, foundedCityId) = receipt.resultJson.split("|")
        return UnitOrderResult(
            command.actorCivId, receipt,
            action = action.ifEmpty { null }, disbanded = disbanded.toBoolean(),
            foundedCityId = foundedCityId.ifEmpty { null }
        )
    }

    private fun executeAttack(command: AttackCommand): AttackResult {
        if ((command.attackerUnitId == null) == (command.attackerCityId == null))
            throw AgentCommandException(
                AgentCommandError.INVALID_ARGUMENT, "Exactly one attacker (unit or city) is required"
            )
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.attack|${command.attackerUnitId}|${command.attackerCityId}|${command.target.x},${command.target.y}",
            snapshotUnitId = command.attackerUnitId
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
                // The same option legal_actions previews for this target.
                val candidate = distinctAttackOptions(unit)
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
            val visualScope = candidateGame.visualCaptureSink
            val visualBefore = visualScope?.city(city)
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
            visualScope?.cityChanged(visualBefore?.city, visualScope.city(city))
            candidateMatch.pendingDecisions.remove(decision)
            // AlertPopup.close parity: the answered alert leaves with the decision.
            conqueringCiv.popupAlerts.removeAll {
                it.type == AlertType.CityConquered && it.value == city.id
            }
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
            val target = candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (target.isMajorCiv())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION,
                    "A major civilization must consent to peace on its own turn. $bilateralPeaceGuidance")
            val diplomacy = civ.getDiplomacyManager(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            if (diplomacy.diplomaticStatus != DiplomaticStatus.War)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Not at war")
            // City-state peace retains its native direct behavior. Major peace
            // reaches this native implementation only through an accepted treaty.
            diplomacy.makePeace()
            diplomacy.diplomaticStatus.name
        }
        return DiplomacyResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private val bilateralPeaceGuidance =
        "Use diplomacy.proposeTrade with {name: \"Peace Treaty\", type: \"Treaty\", amount: 1} " +
            "in BOTH ourOffers and theirOffers, then the recipient must accept with diplomacy.respondTrade on its own turn."

    /** Native PeaceProposal names a third civilization, whose consent it does not obtain. */
    private fun peaceOfferViolation(
        game: GameInfo,
        ourOffers: List<TradeOffer>,
        theirOffers: List<TradeOffer>
    ): AgentCommandException? {
        val allOffers = ourOffers + theirOffers
        if (allOffers.any { it.name == Constants.peaceTreaty }) {
            fun hasOnePeaceTreaty(offers: List<TradeOffer>): Boolean {
                val peaceOffers = offers.filter { it.name == Constants.peaceTreaty }
                return peaceOffers.size == 1 && peaceOffers.single().type == TradeOfferType.Treaty
                    && peaceOffers.single().amount == 1
            }
            if (!hasOnePeaceTreaty(ourOffers) || !hasOnePeaceTreaty(theirOffers))
                return AgentCommandException(AgentCommandError.INVALID_ARGUMENT,
                    "Bilateral peace requires one Peace Treaty of type Treaty and amount 1 on each side. $bilateralPeaceGuidance")
        }
        var majorConsentViolation: AgentCommandException? = null
        for (offer in allOffers.filter { it.type == TradeOfferType.PeaceProposal }) {
            if (offer.amount != 1)
                return AgentCommandException(AgentCommandError.INVALID_ARGUMENT,
                    "PeaceProposal requires a third civilization ID as name and amount 1. $bilateralPeaceGuidance")
            val thirdCiv = game.getCivilizationOrNull(offer.name)
                ?: return AgentCommandException(AgentCommandError.INVALID_ARGUMENT,
                    "PeaceProposal name must be a known third civilization ID. $bilateralPeaceGuidance")
            // Malformed entries on either side take priority over major-consent policy.
            if (thirdCiv.isMajorCiv() && majorConsentViolation == null)
                majorConsentViolation = AgentCommandException(AgentCommandError.ILLEGAL_ACTION,
                    "PeaceProposal cannot obtain a third major civilization's consent. Propose peace to that civilization directly. " +
                        bilateralPeaceGuidance)
        }
        return majorConsentViolation
    }

    /** Runs only on unpublished turn candidates, before native request validation/automation. */
    private fun discardUnsafePeaceRequests(game: GameInfo, civ: Civilization, includeMajorProposals: Boolean) {
        civ.tradeRequests.removeAll { request ->
            val violation = peaceOfferViolation(game, request.trade.ourOffers, request.trade.theirOffers)
            violation != null && (includeMajorProposals || violation.code == AgentCommandError.INVALID_ARGUMENT)
        }
    }

    private fun executeNegotiateCityStatePeace(command: NegotiateCityStatePeaceCommand): DiplomacyResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "human.citystate.negotiatePeace|${command.cityStateId}") { game, match ->
            val civ = requireHumanOnCandidate(game, match, command.actorCivId)
            val cityState = game.getCivilizationOrNull(command.cityStateId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (!civ.isMajorCiv() || !cityState.isCityState || cityState.isDefeated())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No eligible city-state peace target")
            val diplomacy = civ.getDiplomacyManager(cityState)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            val reverse = cityState.getDiplomacyManager(civ)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            // Mirror the native city-state Negotiate Peace button, including the target's war flag.
            if (diplomacy.diplomaticStatus != DiplomaticStatus.War ||
                reverse.hasFlag(DiplomacyFlags.DeclaredWar) ||
                cityState.allyCiv?.let { ally -> civ.getKnownCivs().any { it == ally && it.isAtWarWith(civ) } } == true)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City-state peace cannot be negotiated now")
            val trade = TradeLogic(civ, cityState)
            trade.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
            trade.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
            trade.acceptTrade()
            diplomacy.diplomaticStatus.name
        }
        return DiplomacyResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun buildTrade(
        candidateGame: GameInfo,
        command: ProposeTradeCommand
    ): Trade {
        val ourOffers = command.ourOffers.map { TradeOffer(it.name, it.type, it.amount, candidateGame.speed) }
        val theirOffers = command.theirOffers.map { TradeOffer(it.name, it.type, it.amount, candidateGame.speed) }
        // Inspect the input before TradeOffersList merges duplicate amounts.
        peaceOfferViolation(candidateGame, ourOffers, theirOffers)?.let { throw it }
        val trade = Trade()
        for (offer in ourOffers) trade.ourOffers.add(offer)
        for (offer in theirOffers) trade.theirOffers.add(offer)
        // Duplicate city-state commitments can become malformed only after merging.
        peaceOfferViolation(candidateGame, trade.ourOffers, trade.theirOffers)?.let { throw it }
        return trade
    }

    private fun withTradeReasons(prefix: String, trade: Trade, civ: Civilization, partner: Civilization): String {
        val reasons = tradeInvalidReasons(trade, civ, partner)
        return if (reasons.isEmpty()) prefix else "$prefix: " + reasons.joinToString("; ")
    }

    /**
     * Why [TradeEvaluation.isTradeValid] refuses [trade] between [civ] (ourOffers) and [partner]
     * (theirOffers): each item is re-checked alone by the engine validator, and only failing items
     * are explained. Amounts quoted are those the native trade screen lists for each side.
     */
    private fun tradeInvalidReasons(trade: Trade, civ: Civilization, partner: Civilization): List<String> {
        val evaluation = TradeEvaluation()
        val reasons = ArrayList<String>()
        fun lastCityReason(giver: Civilization) = if (giver == civ) "you cannot trade away every city you own"
            else "${partner.civID} cannot trade away every city it owns"
        for ((offer, ours) in trade.ourOffers.map { it to true } + trade.theirOffers.map { it to false }) {
            val single = Trade().apply { (if (ours) ourOffers else theirOffers).add(offer.copy()) }
            val valid = try { evaluation.isTradeValid(single, civ, partner) } catch (_: Exception) { false }
            if (valid) continue
            val giver = if (ours) civ else partner
            val giverIs = if (ours) "you are" else "${partner.civID} is"
            val giverHas = if (ours) "you have" else "${partner.civID} has"
            reasons += when (offer.type) {
                TradeOfferType.Gold -> "${offer.amount} Gold exceeds the ${giver.gold} Gold $giverHas"
                TradeOfferType.Gold_Per_Turn ->
                    "${offer.amount} Gold per turn exceeds the ${giver.stats.statsForNextTurn.gold.toInt()} Gold per turn $giverHas"
                TradeOfferType.Treaty -> when (offer.name) {
                    Constants.peaceTreaty -> "Peace Treaty: you are not at war with ${partner.civID} " +
                        "(status ${civ.getDiplomacyManager(partner)?.diplomaticStatus?.name}); it only ends a war"
                    Constants.researchAgreement -> "Research Agreement: one is already active with ${partner.civID}"
                    Constants.defensivePact -> "Defensive Pact: one is already in force with ${partner.civID}"
                    else -> "${offer.name} is not available now"
                }
                TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource, TradeOfferType.Stockpiled_Resource ->
                    "${offer.amount} ${offer.name} exceeds the ${giver.getCivResourcesByName()[offer.name] ?: 0} $giverHas"
                TradeOfferType.Introduction -> if (ours) "${partner.civID} already knows ${offer.name}"
                    else "you already know ${offer.name}"
                TradeOfferType.WarDeclaration -> "${if (ours) "you" else partner.civID} cannot declare war on ${offer.name} now"
                TradeOfferType.PeaceProposal -> "$giverIs not at war with ${offer.name}"
                TradeOfferType.City -> if (giver.cities.any { it.id == offer.name }) lastCityReason(giver)
                    else "${if (ours) "you do" else "${partner.civID} does"} not own city ${offer.name}"
                TradeOfferType.Embassy -> "Embassy: an embassy is already established or the giver has no capital"
                else -> "${offer.name} (${offer.type}) is not tradeable now"
            }
        }
        // Items valid alone can still hand over every city together.
        for ((giver, offers) in listOf(civ to trade.ourOffers, partner to trade.theirOffers))
            if (giver.hasEverOwnedOriginalCapital && offers.count { it.type == TradeOfferType.City } == giver.cities.size)
                reasons += lastCityReason(giver)
        return reasons.distinct()
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
            val trade = buildTrade(candidateGame, command)
            // Engine NPEs on unmet-civ lookups surface as a plain rejection.
            val valid = try {
                TradeEvaluation().isTradeValid(trade, civ, target)
            } catch (ex: Exception) {
                throw AgentCommandException(
                    AgentCommandError.ILLEGAL_ACTION, "Offer is not valid: ${ex.message}"
                )
            }
            if (!valid)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION,
                    withTradeReasons("Offer is not valid", trade, civ, target))
            // Stored recipient-perspective, mirroring TradeView proposals.
            target.tradeRequests.add(TradeRequest(command.actorCivId, trade.reverse()))
            target.tradeRequests.count { it.requestingCiv == command.actorCivId }.toString()
        }
        return TradeProposedResult(command.actorCivId, receipt, receipt.resultJson.toInt())
    }

    private fun executeRespondTrade(command: RespondTradeCommand): TradeRespondedResult {
        val receipt = runGameplayMutation(
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
            peaceOfferViolation(candidateGame, request.trade.ourOffers, request.trade.theirOffers)?.let { throw it }
            if (!TradeEvaluation().isTradeValid(request.trade, civ, requester))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION,
                    withTradeReasons("Offer is no longer valid", request.trade, civ, requester))
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

    private fun requireHumanOnCandidate(game: GameInfo, match: AgentMatchState, actor: String): Civilization {
        if (match.controllers[actor]?.kind != ControllerKind.HUMAN)
            throw AgentCommandException(AgentCommandError.AUTHORITY_REVOKED, "This acknowledgement requires a human controller")
        return game.getCivilization(actor)
    }

    private fun executeDismissAlert(command: DismissAlertCommand): HumanPresentationResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "human.dismissAlert|${command.alertType}|${command.alertValue}", collectSightings = false) { game, match ->
            val civ = requireHumanOnCandidate(game, match, command.actorCivId)
            if (!isDismissibleAgentAlert(command.alertType))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "This alert requires its own decision")
            val alert = civ.popupAlerts.firstOrNull { it.type == command.alertType && it.value == command.alertValue }
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such pending informational alert")
            civ.popupAlerts.remove(alert)
            "dismissed"
        }
        return HumanPresentationResult(command.actorCivId, receipt)
    }

    private fun executeDismissVoteResults(command: DismissDiplomaticVoteResultsCommand): HumanPresentationResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "human.dismissVoteResults", collectSightings = false) { game, match ->
            val civ = requireHumanOnCandidate(game, match, command.actorCivId)
            if (!civ.shouldShowDiplomaticVotingResults())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No pending diplomatic voting results")
            civ.addFlag(CivFlags.ShowDiplomaticVotingResults.name, -1)
            "dismissed"
        }
        return HumanPresentationResult(command.actorCivId, receipt)
    }

    private fun executeMarkPolicyPickerOpened(command: MarkPolicyPickerOpenedCommand): HumanPresentationResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "human.policyPickerOpened", collectSightings = false) { game, match ->
            requireHumanOnCandidate(game, match, command.actorCivId).policies.shouldOpenPolicyPicker = false
            "opened"
        }
        return HumanPresentationResult(command.actorCivId, receipt)
    }

    private fun executeChooseFreeGreatPerson(command: ChooseFreeGreatPersonCommand): FreeGreatPersonChosenResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "human.chooseFreeGreatPerson|${command.unitName}") { game, match ->
            val civ = requireHumanOnCandidate(game, match, command.actorCivId)
            val unit = civ.greatPeople.chooseFreeGreatPerson(command.unitName)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No available free great person or space to place it")
            unit.id.toString()
        }
        return FreeGreatPersonChosenResult(command.actorCivId, receipt, receipt.resultJson.toInt())
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
            "city.citizens|${command.cityId}|${command.op}|${command.tilePos}|${command.specialist}|${command.focus}|${command.flag}" +
                if (command.resetLocked) "|resetLocked=true" else ""
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
                    if (!command.flag) city.reassignPopulation()
                }
                CitizenOp.SET_AVOID_GROWTH -> {
                    city.avoidGrowth = command.flag
                    city.reassignPopulation()
                }
                CitizenOp.TOGGLE_AVOID_GROWTH -> {
                    city.avoidGrowth = !city.avoidGrowth
                    city.reassignPopulation()
                }
                CitizenOp.REASSIGN -> city.reassignPopulation(command.resetLocked)
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
            "unit.build|${command.unitId}|${command.improvementName}",
            snapshotUnitId = command.unitId
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val improvement = candidateGame.ruleset.tileImprovements[command.improvementName]
                ?: throw AgentCommandException(
                    AgentCommandError.INVALID_ARGUMENT, "Unknown improvement ${command.improvementName}"
                )
            val context = WorkerBuildContext(candidateGame, command.actorCivId, unit)
            val tile = unit.getTile()
            if (isWaterImprovementCreation(context, improvement)) {
                // Work Boats: the engine's "Create [improvement]" action, which uses up the unit.
                requireWaterImprovementAllowed(context, improvement)
                tile.setImprovement(improvement, unit.civ, unit)
                unit.destroy()
                return@runGameplayMutation "0"
            }
            requireWorkerBuildAllowed(context, improvement)
            tile.startWorkingOnImprovement(improvement, unit.civ, unit)
            unit.action = null
            tile.turnsToImprovement.toString()
        }
        return ImprovementStartedResult(command.actorCivId, receipt, receipt.resultJson.toInt())
    }

    /** Internal human clear-then-build intent: one candidate and one receipt. */
    private fun executeQueueWorkerImprovements(command: QueueWorkerImprovementsCommand): WorkerImprovementsQueuedResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "unit.queueImprovements|${command.unitId}|${json.toJson(command.improvementNames)}") { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            startValidatedWorkerQueue(WorkerBuildContext(candidateGame, command.actorCivId, unit),
                command.improvementNames).toString()
        }
        return WorkerImprovementsQueuedResult(command.actorCivId, receipt, receipt.resultJson.toInt())
    }

    private fun executePromote(command: PromoteUnitCommand): UnitPromotedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.promote|${command.unitId}|${command.promotionName}",
            snapshotUnitId = command.unitId
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
                GreatPersonAction.GOLDEN_AGE -> {
                    val timed = unit.getMatchingUniques(UniqueType.OneTimeEnterGoldenAgeTurns).firstOrNull()
                    val instant = timed
                        ?: unit.getMatchingUniques(UniqueType.OneTimeEnterGoldenAge).firstOrNull()
                        ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot start a golden age")
                    val turns = if (timed != null) timed.params[0].toInt() else 10
                    civ.goldenAges.enterGoldenAge(turns)
                    unit.consume()
                    "golden-age"
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

    private fun executeGiftImprovement(command: GiftImprovementToCityStateCommand): ImprovementGiftedResult {
        val receipt = runGameplayMutation(command.actorCivId, command.actionId, command.expectedRevision,
            "citystate.giftImprovement|${command.cityStateId}|${command.tilePos}|${command.improvementName}") { game, _ ->
            val civ = game.getCivilization(command.actorCivId)
            val cityState = game.getCivilizationOrNull(command.cityStateId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (!cityState.isCityState || !civ.knows(cityState) || civ.isAtWarWith(cityState) ||
                (cityState.getDiplomacyManager(civ)?.getInfluence() ?: 0f) < 60f)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City-state improvement gift is unavailable")
            if (civ.gold < 200)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Insufficient gold")
            val tile = resolveTile(game, command.tilePos)
            val resource = tile.tileResource
            val improvement = game.ruleset.tileImprovements[command.improvementName]
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown improvement ${command.improvementName}")
            if (tile.getOwner() !== cityState || resource == null || !cityState.canSeeResource(resource) ||
                resource.resourceType == ResourceType.Bonus ||
                (tile.improvement != null && resource.isImprovedBy(tile.improvement!!)) ||
                !resource.isImprovedBy(improvement.name) || improvement.turnsToBuild == -1 ||
                !tile.improvementFunctions.canBuildImprovement(improvement, cityState.state))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Resource cannot receive that improvement")
            civ.addGold(-200)
            tile.stopWorkingOnImprovement()
            tile.setImprovement(improvement, cityState)
            cityState.cache.updateCivResources()
            ""
        }
        return ImprovementGiftedResult(command.actorCivId, receipt)
    }

    private fun executePillage(command: PillageCommand): PillagedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.pillage|${command.unitId}",
            snapshotUnitId = command.unitId
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
            "unit.repair|${command.unitId}",
            snapshotUnitId = command.unitId
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val tile = unit.getTile()
            val repairTurns = requireWorkerRepairAllowed(WorkerBuildContext(candidateGame, command.actorCivId, unit))
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

    /** Resolves a [com.unciv.logic.civilization.diplomacy.Demand] by enum name. */
    private fun resolveDemand(civ: Civilization, demandName: String) =
        com.unciv.logic.civilization.diplomacy.Demand.entries.firstOrNull {
            it.name.equals(demandName, ignoreCase = true)
        } ?: throw AgentCommandException(
            AgentCommandError.INVALID_ARGUMENT, "Unknown demand $demandName"
        )

    private fun executeDemand(command: DemandCommand): DemandIssuedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.demand|${command.targetCivId}|${command.demandName}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val target = candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (civ.getDiplomacyManager(command.targetCivId) == null)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            if (civ.isDefeated() || target.isDefeated())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Defeated civilizations demand nothing")
            val demand = resolveDemand(civ, command.demandName)
            if (!demand.show(civ))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "${demand.name} is not available")
            // agreedToDemand lives on the DEMANDER's manager toward the target
            // (agreeToDemand mirrors it there from the promisee's side).
            if (civ.getDiplomacyManager(command.targetCivId)!!.hasFlag(demand.agreedToDemand))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Demand already agreed to")
            if (target.popupAlerts.any { it.type == demand.demandAlert && it.value == civ.civID })
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Demand already pending")
            // Identical to the MajorCivDiplomacyTable demands button.
            target.popupAlerts.add(PopupAlert(demand.demandAlert, civ.civID))
            "issued"
        }
        return DemandIssuedResult(command.actorCivId, receipt, receipt.resultJson == "issued")
    }

    private fun executeRespondDemand(command: RespondDemandCommand): DemandRespondedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.respondDemand|${command.requesterCivId}|${command.demandName}|${command.accept}"
        ) { candidateGame, candidateMatch ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val requester = candidateGame.getCivilizationOrNull(command.requesterCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val demand = resolveDemand(civ, command.demandName)
            val alert = civ.popupAlerts.firstOrNull {
                it.type == demand.demandAlert && it.value == requester.civID
            } ?: throw AgentCommandException(
                AgentCommandError.INVALID_ARGUMENT, "No such demand pending from ${requester.civID}"
            )
            val diplomacy = civ.getDiplomacyManager(command.requesterCivId)!!
            if (command.accept) diplomacy.agreeToDemand(demand) else {
                diplomacy.refuseDemand(demand)
                // UI parity: refusing "Do not attack us" is an immediate
                // declaration of war (AlertPopup.addDemand).
                if (demand == com.unciv.logic.civilization.diplomacy.Demand.DoNotAttackUs)
                    diplomacy.declareWar()
            }
            civ.popupAlerts.remove(alert)
            // Resolution through a command clears its decision, exactly as
            // conquest disposition does (nothing automatic, ever).
            val decisionId = "demand-response:${demand.name}:${requester.civID}"
            candidateMatch.pendingDecisions.removeAll { it.decisionId == decisionId }
            if (command.accept) "accepted" else "refused"
        }
        return DemandRespondedResult(command.actorCivId, receipt, receipt.resultJson == "accepted")
    }

    private fun executeDenounce(command: DenounceCommand): DenouncedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.denounce|${command.targetCivId}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val diplomacy = civ.getDiplomacyManager(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            if (civ.isDefeated())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Defeated civilizations denounce no one")
            if (diplomacy.diplomaticStatus == DiplomaticStatus.War)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot denounce wartime enemies")
            if (diplomacy.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.Denunciation))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Already denounced")
            diplomacy.denounce()
            "denounced"
        }
        return DenouncedResult(command.actorCivId, receipt)
    }

    private fun executeRenameCity(command: RenameCityCommand): CityRenamedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "city.rename|${command.cityId}|${command.newName}"
        ) { candidateGame, _ ->
            if (command.newName.isBlank())
                throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Name cannot be empty")
            val city = candidateGame.getCities().firstOrNull {
                it.id == command.cityId && it.civ.civID == command.actorCivId
            } ?: throw AgentCommandException(
                AgentCommandError.INVALID_ARGUMENT, "Unknown or foreign city ${command.cityId}"
            )
            city.name = command.newName.trim()
            city.name
        }
        return CityRenamedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeAnnexCity(command: AnnexCityCommand): CityAnnexedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "city.annex|${command.cityId}"
        ) { candidateGame, _ ->
            val city = requireOwnCity(candidateGame, command.actorCivId, command.cityId)
            // Gates mirror CityScreen.updateAnnexAndRazeCityButton.
            if (!city.isPuppet)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City is not a puppet")
            if (candidateGame.getCivilization(command.actorCivId)
                    .hasUnique(UniqueType.MayNotAnnexCities))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Civilization cannot annex cities")
            city.annexCity()
            "annexed"
        }
        return CityAnnexedResult(command.actorCivId, receipt)
    }

    private fun executeSetRazing(command: SetRazingCommand): RazingSetResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "city.raze|${command.cityId}|${command.raze}"
        ) { candidateGame, _ ->
            val city = requireOwnCity(candidateGame, command.actorCivId, command.cityId)
            val civ = candidateGame.getCivilization(command.actorCivId)
            if (command.raze) {
                if (city.isBeingRazed)
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City is already being razed")
                if (!city.canBeDestroyed())
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City cannot be destroyed")
                if (civ.hasUnique(UniqueType.MayNotAnnexCities))
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Civilization cannot raze cities")
            } else if (!city.isBeingRazed)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City is not being razed")
            city.isBeingRazed = command.raze
            command.raze.toString()
        }
        return RazingSetResult(command.actorCivId, receipt, receipt.resultJson.toBoolean())
    }

    private fun executeStopImprovement(command: StopImprovementCommand): ImprovementStoppedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.stopImprovement|${command.unitId}",
            snapshotUnitId = command.unitId
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val tile = unit.getTile()
            requireWorkerCancelAllowed(WorkerBuildContext(candidateGame, command.actorCivId, unit))
            tile.stopWorkingOnImprovement()
            "stopped"
        }
        return ImprovementStoppedResult(command.actorCivId, receipt)
    }

    private fun executeSwapUnits(command: SwapUnitsCommand): UnitsSwappedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.swap|${command.unitId}|${command.destination.x},${command.destination.y}",
            snapshotUnitId = command.unitId
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val tile = resolveTile(candidateGame, command.destination)
            if (!unit.movement.canUnitSwapTo(tile))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot swap to that tile")
            val start = unit.getTile().position
            unit.movement.swapMoveToTile(tile)
            val finalPosition = unit.getTile().position
            if (finalPosition == start)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Swap did not move the unit")
            "${finalPosition.x},${finalPosition.y}"
        }
        val (x, y) = receipt.resultJson.split(",")
        return UnitsSwappedResult(command.actorCivId, receipt, HexCoord(x.toInt(), y.toInt()))
    }

    private fun executeRenameUnit(command: RenameUnitCommand): UnitRenamedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.rename|${command.unitId}|${command.newName}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            // UI parity: an empty input clears back to the base name.
            unit.instanceName = command.newName.trim().ifEmpty { null }
            unit.instanceName ?: ""
        }
        return UnitRenamedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeConnectRoad(command: ConnectRoadCommand): RoadConnectionStartedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.connectRoad|${command.unitId}|${command.destination.x},${command.destination.y}",
            snapshotUnitId = command.unitId
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val tile = resolveTile(candidateGame, command.destination)
            // Mirrors OverlayButtonData.connectRoadToTargetTile.
            if (!tile.isLand || tile.isImpassible())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot connect a road there")
            if (unit.movement.getRoadPath(tile) == null)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No road path to that tile")
            unit.automatedRoadConnectionDestination = tile.position
            unit.automatedRoadConnectionPath = null
            unit.action = UnitActionType.ConnectRoad.value
            unit.automated = true
            com.unciv.logic.automation.unit.UnitAutomation.automateUnitMoves(unit)
            "connecting"
        }
        return RoadConnectionStartedResult(command.actorCivId, receipt)
    }

    private fun executeTribute(command: TributeCommand): TributeTakenResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "citystate.tribute|${command.cityStateId}|${command.demandWorker}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val cityState = candidateGame.getCivilizationOrNull(command.cityStateId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (!cityState.isCityState)
                throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Not a city-state")
            if (civ.isAtWarWith(cityState))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot demand tribute at war")
            if (cityState.cityStateFunctions.getTributeWillingness(civ, command.demandWorker) < 0)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City-state would refuse")
            if (command.demandWorker) cityState.cityStateFunctions.tributeWorker(civ)
            else cityState.cityStateFunctions.tributeGold(civ)
            command.demandWorker.toString()
        }
        return TributeTakenResult(command.actorCivId, receipt, receipt.resultJson.toBoolean())
    }

    private fun executeProtection(command: ProtectionCommand): ProtectionSetResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "citystate.protection|${command.cityStateId}|${command.pledge}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val cityState = candidateGame.getCivilizationOrNull(command.cityStateId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (!cityState.isCityState)
                throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Not a city-state")
            if (command.pledge) {
                if (!cityState.cityStateFunctions.otherCivCanPledgeProtection(civ))
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot pledge protection now")
                cityState.cityStateFunctions.addProtectorCiv(civ)
            } else {
                if (!cityState.cityStateFunctions.otherCivCanWithdrawProtection(civ))
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot revoke protection now")
                cityState.cityStateFunctions.removeProtectorCiv(civ)
            }
            command.pledge.toString()
        }
        return ProtectionSetResult(command.actorCivId, receipt, receipt.resultJson.toBoolean())
    }

    private fun executeDiplomaticMarriage(command: DiplomaticMarriageCommand): CityStateMarriedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "citystate.marry|${command.cityStateId}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val cityState = candidateGame.getCivilizationOrNull(command.cityStateId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (!civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Civilization cannot marry city-states")
            if (!cityState.cityStateFunctions.canBeMarriedBy(civ))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot marry this city-state")
            cityState.cityStateFunctions.diplomaticMarriage(civ)
            "married"
        }
        return CityStateMarriedResult(command.actorCivId, receipt)
    }

    private fun executeGiftUnit(command: GiftUnitCommand): UnitGiftedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "unit.gift|${command.unitId}"
        ) { candidateGame, _ ->
            val unit = requireOwnUnit(candidateGame, command.actorCivId, command.unitId)
            val tile = unit.getTile()
            val recipient = tile.getOwner()
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit must be in another civilization's territory to gift")
            if (recipient == unit.civ)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot gift unit to own civilization")
            if (unit.civ.isAtWarWith(recipient))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Cannot gift unit to an enemy at war")
            if (unit.isTransported)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Transported units cannot be gifted")
            if (!unit.hasMovement())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit has no movement left")

            if (recipient.isCityState) {
                if (!unit.isMilitary() && unit.getMatchingUniques(
                    UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true
                ).none { unit.matchesFilter(it.params[1]) })
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "City-states only accept military units or gift-enabled units")
                val diplomacy = recipient.getDiplomacyManager(unit.civ)
                    ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomacy with city-state")
                for (unique in unit.getMatchingUniques(
                    UniqueType.GainInfluenceWithUnitGiftToCityState,
                    checkCivInfoUniques = true
                )) {
                    if (unit.matchesFilter(unique.params[1])) {
                        diplomacy.addInfluence(unique.params[0].toFloat() - 5f)
                        break
                    }
                }
                diplomacy.addInfluence(5f)
            } else {
                if (!tile.isFriendlyTerritory(unit.civ))
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Major civ territory must be friendly to gift unit")
                recipient.getDiplomacyManager(unit.civ)?.addModifier(DiplomaticModifiers.GaveUsUnits, 5f)
            }

            if (recipient.isCityState && unit.isGreatPerson()) {
                unit.destroy()
            } else {
                unit.gift(recipient)
            }
            recipient.civID
        }
        return UnitGiftedResult(command.actorCivId, receipt, receipt.resultJson)
    }

    private fun executeRetractTrade(command: RetractTradeCommand): TradeRetractedResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.retractTrade|${command.targetCivId}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val target = candidateGame.getCivilizationOrNull(command.targetCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val removed = target.tradeRequests.removeAll { it.requestingCiv == civ.civID }
            if (!removed)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No pending offer to retract")
            "retracted"
        }
        return TradeRetractedResult(command.actorCivId, receipt)
    }

    private fun executeRespondProtectorAlert(command: RespondProtectorAlertCommand): ProtectorAlertAnsweredResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.respondProtectorAlert|${command.cityStateId}|${command.attackerCivId}|${command.choice}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val cityState = candidateGame.getCivilizationOrNull(command.cityStateId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val attacker = candidateGame.getCivilizationOrNull(command.attackerCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            // The alert only exists while the pledge does.
            if (!cityState.cityStateFunctions.getProtectorCivs().contains(civ))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No protection pledge to answer for")
            val diplomacy = civ.getDiplomacyManager(command.attackerCivId)!!
            when (command.choice) {
                ProtectorAlertChoice.SIDE_WITH -> diplomacy.sideWithCityState()
                ProtectorAlertChoice.DECLARE_WAR -> {
                    if (civ.isAtWarWith(attacker))
                        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Already at war")
                    diplomacy.sideWithCityState()
                    val warType = if (command.alliedCityState) WarType.AlliedCityStateWar
                    else WarType.ProtectedCityStateWar
                    diplomacy.declareWar(DeclareWarReason(warType, cityState))
                    cityState.getDiplomacyManager(civ)!!.influence += 20f
                }
                ProtectorAlertChoice.BREAK_PLEDGE -> {
                    civ.addNotification(
                        "You have broken your Pledge to Protect [${cityState.civName}]!",
                        cityState.cityStateFunctions.getNotificationActions(),
                        com.unciv.logic.civilization.NotificationCategory.Diplomacy, cityState.civName)
                    cityState.cityStateFunctions.removeProtectorCiv(civ, forced = true)
                }
            }
            // AlertPopup.close parity.
            civ.popupAlerts.removeAll {
                (it.type == AlertType.BulliedProtectedMinor
                    || it.type == AlertType.AttackedProtectedMinor
                    || it.type == AlertType.AttackedAllyMinor)
                    && it.value == "${attacker.civID}@${cityState.civID}"
            }
            command.choice.name
        }
        return ProtectorAlertAnsweredResult(
            command.actorCivId, receipt, ProtectorAlertChoice.valueOf(receipt.resultJson))
    }

    private fun executeRespondFriendshipOffer(command: RespondFriendshipOfferCommand): FriendshipOfferAnsweredResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.respondFriendshipOffer|${command.requesterCivId}|${command.accept}"
        ) { candidateGame, _ ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            candidateGame.getCivilizationOrNull(command.requesterCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            val diplomacy = civ.getDiplomacyManager(command.requesterCivId)
                ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic contact")
            if (command.accept) diplomacy.signDeclarationOfFriendship()
            else diplomacy.otherCivDiplomacy().setFlag(
                com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
            civ.popupAlerts.removeAll {
                it.type == AlertType.DeclarationOfFriendship && it.value == command.requesterCivId
            }
            if (command.accept) "accepted" else "declined"
        }
        return FriendshipOfferAnsweredResult(command.actorCivId, receipt, receipt.resultJson == "accepted")
    }

    private fun executeCastVote(command: CastVoteCommand): VoteCastResult {
        val receipt = runGameplayMutation(
            command.actorCivId, command.actionId, command.expectedRevision,
            "diplomacy.castVote|${command.targetCivId ?: "abstain"}"
        ) { candidateGame, candidateMatch ->
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
            if (!civ.flagsCountdown.containsKey(com.unciv.logic.civilization.CivFlags.TurnsTillNextDiplomaticVote.name)
                || !civ.mayVoteForDiplomaticVictory())
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No diplomatic vote is open")
            val target = command.targetCivId?.takeIf { it.isNotBlank() }
            if (target != null) {
                val targetCiv = candidateGame.getCivilizationOrNull(target)
                    ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization")
                if (!targetCiv.isMajorCiv() || targetCiv.isDefeated() || targetCiv == civ)
                    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Invalid vote target")
            }
            civ.diplomaticVoteForCiv(target)
            val decisionId = "diplomatic-vote:${civ.civID}"
            candidateMatch.pendingDecisions.removeAll { it.decisionId == decisionId }
            target ?: "abstain"
        }
        return VoteCastResult(command.actorCivId, receipt, receipt.resultJson.takeIf { it != "abstain" })
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
            "controller.set|${command.targetCivId}|${command.kind}|${command.profile?.profileRevision ?: "-"}",
            collectSightings = false,
            requiresActiveActor = false
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

        val candidateGame = deserializeGame(json.toJson(committedGame), collectSightings = true)
        val visualScope = beginVisualCapture(candidateGame, "internal:begin")
        try {
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
        } finally {
            visualScope.close()
        }
    }

    private fun pauseCommitted(reason: String) {
        committedMatch.matchPaused = true
        committedMatch.matchPausedReason = reason
        refreshMatchPublication()
    }

    private fun requireActiveActor(actorCivId: String) {
        if (actorCivId != committedGame.currentPlayer)
            throw AgentCommandException(AgentCommandError.NOT_YOUR_TURN, "Only the active civilization may change gameplay")
    }

    private fun executeEndTurn(command: EndTurnCommand): TurnEndedResult {
        val fingerprint = "end-turn"
        (actionReceipts[command.actionId] ?: committedMatch.diplomaticMessageReceipts[command.actionId])?.let { prior ->
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
        requireActiveActor(command.actorCivId)
        if (command.expectedRevision != committedMatch.stateRevision)
            throw AgentCommandException(
                AgentCommandError.STALE_STATE,
                "Expected revision ${command.expectedRevision} but committed is ${committedMatch.stateRevision}",
                currentRevision = committedMatch.stateRevision
            )

        if (command.actorCivId != committedGame.currentPlayer)
            throw AgentCommandException(AgentCommandError.NOT_YOUR_TURN,
                "Only the active civilization may end its turn")

        if (committedMatch.controllers[command.actorCivId]?.kind == ControllerKind.EXTERNAL_AGENT) {
            val unread = committedMatch.diplomaticMessages.filter {
                it.isDeliveredTo(command.actorCivId, committedMatch.turnSerial) && it.acknowledgedTurnSerial == null
            }.map { it.messageId }
            if (unread.isNotEmpty())
                throw AgentCommandException(AgentCommandError.MESSAGES_UNACKNOWLEDGED,
                    "Acknowledge delivered incoming messages first: ${unread.joinToString(",")}",
                    messageIds = unread)
        }

        val blocking = committedMatch.pendingDecisions
            .filter { it.owningCivId == command.actorCivId && it.classification == DecisionClassification.MANDATORY }
        if (blocking.isNotEmpty())
            throw AgentCommandException(
                AgentCommandError.DECISIONS_PENDING,
                "Resolve mandatory decisions first: ${blocking.joinToString(",") { it.decisionId }}. " +
                    "Missing decisions are never chosen automatically."
            )

        // Unsupported legacy religion/espionage choices must never fall through to a turn commit.
        // Normal validated Vanilla matches cannot enter these states.
        val currentCiv = committedGame.getCivilization(command.actorCivId)
        val religion = currentCiv.religionManager
        if (religion.canFoundOrExpandPantheon() || religion.hasFreeBeliefs() ||
            religion.religionState in setOf(ReligionState.FoundingReligion, ReligionState.EnhancingReligion) ||
            committedGame.isEspionageEnabled() && currentCiv.espionageManager.shouldShowMoveSpies())
            throw AgentCommandException(AgentCommandError.DECISIONS_PENDING,
                "This saved match has an unsupported religion or espionage choice; resolve it outside agent mode")

        mutationInFlight = command.actionId
        var visualScope: CaptureScope? = null
        try {
            val candidateGame = deserializeGame(json.toJson(committedGame), collectSightings = true)
            visualScope = beginVisualCapture(candidateGame, command.actionId)
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
            finishStrategicTurn(candidateGame, candidateMatch, command.actorCivId)

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
            visualScope?.close()
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
                discardUnsafePeaceRequests(candidateGame, civ, includeMajorProposals = true)
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
        // Malformed legacy peace must not crash native start-turn validation.
        // Well-formed major mediation remains visible for an explicit decline;
        // accepting it still passes through the consent guard above.
        discardUnsafePeaceRequests(candidateGame, civ, includeMajorProposals = false)
        TurnManager(civ).startTurn(null, suppressAutomation = true)
        candidateGame.currentPlayer = civ.civID
        candidateGame.currentPlayerCiv = civ
        NationControl.handoffToCurrentHuman(candidateGame, candidateMatch)
        candidateGame.currentTurnStartTime = System.currentTimeMillis()
        candidateMatch.turnPhase = TurnPhase.AWAITING_DECISIONS
        candidateMatch.turnSerial++
        // Reconcile the previous committed own end with this admitted state, including effects
        // applied between turns and by startTurn. Publish it before the provider's opening read.
        admitStrategicTurn(candidateGame, candidateMatch, civId)
        // Delivery is persisted with the turn transition. Reads and provider retries never drain it.
        candidateMatch.diplomaticMessages.filter {
            it.targetCivId == civId && it.deliveredTurnSerial == null &&
                it.sentTurnSerial < candidateMatch.turnSerial
        }.forEach { it.deliveredTurnSerial = candidateMatch.turnSerial }
        // Received demands surface as MANDATORY decisions for commandable
        // civs — the agent inspector path, never a modal popup (plan §11.2).
        // Built-in AI answers them through its own automation.
        for (demand in com.unciv.logic.civilization.diplomacy.Demand.entries) {
            for (alert in civ.popupAlerts.filter {
                    it.type == demand.demandAlert
                        && candidateGame.getCivilizationOrNull(it.value) != null
                }) {
                val decisionId = "demand-response:${demand.name}:${alert.value}"
                if (candidateMatch.pendingDecisions.any { it.decisionId == decisionId }) continue
                val options = ArrayList<HashMap<String, String>>()
                options.add(
                    hashMapOf(
                        "optionId" to "accept", "demand" to demand.name,
                        "requesterCivId" to alert.value,
                        "display" to demand.acceptDemandText
                    )
                )
                options.add(
                    hashMapOf(
                        "optionId" to "refuse", "demand" to demand.name,
                        "requesterCivId" to alert.value,
                        "display" to demand.refuseDemandText
                    )
                )
                candidateMatch.pendingDecisions.add(
                    PendingDecision(
                        decisionId, "demand-response", civId,
                        "alert:${demand.demandAlert.name}:${alert.value}",
                        DecisionClassification.MANDATORY, options
                    )
                )
            }
        }
        // A due diplomatic vote is MANDATORY for commandable civs: options
        // cover every living foreign major civ plus abstention. The
        // TurnsTillNextDiplomaticVote flag must EXIST (UN built, vote cycle
        // started) — an absent flag reads as 0 and must not imply a due vote.
        if (civ.flagsCountdown.containsKey(com.unciv.logic.civilization.CivFlags.TurnsTillNextDiplomaticVote.name)
            && civ.mayVoteForDiplomaticVictory()) {
            val decisionId = "diplomatic-vote:${civ.civID}"
            if (candidateMatch.pendingDecisions.none { it.decisionId == decisionId }) {
                val options = ArrayList<HashMap<String, String>>()
                options.add(hashMapOf("optionId" to "abstain", "targetCivId" to "", "display" to "Abstain"))
                for (candidate in candidateGame.civilizations) {
                    if (!candidate.isMajorCiv() || candidate.isDefeated() || candidate == civ) continue
                    options.add(
                        hashMapOf(
                            "optionId" to "vote", "targetCivId" to candidate.civID,
                            "display" to "Vote for ${candidate.civName}"
                        )
                    )
                }
                candidateMatch.pendingDecisions.add(
                    PendingDecision(
                        decisionId, "diplomatic-vote", civId, "un-vote",
                        DecisionClassification.MANDATORY, options
                    )
                )
            }
        }
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
        captureVictoryProgress(candidateGame ?: committedGame, candidateMatch)
        candidateGame?.agentMapSightings?.let { sightings ->
            sightings.captureAll(candidateGame, candidateMatch)
            sightings.flushInto(candidateMatch, candidateMatch.stateRevision)
            candidateGame.agentMapSightings = null
        }
        val visualScope = candidateGame?.visualCaptureSink
        visualScope?.finish(candidateMatch.stateRevision)
        val gameJson = if (candidateGame != null) json.toJson(candidateGame) else json.toJson(committedGame)
        val matchJson = AgentOrdinarySave.encode(candidateMatch)
        try {
            writer.write(gameJson, matchJson)
        } catch (ex: Exception) {
            // Discard the candidate, preserve prior committed state, pause
            // with the reason. No success receipt escapes.
            committedMatch.matchPaused = true
            committedMatch.matchPausedReason = "Checkpoint persistence failed: ${ex.message}"
            refreshMatchPublication()
            throw AgentCommandException(AgentCommandError.INTERNAL_ERROR, "Checkpoint persistence failed", null)
        }
        if (candidateGame != null) committedGame = candidateGame
        committedMatch = candidateMatch
        committedMatchJson = matchJson
        publicationGeneration++
        // Viewing authority may change during a turn/handoff. Never append old-view facts after that boundary.
        if (visualCaptureSession?.view?.viewerCivId == committedMatch.localViewingCivId &&
            visualCaptureSession?.view?.spectatorMode == committedMatch.localViewingCivId?.let { isSpectatorViewer(it) })
            visualScope?.publishCommitted()
    }

    /** Versioned bundle for save/reopen and recovery scans. */
    fun saveToJson(): String {
        val persistedGame = committedGame
        return metadataJson.toJson(
            AgentBundle(
                json.toJson(persistedGame),
                committedMatchJson
            )
        )
    }

    //endregion
    //region Internals

    private fun requireController(civId: String): CivilizationController =
        committedMatch.controllers[civId]
            ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization $civId")

    private fun deserializeGame(gameJson: String, collectSightings: Boolean = false): GameInfo {
        val game = json.fromJson(GameInfo::class.java, gameJson)
        // Canonical/candidate worlds never contain the presentation envelope.
        game.agentMatchStateJson = null
        game.version = com.unciv.logic.CompatibilityVersion.forGame(game)
        // Controlled transient restoration on the unpublished candidate.
        // Agent policy is installed before restoration (executeMove binds it
        // above); restoration itself must not make unsolicited choices -
        // including the settings-driven queue auto-fill.
        game.suppressAutomationDuringTransients = true
        game.agentNormalVisibility = true
        game.setTransients()
        if (collectSightings) game.agentMapSightings = AgentMapSightings()
        return game
    }

    /**
     * Serialized committed state for UI publication (AI_AGENT_PLAN.md §9).
     * Callers hold the host lock — coordinator mutations are single-writer
     * by contract — and run this OFF the GL thread.
     */
    fun serializeCommittedGameForPublication(): String = json.toJson(committedGame)

    fun serializeCommittedMatchForPublication(): String = committedMatchJson

    /** Includes metadata-only commits, whose gameplay revision intentionally does not advance. */
    fun getPublicationGeneration(): Long = publicationGeneration

    private fun refreshMatchPublication() {
        committedMatchJson = AgentOrdinarySave.encode(committedMatch)
        publicationGeneration++
    }

    /** Restores a publication snapshot; mirrors deserializeGame's no-surprises policy. */
    fun deserializeForPublication(gameJson: String): GameInfo = deserializeGame(gameJson)

    private fun rebuildHotIndex() {
        actionReceipts.clear()
        for (receipt in committedMatch.currentTurnReceipts)
            actionReceipts[receipt.actionId] = receipt
    }

    companion object {
        /** Restores a publication snapshot; no-surprises restoration policy. */
        fun deserializeForPublication(gameJson: String): GameInfo {
            // Different hosts may deserialize concurrently; Gdx Json has mutable parser state.
            val game = json().fromJson(GameInfo::class.java, gameJson)
            game.suppressAutomationDuringTransients = true
            game.agentNormalVisibility = true
            game.setTransients()
            return game
        }

        /** Reloads a coordinator from [saveToJson]; hot dedup scope is restored with it. */
        fun loadFromJson(
            bundleJson: String,
            writer: CheckpointWriter = InMemoryCheckpointWriter(),
            policy: AgentExecutionPolicy = AgentExecutionPolicy()
        ): MatchCoordinator {
            val json = json()
            val bundle = migrateAgentBundleJson(bundleJson)
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
