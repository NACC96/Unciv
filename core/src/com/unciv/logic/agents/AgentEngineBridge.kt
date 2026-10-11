package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeOfferType

/**
 * Protocol-to-coordinator bridge (AI_AGENT_PLAN.md §5.1, desktop-owned in
 * production). Maps versioned tool-call argument maps onto typed
 * [AgentCommand]s, executes them on the [MatchCoordinator], and returns
 * JSON-compatible result maps shaped like `agent-protocol/schemas/`:
 * success receipts mirror action-receipt.schema.json, failures throw
 * [EngineCallException] mirroring error-envelope.schema.json (the transport
 * maps it onto the wire; hidden/nonexistent entities share safe responses).
 *
 * All maps use String keys and primitives/lists so Gdx JSON round-trips them
 * losslessly. No threads, no UI, no processes: fully headless-testable.
 */
class EngineCallException(
    val code: String,
    message: String,
    val currentRevision: Int? = null,
    val messageIds: List<String>? = null
) : Exception(message)

class AgentEngineBridge(val coordinator: MatchCoordinator) {
    private val jsonReader = com.badlogic.gdx.utils.JsonReader()
    /**
     * Wire serializer: Gdx prototypes MUST be off. Unciv's shared json()
     * skips default-valued fields (compact saves), which silently drops
     * revision 0, empty lists, and false flags from protocol DTOs.
     */
    private val wireJson = com.badlogic.gdx.utils.Json(
        com.badlogic.gdx.utils.JsonWriter.OutputType.json
    ).apply {
        ignoreUnknownFields = true
        setUsePrototypes(false)
        setTypeName(null)
    }

    /**
     * Converts coordinator DTOs to JSON-compatible primitives/maps/lists.
     * Shared [AgentJson] implementation (tests and the desktop host frame
     * stdio with the same functions).
     */
    fun toJsonMap(value: Any): Any {
        return AgentJson.parse(wireJson.toJson(value))!!
    }

    private fun requireString(args: Map<String, Any?>, name: String): String =
        args[name] as? String
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing string argument $name")

    private fun requireBoolean(args: Map<String, Any?>, name: String): Boolean =
        args[name] as? Boolean
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing boolean argument $name")

    private fun optionalBoolean(args: Map<String, Any?>, name: String): Boolean =
        if (!args.containsKey(name)) false else requireBoolean(args, name)

    private fun optionalString(args: Map<String, Any?>, name: String): String? =
        if (args[name] == null) null else requireString(args, name)

    private fun requireInt(args: Map<String, Any?>, name: String): Int =
        (args[name] as? Number)?.toInt()
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing integer argument $name")

    private fun requireHex(args: Map<String, Any?>, name: String): HexCoord {
        @Suppress("UNCHECKED_CAST")
        val map = args[name] as? Map<String, Any?>
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing coordinate $name")
        val x = (map["x"] as? Number)?.toInt()
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing coordinate $name.x")
        val y = (map["y"] as? Number)?.toInt()
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing coordinate $name.y")
        return HexCoord(x, y)
    }

    private fun optionalHex(args: Map<String, Any?>, name: String): HexCoord? {
        if (!args.containsKey(name) || args[name] == null) return null
        return requireHex(args, name)
    }

    private inline fun <reified E : Enum<E>> requireEnum(args: Map<String, Any?>, name: String): E {
        val raw = requireString(args, name)
        return try {
            enumValueOf<E>(raw)
        } catch (ex: Exception) {
            throw EngineCallException("INVALID_ARGUMENT", "Unknown $name $raw")
        }
    }

    private fun requireOffers(args: Map<String, Any?>, name: String): List<TradeOfferDto> {
        @Suppress("UNCHECKED_CAST")
        val list = args[name] as? List<Map<String, Any?>> ?: return emptyList()
        return list.map {
            val type = try {
                TradeOfferType.valueOf(it["type"] as? String ?: "")
            } catch (ex: Exception) {
                throw EngineCallException("INVALID_ARGUMENT", "Unknown offer type")
            }
            TradeOfferDto(
                it["name"] as? String ?: throw EngineCallException("INVALID_ARGUMENT", "Offer needs a name"),
                type,
                (it["amount"] as? Number)?.toInt()
                    ?: throw EngineCallException("INVALID_ARGUMENT", "Offer needs an amount")
            )
        }
    }

    private fun receiptMap(receipt: ActionReceipt, result: Any?, decisions: List<PendingDecision>): Map<String, Any?> {
        return mapOf(
            "protocolVersion" to 1,
            "actionId" to receipt.actionId,
            "status" to receipt.status(),
            "revision" to receipt.afterRevision,
            "result" to (result ?: mapOf<String, Any?>()),
            "pendingDecisions" to decisions.map { toJsonMap(it) }
        )
    }

    private fun decisionsFor(actorCivId: String): List<PendingDecision> =
        coordinator.getPendingDecisions(actorCivId)

    /** game.act dispatch over the v1 command surface. */
    fun act(envelope: Map<String, Any?>): Map<String, Any?> {
        val version = (envelope["protocolVersion"] as? Number)?.toInt()
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing protocolVersion")
        if (version != 1)
            throw EngineCallException("INVALID_ARGUMENT", "Unsupported protocolVersion $version")
        val actionId = requireString(envelope, "actionId")
        val expectedRevision = requireInt(envelope, "expectedRevision")
        val type = requireString(envelope, "type")
        @Suppress("UNCHECKED_CAST")
        val args = envelope["args"] as? Map<String, Any?> ?: emptyMap()
        // Identity rides at the envelope level (supervisor-supplied, never
        // model-selected); command arguments carry only game parameters.
        val actorCivId = requireString(envelope, "actorCivId")

        try {
            return when (type) {
                "diplomacy.message" -> {
                    val result = coordinator.execute(SendDiplomaticMessageCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "targetCivId"), requireString(args, "text"))) as DiplomaticMessageSentResult
                    receiptMap(result.receipt, toJsonMap(result.message), decisionsFor(actorCivId))
                }
                "diplomacy.acknowledgeMessage" -> {
                    val result = coordinator.execute(AcknowledgeDiplomaticMessageCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "messageId"), requireString(args, "text"))) as DiplomaticMessageAcknowledgedResult
                    receiptMap(result.receipt, toJsonMap(result.message), decisionsFor(actorCivId))
                }
                "unit.move" -> {
                    val result = coordinator.execute(MoveUnitCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"), requireHex(args, "destination"))) as UnitMovedResult
                    receiptMap(result.receipt,
                        mapOf("unitId" to result.unitId,
                            "position" to mapOf("x" to result.finalPosition.x, "y" to result.finalPosition.y)),
                        decisionsFor(actorCivId))
                }
                "research.select" -> {
                    val result = coordinator.execute(SelectResearchCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "techName"),
                        (args["queue"] as? Boolean) ?: false,
                        (args["useFreeTech"] as? Boolean) ?: false)) as ResearchSelectedResult
                    receiptMap(result.receipt, mapOf("currentTech" to result.currentTech), decisionsFor(actorCivId))
                }
                "city.produce" -> {
                    val result = coordinator.execute(CityProductionCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityId"),
                        requireEnum<ProductionOp>(args, "op"),
                        (args["name"] as? String) ?: "",
                        (args["index"] as? Number)?.toInt() ?: -1,
                        optionalHex(args, "tilePos"))) as CityProductionResult
                    receiptMap(result.receipt, mapOf("queue" to result.queue), decisionsFor(actorCivId))
                }
                "unit.found" -> {
                    val result = coordinator.execute(FoundCityCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"))) as CityFoundedResult
                    receiptMap(result.receipt, mapOf("cityId" to result.cityId), decisionsFor(actorCivId))
                }
                "unit.order" -> {
                    val result = coordinator.execute(UnitOrderCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"),
                        requireEnum<UnitOrder>(args, "order"))) as UnitOrderResult
                    receiptMap(result.receipt,
                        mapOf("action" to result.action, "disbanded" to result.disbanded),
                        decisionsFor(actorCivId))
                }
                "unit.attack" -> {
                    val result = coordinator.execute(AttackCommand(
                        actorCivId, actionId, expectedRevision,
                        (args["attackerUnitId"] as? Number)?.toInt(),
                        args["attackerCityId"] as? String,
                        requireHex(args, "target"))) as AttackResult
                    receiptMap(result.receipt, mapOf(
                        "attackerAlive" to result.attackerAlive,
                        "defenderAlive" to result.defenderAlive,
                        "attackerDamageDealt" to result.attackerDamageDealt,
                        "defenderDamageDealt" to result.defenderDamageDealt), decisionsFor(actorCivId))
                }
                "conquest.resolve" -> {
                    val result = coordinator.execute(ResolveConquestCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "decisionId"),
                        requireEnum<ConquestDisposition>(args, "disposition"))) as ConquestResolvedResult
                    receiptMap(result.receipt,
                        mapOf("cityId" to result.cityId, "disposition" to result.disposition.name),
                        decisionsFor(actorCivId))
                }
                "diplomacy.declareWar" -> {
                    val result = coordinator.execute(DeclareWarCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "targetCivId"))) as DiplomacyResult
                    receiptMap(result.receipt, mapOf("status" to result.status), decisionsFor(actorCivId))
                }
                "diplomacy.makePeace" -> {
                    val result = coordinator.execute(MakePeaceCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "targetCivId"))) as DiplomacyResult
                    receiptMap(result.receipt, mapOf("status" to result.status), decisionsFor(actorCivId))
                }
                "diplomacy.friendship" -> {
                    val result = coordinator.execute(FriendshipCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "targetCivId"))) as DiplomacyResult
                    receiptMap(result.receipt, mapOf("status" to result.status), decisionsFor(actorCivId))
                }
                "diplomacy.proposeTrade" -> {
                    val result = coordinator.execute(ProposeTradeCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "targetCivId"),
                        requireOffers(args, "ourOffers"),
                        requireOffers(args, "theirOffers"))) as TradeProposedResult
                    receiptMap(result.receipt,
                        mapOf("pendingRequestsWithTarget" to result.pendingRequestsWithTarget),
                        decisionsFor(actorCivId))
                }
                "diplomacy.respondTrade" -> {
                    val result = coordinator.execute(RespondTradeCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "requestingCivId"),
                        (args["accept"] as? Boolean) ?: false)) as TradeRespondedResult
                    receiptMap(result.receipt, mapOf("accepted" to result.accepted), decisionsFor(actorCivId))
                }
                "civics.adopt" -> {
                    val result = coordinator.execute(AdoptPolicyCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "policyName"))) as PolicyAdoptedResult
                    receiptMap(result.receipt, mapOf("adopted" to result.adopted), decisionsFor(actorCivId))
                }
                "city.citizens" -> {
                    val result = coordinator.execute(CityCitizensCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityId"),
                        requireEnum<CitizenOp>(args, "op"),
                        optionalHex(args, "tilePos"),
                        (args["specialist"] as? String) ?: "",
                        (args["focus"] as? String) ?: "",
                        (args["flag"] as? Boolean) ?: false)) as CityCitizensResult
                    receiptMap(result.receipt, mapOf("freePopulation" to result.freePopulation),
                        decisionsFor(actorCivId))
                }
                "city.buyTile" -> {
                    val result = coordinator.execute(BuyTileCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityId"),
                        requireHex(args, "tilePos"))) as TileBoughtResult
                    receiptMap(result.receipt,
                        mapOf("tilePos" to mapOf("x" to result.tilePos.x, "y" to result.tilePos.y)),
                        decisionsFor(actorCivId))
                }
                "unit.build" -> {
                    val result = coordinator.execute(BuildImprovementCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"),
                        requireString(args, "improvementName"))) as ImprovementStartedResult
                    receiptMap(result.receipt, mapOf("turnsToBuild" to result.turnsToBuild),
                        decisionsFor(actorCivId))
                }
                "unit.promote" -> {
                    val result = coordinator.execute(PromoteUnitCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"),
                        requireString(args, "promotionName"))) as UnitPromotedResult
                    receiptMap(result.receipt, mapOf("promotionName" to result.promotionName),
                        decisionsFor(actorCivId))
                }
                "unit.upgrade" -> {
                    val result = coordinator.execute(UpgradeUnitCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"))) as UnitUpgradedResult
                    receiptMap(result.receipt, mapOf("newUnitName" to result.newUnitName),
                        decisionsFor(actorCivId))
                }
                "unit.great" -> {
                    val result = coordinator.execute(GreatPersonCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"),
                        requireEnum<GreatPersonAction>(args, "action"))) as GreatPersonResult
                    receiptMap(result.receipt, mapOf("action" to result.action.name),
                        decisionsFor(actorCivId))
                }
                "citystate.giftGold" -> {
                    val result = coordinator.execute(GiftGoldToCityStateCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityStateId"),
                        requireInt(args, "amount"))) as GoldGiftedResult
                    receiptMap(result.receipt, mapOf("influenceGained" to result.influenceGained),
                        decisionsFor(actorCivId))
                }
                "unit.pillage" -> {
                    val result = coordinator.execute(PillageCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"))) as PillagedResult
                    receiptMap(result.receipt, mapOf("pillaged" to result.pillaged),
                        decisionsFor(actorCivId))
                }
                "unit.repair" -> {
                    val result = coordinator.execute(RepairCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"))) as RepairStartedResult
                    receiptMap(result.receipt, mapOf("turnsToRepair" to result.turnsToRepair),
                        decisionsFor(actorCivId))
                }
                "diplomacy.denounce" -> {
                    val result = coordinator.execute(DenounceCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "targetCivId"))) as DenouncedResult
                    receiptMap(result.receipt, mapOf("denounced" to true), decisionsFor(actorCivId))
                }
                "diplomacy.demand" -> {
                    val result = coordinator.execute(DemandCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "targetCivId"), requireString(args, "demandName"))) as DemandIssuedResult
                    receiptMap(result.receipt, mapOf("issued" to result.issued), decisionsFor(actorCivId))
                }
                "diplomacy.respondDemand" -> {
                    val result = coordinator.execute(RespondDemandCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "requesterCivId"), requireString(args, "demandName"),
                        requireBoolean(args, "accept"))) as DemandRespondedResult
                    receiptMap(result.receipt, mapOf("accepted" to result.accepted), decisionsFor(actorCivId))
                }
                "diplomacy.castVote" -> {
                    val result = coordinator.execute(CastVoteCommand(
                        actorCivId, actionId, expectedRevision,
                        optionalString(args, "targetCivId"))) as VoteCastResult
                    receiptMap(result.receipt, mapOf("targetCivId" to result.targetCivId), decisionsFor(actorCivId))
                }
                "city.rename" -> {
                    val result = coordinator.execute(RenameCityCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityId"), requireString(args, "newName"))) as CityRenamedResult
                    receiptMap(result.receipt, mapOf("name" to result.name), decisionsFor(actorCivId))
                }
                "city.annex" -> {
                    val result = coordinator.execute(AnnexCityCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityId"))) as CityAnnexedResult
                    receiptMap(result.receipt, mapOf("annexed" to true), decisionsFor(actorCivId))
                }
                "city.raze" -> {
                    val result = coordinator.execute(SetRazingCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityId"), requireBoolean(args, "raze"))) as RazingSetResult
                    receiptMap(result.receipt, mapOf("razing" to result.razing), decisionsFor(actorCivId))
                }
                "unit.stopImprovement" -> {
                    val result = coordinator.execute(StopImprovementCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"))) as ImprovementStoppedResult
                    receiptMap(result.receipt, mapOf("stopped" to true), decisionsFor(actorCivId))
                }
                "unit.swap" -> {
                    val result = coordinator.execute(SwapUnitsCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"), requireHex(args, "destination"))) as UnitsSwappedResult
                    receiptMap(result.receipt, mapOf("position" to mapOf("x" to result.finalPosition.x, "y" to result.finalPosition.y)), decisionsFor(actorCivId))
                }
                "unit.rename" -> {
                    val result = coordinator.execute(RenameUnitCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"), requireString(args, "newName"))) as UnitRenamedResult
                    receiptMap(result.receipt, mapOf("name" to result.name), decisionsFor(actorCivId))
                }
                "unit.connectRoad" -> {
                    val result = coordinator.execute(ConnectRoadCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"), requireHex(args, "destination"))) as RoadConnectionStartedResult
                    receiptMap(result.receipt, mapOf("connecting" to true), decisionsFor(actorCivId))
                }
                "citystate.tribute" -> {
                    val result = coordinator.execute(TributeCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityStateId"), requireBoolean(args, "demandWorker"))) as TributeTakenResult
                    receiptMap(result.receipt, mapOf("worker" to result.worker), decisionsFor(actorCivId))
                }
                "citystate.protection" -> {
                    val result = coordinator.execute(ProtectionCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityStateId"), requireBoolean(args, "pledge"))) as ProtectionSetResult
                    receiptMap(result.receipt, mapOf("pledged" to result.pledged), decisionsFor(actorCivId))
                }
                "citystate.marry" -> {
                    val result = coordinator.execute(DiplomaticMarriageCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityStateId"))) as CityStateMarriedResult
                    receiptMap(result.receipt, mapOf("married" to true), decisionsFor(actorCivId))
                }
                "diplomacy.retractTrade" -> {
                    val result = coordinator.execute(RetractTradeCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "targetCivId"))) as TradeRetractedResult
                    receiptMap(result.receipt, mapOf("retracted" to true), decisionsFor(actorCivId))
                }
                "diplomacy.respondProtectorAlert" -> {
                    val result = coordinator.execute(RespondProtectorAlertCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "cityStateId"), requireString(args, "attackerCivId"),
                        requireEnum<ProtectorAlertChoice>(args, "choice"), optionalBoolean(args, "alliedCityState"))) as ProtectorAlertAnsweredResult
                    receiptMap(result.receipt, mapOf("choice" to result.choice.name), decisionsFor(actorCivId))
                }
                "diplomacy.respondFriendshipOffer" -> {
                    val result = coordinator.execute(RespondFriendshipOfferCommand(
                        actorCivId, actionId, expectedRevision,
                        requireString(args, "requesterCivId"), requireBoolean(args, "accept"))) as FriendshipOfferAnsweredResult
                    receiptMap(result.receipt, mapOf("accepted" to result.accepted), decisionsFor(actorCivId))
                }
                "unit.gift" -> {
                    val result = coordinator.execute(GiftUnitCommand(
                        actorCivId, actionId, expectedRevision,
                        requireInt(args, "unitId"))) as UnitGiftedResult
                    receiptMap(result.receipt, mapOf("recipientCivId" to result.recipientCivId), decisionsFor(actorCivId))
                }
                else -> throw EngineCallException("INVALID_ARGUMENT", "Unknown command type $type")
            }
        } catch (ex: AgentCommandException) {
            throw EngineCallException(ex.code.name, ex.message ?: ex.code.name, ex.currentRevision, ex.messageIds)
        }
    }

    /** game.observe — permitted overview as JSON-compatible maps. */
    fun observe(actorCivId: String): Any {
        try {
            return toJsonMap(coordinator.observe(actorCivId))
        } catch (ex: AgentCommandException) {
            throw EngineCallException(ex.code.name, ex.message ?: ex.code.name)
        }
    }

    /** game.inspect — permitted detail or null (hidden/nonexistent share null). */
    fun inspect(actorCivId: String, unitId: Int): Any? {
        try {
            val unit = coordinator.inspectUnit(actorCivId, unitId) ?: return null
            return toJsonMap(unit)
        } catch (ex: AgentCommandException) {
            throw EngineCallException(ex.code.name, ex.message ?: ex.code.name)
        }
    }

    private fun readInteger(args: Map<String, Any?>, name: String, default: Int? = null): Int {
        if (!args.containsKey(name)) return default
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing integer argument $name")
        val number = args[name] as? Number
            ?: throw EngineCallException("INVALID_ARGUMENT", "Invalid integer argument $name")
        val value = number.toDouble()
        if (!value.isFinite() || value < Int.MIN_VALUE || value > Int.MAX_VALUE || value != number.toInt().toDouble())
            throw EngineCallException("INVALID_ARGUMENT", "Invalid integer argument $name")
        return number.toInt()
    }

    /** game.tile — unknown and nonexistent tiles share null. */
    fun inspectTile(actorCivId: String, args: Map<String, Any?>): Any? = readResult {
        coordinator.inspectTile(actorCivId, HexCoord(readInteger(args, "x"), readInteger(args, "y")))
            ?.toWire()
    }

    /** game.map — bounded actor-scoped reads of committed knowledge. */
    fun map(actorCivId: String, args: Map<String, Any?>): Any? = readResult {
        val mode = if (args.containsKey("mode")) requireEnum<MapQueryMode>(args, "mode") else MapQueryMode.KNOWN
        val center = if (args.containsKey("center")) {
            @Suppress("UNCHECKED_CAST")
            val position = args["center"] as? Map<String, Any?>
                ?: throw EngineCallException("INVALID_ARGUMENT", "Invalid center")
            HexCoord(readInteger(position, "x"), readInteger(position, "y"))
        } else null
        coordinator.map(actorCivId, MapQuery(mode, center,
            if (args.containsKey("radius")) readInteger(args, "radius") else null,
            readInteger(args, "limit", 64), optionalReadString(args, "cursor"))).toWire()
    }

    private fun optionalReadString(args: Map<String, Any?>, name: String): String? =
        if (!args.containsKey(name)) null else args[name] as? String
            ?: throw EngineCallException("INVALID_ARGUMENT", "Invalid string argument $name")

    private inline fun readResult(block: () -> Any?): Any? = try { block() }
    catch (ex: AgentCommandException) {
        throw EngineCallException(ex.code.name, ex.message ?: ex.code.name, ex.currentRevision)
    }

    /** game.rules — identity by default; bounded section catalogs otherwise. */
    fun rules(args: Map<String, Any?> = emptyMap()): Any = readResult {
        toJsonMap(coordinator.rules(RulesQuery(optionalReadString(args, "section"),
            optionalReadString(args, "name"), readInteger(args, "limit", 64), optionalReadString(args, "cursor"))))
    }!!

    /** game.legal_actions — gated per-entity options. */
    fun legalActions(actorCivId: String, unitId: Int?, cityId: String?): Any {
        try {
            return toJsonMap(coordinator.legalActions(actorCivId, unitId, cityId))
        } catch (ex: AgentCommandException) {
            throw EngineCallException(ex.code.name, ex.message ?: ex.code.name)
        }
    }

    /** game.action_result — prior receipt lookup without resubmission. */
    fun actionResult(actionId: String, actorCivId: String = coordinator.getCurrentCivId()): Any {
        val unavailable = mapOf("status" to "rejected", "actionId" to actionId)
        val receipt = coordinator.getReceipt(actionId)
            ?: return unavailable
        val acknowledgement = receipt.requestFingerprint.startsWith("diplomacy.acknowledgeMessage|")
        val diplomatic = acknowledgement || receipt.requestFingerprint.startsWith("diplomacy.message|")
        val result = if (diplomatic) {
            // IDs alone grant no access, including to a receipt's actor/revision metadata.
            if (receipt.actorCivId != actorCivId || receipt.status() != "applied") return unavailable
            val message = coordinator.getMessageHistory(actorCivId).firstOrNull {
                it.messageId == receipt.resultJson &&
                    (!acknowledgement || (it.targetCivId == actorCivId &&
                        it.acknowledgedTurnSerial != null && it.acknowledgementText != null))
            } ?: return unavailable
            toJsonMap(message)
        } else receipt.resultJson
        return mapOf(
            "protocolVersion" to 1,
            "actionId" to receipt.actionId,
            "status" to receipt.status(),
            "revision" to receipt.afterRevision,
            "result" to result,
            "pendingDecisions" to emptyList<Any>()
        )
    }

    /** game.notes read/replace with revision. */
    fun readNotes(actorCivId: String): Map<String, Any?> {
        val (notes, revision) = try {
            coordinator.getNotes(actorCivId)
        } catch (ex: AgentCommandException) {
            throw EngineCallException(ex.code.name, ex.message ?: ex.code.name)
        }
        return mapOf("notes" to notes, "notesRevision" to revision)
    }

    fun updateNotes(actorCivId: String, notes: String, expectedNotesRevision: Int): Map<String, Any?> {
        if (notes.toByteArray(Charsets.UTF_8).size > 16 * 1024)
            throw EngineCallException("INVALID_ARGUMENT", "Notes exceed 16 KiB UTF-8")
        try {
            val result = coordinator.execute(UpdateNotesCommand(actorCivId, notes, expectedNotesRevision))
            return mapOf("notesRevision" to (result as NotesUpdatedResult).notesRevision)
        } catch (ex: AgentCommandException) {
            throw EngineCallException(ex.code.name, ex.message ?: ex.code.name, ex.currentRevision)
        }
    }

    /**
     * game.end_turn — verify and commit; never auto-chooses decisions.
     * Revision is required (no silent default): callers observe first, then
     * commit under the host lock, so stale turns can never slip through.
     */
    fun endTurn(actorCivId: String, actionId: String, expectedRevision: Int): Map<String, Any?> {
        try {
            val result = coordinator.execute(EndTurnCommand(
                actorCivId, actionId, expectedRevision
            )) as TurnEndedResult
            return mapOf(
                "turnEnded" to true,
                "nextCivId" to result.nextCivId,
                "revision" to result.receipt.afterRevision
            )
        } catch (ex: AgentCommandException) {
            throw EngineCallException(ex.code.name, ex.message ?: ex.code.name, ex.currentRevision, ex.messageIds)
        }
    }

    private fun ActionReceipt.status(): String = outcome.ifEmpty { "applied" }
}
