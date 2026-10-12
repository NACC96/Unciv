package com.unciv.ui.components

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.agents.*
import com.unciv.models.skins.SkinCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.AgentActivityFeed
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class AtlasAgentPanelsTest {
    private lateinit var prior: SetupUiStateSnapshot
    @Before fun setup() { prior = SetupUiStateSnapshot() }
    @After fun restore() { prior.restore() }

    private class Fixture {
        val test = TestGame()
        val spectator = test.addCiv(test.ruleset.nations.getValue(Constants.spectator))
        val rome = test.addCiv(test.ruleset.nations.getValue("Rome"))
        val greece = test.addCiv(test.ruleset.nations.getValue("Greece"))
        var viewer = spectator
        var entries = (0..16).map { AgentActivityEntry("message-$it", rome.civID, 1, "message", "", "", "time-$it") }
        val root = Group()
        val stage = Mockito.mock(UncivStage::class.java)
        val screen = Mockito.mock(WorldScreen::class.java)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val detailReads = mutableListOf<String>()
        init {
            Fonts.fontImplementation = FontDesktop(); ImageGetter.setNewRuleset(test.ruleset)
            SkinCache.loadSkinConfigs(); UncivGame.Current.settings.skin = "Atlas"; BaseScreen.setSkin()
            Mockito.`when`(stage.width).thenReturn(960f); Mockito.`when`(stage.height).thenReturn(720f)
            Mockito.`when`(stage.root).thenReturn(root)
            Group::class.java.getDeclaredMethod("setStage", com.badlogic.gdx.scenes.scene2d.Stage::class.java)
                .apply { isAccessible = true }.invoke(root, stage)
            Mockito.doAnswer { root.addActor(it.getArgument(0)); null }.`when`(stage).addActor(Mockito.any(Actor::class.java))
            Mockito.`when`(screen.stage).thenReturn(stage); Mockito.`when`(screen.gameInfo).thenReturn(test.gameInfo)
            Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
            Mockito.`when`(screen.viewingCiv).thenAnswer { viewer }
            Mockito.`when`(screen.currentViewerCivId()).thenAnswer { viewer.civID }
            val tech = Mockito.mock(TechPolicyDiplomacyButtons::class.java)
            Mockito.`when`(tech.y).thenReturn(620f)
            val getter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getTechPolicyAndDiplomacy" }
            Mockito.`when`(getter.invoke(screen) as TechPolicyDiplomacyButtons?).thenReturn(tech)
            Mockito.`when`(session.getRunState()).thenReturn(AgentRunState.AGENT_DECIDING)
            Mockito.`when`(session.getActiveCivId()).thenReturn(rome.civID)
            Mockito.`when`(session.getAgentProfile(Mockito.anyString())).thenAnswer { AgentProfile.defaultFor(it.getArgument(0)) }
            Mockito.`when`(session.getActivity()).thenAnswer { entries }
            Mockito.`when`(session.getActivityDetails(Mockito.anyString())).thenAnswer {
                val id = it.getArgument<String>(0); detailReads += id
                "$id: " + "The river remains safe while our cities prepare their next production. ".repeat(4)
            }
        }
    }
    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }
    private fun AgentActivityFeed.call(method: String) = javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(this)
    private fun anchor(feed: AgentActivityFeed): String {
        val mark = feed.call("readingAnchor")!!
        return mark.javaClass.getDeclaredMethod("getKey").apply { isAccessible = true }.invoke(mark) as String
    }
    private fun Actor.press() = listeners.filterIsInstance<ActivationListener>().single()
        .tap(InputEvent().apply { listenerActor = this@press }, 1f, 1f, 1, 0)

    @Test fun explicitActivityNavigationReopensClosedFeedWithoutResettingReading() {
        val f = Fixture()
        Mockito.`when`(f.stage.width).thenReturn(420f)
        val top = Mockito.mock(com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar::class.java)
        val getter = f.screen.javaClass.methods.single { it.name.substringBefore('$') == "getTopBar" }
        Mockito.`when`(getter.invoke(f.screen)).thenReturn(top)
        val feed = AgentActivityFeed(f.screen, f.session)
        val minimap = Mockito.mock(com.unciv.ui.screens.worldscreen.minimap.MinimapHolder::class.java)
        Mockito.`when`(minimap.children).thenReturn(com.badlogic.gdx.utils.SnapshotArray<Actor>())
        val hud = com.unciv.ui.screens.worldscreen.AtlasWorldHud(f.screen,
            com.badlogic.gdx.scenes.scene2d.ui.Table(), com.badlogic.gdx.scenes.scene2d.ui.Table(),
            com.badlogic.gdx.scenes.scene2d.ui.Table(), minimap,
            GameUiStyle.button("Next turn"), com.badlogic.gdx.scenes.scene2d.ui.Table())
        try {
            fun tab(label: String) = f.root.descendants().first { it.name == "AtlasWorldTabs" }
                .descendants().filterIsInstance<TextButton>().single { it.text.toString().removePrefix("✓ ") == label }
            tab("Activity").press(); feed.call("holdPosition")
            val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
            scroll.scrollY = scroll.maxY * .5f; val before = anchor(feed)
            feed.hide(); hud.layout(); assertFalse(feed.isVisible)
            tab("Research").press(); assertFalse(feed.isVisible)
            tab("Activity").press(); assertTrue(feed.isVisible); assertEquals(before, anchor(feed))
        } finally { feed.dispose() }
    }

    @Test fun allocationKeepsReadingComposerAndFooterBounded() {
        val type = try { Class.forName("com.unciv.ui.screens.worldscreen.status.AgentPanelLayout") }
            catch (_: ClassNotFoundException) { fail("Missing view-only AgentPanelLayout allocation"); return }
        val layout = type.getField("INSTANCE").get(null)
        val calculate = type.getMethod("calculate", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        for (size in listOf(480f to 480f, 388f to 260f, 1f to 1f)) for (scale in listOf(1f, 1.5f)) {
            val regions = calculate.invoke(layout, size.first, size.second, scale)
            val rectangles = listOf("Content", "Composer", "Footer").map { regions.javaClass.getMethod("get$it").invoke(regions) as Rectangle }
            rectangles.forEach { assertTrue(it.x >= 0 && it.y >= 0 && it.width > 0 && it.height > 0); assertTrue(it.x + it.width <= size.first + .01f); assertTrue(it.y + it.height <= size.second + .01f) }
            assertEquals(size.second, rectangles.sumOf { it.height.toDouble() }.toFloat(), .01f)
            assertTrue("The reading area must stay usable", rectangles[0].height >= size.second * .3f)
        }
    }
    @Test fun atlasComposerAndActionsRemainInsideTheirAllocatedPanels() {
        val f = Fixture()
        Mockito.`when`(f.session.getMessages()).thenReturn(emptyList())
        Mockito.`when`(f.session.getMessageContacts()).thenReturn(listOf(MessageContact("Greece", "Athena", true)))
        Mockito.`when`(f.session.getNationSettings()).thenReturn(listOf(NationSetting("Rome", "Rome", ControllerKind.HUMAN, AgentProfile.defaultFor("Rome"), true)))
        for ((w, h) in listOf(1440f to 900f, 1920f to 1080f, 960f to 720f, 800f to 500f, 420f to 600f)) {
            Mockito.`when`(f.stage.width).thenReturn(w); Mockito.`when`(f.stage.height).thenReturn(h)
            for (scale in listOf(1f, 1.5f)) {
                UncivGame.Current.settings.fontSizeMultiplier = scale
                Fonts.fontImplementation = com.unciv.app.desktop.DesktopFont()
                BaseScreen.setSkin()
                val panel = com.unciv.ui.screens.worldscreen.status.AgentMessagesPanel(f.screen, f.session)
                val settings = com.unciv.ui.screens.worldscreen.status.AgentNationSettingsPanel(f.screen, f.session)
                try {
                    panel.toggle(); panel.validate()
                    for (name in listOf("MessageReadingPane", "MessageComposerPane", "MessageFooter", "MessageSend")) {
                        val actor = panel.descendants().single { it.name == name }
                        val p = actor.localToAscendantCoordinates(panel, com.badlogic.gdx.math.Vector2())
                        assertTrue("$w x $h @$scale: $name $p ${actor.width} ${actor.height}", p.x >= -.5f && p.y >= -.5f && p.x + actor.width <= panel.width + .5f && p.y + actor.height <= panel.height + .5f)
                    }
                    val reading = panel.descendants().single { it.name == "MessageReadingPane" }
                    assertTrue(reading.height >= 80f)
                    settings.toggle(); settings.validate()
                    val save = settings.descendants().single { it.name == "NationSave" }
                    val p = save.localToAscendantCoordinates(settings, com.badlogic.gdx.math.Vector2())
                    assertTrue(p.x >= -.5f && p.y >= -.5f && p.x + save.width <= settings.width + .5f && p.y + save.height <= settings.height + .5f)
                } finally { panel.dispose(); settings.dispose() }
            }
        }
    }

    @Test fun readingPaneUsesSettledVisualPositionAfterReflow() {
        val f = Fixture()
        Mockito.`when`(f.session.getMessages()).thenReturn((1..6).map {
            DiplomaticMessage("dispatch-$it", "Rome", "Greece", "A long diplomatic dispatch from the river frontier. ".repeat(8), it, null)
        })
        Mockito.`when`(f.session.getMessageContacts()).thenReturn(listOf(MessageContact("Greece", "Athena", true)))
        val panel = com.unciv.ui.screens.worldscreen.status.AgentMessagesPanel(f.screen, f.session)
        try {
            panel.toggle()
            val scroll = panel.descendants().single { it.name == "MessageReadingPane" } as AutoScrollPane
            repeat(3) { panel.validate(); panel.invalidateHierarchy() }
            panel.validate(); scroll.validate()
            assertEquals(scroll.maxY, scroll.scrollY, 1f)
            assertEquals("The rendered reading position must settle with the target", scroll.scrollY, scroll.visualScrollY, 1f)
        } finally { panel.dispose() }
    }

    @Test fun boundedHistoryStillReachesTheOldestEntries() {
        val f = Fixture(); f.entries = (1..1000).map { AgentActivityEntry("message-$it", f.rome.civID, 1, "message", "", "", "time-$it") }
        val feed = AgentActivityFeed(f.screen, f.session)
        try {
            repeat(9) { feed.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Earlier" }.press() }
            val labels = feed.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
            assertEquals(600, labels.count { it.startsWith("message-") })
            assertTrue(labels.any { it.startsWith("message-1:") })
            val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
            scroll.scrollY = 0f; val before = anchor(feed)
            f.entries += (1001..1100).map { AgentActivityEntry("message-$it", f.rome.civID, 1, "message", "", "", "time-$it") }
            feed.refresh()
            assertEquals(before, anchor(feed))
            assertEquals(600, feed.descendants().filterIsInstance<Label>().count { it.text.toString().startsWith("message-") })
        } finally { feed.dispose() }
    }

    @Test fun streamingKeepsHistoricalAnchor() {
        val f = Fixture(); val feed = AgentActivityFeed(f.screen, f.session)
        try {
            feed.validate(); val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
            feed.call("holdPosition"); scroll.scrollY = scroll.maxY * .45f; scroll.updateVisualScroll()
            val beforeAnchorId = anchor(feed)
            f.entries = f.entries.mapIndexed { i, e -> if (i == 0) e.copy(timestamp = "updated") else e } + AgentActivityEntry("new", f.rome.civID, 1, "message", "", "", "new")
            feed.call("refresh"); feed.validate()
            assertEquals(beforeAnchorId, anchor(feed))
        } finally { feed.dispose() }
    }
    @Test fun liveJumpsToNewest() {
        val f = Fixture(); val feed = AgentActivityFeed(f.screen, f.session)
        try {
            val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
            feed.call("holdPosition"); scroll.scrollY = 0f
            f.entries += AgentActivityEntry("new", f.rome.civID, 1, "message", "", "", "new")
            feed.call("refresh")
            feed.descendants().filterIsInstance<TextButton>().single { it.text.toString().endsWith("new") }.press()
            feed.validate(); feed.act(0f)
            assertEquals(scroll.maxY, scroll.scrollY, 1f)
        } finally { feed.dispose() }
    }
    @Test fun tenToolsCollapseWithoutFetchingDetails() {
        val f = Fixture(); f.entries = (1..10).map { AgentActivityEntry("tool-$it", f.rome.civID, 1, "tool", "Inspect $it", "", "time-$it", if (it == 10) "running" else "succeeded") }
        val feed = AgentActivityFeed(f.screen, f.session)
        try {
            val labels = feed.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
            assertEquals(1, labels.count { it.startsWith("Inspect ") }); assertTrue("Inspect 10" in labels)
            assertTrue(labels.any { it.startsWith("10 tool calls") }); assertTrue(f.detailReads.isEmpty())
        } finally { feed.dispose() }
    }
    @Test fun viewSwitchRemovesPrivateContent() {
        val f = Fixture(); f.entries += AgentActivityEntry("private", f.greece.civID, 1, "message", "", "", "private")
        val feed = AgentActivityFeed(f.screen, f.session)
        try {
            val picker = feed.descendants().filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.SelectBox<*>>().single()
            @Suppress("UNCHECKED_CAST") val box = picker as com.badlogic.gdx.scenes.scene2d.ui.SelectBox<Any>
            box.selected = box.items.first { it.toString() == "All nations" }
            assertTrue(feed.descendants().filterIsInstance<Label>().any { it.text.toString().startsWith("private:") })
            f.viewer = f.rome; feed.call("refresh")
            assertFalse(feed.descendants().filterIsInstance<Label>().any { it.text.toString().startsWith("private:") })
        } finally { feed.dispose() }
    }
}
