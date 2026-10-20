package com.unciv.logic.agents

import com.unciv.logic.IsPartOfGameInfoSerialization

/** Fork save-format version for agent-match metadata. Reject others explicitly. */
const val AGENT_MATCH_FORMAT_VERSION = 3

/** Mandatory choices block end-turn; optional ones are deferrable reminders; informational ones log only. */
enum class DecisionClassification : IsPartOfGameInfoSerialization {
    MANDATORY,
    OPTIONAL,
    INFORMATIONAL;
}

/** Delivery state for spectator/human guidance; never injected mid-invocation. */
enum class GuidanceState : IsPartOfGameInfoSerialization {
    QUEUED,
    DELIVERED,
    SUPERSEDED;
}

/**
 * One pending choice owned by a civilization (AI_AGENT_PLAN.md §4.1, §6.1).
 * Typed options are revalidated immediately before application; listing is
 * not a reservation. Stable ID so UI and receipts can rebind across snapshot
 * publication.
 */
class PendingDecision(
    var decisionId: String = "",
    /** Decision family, e.g. "conquest-disposition", "free-tech". */
    var decisionType: String = "",
    var owningCivId: String = "",
    /** Source entity/event reference, e.g. "city:Rome" or "trade:<offer-id>". */
    var sourceRef: String = "",
    var classification: DecisionClassification = DecisionClassification.MANDATORY,
    /** Typed option payloads (option IDs + display data); schema per family. */
    var options: ArrayList<HashMap<String, String>> = ArrayList()
) : IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this("", "", "", "", DecisionClassification.MANDATORY, ArrayList())

    fun copy(): PendingDecision = PendingDecision(
        decisionId, decisionType, owningCivId, sourceRef, classification,
        ArrayList(options.map { HashMap(it) })
    )
}

/** A queued user guidance message for one leader (AI_AGENT_PLAN.md §8.2). */
class GuidanceMessage(
    var guidanceId: String = "",
    var targetCivId: String = "",
    var sentAtMillis: Long = 0L,
    var state: GuidanceState = GuidanceState.QUEUED,
    var text: String = ""
) : IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this("", "", 0L, GuidanceState.QUEUED, "")

    fun copy(): GuidanceMessage =
        GuidanceMessage(guidanceId, targetCivId, sentAtMillis, state, text)
}

/** Receipt-time own-unit state. An absent unit was consumed or destroyed by the command. */
class ActionUnitSnapshot(
    var unitId: Int = 0,
    var present: Boolean = false,
    var x: Int = 0,
    var y: Int = 0,
    var movementLeft: Float = 0f,
    var health: Int = 0,
    var action: String? = null
) : IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this(0, false, 0, 0, 0f, 0, null)

    fun copy(): ActionUnitSnapshot = ActionUnitSnapshot(unitId, present, x, y, movementLeft, health, action)
}

/**
 * Durable outcome of one applied mutation or internal turn boundary
 * (AI_AGENT_PLAN.md §4.1). The committed receipt governs over any lost
 * acknowledgement; after authority moves to a later turn, old mutation
 * requests are rejected even though their receipts remain inspectable.
 */
class ActionReceipt(
    var actionId: String = "",
    var actorCivId: String = "",
    var timelineId: String = "",
    var requestFingerprint: String = "",
    var beforeRevision: Int = 0,
    var afterRevision: Int = 0,
    /** "applied" for terminal success; rejections travel as typed errors, not receipts. */
    var outcome: String = "",
    /** Committed engine event IDs for correlation with observations. */
    var committedEventIds: ArrayList<String> = ArrayList(),
    /** Sanitized command-specific result (e.g. "x,y" final position); only permitted post-action knowledge. */
    var resultJson: String = "",
    /** Null for old receipts or commands without a unit snapshot; never reconstructed from live state. */
    var unitSnapshot: ActionUnitSnapshot? = null
) : IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this("", "", "", "", 0, 0, "", ArrayList(), "")

    fun copy(): ActionReceipt = ActionReceipt(
        actionId, actorCivId, timelineId, requestFingerprint,
        beforeRevision, afterRevision, outcome, ArrayList(committedEventIds), resultJson, unitSnapshot?.copy()
    )
}

/**
 * Authoritative controller/decision state for one agent-enabled match
 * timeline (AI_AGENT_PLAN.md §4.1, §10.1). Stored alongside the game payload
 * in the versioned `.unciv-agent` bundle — never merged with an
 * independently-newer copy; restore always takes game + metadata together.
 *
 * Machine-local provider session/process bindings are deliberately NOT here
 * (separate optional recovery metadata); neither are credentials or live
 * capabilities. Mutable maps are deep-copied by [deepCopy] so unpublished
 * candidates can never share state with the published checkpoint.
 */
class AgentMatchState(
    var formatVersion: Int = AGENT_MATCH_FORMAT_VERSION,
    var matchId: String = "",
    var timelineId: String = "",
    var stateRevision: Int = 0,
    var turnSerial: Int = 0,
    var turnPhase: TurnPhase = TurnPhase.READY_TO_START,
    /** Civ ID -> controller. Fixed slots while agents are configured. */
    var controllers: HashMap<String, CivilizationController> = HashMap(),
    /** True only with the explicit saved full-inspection option (viewing, not control). */
    var fullInspection: Boolean = false,
    /** Civ ID -> private structured strategy notes (16 KiB UTF-8 cap enforced by callers). */
    var privateNotes: HashMap<String, String> = HashMap(),
    /** Civ ID -> notes revision, mirroring the game.notes contract. */
    var notesRevisions: HashMap<String, Int> = HashMap(),
    var pendingDecisions: ArrayList<PendingDecision> = ArrayList(),
    var guidanceQueue: ArrayList<GuidanceMessage> = ArrayList(),
    /** Current-turn receipts; completed-turn history lives in retained turn checkpoints. */
    var currentTurnReceipts: ArrayList<ActionReceipt> = ArrayList(),
    /** Coordinator-level pause: while true, no new mutation may commit. */
    var matchPaused: Boolean = false,
    /** Why the match paused (failure reason, storage error, user action). */
    var matchPausedReason: String? = null,
    /**
     * Completed end-turn commits, monotonic. An invocation classifies its
     * turn completed iff this counter advanced during the invocation
     * (a sticky boolean would stay true forever after the first turn).
     */
    var endTurnsCommitted: Int = 0,
    /** Saved sent/received mail; queued mail becomes visible only at the recipient's next turn. */
    var diplomaticMessages: ArrayList<DiplomaticMessage> = ArrayList(),
    /** Mail-send and acknowledgement receipts outlive turn-scoped gameplay receipts; private to the host. */
    var diplomaticMessageReceipts: HashMap<String, ActionReceipt> = HashMap(),
    /** Sanitized last sightings and geometry-only discovery, isolated per controller. */
    var mapKnowledge: AgentMapKnowledge = AgentMapKnowledge(),
    /** Immutable local management origin; absent historical identities are initialized without choosing an agent. */
    var localControlCivId: String? = null,
    /** Actual saved presentation identity, independent of management origin and active turn. */
    var localViewingCivId: String? = null,
    /** Last external personality/configuration survives explicit Human assignment. */
    var retainedAgentProfiles: HashMap<String, AgentProfile> = HashMap(),
    /** Fog for saves made before this setting existed; new matches start with [MatchVision.FULL]. */
    var vision: MatchVision = MatchVision.FOG
) : IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this(
        AGENT_MATCH_FORMAT_VERSION, "", "", 0, 0, TurnPhase.READY_TO_START,
        HashMap(), false, HashMap(), HashMap(), ArrayList(), ArrayList(), ArrayList(),
        false, null, 0, ArrayList(), HashMap(), AgentMapKnowledge()
    )

    /** Rejects incompatible format versions explicitly; never silently converts. */
    fun validateFormat() {
        require(formatVersion == AGENT_MATCH_FORMAT_VERSION) {
            "Unsupported agent match format version $formatVersion (expected $AGENT_MATCH_FORMAT_VERSION)"
        }
    }

    fun deepCopy(): AgentMatchState = AgentMatchState(
        formatVersion, matchId, timelineId, stateRevision, turnSerial, turnPhase,
        HashMap(controllers.mapValues { it.value.copy() }),
        fullInspection,
        HashMap(privateNotes),
        HashMap(notesRevisions),
        ArrayList(pendingDecisions.map { it.copy() }),
        ArrayList(guidanceQueue.map { it.copy() }),
        ArrayList(currentTurnReceipts.map { it.copy() }),
        matchPaused,
        matchPausedReason,
        endTurnsCommitted,
        ArrayList(diplomaticMessages.map { it.copy() }),
        HashMap(diplomaticMessageReceipts.mapValues { it.value.copy() }),
        mapKnowledge.deepCopy(),
        localControlCivId,
        localViewingCivId,
        HashMap(retainedAgentProfiles.mapValues { it.value.copy() }),
        vision
    )
}
