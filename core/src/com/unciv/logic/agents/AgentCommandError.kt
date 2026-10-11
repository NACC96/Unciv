package com.unciv.logic.agents

/**
 * Typed command failures, mirroring the `code` enum of
 * `agent-protocol/schemas/error-envelope.schema.json` (AI_AGENT_PLAN.md §5.1).
 * The supervisor maps these 1:1 onto wire errors; hidden and nonexistent
 * entities share the externally safe response produced by the caller.
 */
enum class AgentCommandError {
    STALE_STATE,
    NOT_YOUR_TURN,
    MATCH_PAUSED,
    AUTHORITY_REVOKED,
    INVALID_ARGUMENT,
    ILLEGAL_ACTION,
    DECISIONS_PENDING,
    MESSAGES_UNACKNOWLEDGED,
    UNSUPPORTED_RULESET,
    ACTION_ID_CONFLICT,
    MUTATION_IN_PROGRESS,
    INTERNAL_ERROR;
}

/** Thrown by [MatchCoordinator] instead of returning partial state. */
class AgentCommandException(
    val code: AgentCommandError,
    message: String,
    /** Committed revision the client should re-observe from (STALE_STATE). */
    val currentRevision: Int? = null,
    /** Only delivered incoming mail belonging to the acting civilization. */
    val messageIds: List<String>? = null
) : Exception(message)
