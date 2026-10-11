package com.unciv.logic.agents

import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentModelCatalogTest {

    @Test
    fun `codex defaults to gpt-6-luna with xhigh thinking`() {
        val defaultModel = AgentModelCatalog.getDefaultModel("codex")
        Assert.assertEquals("gpt-6-luna", defaultModel.id)
        Assert.assertEquals("GPT-6 Luna", defaultModel.label)
        Assert.assertEquals(ThinkingLevel.XHIGH, defaultModel.defaultThinkingLevel)
        Assert.assertEquals("xhigh", defaultModel.defaultThinkingLevel.wireValue)
        Assert.assertTrue(defaultModel.supportsThinking)
    }

    @Test
    fun `profile factory produces valid luna profile`() {
        val profile = AgentProfile.defaultFor("America", "codex")
        Assert.assertEquals("codex", profile.provider)
        Assert.assertEquals("gpt-6-luna", profile.modelId)
        Assert.assertEquals("xhigh", profile.reasoning)
        Assert.assertEquals("GPT-6 Luna America", profile.displayName)

        val errors = AgentModelCatalog.validateProfile(profile)
        Assert.assertTrue("Expected no errors but got: $errors", errors.isEmpty())
    }

    @Test
    fun `luna max and sol reasoning levels round trip without substitution`() {
        for (id in listOf("gpt-6-luna", "gpt-6.1-sol")) {
            val spec = AgentModelCatalog.findModel("codex", id)
            for (wire in listOf("low", "medium", "high", "xhigh", "max")) {
                val level = ThinkingLevel.fromWireValue(wire)
                Assert.assertEquals(wire, level.wireValue)
                Assert.assertEquals(level, AgentModelCatalog.revalidateThinkingLevel(spec, level))
                Assert.assertNull(AgentModelCatalog.validateThinkingLevel("codex", id, level))
            }
        }
        Assert.assertNull(AgentModelCatalog.validateThinkingLevel("codex", "gpt-6.1-sol", ThinkingLevel.ULTRA))
        Assert.assertNotNull(AgentModelCatalog.validateThinkingLevel("codex", "gpt-6-luna", ThinkingLevel.ULTRA))
        val saved = AgentProfile.defaultFor("Rome").apply { modelId = "gpt-5.6-luna"; reasoning = "low" }
        Assert.assertTrue(AgentModelCatalog.validateProfile(saved).isEmpty())
        Assert.assertEquals("gpt-5.6-luna", saved.modelId)
    }

    @Test
    fun `thinking level revalidation adjusts incompatible level`() {
        val luna = AgentModelCatalog.findModel("codex", "gpt-5.6-luna")
        // Luna supports DEFAULT, LOW, MEDIUM, HIGH
        Assert.assertEquals(ThinkingLevel.LOW, AgentModelCatalog.revalidateThinkingLevel(luna, ThinkingLevel.LOW))

        val gpt4o = AgentModelCatalog.findModel("codex", "gpt-4o")
        // gpt-4o does not support thinking
        Assert.assertEquals(ThinkingLevel.PROVIDER_DEFAULT, AgentModelCatalog.revalidateThinkingLevel(gpt4o, ThinkingLevel.HIGH))
    }

    @Test
    fun `claude and opencode catalogs have provider-scoped models`() {
        val claudeModels = AgentModelCatalog.getModelsForProvider("claude")
        Assert.assertTrue(claudeModels.any { it.id == "claude-3-7-sonnet" })
        Assert.assertFalse(claudeModels.any { it.id == "gpt-5.6-luna" })

        val opencodeModels = AgentModelCatalog.getModelsForProvider("opencode")
        Assert.assertTrue(opencodeModels.any { it.id == "openai/gpt-5.6-luna" })
        Assert.assertTrue(opencodeModels.any { it.id == "anthropic/claude-3-7-sonnet" })
    }

    @Test
    fun `unknown custom models are preserved and flagged as custom`() {
        val custom = AgentModelCatalog.findModel("codex", "my-private-model")
        Assert.assertEquals("my-private-model", custom.id)
        Assert.assertTrue(custom.isCustom)
        Assert.assertEquals(ThinkingLevel.PROVIDER_DEFAULT, custom.defaultThinkingLevel)

        val profile = AgentProfile(
            profileId = "test-custom",
            displayName = "Custom Agent",
            provider = "codex",
            modelId = "my-private-model",
            reasoning = null
        )
        val errors = AgentModelCatalog.validateProfile(profile)
        Assert.assertTrue("Custom model should be valid with default reasoning", errors.isEmpty())
    }

    @Test
    fun `profile validation catches missing display name and invalid reasoning`() {
        val noName = AgentProfile(
            profileId = "1",
            displayName = "   ",
            provider = "codex",
            modelId = "gpt-5.6-luna"
        )
        val errs = AgentModelCatalog.validateProfile(noName)
        Assert.assertTrue(errs.any { it.contains("display name cannot be blank") })

        val gpt4oWithThinking = AgentProfile(
            profileId = "2",
            displayName = "GPT-4o Agent",
            provider = "codex",
            modelId = "gpt-4o",
            reasoning = "high"
        )
        val errs2 = AgentModelCatalog.validateProfile(gpt4oWithThinking)
        Assert.assertTrue(errs2.any { it.contains("does not support thinking levels") })
    }
    @Test
    fun `deepseek flash via opencode go has verified efforts and stays selectable without discovery`() {
        AgentModelCatalog.setDiscoveredModels("opencode", emptyList())
        val model = AgentModelCatalog.getModelsForProvider("opencode")
            .first { it.id == "opencode-go/deepseek-v4.1-flash" }
        Assert.assertEquals("OpenCode Go: DeepSeek V4.1 Flash", model.label)
        Assert.assertFalse(model.isCustom)
        Assert.assertEquals(listOf(ThinkingLevel.PROVIDER_DEFAULT, ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX), model.supportedThinkingLevels)
        for (level in model.supportedThinkingLevels)
            Assert.assertNull(AgentModelCatalog.validateThinkingLevel("opencode", model.id, level))
        Assert.assertNotNull(AgentModelCatalog.validateThinkingLevel("opencode", model.id, ThinkingLevel.MEDIUM))
        Assert.assertNotNull(AgentModelCatalog.validateThinkingLevel("opencode", model.id, ThinkingLevel.OFF))
        Assert.assertEquals("openai/gpt-5.6-luna", AgentModelCatalog.getDefaultModel("opencode").id)
        try {
            AgentModelCatalog.setDiscoveredModels("opencode", listOf(AgentModelCatalog.OPENCODE_MODELS.first()))
            Assert.assertTrue(AgentModelCatalog.getModelsForProvider("opencode").any { it.id == model.id })
            AgentModelCatalog.setDiscoveredModels("opencode", listOf(model.copy(supportedThinkingLevels = listOf(ThinkingLevel.MEDIUM), isDiscovered = true)))
            val discovered = AgentModelCatalog.getModelsForProvider("opencode").first { it.id == model.id }
            Assert.assertEquals(model.supportedThinkingLevels, discovered.supportedThinkingLevels)
            Assert.assertTrue(discovered.isDiscovered)
        } finally {
            AgentModelCatalog.setDiscoveredModels("opencode", emptyList())
        }
    }

}
