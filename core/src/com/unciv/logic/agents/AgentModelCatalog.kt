package com.unciv.logic.agents

/**
 * Supported reasoning / thinking levels for agent models (AI_AGENT_PLAN.md §4.1).
 * Maps user-facing labels to provider-specific CLI / API adapter values.
 */
enum class ThinkingLevel(
    val label: String,
    val wireValue: String?
) {
    PROVIDER_DEFAULT("Provider default", null),
    OFF("Off", "off"),
    LOW("Low", "low"),
    MEDIUM("Medium", "medium"),
    HIGH("High", "high"),
    XHIGH("XHigh", "xhigh"),
    MAX("Max", "max"),
    ULTRA("Ultra", "ultra");

    companion object {
        fun fromWireValue(value: String?): ThinkingLevel {
            if (value.isNullOrBlank()) return PROVIDER_DEFAULT
            return entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
                ?: PROVIDER_DEFAULT
        }
    }
}

/**
 * Model specification scoped to a provider (AI_AGENT_PLAN.md §4.1).
 */
data class ModelSpec(
    val id: String,
    val label: String,
    val provider: String,
    val supportedThinkingLevels: List<ThinkingLevel>,
    val defaultThinkingLevel: ThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT,
    val isDiscovered: Boolean = false,
    val isCustom: Boolean = false
) {
    val supportsThinking: Boolean get() = supportedThinkingLevels.isNotEmpty()
}

/**
 * Model catalog and validation service for external agent profiles.
 * Maintains provider-scoped fallback catalogs, supports background discovery,
 * and preserves uncataloged saved models without silent substitution.
 */
object AgentModelCatalog {

    const val PROVIDER_CODEX = "codex"
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_OPENCODE = "opencode"

    val SUPPORTED_PROVIDERS = listOf(PROVIDER_CODEX, PROVIDER_CLAUDE, PROVIDER_OPENCODE)

    // Codex models: Luna is the immediate target and default for new profiles.
    val CODEX_MODELS = listOf(
        ModelSpec(
            id = "gpt-6-luna",
            label = "GPT-6 Luna",
            provider = PROVIDER_CODEX,
            supportedThinkingLevels = listOf(ThinkingLevel.PROVIDER_DEFAULT, ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX),
            defaultThinkingLevel = ThinkingLevel.XHIGH
        ),
        ModelSpec(
            id = "gpt-6.1-sol",
            label = "GPT-6.1 Sol",
            provider = PROVIDER_CODEX,
            supportedThinkingLevels = listOf(ThinkingLevel.PROVIDER_DEFAULT, ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA),
            defaultThinkingLevel = ThinkingLevel.LOW
        ),
        ModelSpec(
            id = "gpt-5.6-luna",
            label = "GPT-5.6 Luna",
            provider = PROVIDER_CODEX,
            supportedThinkingLevels = listOf(
                ThinkingLevel.PROVIDER_DEFAULT,
                ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM,
                ThinkingLevel.HIGH
            ),
            defaultThinkingLevel = ThinkingLevel.LOW
        ),
        ModelSpec(
            id = "o3-mini",
            label = "o3-mini",
            provider = PROVIDER_CODEX,
            supportedThinkingLevels = listOf(
                ThinkingLevel.PROVIDER_DEFAULT,
                ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM,
                ThinkingLevel.HIGH
            ),
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        ),
        ModelSpec(
            id = "o3",
            label = "o3",
            provider = PROVIDER_CODEX,
            supportedThinkingLevels = listOf(
                ThinkingLevel.PROVIDER_DEFAULT,
                ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM,
                ThinkingLevel.HIGH
            ),
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        ),
        ModelSpec(
            id = "gpt-4o",
            label = "GPT-4o",
            provider = PROVIDER_CODEX,
            supportedThinkingLevels = emptyList(), // thinking unsupported
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        )
    )

    val CLAUDE_MODELS = listOf(
        ModelSpec(
            id = "claude-3-7-sonnet",
            label = "Claude 3.7 Sonnet",
            provider = PROVIDER_CLAUDE,
            supportedThinkingLevels = listOf(
                ThinkingLevel.PROVIDER_DEFAULT,
                ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM,
                ThinkingLevel.HIGH,
                ThinkingLevel.MAX
            ),
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        ),
        ModelSpec(
            id = "claude-3-5-sonnet",
            label = "Claude 3.5 Sonnet",
            provider = PROVIDER_CLAUDE,
            supportedThinkingLevels = emptyList(),
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        ),
        ModelSpec(
            id = "claude-3-5-haiku",
            label = "Claude 3.5 Haiku",
            provider = PROVIDER_CLAUDE,
            supportedThinkingLevels = emptyList(),
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        )
    )

    /** OpenCode Go id/efforts verified against its model metadata (2026-10-11). */
    val OPENCODE_GO_DEEPSEEK = ModelSpec(
        id = "opencode-go/deepseek-v4.1-flash",
        label = "OpenCode Go: DeepSeek V4.1 Flash",
        provider = PROVIDER_OPENCODE,
        supportedThinkingLevels = listOf(ThinkingLevel.PROVIDER_DEFAULT, ThinkingLevel.LOW,
            ThinkingLevel.HIGH, ThinkingLevel.MAX),
        defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
    )

    val OPENCODE_MODELS = listOf(
        ModelSpec(
            id = "openai/gpt-5.6-luna",
            label = "OpenAI: GPT-5.6 Luna",
            provider = PROVIDER_OPENCODE,
            supportedThinkingLevels = listOf(
                ThinkingLevel.PROVIDER_DEFAULT,
                ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM,
                ThinkingLevel.HIGH
            ),
            defaultThinkingLevel = ThinkingLevel.LOW
        ),
        ModelSpec(
            id = "anthropic/claude-3-7-sonnet",
            label = "Anthropic: Claude 3.7 Sonnet",
            provider = PROVIDER_OPENCODE,
            supportedThinkingLevels = listOf(
                ThinkingLevel.PROVIDER_DEFAULT,
                ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM,
                ThinkingLevel.HIGH
            ),
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        ),
        ModelSpec(
            id = "google/gemini-2.5-pro",
            label = "Google: Gemini 2.5 Pro",
            provider = PROVIDER_OPENCODE,
            supportedThinkingLevels = listOf(
                ThinkingLevel.PROVIDER_DEFAULT,
                ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM,
                ThinkingLevel.HIGH
            ),
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        ),
        ModelSpec(
            id = "google/gemini-2.5-flash",
            label = "Google: Gemini 2.5 Flash",
            provider = PROVIDER_OPENCODE,
            supportedThinkingLevels = emptyList(),
            defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT
        )
    ) + OPENCODE_GO_DEEPSEEK

    /** Optional host-provided discovery function. Called off the UI thread. */
    var modelDiscoverer: ((provider: String) -> List<ModelSpec>?)? = null

    /** Cached discovered models per provider. */
    private val discoveredModels = HashMap<String, List<ModelSpec>>()

    /** Records discovered models for a provider. */
    fun setDiscoveredModels(provider: String, models: List<ModelSpec>) {
        synchronized(discoveredModels) {
            discoveredModels[provider] = models
        }
    }

    /**
     * Returns the list of models for [provider].
     * If discovered models exist, returns them. Otherwise returns the maintained fallback catalog.
     */
    fun getModelsForProvider(provider: String): List<ModelSpec> {
        synchronized(discoveredModels) {
            val discovered = discoveredModels[provider]
            if (!discovered.isNullOrEmpty()) {
                return if (provider == PROVIDER_OPENCODE) {
                    val verified = discovered.map { model ->
                        if (model.id == OPENCODE_GO_DEEPSEEK.id) model.copy(
                            supportedThinkingLevels = OPENCODE_GO_DEEPSEEK.supportedThinkingLevels,
                            defaultThinkingLevel = OPENCODE_GO_DEEPSEEK.defaultThinkingLevel
                        ) else model
                    }
                    verified + listOf(OPENCODE_GO_DEEPSEEK).filter { fallback -> discovered.none { it.id == fallback.id } }
                } else discovered
            }
        }
        return when (provider) {
            PROVIDER_CODEX -> CODEX_MODELS
            PROVIDER_CLAUDE -> CLAUDE_MODELS
            PROVIDER_OPENCODE -> OPENCODE_MODELS
            else -> emptyList()
        }
    }

    /** Default model spec for newly created profiles of [provider]. */
    fun getDefaultModel(provider: String): ModelSpec {
        val models = getModelsForProvider(provider)
        return if (provider == PROVIDER_CODEX) {
            (models.firstOrNull { it.id == "gpt-6-luna" }
                ?: CODEX_MODELS.first { it.id == "gpt-6-luna" }).copy(defaultThinkingLevel = ThinkingLevel.XHIGH)
        } else {
            models.firstOrNull() ?: customModel("default", provider)
        }
    }

    /** Finds a ModelSpec by id and provider, checking catalog or creating a custom/saved placeholder. */
    fun findModel(provider: String, modelId: String): ModelSpec {
        val inCatalog = getModelsForProvider(provider).firstOrNull { it.id == modelId }
        if (inCatalog != null) return inCatalog
        // Check standard fallback catalogs even if discovery was active
        val inFallback = (CODEX_MODELS + CLAUDE_MODELS + OPENCODE_MODELS).firstOrNull { it.provider == provider && it.id == modelId }
        if (inFallback != null) return inFallback
        return customModel(modelId, provider)
    }

    fun customModel(modelId: String, provider: String): ModelSpec = ModelSpec(
        id = modelId,
        label = if (modelId.isBlank()) "Custom model…" else "$modelId (Custom / Saved)",
        provider = provider,
        supportedThinkingLevels = listOf(
            ThinkingLevel.PROVIDER_DEFAULT,
            ThinkingLevel.LOW,
            ThinkingLevel.MEDIUM,
            ThinkingLevel.HIGH
        ),
        defaultThinkingLevel = ThinkingLevel.PROVIDER_DEFAULT,
        isDiscovered = false,
        isCustom = true
    )

    /**
     * Validates whether [thinkingLevel] is valid for [provider] and [modelId].
     * Returns null if valid, or a descriptive error message if invalid.
     */
    fun revalidateThinkingLevel(spec: ModelSpec, current: ThinkingLevel): ThinkingLevel {
        if (!spec.supportsThinking) return ThinkingLevel.PROVIDER_DEFAULT
        if (current in spec.supportedThinkingLevels) return current
        return spec.defaultThinkingLevel
    }

    fun validateThinkingLevel(provider: String, modelId: String, thinkingLevel: ThinkingLevel): String? {
        if (thinkingLevel == ThinkingLevel.PROVIDER_DEFAULT) return null
        val spec = findModel(provider, modelId)
        if (!spec.supportsThinking) {
            return "Model ${spec.label} does not support thinking levels (only Provider Default is permitted)."
        }
        if (thinkingLevel !in spec.supportedThinkingLevels) {
            return "Thinking level ${thinkingLevel.label} is not supported by ${spec.label}. Supported: ${spec.supportedThinkingLevels.joinToString { it.label }}."
        }
        return null
    }

    /**
     * Validates an entire profile against catalog capabilities.
     */
    fun validateProfile(profile: AgentProfile): List<String> {
        val errors = ArrayList<String>()
        if (profile.displayName.isBlank()) {
            errors.add("Agent display name cannot be blank.")
        }
        if (profile.provider.isBlank()) {
            errors.add("Agent provider is required.")
        } else if (profile.provider !in SUPPORTED_PROVIDERS) {
            errors.add("Unknown provider '${profile.provider}'. Supported: ${SUPPORTED_PROVIDERS.joinToString()}.")
        }
        if (profile.modelId.isBlank()) {
            errors.add("Model ID is required.")
        } else {
            val level = ThinkingLevel.fromWireValue(profile.reasoning)
            val thinkingErr = validateThinkingLevel(profile.provider, profile.modelId, level)
            if (thinkingErr != null) errors.add(thinkingErr)
        }
        return errors
    }
}
