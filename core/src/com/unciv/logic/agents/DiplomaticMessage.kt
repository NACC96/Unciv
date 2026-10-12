package com.unciv.logic.agents

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.GameInfo

/** Saved diplomatic mail. Delivery is a turn boundary, not proof that a provider read it. */
class DiplomaticMessage(
    var messageId: String = "",
    var senderCivId: String = "",
    var targetCivId: String = "",
    var text: String = "",
    var sentTurnSerial: Int = 0,
    var deliveredTurnSerial: Int? = null,
    /** NATION for human/agent diplomacy, SPECTATOR for explicitly identified guidance. */
    var origin: String = "NATION",
    /** A durable, explicit response by the recipient; delivery alone does not acknowledge mail. */
    var acknowledgedTurnSerial: Int? = null,
    var acknowledgementText: String? = null,
    var attachments: ArrayList<DiplomaticImageAttachment> = ArrayList(),
    var acknowledgedRevision: Int? = null
) : IsPartOfGameInfoSerialization {
    @Suppress("unused")
    constructor() : this("", "", "", "", 0, null, "NATION")

    fun copy(): DiplomaticMessage = DiplomaticMessage(
        messageId, senderCivId, targetCivId, text, sentTurnSerial, deliveredTurnSerial, origin,
        acknowledgedTurnSerial, acknowledgementText, ArrayList(attachments.map { it.copy() }),
        acknowledgedRevision
    )
}

/** A migrated acknowledgement group with missing revisions retains its complete saved order. */
fun orderedDiplomaticMessages(messages: List<DiplomaticMessage>): List<DiplomaticMessage> =
    messages.groupBy { it.acknowledgedTurnSerial }.entries
        .sortedWith(compareBy<Map.Entry<Int?, List<DiplomaticMessage>>> { it.key == null }
            .thenBy { it.key ?: Int.MAX_VALUE })
        .flatMap { (_, group) ->
            if (group.any { it.acknowledgedRevision == null }) group
            else group.sortedBy { it.acknowledgedRevision }
        }

/** Delivered incoming mail is readable only by its target, never by guessed IDs. */
internal fun DiplomaticMessage.isDeliveredTo(actorCivId: String, turnSerial: Int): Boolean =
    targetCivId == actorCivId && deliveredTurnSerial?.let { it <= turnSerial } == true

/** Only contacts the viewer may message; provider names do not reveal an unmet civilization. */
class MessageContact(
    var civId: String = "",
    var displayName: String = "",
    var isAgent: Boolean = false
) {
    @Suppress("unused")
    constructor() : this("", "", false)
}

internal object DiplomaticMessageLimits {
    const val MAX_BODY_CHARS = 2000
    const val MAX_BODY_BYTES = 8 * 1024
    const val MAX_MESSAGES_PER_TURN = 10
}

/** Shared by the host UI and the visibility-filtered provider observation. */
internal fun buildMessageContacts(
    game: GameInfo,
    match: AgentMatchState,
    viewerCivId: String
): ArrayList<MessageContact> {
    val viewer = game.getCivilizationOrNull(viewerCivId) ?: return ArrayList()
    val viewerKind = match.controllers[viewerCivId]?.kind
    if (!viewer.isSpectator() && viewerKind != ControllerKind.HUMAN &&
        viewerKind != ControllerKind.EXTERNAL_AGENT) return ArrayList()
    return ArrayList(game.civilizations.asSequence().filter { candidate ->
        if (candidate.civID == viewerCivId || !candidate.isMajorCiv() || candidate.isDefeated())
            return@filter false
        val kind = match.controllers[candidate.civID]?.kind
        if (kind != ControllerKind.EXTERNAL_AGENT &&
            !(viewerKind == ControllerKind.EXTERNAL_AGENT && kind == ControllerKind.HUMAN))
            return@filter false
        viewer.isSpectator() || viewer.knows(candidate)
    }.map { candidate ->
        val controller = match.controllers[candidate.civID]!!
        MessageContact(candidate.civID,
            controller.agentProfile?.displayName?.takeIf { it.isNotBlank() } ?: candidate.civName,
            controller.kind == ControllerKind.EXTERNAL_AGENT)
    }.sortedBy { it.civId }.toList())
}
