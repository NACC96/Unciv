package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.HdpiMode
import com.badlogic.gdx.graphics.glutils.HdpiUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.logic.agents.AgentActivityEntry
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.MessageImageDraft
import com.unciv.logic.agents.MessageImageClipboard
import com.unciv.logic.agents.DiplomaticImageAttachment
import com.unciv.logic.agents.DiplomaticMessage
import com.unciv.logic.agents.MessageContact
import com.unciv.testing.TestGame
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.status.AgentActivityFeed
import com.unciv.ui.screens.worldscreen.status.AgentMessagesPanel
import org.mockito.Mockito

/** Real widgets and GPU; deterministic session data, no providers, checkpoints or user saves. */
object AgentMessagesRenderCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide an output directory for the PNG captures" }
        val sizes = listOf(960 to 720, 420 to 600)
        val synchronous = java.lang.Boolean.getBoolean("unciv.renderCheck.synchronous")
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv messages render check")
            setWindowedMode(sizes[0].first, sizes[0].second)
            setInitialVisible(false)
            disableAudio(true)
        }
        var failure: Throwable? = null
        Lwjgl3Application(object : ApplicationAdapter() {
            lateinit var stage: UncivStage
            lateinit var panel: AgentMessagesPanel
            lateinit var feed: AgentActivityFeed
            lateinit var picker: SelectBox<*>
            lateinit var recipientPicker: SelectBox<*>
            var sizeIndex = 0
            var frames = 0
            var completed = false
            var captureFramebuffer: FrameBuffer? = null
            var captureState = 0
            val captureFilter get() = captureState == 5
            val captureRecipient get() = captureState == 4
            var collapsedReadingHeight = 0f
            val nations = listOf("America", "Arabia", "Germany", "China", "Russia", "The Ottomans", "Persia", "England", "France")
            val historyCallbacks = ArrayList<() -> Unit>()
            val profiles = nations.associateWith { AgentProfile.defaultFor(it) }.also {
                it["The Ottomans"]!!.displayName += " · A patient ruler of coastlines, trade routes and a very long imperial title"
            }

            override fun create() {
                try {
                    val fixture = TestGame()
                    fixture.makeHexagonalMap(5, Constants.grassland)
                    for (name in nations)
                        fixture.addCiv(fixture.ruleset.nations[name]!!)
                    val spectator = fixture.addCiv(fixture.ruleset.nations[Constants.spectator]!!)
                    Fonts.fontImplementation = FontDesktop()
                    ImageGetter.setNewRuleset(fixture.ruleset)
                    if (synchronous) HdpiUtils.setMode(HdpiMode.Pixels)
                    stage = UncivStage(ScreenViewport())
                    stage.viewport.update(sizes[0].first, sizes[0].second, true)
                    stage.addActor(Table().apply {
                        background = WorldScreenStyle.background("", Color.valueOf("314b4b"))
                        setFillParent(true)
                        add("Deterministic GPU fixture · no agents running".toLabel(WorldScreenStyle.muted, 12))
                            .expand().left().top().pad(16f)
                    })

                    // The facade supplies stage/viewer access; both displayed panels are production widgets.
                    val screen = Mockito.mock(WorldScreen::class.java)
                    Mockito.`when`(screen.stage).thenReturn(stage)
                    Mockito.`when`(screen.gameInfo).thenReturn(fixture.gameInfo)
                    Mockito.`when`(screen.viewingCiv).thenReturn(spectator)
                    Mockito.`when`(screen.currentViewerCivId()).thenReturn(spectator.civID)
                    val tech = Mockito.mock(TechPolicyDiplomacyButtons::class.java)
                    Mockito.`when`(tech.y).thenAnswer { stage.height - 80f }
                    val techGetter = screen.javaClass.methods.single {
                        it.name.substringBefore('$') == "getTechPolicyAndDiplomacy"
                    }
                    Mockito.`when`(techGetter.invoke(screen) as TechPolicyDiplomacyButtons?).thenReturn(tech)

                    val session = Mockito.mock(AgentMatchSession::class.java)
                    Mockito.`when`(screen.agentSession).thenReturn(session)
                    Mockito.`when`(session.getActiveCivId()).thenReturn("The Ottomans")
                    Mockito.`when`(session.getRunState()).thenReturn(AgentRunState.AGENT_DECIDING)
                    Mockito.`when`(session.getAgentProfile(Mockito.anyString())).thenAnswer {
                        profiles[it.getArgument<String>(0)]
                    }
                    Mockito.`when`(session.getMessageContacts()).thenReturn(nations.map {
                        MessageContact(it, profiles[it]!!.displayName, true)
                    })
                    Mockito.`when`(session.getMessages()).thenReturn(listOf(
                        DiplomaticMessage("nation-delivered", "America", "China",
                            "We propose peace along the river. Our scouts will stay on the western bank.", 30, 31,
                            acknowledgedTurnSerial = 31,
                            acknowledgementText = "Received. I will respect the river border and consider peaceful trade."),
                        DiplomaticMessage("reply-delivered", "China", "America",
                            "Agreed. I will keep my Warrior east of the river while we discuss trade.", 31, 32),
                        DiplomaticMessage("spectator-queued", spectator.civID, "China",
                            "Consider training another Scout.\nThis is spectator guidance, not a change to the game rules.",
                            32, null, "SPECTATOR"),
                        DiplomaticMessage("nation-queued", "America", "Arabia",
                            "Could we exchange spare luxuries next turn?", 32).apply {
                            attachments.add(DiplomaticImageAttachment("a".repeat(64), "image/png", 320, 180, 1000))
                        }
                    ))
                    val activity = listOf(
                        AgentActivityEntry("old-rome", "America", 30, "message", "", "", "2026-10-11T00:00:00Z"),
                        AgentActivityEntry("korea-plan", "China", 31, "message", "", "", "2026-10-11T00:00:01Z"),
                        AgentActivityEntry("korea-observe", "China", 31, "tool", "Read game state", "2 units · 1 city", "2026-10-11T00:00:02Z"),
                        AgentActivityEntry("korea-mail", "China", 31, "tool", "Sent message to America", "Queued for their next turn.", "2026-10-11T00:00:03Z"),
                        AgentActivityEntry("korea-think", "China", 31, "reasoning", "", "", "2026-10-11T00:00:04Z"),
                        AgentActivityEntry("ottomans-plan", "The Ottomans", 32, "message", "", "", "2026-10-11T00:00:05Z"),
                        AgentActivityEntry("ottomans-observe", "The Ottomans", 32, "tool", "Inspect coastal cities, available trade routes and every possible infrastructure improvement", "2 units · 1 city", "2026-10-11T00:00:06Z"),
                        AgentActivityEntry("ottomans-path", "The Ottomans", 32, "tool", "Evaluate alternate coastline routes to each possible location for the next city", "3 routes checked", "2026-10-11T00:00:06Z"),
                        AgentActivityEntry("ottomans-think", "The Ottomans", 32, "reasoning", "", "", "2026-10-11T00:00:07Z")
                    )
                    val details = mapOf(
                        "old-rome" to "I will explore west and leave China a diplomatic message.",
                        "korea-plan" to "America has offered a peaceful border. I will consider the proposal and continue exploring east.",
                        "korea-observe" to "Observed 2 units and 1 city from the committed game state.",
                        "korea-mail" to "Private message to America queued successfully for America's next turn.",
                        "korea-think" to "A peaceful border lets me focus production on growth and science.",
                        "ottomans-plan" to "I will secure a coastal city, improve the nearby resources and send a diplomatic proposal before expanding inland.",
                        "ottomans-think" to "A strong navy protects trade routes while the capital invests in infrastructure. The long imperial name should wrap within this floating transcript."
                    )
                    Mockito.`when`(session.getActivity()).thenReturn(activity)
                    Mockito.`when`(session.getActivityDetails(Mockito.anyString())).thenAnswer {
                        details[it.getArgument<String>(0)].orEmpty()
                    }
                    // Synthetic in-memory source avoids the real clipboard and any external images.
                    val source = java.awt.image.BufferedImage(320, 180, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    val painter = source.createGraphics()
                    try {
                        painter.color = java.awt.Color(49, 105, 151); painter.fillRect(0, 0, 320, 180)
                        painter.color = java.awt.Color(126, 173, 102); painter.fillOval(15, 15, 200, 150)
                        painter.color = java.awt.Color.WHITE; painter.drawString("Proposed coastal border", 90, 100)
                    } finally { painter.dispose() }
                    val draft = com.unciv.app.desktop.MessageImageNormalizer.normalize(source)
                    Mockito.doAnswer {
                        val complete = it.getArgument<(MessageImageDraft?, String?) -> Unit>(2)
                        historyCallbacks.add { complete(draft.copy(), null) }
                        null
                    }.`when`(session).readMessageImage(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any<(MessageImageDraft?, String?) -> Unit>() ?: { _, _ -> })
                    // Unknown metadata exercises a persistent, warning-only recipient capability state.
                    profiles["The Ottomans"]!!.modelId = "render-fixture-unknown-vision"
                    val clipboard = object : MessageImageClipboard {
                        override fun hasImageContents() = true
                        override fun readImage(onComplete: (MessageImageDraft?, String?) -> Unit) { onComplete(draft.copy(), null) }
                    }
                    feed = AgentActivityFeed(screen, session)
                    panel = AgentMessagesPanel(screen, session, clipboard)
                    panel.toggle()
                    historyCallbacks.forEach { it() }; historyCallbacks.clear()
                    picker = feed.descendants().filterIsInstance<SelectBox<*>>().single()
                    recipientPicker = panel.descendants().filterIsInstance<SelectBox<*>>().single { it.name == "MessageRecipient" }
                    selectOttomans(picker)
                    selectOttomans(recipientPicker)
                    val calls = feed.descendants().filterIsInstance<Label>().single { it.text.toString() == "2 tool calls · Expand" }
                    calls.listeners.filterIsInstance<ActivationListener>().single().tap(
                        InputEvent().apply { listenerActor = calls }, 1f, 1f, 1, 0)
                    Gdx.files.absolute(args[0]).mkdirs()
                    // Asset setup can leave a benign GL enum error; each actual draw is checked below.
                    var setupError = Gdx.gl.glGetError()
                    while (setupError != GL20.GL_NO_ERROR) {
                        println("Setup GL error: $setupError"); setupError = Gdx.gl.glGetError()
                    }
                    if (synchronous) {
                        // Hidden macOS windows can stall in native event polling. Use the current
                        // context and logical-size FBOs for every state, including dropdown fades.
                        while (!completed && failure == null) render()
                        failure?.let { throw it }
                        dispose()
                        kotlin.system.exitProcess(0)
                    }
                } catch (ex: Throwable) {
                    failure = ex
                    if (synchronous) {
                        ex.printStackTrace()
                        dispose()
                        kotlin.system.exitProcess(1)
                    }
                    Gdx.app.exit()
                }
            }

            override fun render() {
                if (failure != null || completed) return
                try {
                    val (width, height) = sizes[sizeIndex]
                    if (synchronous && (captureFramebuffer?.width != width || captureFramebuffer?.height != height)) {
                        captureFramebuffer?.dispose()
                        captureFramebuffer = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
                    }
                    val target = captureFramebuffer
                    target?.begin()
                    try {
                        if (target == null) stage.viewport.apply()
                        drawAndCapture(width, height)
                    } finally { target?.end() }
                } catch (ex: Throwable) {
                    failure = ex
                    if (!synchronous) Gdx.app.exit()
                }
            }

            private fun drawAndCapture(width: Int, height: Int) {
                Gdx.gl.glClearColor(0.1f, 0.16f, 0.17f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                stage.root.act(1 / 60f)
                historyCallbacks.toList().forEach { it() }; historyCallbacks.clear()
                stage.camera.update()
                stage.batch.projectionMatrix = stage.camera.combined
                stage.batch.begin()
                try { stage.root.draw(stage.batch, 1f) } finally { stage.batch.end() }
                check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Messages/feed rendering leaked a GL error" }
                // Let SelectBox's show/hide fades settle before evaluating the final UI.
                if (++frames < 30) return
                if (synchronous) check(stage.width == width.toFloat() && stage.height == height.toFloat()) {
                    "Logical viewport resize was not applied"
                } else check(Gdx.graphics.width == width && Gdx.graphics.height == height) { "Window resize was not applied" }
                checkBounds(panel, "Messages panel")
                checkBounds(feed, "Activity feed")
                feed.validate()
                for (control in feed.descendants().filter { it !== feed }) {
                    val point = control.localToAscendantCoordinates(feed, Vector2())
                    check(point.x >= -0.5f && point.x + control.width <= feed.width + 0.5f) {
                        "Activity ${control.javaClass.simpleName} overflows glass at ${width}x$height: x=${point.x} width=${control.width} panel=${feed.width}"
                    }
                    if (control.rotation != 0f) for (corner in listOf(Vector2(), Vector2(control.width, 0f),
                        Vector2(0f, control.height), Vector2(control.width, control.height))) {
                        val painted = control.localToParentCoordinates(corner)
                        check(painted.x >= -0.5f && painted.y >= -0.5f &&
                            painted.x <= control.parent.width + 0.5f && painted.y <= control.parent.height + 0.5f) {
                            "Rotated activity arrow paints outside its parent at ${width}x$height: $painted in ${control.parent.width}x${control.parent.height}"
                        }
                    }
                }
                check(picker.items.size == 11) { "Activity picker must include all nine nations" }
                if (captureFilter || captureRecipient) {
                    val popup = (if (captureFilter) picker else recipientPicker).scrollPane
                    check(popup.x >= -0.5f && popup.x + popup.width <= stage.width + 0.5f) {
                        "${if (captureFilter) "Nation" else "Recipient"} dropdown extends past the viewport at ${width}x$height: x=${popup.x} width=${popup.width} stage=${stage.width}"
                    }
                }
                panel.validate()
                val composerOpen = captureState in listOf(1, 3, 4)
                if (panel.isVisible) {
                    val names = mutableListOf("MessageConversation", "MessageReadingPane", "MessageComposerToggle")
                    if (composerOpen) names += listOf("MessageComposerPane", "MessageFooter", "MessageSend", "MessagePasteImage")
                    for (name in names) {
                        val control = panel.descendants().single { it.name == name }
                        val point = control.localToStageCoordinates(Vector2())
                        check(point.x >= panel.x - 0.5f && point.x + control.width <= panel.x + panel.width + 0.5f &&
                            point.y >= panel.y - 0.5f && point.y + control.height <= panel.y + panel.height + 0.5f) {
                            "$name falls outside the panel at ${width}x$height"
                        }
                    }
                    val reading = panel.descendants().single { it.name == "MessageReadingPane" }
                    val composer = panel.descendants().single { it.name == "MessageComposerPane" }
                    if (captureState == 0) collapsedReadingHeight = reading.height
                    if (composerOpen) check(reading.height < collapsedReadingHeight - 60f) {
                        "Composer visibility did not change the real reading viewport"
                    } else check(composer.height == 0f && !composer.isVisible) { "Collapsed composer still occupies space" }
                    check(panel.descendants().any { it.name == "MessageHistoryImage" }) { "History attachment must render through a session preview" }
                    if (captureState > 0) {
                        check(panel.descendants().filterIsInstance<Label>().any { it.text.toString().contains("Image support is unknown") }) {
                            "Unknown vision warning must remain in the retained attached draft"
                        }
                        check(panel.descendants().filterIsInstance<Label>().any { it.name == "MessageDraftIndicator" && it.text.toString() == "Draft" })
                    }
                }
                val pixels = Pixmap.createFromFrameBuffer(0, 0,
                    if (synchronous) width else Gdx.graphics.backBufferWidth,
                    if (synchronous) height else Gdx.graphics.backBufferHeight)
                val kind = listOf("messages-collapsed", "messages-expanded", "messages-collapsed-draft", "messages-reopened", "message-recipient", "activity-filter")[captureState]
                try {
                    check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Messages capture readback leaked a GL error" }
                    PixmapIO.writePNG(Gdx.files.absolute("${args[0]}/$kind-$width.png"), pixels, -1, true)
                } finally { pixels.dispose() }
                frames = 0
                when (captureState++) {
                    0 -> {
                        tap("MessageComposerToggle")
                        panel.descendants().filterIsInstance<TextArea>().single().apply {
                            text = "Please consider a coastal city.\nKeep a defensive unit near the capital."
                            fire(ChangeListener.ChangeEvent())
                        }
                        tap("MessagePasteImage")
                    }
                    1 -> tap("MessageComposerToggle")
                    2 -> tap("MessageComposerToggle")
                    3 -> recipientPicker.showList()
                    4 -> { recipientPicker.hideList(); panel.hide(); picker.showList() }
                    else -> {
                        picker.hideList()
                        if (++sizeIndex == sizes.size) {
                            println("MESSAGES_RENDER_CHECK_PASSED: real GPU; collapsed, expanded, collapsed draft and reopened composer; recovered reading height; retained image and warning; activity and recipient dropdown bounds")
                            completed = true
                            if (!synchronous) Gdx.app.exit()
                        } else {
                            panel.toggle()
                            tap("MessageRemoveImage")
                            panel.descendants().filterIsInstance<TextArea>().single().apply { text = ""; fire(ChangeListener.ChangeEvent()) }
                            tap("MessageComposerToggle")
                            captureState = 0
                            if (synchronous) resizeLogical(sizes[sizeIndex].first, sizes[sizeIndex].second)
                            else Gdx.graphics.setWindowedMode(sizes[sizeIndex].first, sizes[sizeIndex].second)
                        }
                    }
                }
            }

            private fun tap(name: String) {
                val control = panel.descendants().single { it.name == name }
                control.listeners.filterIsInstance<ActivationListener>().single().tap(
                    InputEvent().apply { listenerActor = control }, 1f, 1f, 1, 0)
            }

            private fun checkBounds(actor: Actor, description: String) {
                check(actor.width > 0f && actor.height > 0f && actor.x >= 0f && actor.y >= 0f &&
                    actor.x + actor.width <= stage.width + 0.5f && actor.y + actor.height <= stage.height + 0.5f) {
                    "$description falls outside the ${stage.width}x${stage.height} viewport"
                }
            }

            private fun selectOttomans(box: SelectBox<*>) {
                @Suppress("UNCHECKED_CAST")
                val selection = box as SelectBox<Any>
                selection.selected = selection.items.single { it.toString().contains("The Ottomans · ") }
            }

            private fun resizeLogical(width: Int, height: Int) {
                stage.viewport.update(width, height, true)
                AgentActivityFeed::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(feed)
                panel.refresh()
            }

            override fun resize(width: Int, height: Int) {
                if (synchronous || !::stage.isInitialized || !::panel.isInitialized) return
                resizeLogical(width, height)
            }

            override fun dispose() {
                captureFramebuffer?.dispose()
                captureFramebuffer = null
                if (::panel.isInitialized) panel.dispose()
                if (::feed.isInitialized) feed.dispose()
                if (::stage.isInitialized) stage.dispose()
            }
        }, config)
        failure?.printStackTrace()
        kotlin.system.exitProcess(if (failure == null) 0 else 1)
    }

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }
}
