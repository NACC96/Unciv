package com.unciv.ui.components

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.agents.AgentActivityEntry
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.AgentRunTiming
import com.unciv.ui.screens.worldscreen.status.AgentMatchControlTable
import com.badlogic.gdx.utils.Timer
import com.unciv.models.skins.SkinCache
import com.unciv.models.translations.tr
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.status.AgentActivityFeed
import com.unciv.ui.screens.worldscreen.overview.CivilizationOverviewPanel
import com.unciv.ui.screens.worldscreen.overview.CivilizationOverviewData
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.view.GameView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

/** Real HUD widgets with a mocked native stage, not tests of palette constants. */
@RunWith(GdxTestRunner::class)
class WorldScreenHudTest {
    private lateinit var priorNames: Map<String, Char>
    private lateinit var priorActors: Map<Char, Actor>
    private val feeds = ArrayList<AgentActivityFeed>()
    private val controls = ArrayList<AgentMatchControlTable>()
    private val overviews = ArrayList<CivilizationOverviewPanel>()

    @Before fun setup() {
        priorNames = FontRulesetIcons.rulesetObjectNameToChar.toMap()
        priorActors = FontRulesetIcons.charToRulesetImageActor.toMap()
    }
    @After fun cleanup() {
        controls.forEach { it.dispose() }
        feeds.forEach { it.dispose() }
        overviews.forEach { it.dispose() }
        FontRulesetIcons.rulesetObjectNameToChar.clear()
        FontRulesetIcons.rulesetObjectNameToChar.putAll(priorNames)
        FontRulesetIcons.charToRulesetImageActor.clear()
        FontRulesetIcons.charToRulesetImageActor.putAll(priorActors)
    }

    private class Fixture(var width: Float = 960f, var height: Float = 600f, spectator: Boolean = true) {
        val game = TestGame()
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true).apply { addGold(123) }
        val korea = game.addCiv(game.ruleset.nations["Korea"]!!).apply { addGold(456) }
        val spectatorCiv = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
        val selected = if (spectator) spectatorCiv else rome
        val root = Group()
        val stage = Mockito.mock(UncivStage::class.java)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val screen = Mockito.mock(WorldScreen::class.java)
        var active: String? = "Rome"
        var activity = emptyList<AgentActivityEntry>()
        var timing: AgentRunTiming? = null

        init {
            Fonts.fontImplementation = FontDesktop()
            ImageGetter.setNewRuleset(game.ruleset)
            Mockito.`when`(stage.width).thenAnswer { width }
            Mockito.`when`(stage.height).thenAnswer { height }
            Mockito.`when`(stage.root).thenReturn(root)
            Group::class.java.getDeclaredMethod("setStage", com.badlogic.gdx.scenes.scene2d.Stage::class.java)
                .apply { isAccessible = true }.invoke(root, stage)
            Mockito.doAnswer { root.addActor(it.getArgument(0)); null }.`when`(stage).addActor(Mockito.any(Actor::class.java))
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
            Mockito.`when`(screen.selectedCiv).thenReturn(selected)
            Mockito.`when`(screen.viewingCiv).thenReturn(selected)
            Mockito.`when`(screen.selectedGameView).thenReturn(GameView(game.gameInfo, selected, spectatorMode = spectator))
            Mockito.`when`(screen.agentSession).thenReturn(session)
            Mockito.`when`(session.getActiveCivId()).thenAnswer { active }
            Mockito.`when`(session.getAgentProfile(Mockito.anyString())).thenAnswer {
                AgentProfile.defaultFor(it.getArgument(0)).apply { displayName = if (it.getArgument<String>(0) == "Rome") "Atlas" else "Nova" }
            }
            Mockito.`when`(session.getRunState()).thenReturn(AgentRunState.AGENT_DECIDING)
            Mockito.`when`(session.getActivity()).thenAnswer { activity }
            Mockito.`when`(session.getActiveRunTiming()).thenAnswer { timing }
            Mockito.`when`(session.getActiveAgentProfile()).thenReturn(AgentProfile.defaultFor("Rome").apply {
                displayName = "A patient ruler of coastlines, trade routes and a very long imperial title"
                modelId = "saved-provider/custom-model-with-a-long-identity"
            })
            Mockito.`when`(session.getActivityDetails(Mockito.anyString())).thenAnswer {
                val id = it.getArgument<String>(0)
                if (id == "tool") "RAW TOOL DETAILS" else "Message $id. The Warrior will explore the nearby coast while the city builds a Scout."
            }
            val tech = Mockito.mock(TechPolicyDiplomacyButtons::class.java)
            Mockito.`when`(tech.y).thenAnswer { height - 100f }
            val getter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getTechPolicyAndDiplomacy" }
            Mockito.`when`(getter.invoke(screen) as TechPolicyDiplomacyButtons?).thenReturn(tech)
        }

        fun bar() = WorldScreenTopBar(screen).also { stage.addActor(it); update(it) }
        fun update(bar: WorldScreenTopBar) {
            bar.javaClass.methods.single { it.name.substringBefore('$') == "update" }.invoke(bar, selected)
        }
    }

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) for (child in children) yieldAll(child.descendants())
    }
    private fun Actor.press() {
        listeners.filterIsInstance<ActivationListener>().single().tap(InputEvent().apply { listenerActor = this@press }, 1f, 1f, 1, 0)
    }
    private fun AgentActivityFeed.refreshNow() = javaClass.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(this)
    private fun AgentActivityFeed.selectNation(label: String) {
        @Suppress("UNCHECKED_CAST")
        val picker = descendants().filterIsInstance<SelectBox<*>>().single() as SelectBox<Any>
        picker.selected = picker.items.first { it.toString() == label || it.toString().endsWith(" · $label") }
    }
    private fun AgentActivityFeed.messageIds() = descendants().filterIsInstance<Label>()
        .map { it.text.toString() }.filter { it.startsWith("Message ") }.toList()
    private fun Fixture.feed() = AgentActivityFeed(screen, session).also { feeds.add(it) }
    private fun Fixture.overview() = CivilizationOverviewPanel(screen).also { overviews.add(it) }
    private fun entry(id: String, kind: String = "message") = AgentActivityEntry(id, "Rome", 1, kind, "Move Warrior", "Moved to (1, 2)", id, "succeeded")

    private fun Fixture.controls() = AgentMatchControlTable(session, screen).also { controls.add(it) }
    private fun AgentMatchControlTable.tickTimer() = javaClass.getDeclaredField("timingRefreshTask")
        .apply { isAccessible = true }.get(this).let { (it as Timer.Task).run() }
    private fun AgentMatchControlTable.timerScheduled() = javaClass.getDeclaredField("timingRefreshTask")
        .apply { isAccessible = true }.get(this).let { (it as Timer.Task).isScheduled }
    private fun AgentMatchControlTable.timerLabel() = descendants().filterIsInstance<Label>().single { it.name == "AgentRunElapsed" }

    @Test fun `elapsed updates while activity is hidden without polling activity or rebuilding status`() {
        val f = Fixture()
        f.timing = AgentRunTiming("run-1", "Rome", 65_000)
        val controls = f.controls()
        assertTrue(controls.timerScheduled())
        val feed = f.root.children.filterIsInstance<AgentActivityFeed>().single()
        feed.hide()
        val statusLabels = controls.descendants().filterIsInstance<Label>().toList()
        Mockito.clearInvocations(f.session, f.screen)
        f.timing = AgentRunTiming("run-1", "Rome", 66_000)
        controls.tickTimer()
        assertEquals("Elapsed 01:06 · Rome", controls.timerLabel().text.toString())
        assertEquals(statusLabels, controls.descendants().filterIsInstance<Label>().toList())
        Mockito.verify(f.session, Mockito.never()).getActivity()
        Mockito.verify(f.session, Mockito.never()).getRunState()
        Mockito.verify(f.screen).viewingCiv
        Mockito.verifyNoMoreInteractions(f.screen)
    }

    @Test fun `timer stays visible during pause drains resets on retry and disappears at completion`() {
        val f = Fixture()
        f.timing = AgentRunTiming("run-1", "Rome", 3_600_000)
        val controls = f.controls()
        Mockito.`when`(f.session.getRunState()).thenReturn(AgentRunState.PAUSING)
        controls.update()
        f.timing = AgentRunTiming("run-1", "Rome", 3_601_000)
        controls.tickTimer()
        assertEquals("Elapsed 1:00:01 · Rome", controls.timerLabel().text.toString())
        f.timing = null
        controls.tickTimer()
        assertFalse(controls.timerLabel().isVisible)
        assertFalse(controls.timerScheduled())
        f.timing = AgentRunTiming("retry-1", "Rome", 0)
        controls.tickTimer()
        assertTrue(controls.timerLabel().isVisible)
        assertTrue(controls.timerScheduled())
        assertEquals("Elapsed 00:00 · Rome", controls.timerLabel().text.toString())
        controls.dispose()
        assertFalse(controls.timerScheduled())
        Mockito.clearInvocations(f.session)
        controls.tickTimer()
        Mockito.verifyNoInteractions(f.session)
    }

    @Test fun `idle or unchanged timer never requests a redraw`() {
        val f = Fixture()
        val controls = f.controls()
        assertFalse(controls.timerScheduled())
        val previous = com.badlogic.gdx.Gdx.graphics
        val graphics = Mockito.mock(com.badlogic.gdx.Graphics::class.java)
        com.badlogic.gdx.Gdx.graphics = graphics
        try {
            controls.tickTimer()
            Mockito.verifyNoInteractions(graphics)
            f.timing = AgentRunTiming("run", "Rome", 1_000)
            controls.tickTimer()
            Mockito.verify(graphics).requestRendering()
            Mockito.clearInvocations(graphics)
            controls.tickTimer()
            Mockito.verifyNoInteractions(graphics)
            f.timing = null
            controls.tickTimer()
            Mockito.verify(graphics).requestRendering()
            Mockito.clearInvocations(graphics)
            controls.tickTimer()
            Mockito.verifyNoInteractions(graphics)
        } finally { com.badlogic.gdx.Gdx.graphics = previous }
    }

    @Test fun `ordinary viewer sees only their own timer and spectator sees every active timer`() {
        val f = Fixture(spectator = false)
        f.timing = AgentRunTiming("foreign", "Korea", 65_000)
        val controls = f.controls()
        assertFalse(controls.timerLabel().isVisible)
        f.timing = AgentRunTiming("own", "Rome", 65_000)
        controls.tickTimer()
        assertTrue(controls.timerLabel().isVisible)
        Mockito.`when`(f.screen.viewingCiv).thenReturn(f.spectatorCiv)
        f.timing = AgentRunTiming("foreign", "Korea", 65_000)
        controls.tickTimer()
        assertTrue(controls.timerLabel().isVisible)
    }

    @Test fun `long agent identity model and hour timer retain their own rows within narrow controls`() {
        val f = Fixture(width = 420f)
        f.active = "The Ottomans"
        f.game.addCiv(f.game.ruleset.nations["The Ottomans"]!!)
        f.timing = AgentRunTiming("run", "The Ottomans", 45_296_000)
        val controls = f.controls()
        controls.validate()
        val elapsed = controls.timerLabel()
        assertEquals("Elapsed 12:34:56 · The Ottomans", elapsed.text.toString())
        assertTrue(elapsed.width >= com.badlogic.gdx.graphics.g2d.GlyphLayout(elapsed.style.font, "Elapsed 12:34:56").width * elapsed.fontScaleX)
        assertTrue(controls.width <= f.width - 20f)
        for (label in controls.descendants().filterIsInstance<Label>()) {
            val point = label.localToAscendantCoordinates(controls, Vector2())
            assertTrue("Label outside compact controls: ${label.text}", point.x >= -0.1f && point.x + label.width <= controls.width + 0.1f)
        }
    }

    @Test fun `activity fits below elapsed controls when narrow but retains left top on wide screens`() {
        val f = Fixture(width = 420f)
        f.timing = AgentRunTiming("run", "Rome", 45_296_000)
        val controls = f.controls()
        f.stage.addActor(controls)
        val feed = f.root.children.filterIsInstance<AgentActivityFeed>().single()
        for (width in listOf(420f, 960f, 420f)) {
            f.width = width
            controls.update()
            controls.setPosition(width - controls.width - 8f, f.height - controls.height - 8f)
            feed.refreshNow()
            if (width == 420f) assertTrue("Activity header must remain below controls", feed.y + feed.height <= controls.y - 8f + 0.1f)
            else assertEquals("Wide left activity retains its original top", f.height - 108f, feed.y + feed.height, 0.1f)
            assertTrue(feed.y >= 8f && feed.height >= 160f)
        }
    }

    @Test fun `run durations remain named visible distinct from collapsed tools and obey nation filters`() {
        val f = Fixture()
        f.activity = listOf(entry("tool-1", "tool"), entry("tool-2", "tool"),
            entry("run-1", "run").copy(title = "**Provider** completed in 01:05", summary = "**Duration** 01:05", status = "completed"),
            entry("tool-3", "tool"),
            entry("run-2", "run").copy(civId = "Korea", title = "Provider failed in 1:00:01", summary = "Duration 1:00:01", status = "failed"))
        val feed = f.feed()
        fun labels() = feed.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
        assertTrue("Completed run identity missing from ${labels()}", labels().contains("Atlas · Run completed"))
        assertTrue("Completed title must retain padded duration: ${labels()}", labels().contains("Provider completed in 01:05"))
        assertTrue("Completed duration missing from ${labels()}", labels().contains("Duration 01:05"))
        assertFalse(labels().any { it.contains("1:00:01") })
        feed.selectNation("All nations")
        assertTrue("Failed run identity missing from ${labels()}", labels().contains("Nova · Run failed"))
        assertTrue("Failed title must retain padded duration: ${labels()}", labels().contains("Provider failed in 1:00:01"))
        assertTrue("Failed duration missing from ${labels()}", labels().contains("Duration 1:00:01"))
        val failure = feed.descendants().filterIsInstance<Label>().single { it.text.toString() == "Duration 1:00:01" }
        assertEquals(WorldScreenStyle.error, failure.style.fontColor)
        feed.selectNation("Rome · Atlas")
        assertFalse(labels().any { it.contains("1:00:01") })
        Mockito.`when`(f.screen.viewingCiv).thenReturn(f.rome)
        feed.selectNation("All nations")
        assertFalse(labels().any { it.contains("1:00:01") })
    }

    @Test fun `spectator economy and inspection view follow the active agent without switching the map view`() {
        val f = Fixture()
        val bar = f.bar()
        assertTrue(bar.descendants().filterIsInstance<Label>().any { it.text.toString() == "123" })
        f.active = "Korea"
        f.update(bar)
        assertTrue(bar.descendants().filterIsInstance<Label>().any { it.text.toString() == "456" })
        val view = bar.javaClass.methods.single { it.name.substringBefore('$') == "getDisplayedGameView" }.invoke(bar) as GameView
        assertSame(f.korea, view.civView.getCiv())
        assertSame(f.spectatorCiv, f.screen.selectedCiv)
    }

    @Test fun `human economy stays on the selected civilization during an agent turn`() {
        val f = Fixture(spectator = false)
        f.active = "Korea"
        val bar = f.bar()
        assertTrue(bar.descendants().filterIsInstance<Label>().any { it.text.toString() == "123" })
        assertFalse(bar.descendants().filterIsInstance<Label>().any { it.text.toString() == "456" })
    }

    @Test fun `top bar controls remain inside the window through narrow and wide resizes`() {
        val f = Fixture()
        val bar = f.bar()
        for (width in listOf(960f, 520f, 420f, 360f, 960f)) {
            f.width = width
            f.update(bar)
            assertEquals(width, bar.width, 0.01f)
            assertEquals(f.height, bar.y + bar.height, 0.01f)
            for (button in bar.descendants().filterIsInstance<Button>()) {
                val left = button.localToAscendantCoordinates(bar, Vector2.Zero.cpy())
                assertTrue("Button left at $width: $left", left.x >= 0f && left.y >= 0f)
                assertTrue("Button right at $width", left.x + button.width <= bar.width + 0.1f)
                assertTrue("Button top at $width", left.y + button.height <= bar.height + 0.1f)
            }
        }
    }

    @Test fun `long active civilization identity stays within the top bar at narrow widths`() {
        val f = Fixture()
        f.game.addCiv(f.game.ruleset.nations["The Ottomans"]!!)
        f.active = "The Ottomans"
        val bar = f.bar()
        for (width in listOf(960f, 420f, 360f, 960f)) {
            f.width = width
            f.update(bar)
            val identity = bar.descendants().filterIsInstance<Label>().single { it.text.toString() == "The Ottomans" }
            for (corner in listOf(Vector2(), Vector2(identity.width, identity.height))) {
                val point = identity.localToAscendantCoordinates(bar, corner)
                assertTrue("Civilization identity must stay within the HUD at $width: $point", point.x >= 0f && point.x <= bar.width)
                assertTrue(point.y >= 0f && point.y <= bar.height)
            }
        }
    }

    @Test fun `floating feed keeps tool details available and leaves surrounding map input free`() {
        val f = Fixture(width = 520f, height = 360f)
        f.activity = listOf(entry("tool", "tool"))
        val feed = f.feed()
        val title = feed.descendants().filterIsInstance<Label>().single { it.text.toString() == "Move Warrior" }
        (title.parent as Button).press()
        assertTrue(feed.descendants().filterIsInstance<Label>().any { it.text.toString() == "RAW TOOL DETAILS" })
        assertNull(feed.hit(feed.width + 5f, 10f, true))
        assertTrue(feed.x + feed.width <= f.width)
        assertTrue(feed.y + feed.height <= f.height)
        feed.toggle()
        assertFalse(feed.isVisible)
        feed.toggle()
        assertTrue(feed.isVisible)
    }

    @Test fun `feed content remains inside its glass for nine default agent names and long names`() {
        val f = Fixture(height = 720f)
        val nations = listOf("America", "Arabia", "Germany", "China", "Russia", "The Ottomans", "Persia", "England", "France")
        for (nation in nations) f.game.addCiv(f.game.ruleset.nations[nation]!!)
        val profiles = nations.associateWith { AgentProfile.defaultFor(it) }
        Mockito.`when`(f.session.getAgentProfile(Mockito.anyString())).thenAnswer { profiles[it.getArgument<String>(0)] }
        f.active = "America"
        f.activity = nations.flatMapIndexed { index, nation ->
            listOf(entry("$nation-message").copy(civId = nation, turnSerial = index + 1),
                entry("$nation-thinking", "reasoning").copy(civId = nation, turnSerial = index + 1),
                entry("$nation-tool", "tool").copy(civId = nation, turnSerial = index + 1,
                    title = "Inspect $nation's coastal expansion opportunities and available city improvements"))
        }
        val feed = f.feed()
        for (longName in listOf(false, true)) {
            if (longName) profiles["The Ottomans"]!!.displayName =
                "GPT-6 Luna The Ottomans · A patient ruler of coastlines, trade routes and a very long imperial title"
            for (width in listOf(960f, 420f, 960f)) {
                f.width = width
                feed.refreshNow()
                for (filter in listOf("All nations", "Follow active nation")) {
                    feed.selectNation(filter)
                    feed.validate()
                    val picker = feed.descendants().filterIsInstance<SelectBox<*>>().single()
                    assertEquals(11, picker.items.size)
                    assertEquals("Every real nation should retain its full identity in the choices", nations.toSet(),
                        picker.items.toList().drop(2).map { it.toString().substringAfter(". ")
                            .removePrefix("Current · ").removePrefix("Eliminated · ")
                            .removePrefix("Previous in order · ").substringBefore(" · ") }.toSet())
                    for (actor in feed.descendants().filter { it !== feed }) {
                        val point = actor.localToAscendantCoordinates(feed, Vector2())
                        val label = when (actor) {
                            is Label -> actor.text.toString().take(50)
                            is SelectBox<*> -> "nation selector"
                            else -> actor.javaClass.simpleName
                        }
                        assertTrue("$label extends past glass left at width=$width longName=$longName: x=${point.x}", point.x >= -0.5f)
                        assertTrue("$label extends past glass right at width=$width longName=$longName: x=${point.x} width=${actor.width} panel=${feed.width}",
                            point.x + actor.width <= feed.width + 0.5f)
                    }
                    assertEquals("Transcript must wrap rather than overflow horizontally", 0f,
                        feed.descendants().filterIsInstance<AutoScrollPane>().single().maxX, 0.5f)
                }
            }
        }
    }

    @Test fun `rotated filter and expanded tool arrows paint inside their parent rows`() {
        val f = Fixture()
        f.activity = listOf(entry("first", "tool"), entry("second", "tool"))
        val feed = f.feed()
        fun checkPaintBounds() {
            feed.validate()
            val arrows = feed.descendants().filter { it.rotation != 0f }.toList()
            assertTrue("The filter arrow should be rotated", arrows.isNotEmpty())
            for (arrow in arrows) {
                for (corner in listOf(Vector2(), Vector2(arrow.width, 0f),
                    Vector2(0f, arrow.height), Vector2(arrow.width, arrow.height))) {
                    val painted = arrow.localToParentCoordinates(corner)
                    assertTrue("Rotated arrow paints outside parent ${arrow.parent.javaClass.simpleName}: $painted in ${arrow.parent.width}x${arrow.parent.height}",
                        painted.x >= -0.5f && painted.y >= -0.5f &&
                            painted.x <= arrow.parent.width + 0.5f && painted.y <= arrow.parent.height + 0.5f)
                }
            }
        }
        checkPaintBounds()
        feed.descendants().filterIsInstance<Label>().single { it.text.toString() == "2 tool calls · Expand" }.press()
        checkPaintBounds()
        assertTrue("Expanded tools should also rotate their disclosure arrow",
            feed.descendants().count { it.rotation != 0f } >= 2)
    }

    @Test fun `reading earlier activity keeps scroll position and offers a jump to new entries`() {
        val f = Fixture()
        f.activity = (1..40).map { entry("$it") }
        val feed = f.feed()
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        assertEquals(scroll.maxY, scroll.scrollY, 0.1f)
        scroll.captureListeners.filterIsInstance<InputListener>().forEach { it.scrolled(InputEvent(), 0f, 0f, 0f, -1f) }
        scroll.scrollY = 20f
        f.activity = f.activity + (1..25).map { entry("new-$it") }
        feed.refreshNow()
        assertEquals(20f, scroll.scrollY, 0.1f)
        feed.act(5f)
        assertEquals("Even a burst and delayed frame must leave manual reading in place", 20f, scroll.scrollY, 0.1f)
        val live = feed.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "25 new" }
        live.press()
        assertEquals(scroll.maxY, scroll.scrollY, 0.1f)
        assertEquals("Live", live.text.toString())
    }

    @Test fun `ten consecutive calls show only the latest until their group is expanded`() {
        val f = Fixture()
        f.activity = (1..10).map { entry("call$it", "tool").copy(title = "Tool call $it") }
        val feed = f.feed()
        fun titles() = feed.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
        assertTrue(titles().contains("Tool call 10"))
        assertFalse("Earlier calls should not rush past the reader", titles().contains("Tool call 1"))
        assertTrue(titles().any { "10 tool calls" in it })
        val latest = feed.descendants().filterIsInstance<Label>().single { it.text.toString() == "Tool call 10" }
        (latest.parent as Button).press()
        assertTrue(titles().contains("Tool call 1"))
        assertTrue(titles().contains("Tool call 10"))
    }

    @Test fun `tool groups stop at messages agent changes turns and failures`() {
        val f = Fixture()
        f.activity = listOf(
            entry("one", "tool").copy(title = "Before message"), entry("message"),
            entry("two", "tool").copy(title = "Before agent change"),
            entry("three", "tool").copy(title = "Korean tool", civId = "Korea"),
            entry("four", "tool").copy(title = "New turn", civId = "Korea", turnSerial = 2),
            entry("failed", "tool").copy(title = "Action rejected", status = "failed", civId = "Korea", turnSerial = 2),
            entry("five", "tool").copy(title = "Recovery", civId = "Korea", turnSerial = 2))
        val feed = f.feed()
        feed.selectNation("All nations")
        val labels = feed.descendants().filterIsInstance<Label>().map { it.text.toString() }.toSet()
        for (title in listOf("Before message", "Before agent change", "Korean tool", "New turn", "Action rejected", "Recovery")) assertTrue(title, title in labels)
    }

    @Test fun `live feed immediately follows bursts and growing streamed messages`() {
        val f = Fixture()
        f.activity = (1..40).map { entry("$it") }
        val feed = f.feed()
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        for (burst in 1..3) {
            f.activity = f.activity + (1..25).map { entry("burst-$burst-$it") }
            feed.refreshNow()
            assertEquals("Live must catch the newest entry in the same refresh", scroll.maxY, scroll.scrollY, 0.1f)
            assertEquals("Painted text must catch up with the logical scroll position", scroll.maxY, scroll.visualScrollY, 0.1f)
        }
        val latest = f.activity.last()
        Mockito.`when`(f.session.getActivityDetails(latest.id)).thenReturn("A long streamed paragraph. ".repeat(250))
        f.activity = f.activity.dropLast(1) + latest.copy(timestamp = "stream-grown")
        feed.refreshNow()
        assertEquals("Streaming growth must remain caught up", scroll.maxY, scroll.scrollY, 0.1f)
        assertEquals(scroll.maxY, scroll.visualScrollY, 0.1f)
        val beforeResize = scroll.maxY
        f.width = 420f
        feed.refreshNow()
        feed.act(0.001f)
        assertTrue("Narrower wrapping should grow this long transcript", scroll.maxY > beforeResize)
        assertEquals("Live also follows layout growth", scroll.maxY, scroll.scrollY, 0.1f)
        assertEquals(scroll.maxY, scroll.visualScrollY, 0.1f)
    }

    @Test fun `each agent keeps its name and distinct readable identity color across turns`() {
        val f = Fixture()
        f.activity = listOf(entry("rome"), entry("korea").copy(civId = "Korea"), entry("rome-again").copy(turnSerial = 2))
        val feed = f.feed()
        feed.selectNation("All nations")
        val names = feed.descendants().filterIsInstance<Label>().filter { it.text.toString() in setOf("Atlas", "Nova") }.toList()
        assertEquals("Turn headings and each message both identify the speaker", 6, names.size)
        val atlas = names.filter { it.text.toString() == "Atlas" }
        val nova = names.filter { it.text.toString() == "Nova" }
        assertTrue(atlas.all { it.style.fontColor == atlas.first().style.fontColor })
        assertNotEquals(atlas.first().style.fontColor, nova.first().style.fontColor)
        f.active = "Korea"
        feed.refreshNow()
        assertTrue(feed.descendants().filterIsInstance<Label>().any { "Nova" in it.text.toString() })
    }

    @Test fun `long turns identify the agent before every message thinking entry and tool group`() {
        val f = Fixture()
        f.activity = listOf(entry("first"), entry("thinking", "reasoning"), entry("middle"),
            entry("call1", "tool"), entry("call2", "tool"), entry("last"))
        val feed = f.feed()
        val labels = feed.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
        assertEquals(4, labels.count { it == "Atlas" }) // turn heading plus three messages
        assertTrue("Atlas · Thinking" in labels)
        assertTrue("Atlas · Tools" in labels)
    }

    /** Atlas-only behaviour runs under the Atlas skin; the shared fixture otherwise keeps the default skin.
     * The fixture's ruleset load resets the skin, so the skin is selected after it. */
    private fun withAtlasSkin(block: (Fixture) -> Unit) {
        val snapshot = SetupUiStateSnapshot()
        try {
            val f = Fixture()
            SkinCache.loadSkinConfigs(); UncivGame.Current.settings.skin = "Atlas"; BaseScreen.setSkin()
            block(f)
        } finally {
            controls.forEach { it.dispose() }; controls.clear()
            feeds.forEach { it.dispose() }; feeds.clear()
            snapshot.restore()
        }
    }

    @Test fun `atlas feed names the speaker once per turn and keeps kind only captions`() = withAtlasSkin { f ->
        f.activity = listOf(entry("first"), entry("thinking", "reasoning"), entry("call1", "tool"), entry("call2", "tool"),
            entry("run-1", "run").copy(title = "Provider completed in 01:05", summary = "Duration 01:05", status = "completed"))
        val feed = f.feed()
        fun labels() = feed.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
        assertEquals("Only the turn heading names the agent", 1, labels().count { it == "Atlas" })
        for (kind in listOf("Thinking", "Tools", "Run completed")) assertTrue("$kind caption missing from ${labels()}", kind in labels())
        assertFalse(labels().any { it.startsWith("Atlas · ") })
        assertFalse("One header row: no title, identity, elapsed or state rows", labels().any {
            it == "Agent activity" || it == "No active provider run" || it.contains(" activity · ") })
        feed.selectNation("All nations")
        assertTrue("All nations keeps full speaker captions", "Atlas · Thinking" in labels() && "Atlas · Run completed" in labels())
    }

    @Test fun `atlas Live chip floats only while reading and Earlier appears only with older history`() = withAtlasSkin { f ->
        f.activity = (1..40).map { entry("$it") }
        val feed = f.feed()
        val live = feed.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Live" }
        val earlier = feed.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Earlier" }
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        assertFalse("Following needs no Live control", live.isVisible)
        assertFalse("No older history to offer", earlier.isVisible)
        assertTrue("Earlier sits atop the entries, not in a footer", earlier.isDescendantOf(scroll))
        scroll.captureListeners.filterIsInstance<InputListener>().forEach { it.scrolled(InputEvent(), 0f, 0f, 0f, -1f) }
        scroll.scrollY = 20f
        f.activity = f.activity + (1..3).map { entry("new-$it") }
        feed.refreshNow()
        feed.validate()
        assertTrue(live.isVisible)
        assertEquals("3 new", live.text.toString())
        assertTrue("The chip floats inside the sheet", live.parent === feed && live.x >= 0f && live.x + live.width <= feed.width)
        live.press()
        assertFalse(live.isVisible)
        assertEquals(scroll.maxY, scroll.scrollY, 0.1f)
        f.activity = (1..200).map { entry("old-$it") }
        feed.refreshNow()
        assertTrue("Older history offers Earlier", earlier.isVisible)
    }

    @Test fun `atlas agent card lives in the turn slot and hosts the turn action only while a human may act`() = withAtlasSkin { f ->
        Mockito.`when`(f.session.canResume()).thenReturn(true)
        val next = com.unciv.ui.screens.worldscreen.status.NextTurnButton(f.screen)
        val status = com.unciv.ui.screens.worldscreen.status.StatusButtons(next, f.screen)
        try {
            status.update(false)
            val card = status.agentMatchControlTable!!
            assertSame(status, card.parent)
            assertNull("Spectators and agent turns get no turn button", next.parent)
            val buttons = card.descendants().filterIsInstance<TextButton>().map { it.text.toString() }.toList()
            assertEquals(listOf("Resume", "Activity", "Messages", "Settings"), buttons)
            val resume = card.descendants().filterIsInstance<TextButton>().first()
            assertEquals(GameUiStyle.accent, (resume.style.up as com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable).patch.color)
            assertTrue("The HUD measures an honest turn slot", status.prefHeight >= card.height - .5f && status.prefWidth >= card.width - .5f)
            assertTrue(card.descendants().filterIsInstance<Label>().any { it.text.toString() == "Thinking · Rome" })
            Mockito.`when`(f.session.canResume()).thenReturn(false)
            Mockito.`when`(f.session.isHumanInputAllowed()).thenReturn(true)
            status.update(false)
            assertTrue("A human turn puts the turn button in the card", next.isDescendantOf(card))
            assertFalse(card.descendants().filterIsInstance<TextButton>().any { it.text.toString() in setOf("Control", "Resume") })
        } finally { status.dispose() }
    }

    @Test fun `mouse wheel scrolls by a small fixed step rather than a fraction of the history`() {
        val f = Fixture()
        f.activity = (1..40).map { entry("$it") }
        val feed = f.feed()
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        val before = scroll.scrollY
        scroll.fire(InputEvent().apply { type = InputEvent.Type.scrolled; scrollAmountY = -1f })
        assertEquals(24f, before - scroll.scrollY, 0.1f)
        val afterWheel = scroll.scrollY
        feed.act(0.1f)
        assertEquals("Manual scrolling suspends Live", afterWheel, scroll.scrollY, 0.1f)
    }

    @Test fun `the paragraph being read stays in place when earlier streaming text grows`() {
        val f = Fixture()
        f.activity = (1..40).map { entry("$it") }
        val feed = f.feed()
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        val paragraph = feed.descendants().filterIsInstance<Label>().single { it.text.toString().startsWith("Message 25.") }
        fun top(label: Label) = label.parent.height - label.y - label.height
        scroll.captureListeners.filterIsInstance<InputListener>().forEach { it.scrolled(InputEvent(), 0f, 0f, 0f, -1f) }
        scroll.scrollY = top(paragraph) + 3f
        Mockito.`when`(f.session.getActivityDetails("1")).thenReturn("An earlier streaming paragraph grows. ".repeat(150))
        f.activity = f.activity.map { if (it.id == "1") it.copy(timestamp = "updated") else it }
        feed.refreshNow()
        val after = feed.descendants().filterIsInstance<Label>().single { it.text.toString().startsWith("Message 25.") }
        assertEquals(3f, scroll.scrollY - top(after), 0.1f)
    }

    @Test fun `an expanded tool group keeps its details open when another call arrives`() {
        val f = Fixture()
        f.activity = (1..10).map { entry("call$it", "tool").copy(title = "Tool call $it") }
        val feed = f.feed()
        fun label(title: String) = feed.descendants().filterIsInstance<Label>().single { it.text.toString() == title }
        // Both the latest call and its count afford expanding the group.
        label("10 tool calls · Expand").press()
        (label("Tool call 1").parent as Button).press()
        f.activity = f.activity + entry("call11", "tool").copy(title = "Tool call 11")
        feed.refreshNow()
        assertNotNull(label("Tool call 1"))
        assertNotNull(label("Tool call 11"))
        assertTrue(feed.descendants().filterIsInstance<Label>().any { it.text.toString().startsWith("Message call1.") })
    }

    @Test fun `feed defaults to the active agent nation and follows a live turn change`() {
        val f = Fixture()
        f.activity = (1..30).map { entry("rome$it") } + (1..30).map { entry("korea$it").copy(civId = "Korea") }
        val feed = f.feed()
        assertEquals(30, feed.messageIds().size)
        assertTrue(feed.messageIds().all { it.startsWith("Message rome") })
        val picker = feed.descendants().filterIsInstance<SelectBox<*>>().single()
        assertEquals("Follow active nation", picker.selected.toString())
        assertEquals(listOf("Follow active nation", "All nations", "1. Current · Rome · Atlas", "2. Eliminated · Korea · Nova"),
            picker.items.map { it.toString() })
        f.active = "Korea"
        feed.refreshNow()
        assertTrue(feed.messageIds().all { it.startsWith("Message korea") })
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        assertEquals(scroll.maxY, scroll.scrollY, 0.1f)
    }

    @Test fun `nation picker uses numbered engine order rather than alphabetical agent names`() {
        val f = Fixture()
        val china = f.game.addCiv(f.game.ruleset.nations["China"]!!)
        for (civ in listOf(f.rome, f.korea, china)) f.game.addUnit("Settler", civ, null)
        f.active = "Korea"
        val feed = f.feed()
        val picker = feed.descendants().filterIsInstance<SelectBox<*>>().single()
        assertEquals(listOf("Follow active nation", "All nations",
            "1. Previous in order · Rome · Atlas", "2. Current · Korea · Nova", "3. China · Nova"),
            picker.items.map { it.toString() })
    }

    @Test fun `previous nation wraps and skips eliminated nations without losing historical filters`() {
        val f = Fixture()
        val china = f.game.addCiv(f.game.ruleset.nations["China"]!!)
        for (civ in listOf(f.rome, f.korea, china)) f.game.addUnit("Settler", civ, null)
        f.korea.hasEverOwnedOriginalCapital = true // No remaining cities: excluded from rotation.
        Mockito.`when`(f.session.getAgentProfile("China")).thenReturn(null)
        f.activity = listOf(entry("old-china").copy(civId = "China"), entry("unknown").copy(civId = "Unknown"))
        val feed = f.feed()
        val picker = feed.descendants().filterIsInstance<SelectBox<*>>().single()
        assertEquals(listOf("Follow active nation", "All nations",
            "1. Current · Rome · Atlas", "2. Eliminated · Korea · Nova", "3. Previous in order · China"),
            picker.items.map { it.toString() })
        f.active = "China"
        feed.refreshNow()
        assertEquals(listOf("Follow active nation", "All nations",
            "1. Previous in order · Rome · Atlas", "2. Eliminated · Korea · Nova", "3. Current · China"),
            picker.items.map { it.toString() })
    }

    @Test fun `turn order label changes preserve fixed nation selection and reading position`() {
        val f = Fixture()
        for (civ in listOf(f.rome, f.korea)) f.game.addUnit("Settler", civ, null)
        f.activity = (1..40).map { entry("rome$it") }
        val feed = f.feed()
        @Suppress("UNCHECKED_CAST")
        val picker = feed.descendants().filterIsInstance<SelectBox<*>>().single() as SelectBox<Any>
        picker.selected = picker.items.first { it.toString() == "1. Current · Rome · Atlas" }
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        scroll.captureListeners.filterIsInstance<InputListener>().forEach { it.scrolled(InputEvent(), 0f, 0f, 0f, -1f) }
        scroll.scrollY = 20f
        f.active = "Korea"
        feed.refreshNow()
        assertEquals("1. Previous in order · Rome · Atlas", picker.selected.toString())
        assertEquals(20f, scroll.scrollY, 0.1f)
        assertTrue(feed.messageIds().all { it.startsWith("Message rome") })
        assertFalse(feed.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Live" }.isChecked)
    }

    @Test fun `reading holds the auto nation across turns and hidden activity leaves scroll and unread alone`() {
        val f = Fixture()
        f.activity = (1..40).map { entry("rome$it") } + (1..40).map { entry("korea$it").copy(civId = "Korea") }
        val feed = f.feed()
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        scroll.captureListeners.filterIsInstance<InputListener>().forEach { it.scrolled(InputEvent(), 0f, 0f, 0f, -1f) }
        scroll.scrollY = 20f
        f.active = "Korea"
        f.activity = f.activity + entry("korea-new").copy(civId = "Korea")
        feed.refreshNow()
        assertTrue(feed.messageIds().all { it.startsWith("Message rome") })
        assertEquals(20f, scroll.scrollY, 0.1f)
        assertTrue(feed.descendants().filterIsInstance<TextButton>().any { it.text.toString() == "Live" && !it.isChecked })
        f.activity = f.activity + entry("rome-new")
        feed.refreshNow()
        assertEquals(20f, scroll.scrollY, 0.1f)
        val live = feed.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "1 new" }
        live.press()
        assertTrue(feed.messageIds().all { it.startsWith("Message korea") })
        assertTrue(feed.messageIds().any { it.startsWith("Message korea-new.") })
        assertEquals(scroll.maxY, scroll.scrollY, 0.1f)
        assertEquals("Live", live.text.toString())
    }

    @Test fun `expanding tools freezes the auto nation until Live resumes it`() {
        val f = Fixture()
        f.activity = listOf(entry("call1", "tool"), entry("call2", "tool"), entry("korea").copy(civId = "Korea"))
        val feed = f.feed()
        feed.descendants().filterIsInstance<Label>().single { it.text.toString() == "2 tool calls · Expand" }.press()
        f.active = "Korea"
        feed.refreshNow()
        assertEquals(2, feed.descendants().filterIsInstance<Label>().count { it.text.toString() == "Move Warrior" })
        assertTrue(feed.messageIds().isEmpty())
        feed.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Live" }.press()
        assertTrue(feed.messageIds().single().startsWith("Message korea."))
        assertFalse(feed.descendants().filterIsInstance<Label>().any { it.text.toString() == "Move Warrior" })
    }

    @Test fun `All and fixed nation filters stay selected through turn changes and reopening`() {
        val f = Fixture()
        f.activity = listOf(entry("rome"), entry("korea").copy(civId = "Korea"))
        val feed = f.feed()
        feed.selectNation("All nations")
        assertEquals(2, feed.messageIds().size)
        f.active = "Korea"
        feed.refreshNow()
        assertEquals(2, feed.messageIds().size)
        feed.selectNation("Rome · Atlas")
        assertTrue(feed.messageIds().single().startsWith("Message rome."))
        feed.toggle()
        feed.toggle()
        feed.refreshNow()
        assertTrue(feed.descendants().filterIsInstance<SelectBox<*>>().single().selected.toString().endsWith(" · Rome · Atlas"))
        assertTrue(feed.messageIds().single().startsWith("Message rome."))
        assertTrue("A fixed feed must identify its selected nation while a different agent thinks", feed.descendants().filterIsInstance<Label>().any { it.text.toString().contains("Rome") && it.text.toString().contains("activity", ignoreCase = true) })
    }

    @Test fun `deliberately choosing another nation clears old unread and reading position`() {
        val f = Fixture()
        f.activity = (1..40).map { entry("rome$it") } + (1..40).map { entry("korea$it").copy(civId = "Korea") }
        val feed = f.feed()
        val scroll = feed.descendants().filterIsInstance<AutoScrollPane>().single()
        scroll.captureListeners.filterIsInstance<InputListener>().forEach { it.scrolled(InputEvent(), 0f, 0f, 0f, -1f) }
        scroll.scrollY = 20f
        f.activity = f.activity + entry("rome-new")
        feed.refreshNow()
        assertTrue(feed.descendants().filterIsInstance<TextButton>().any { it.text.toString() == "1 new" })
        feed.selectNation("Korea · Nova")
        assertTrue(feed.messageIds().all { it.startsWith("Message korea") })
        assertTrue(feed.descendants().filterIsInstance<TextButton>().any { it.text.toString() == "Live" && it.isChecked })
        assertEquals(scroll.maxY, scroll.scrollY, 0.1f)
    }

    @Test fun `auto retains the last agent nation during human and null turn states`() {
        val f = Fixture()
        f.active = "Korea"
        f.activity = listOf(entry("rome"), entry("korea").copy(civId = "Korea"))
        val feed = f.feed()
        f.active = null
        feed.refreshNow()
        assertTrue(feed.messageIds().single().startsWith("Message korea."))
        Mockito.`when`(f.session.getAgentProfile("Rome")).thenReturn(null)
        f.active = "Rome"
        Mockito.`when`(f.session.getRunState()).thenReturn(AgentRunState.WAITING_HUMAN)
        feed.refreshNow()
        assertTrue(feed.messageIds().single().startsWith("Message korea."))
    }

    @Test fun `human feed offers only its own nation and hides another active agent identity`() {
        val f = Fixture(spectator = false)
        f.active = "Korea"
        f.activity = listOf(entry("own")) // Host supplies only the viewer's private activity.
        val feed = f.feed()
        val picker = feed.descendants().filterIsInstance<SelectBox<*>>().single()
        assertEquals(listOf("Follow active nation", "All nations", "1. Eliminated · Rome · Atlas"), picker.items.map { it.toString() })
        assertTrue(feed.messageIds().single().startsWith("Message own."))
        feed.selectNation("All nations")
        assertTrue(feed.messageIds().single().startsWith("Message own."))
        assertFalse("Neither the picker nor its state should reveal the other agent", feed.descendants().filterIsInstance<Label>().any {
            it.text.toString().contains("Korea") || it.text.toString().contains("Nova")
        })
    }

    @Test fun `disabled HUD button retains its style and rejects activation`() {
        Fixture()
        var calls = 0
        val button = WorldScreenStyle.button("Pause")
        button.onClick { calls++ }
        val style = button.style
        WorldScreenStyle.setEnabled(button, false)
        button.press()
        assertEquals(0, calls)
        assertSame(style, button.style)
        WorldScreenStyle.setEnabled(button, true)
        button.press()
        assertEquals(1, calls)
    }

    @Test fun `floating overview shows all spectator nations and keeps the map usable outside its bounds`() {
        val f = Fixture()
        val panel = f.overview()
        val labels = panel.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
        assertTrue("Rome" in labels)
        assertTrue("Korea" in labels)
        assertFalse(Constants.spectator in labels)
        assertNull(panel.hit(-1f, panel.height / 2, true))
        assertNotNull(panel.hit(panel.width / 2, panel.height / 2, true))
        assertSame(f.selected, f.screen.selectedCiv)
        panel.toggle()
        assertFalse(panel.isVisible)
        panel.toggle()
        assertTrue(panel.isVisible)
    }

    @Test fun `overview sorting search and live updates keep the selected civilization`() {
        val f = Fixture()
        val panel = f.overview()
        fun labels() = panel.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
        val buttons = panel.descendants().filterIsInstance<TextButton>().toList()
        val gold = buttons.singleOrNull { it.text.toString().endsWith("Gold") }
        assertNotNull("Gold heading missing: ${buttons.map { it.text.toString() }}", gold)
        gold!!.press()
        val names = labels().filter { it == "Rome" || it == "Korea" }
        assertEquals(listOf("Korea", "Rome"), names.take(2))
        val romeRow = panel.descendants().filterIsInstance<Label>().first { it.text.toString() == "Rome" }.parent.parent.parent as Button
        romeRow.press()
        f.rome.addGold(1000)
        f.active = "Korea"
        panel.refresh()
        assertTrue("Selected Rome's latest gold should appear", labels().any { it.startsWith("${1123.tr()} (") })
        assertTrue(labels().any { "Korea · Thinking" in it })
        Mockito.`when`(f.session.getRunState()).thenReturn(AgentRunState.PAUSED)
        panel.refresh()
        assertTrue(labels().any { "Korea · Paused" in it })
        val search = panel.descendants().filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.TextField>().single()
        search.setProgrammaticChangeEvents(true)
        search.text = "Nova"
        assertFalse("Rome" in labels())
        assertTrue("Korea" in labels())
        search.text = "not found"
        assertTrue(labels().any { "No civilizations match" in it })
    }

    @Test fun `overview stays inside the stage after dragging and narrow resizes`() {
        val f = Fixture()
        val panel = f.overview()
        val header = panel.children.first() as com.badlogic.gdx.scenes.scene2d.ui.Table
        val drag = header.listeners.filterIsInstance<InputListener>().single()
        assertNotNull("The header must receive pointer hits for dragging", header.hit(2f, header.height / 2, true))
        val startX = panel.x
        val event = InputEvent().apply { target = header; stageX = startX; stageY = panel.y }
        assertTrue(drag.touchDown(event, 2f, 2f, 0, 0))
        event.stageX -= 30f
        drag.touchDragged(event, 2f, 2f, 0)
        assertEquals(startX - 30f, panel.x, 0.1f)
        for ((width, height) in listOf(960f to 600f, 520f to 480f, 360f to 380f, 960f to 600f)) {
            f.width = width; f.height = height
            panel.setPosition(-100f, 2000f)
            panel.refresh()
            assertTrue(panel.x >= 0f && panel.y >= 0f)
            assertTrue(panel.x + panel.width <= width)
            assertTrue(panel.y + panel.height <= height)
            val close = panel.descendants().filterIsInstance<Button>().first { it !is TextButton }
            val point = close.localToAscendantCoordinates(panel, Vector2())
            assertTrue(point.x + close.width <= panel.width)
            for (button in panel.descendants().filterIsInstance<TextButton>()) {
                if (button.text.toString().contains("Score") || button.text.toString().contains("Science"))
                    assertTrue("Comparison heading must fit its column", button.label.prefWidth <= button.width)
            }
        }
    }

    @Test fun `human overview excludes unmet nations and never returns private opponent details`() {
        val f = Fixture(spectator = false)
        var data = CivilizationOverviewData.read(f.game.gameInfo, f.rome, null)
        assertEquals(listOf("Rome"), data.civilizations.map { it.id })
        f.rome.diplomacyFunctions.makeCivilizationsMeet(f.korea)
        f.game.gameInfo.gameParameters.hideOtherCivilizationStats = false
        data = CivilizationOverviewData.read(f.game.gameInfo, f.rome, f.session)
        val foreign = data.civilizations.single { it.id == "Korea" }
        assertNotNull(foreign.score)
        assertNull(foreign.gold); assertNull(foreign.income); assertNull(foreign.science)
        assertNull(foreign.research); assertNull(foreign.cityDetails); assertNull(foreign.units); assertNull(foreign.resources)
        f.game.gameInfo.gameParameters.hideOtherCivilizationStats = true
        assertNull(CivilizationOverviewData.read(f.game.gameInfo, f.rome, null).civilizations.single { it.id == "Korea" }.score)
        assertEquals(123, CivilizationOverviewData.read(f.game.gameInfo, f.rome, null).civilizations.single { it.id == "Rome" }.gold)
    }

    @Test fun `overview includes city states through filters and copies data from the current snapshot`() {
        val f = Fixture()
        f.game.addCiv(f.game.ruleset.nations["Geneva"]!!)
        val initial = CivilizationOverviewData.read(f.game.gameInfo, f.spectatorCiv, f.session)
        val panel = f.overview()
        fun labels() = panel.descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
        assertFalse("Geneva" in labels())
        panel.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "City-states" }.press()
        assertTrue("Geneva" in labels())
        assertFalse("Rome" in labels())
        panel.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "All" }.press()
        assertTrue("Rome" in labels() && "Geneva" in labels())
        f.rome.addGold(200)
        assertEquals("Rendered values are isolated from engine mutations", 123, initial.civilizations.single { it.id == "Rome" }.gold)
        assertEquals(323, CivilizationOverviewData.read(f.game.gameInfo, f.spectatorCiv, f.session).civilizations.single { it.id == "Rome" }.gold)
    }

    @Test fun `overview shows waiting starting units to spectators and keeps opponents pending grants private`() {
        val f = Fixture()
        f.rome.diplomacyFunctions.makeCivilizationsMeet(f.korea)
        f.rome.pendingStartingUnits["Nuclear Missile"] = 2
        f.korea.pendingStartingUnits["Bomber"] = 4
        val panel = f.overview()
        val names = panel.descendants().filterIsInstance<Label>().toList()
        val romeLabel = names.firstOrNull { it.text.toString() == "Rome" }
        assertNotNull("Overview labels: ${names.map { it.text.toString() }}", romeLabel)
        val romeRow = romeLabel!!.parent.parent.parent as Button
        romeRow.press()
        assertTrue(panel.descendants().filterIsInstance<Label>().any { "2 Nuclear Missile" in it.text.toString() })
        val own = CivilizationOverviewData.read(f.game.gameInfo, f.rome, f.session).civilizations.single { it.id == "Rome" }
        val foreign = CivilizationOverviewData.read(f.game.gameInfo, f.rome, f.session).civilizations.single { it.id == "Korea" }
        assertTrue(com.unciv.json.json().toJson(own).contains("Nuclear Missile"))
        assertFalse(com.unciv.json.json().toJson(foreign).contains("Bomber"))
    }
}
