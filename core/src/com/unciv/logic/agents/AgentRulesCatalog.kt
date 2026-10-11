package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.models.stats.Stats
import java.security.MessageDigest

/** Optional section preserves the existing summary response when absent. */
data class RulesQuery(
    val section: String? = null,
    val name: String? = null,
    val limit: Int = 64,
    val cursor: String? = null
)

/** Static definitions only. There are deliberately no live locations, eligibility or sightings. */
class RulesCatalogPage(
    val rulesetName: String,
    val baseRuleset: String,
    val rulesetIdentity: String,
    val section: String,
    val entries: List<Map<String, Any?>>,
    val nextCursor: String?
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "rulesetName" to rulesetName, "baseRuleset" to baseRuleset,
        "rulesetIdentity" to rulesetIdentity, "section" to section,
        "entries" to entries, "nextCursor" to nextCursor
    )
}

const val AGENT_RULES_BYTE_BUDGET = 96 * 1024

private fun rulesInvalid(message: String): Nothing =
    throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, message)

/** Identity must be pinned by the match; the content digest additionally rejects edited catalogs. */
fun buildRulesCatalog(game: GameInfo, rulesetIdentity: String, query: RulesQuery): RulesCatalogPage {
    val section = query.section ?: rulesInvalid("Catalog section is required")
    if (section.length > 128) rulesInvalid("Rules section is too long")
    if (query.name != null && query.name.length > 128) rulesInvalid("Rules name is too long")
    if (query.cursor != null && query.cursor.length > 512) rulesInvalid("Rules cursor is too long")
    if (section !in setOf("improvements", "terrains", "resources")) rulesInvalid("Unknown rules section: $section")
    if (query.limit !in 1..128) rulesInvalid("Rules limit must be 1 through 128")
    if (query.name != null && query.name.isBlank()) rulesInvalid("Rules name cannot be blank")
    val ruleset = game.ruleset
    fun yields(stats: Stats): Map<String, Float> = stats.associate { it.key.name to it.value }
    val entries: List<Map<String, Any?>> = when (section) {
        "improvements" -> ruleset.tileImprovements.values.map { improvement -> linkedMapOf(
            "name" to improvement.name, "section" to section,
            "techRequired" to improvement.techRequired,
            "terrainsCanBeBuiltOn" to improvement.terrainsCanBeBuiltOn.toList(),
            "turnsToBuild" to improvement.turnsToBuild, "uniqueTo" to improvement.uniqueTo,
            "replaces" to improvement.replaces, "yields" to yields(improvement),
            "effects" to improvement.uniques.toList(),
            "maintenance" to improvement.uniqueObjects.filter {
                it.type == com.unciv.models.ruleset.unique.UniqueType.ImprovementMaintenance
                    || it.type == com.unciv.models.ruleset.unique.UniqueType.ImprovementAllMaintenance
            }.map { it.text }
        ) }
        "terrains" -> ruleset.terrains.values.map { terrain -> linkedMapOf(
            "name" to terrain.name, "section" to section, "type" to terrain.type.name,
            "occursOn" to terrain.occursOn.toList(), "turnsInto" to terrain.turnsInto,
            "yields" to yields(terrain), "overrideStats" to terrain.overrideStats,
            "movementCost" to terrain.movementCost, "defenceBonus" to terrain.defenceBonus,
            "impassable" to terrain.impassable, "unbuildable" to terrain.unbuildable,
            "effects" to terrain.uniques.toList()
        ) }
        else -> ruleset.tileResources.values.map { resource -> linkedMapOf(
            "name" to resource.name, "section" to section, "type" to resource.resourceType.name,
            "revealedBy" to resource.revealedBy,
            "terrainsCanBeFoundOn" to resource.terrainsCanBeFoundOn.toList(),
            "improvedBy" to resource.getImprovements().sorted(),
            "yields" to yields(resource),
            "improvementYields" to resource.improvementStats?.let { yields(it) },
            "effects" to resource.uniques.toList()
        ) }
    }.filter { query.name == null || it["name"] == query.name }.sortedBy { it["name"] as String }
    if (query.name != null && entries.isEmpty()) rulesInvalid("Unknown $section rule: ${query.name}")
    val digestInput = AgentJson.stringify(listOf(rulesetIdentity, section, query.name, query.limit, entries))
    val digest = MessageDigest.getInstance("SHA-256").digest(digestInput.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun cursor(index: Int) = "rules1:$digest:$index"
    val start = if (query.cursor == null) 0 else {
        val parts = query.cursor.split(':')
        if (parts.size != 3 || parts[0] != "rules1" || parts[1] != digest) rulesInvalid("Rules cursor does not match this query or ruleset")
        val index = parts[2].toIntOrNull() ?: rulesInvalid("Invalid rules cursor")
        if (index <= 0 || index >= entries.size) rulesInvalid("Rules cursor is outside this catalog")
        index
    }
    fun page(values: List<Map<String, Any?>>, next: String?) = RulesCatalogPage(
        ruleset.name, game.gameParameters.baseRuleset, rulesetIdentity, section, values, next)
    val accepted = ArrayList<Map<String, Any?>>()
    var index = start
    while (index < entries.size && accepted.size < query.limit) {
        val prospective = accepted + entries[index]
        val next = if (index + 1 < entries.size) cursor(index + 1) else null
        if (!AgentReadBudget.fits(page(prospective, next).toJson())) {
            if (accepted.isEmpty()) rulesInvalid("Rules entry exceeds the 96 KiB response budget")
            break
        }
        accepted.add(entries[index++])
    }
    val result = page(accepted, if (index < entries.size) cursor(index) else null)
    if (!AgentReadBudget.fits(result.toJson()))
        rulesInvalid("Rules response metadata exceeds the 96 KiB response budget")
    return result
}
