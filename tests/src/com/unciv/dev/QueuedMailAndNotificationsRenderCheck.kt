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
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.DiplomaticMessage
import com.unciv.logic.agents.MessageContact
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.NotificationsScroll
import com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.status.AgentMessagesPanel
import org.mockito.Mockito

/** Deleting your own queued mail and hiding the notification list, on a real GPU with synthetic data only. */
object QueuedMailAndNotificationsRenderCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide an output directory for the PNG captures" }
        var failure: Throwable? = null
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv queued mail and notifications render check")
            setWindowedMode(960, 720)
            setHdpiMode(HdpiMode.Pixels)
            setInitialVisible(false)
            disableAudio(true)
        }
        Lwjgl3Application(object : ApplicationAdapter() {
            override fun create() {
                try {
                    Gdx.files.absolute(args[0]).mkdirs()
                    capture(args[0])
                    println("QUEUED_MAIL_AND_NOTIFICATIONS_RENDER_CHECK_PASSED")
                    if (System.getProperty("unciv.renderCheck.synchronous") == "true") System.exit(0)
                    Gdx.app.exit()
                } catch (ex: Throwable) {
                    failure = ex
                    ex.printStackTrace()
                    if (System.getProperty("unciv.renderCheck.synchronous") == "true") System.exit(1)
                    Gdx.app.exit()
                }
            }
        }, config)
        failure?.let { kotlin.system.exitProcess(1) }
    }

    private fun capture(output: String) {
        val game = TestGame()
        game.makeHexagonalMap(4, Constants.grassland)
        val spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
        for (name in listOf("Russia", "England", "Germany")) game.addCiv(game.ruleset.nations[name]!!)
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        UncivGame.Current.settings.notificationScroll = NotificationsScroll.UserSetting.Visible.name

        val (width, height) = 960 to 720
        val stage = UncivStage(ScreenViewport()).apply { viewport.update(width, height, true) }
        val frame = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
        try {
            stage.addActor(Table().apply {
                background = WorldScreenStyle.background("", Color.valueOf("314b4b"))
                setFillParent(true)
            })
            val screen = Mockito.mock(WorldScreen::class.java)
            val session = Mockito.mock(AgentMatchSession::class.java)
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.viewingCiv).thenReturn(spectator)
            Mockito.`when`(screen.selectedCiv).thenReturn(spectator)
            Mockito.`when`(screen.currentViewerCivId()).thenReturn(spectator.civID)
            Mockito.`when`(screen.agentSession).thenReturn(session)
            val tech = Mockito.mock(TechPolicyDiplomacyButtons::class.java)
            Mockito.`when`(tech.y).thenAnswer { stage.height - 80f }
            val techGetter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getTechPolicyAndDiplomacy" }
            Mockito.`when`(techGetter.invoke(screen) as TechPolicyDiplomacyButtons?).thenReturn(tech)
            Mockito.`when`(session.getMessageContacts()).thenReturn(listOf("Russia", "England").map {
                MessageContact(it, AgentProfile.defaultFor(it).displayName, true)
            })
            Mockito.`when`(session.getMessages()).thenReturn(listOf(
                DiplomaticMessage("delivered", spectator.civID, "Russia", "Please keep the nonaggression pact.", 3, 4, "SPECTATOR"),
                DiplomaticMessage("foreign", "England", "Germany", "Our fleets will stay north of Dublin.", 5, null, "NATION"),
                DiplomaticMessage("queued", spectator.civID, "England", "Consider a university in London before more ships.", 5, null, "SPECTATOR"),
            ))

            val panel = AgentMessagesPanel(screen, session, null)
            panel.toggle()
            val deletes = panel.descendants().filterIsInstance<TextButton>().filter { it.name == "MessageDelete" }.toList()
            check(deletes.size == 1) { "Only the spectator's own queued message may offer Delete, found ${deletes.size}" }
            checkInside(deletes.single(), panel, "Delete")
            draw(stage, frame, width, height, "$output/queued-mail-delete.png")
            deletes.single().listeners.filterIsInstance<ActivationListener>().single()
                .tap(InputEvent().apply { listenerActor = deletes.single() }, 1f, 1f, 1, 0)
            val confirm = panel.descendants().filterIsInstance<TextButton>().single { it.name == "MessageDelete" }
            check(confirm.text.toString() == "Confirm delete") { "The first click must ask for confirmation" }
            checkInside(confirm, panel, "Confirm delete")
            draw(stage, frame, width, height, "$output/queued-mail-confirm.png")

            panel.toggle()
            val scroll = NotificationsScroll(screen)
            stage.addActor(scroll)
            val notifications = listOf(
                "Russia and The Ottomans have signed a Declaration of Friendship!",
                "Korea has declared war on Japan!",
                "Germany and England have signed a Declaration of Friendship!",
            ).map { Notification(it, arrayOf(), null, NotificationCategory.Diplomacy) }
            NotificationsScroll::class.java.methods.single { it.name.substringBefore('$') == "update" && it.parameterCount == 3 }
                .invoke(scroll, notifications, 80f, 160f)
            scroll.updateVisualScroll()
            scroll.validate()
            val hide = scroll.descendants().first { actor ->
                actor.listeners.any { it is ActivationListener } && actor.descendants().any { it is Label && it.text.toString() == "Hide" }
            }
            checkInside(hide, Actor().apply { setSize(stage.width, stage.height) }, "Hide")
            draw(stage, frame, width, height, "$output/notifications-hide.png")
        } finally {
            frame.dispose()
            stage.dispose()
        }
    }

    private fun checkInside(actor: Actor, container: Actor, label: String) {
        val bottomLeft = actor.localToStageCoordinates(Vector2())
        val topRight = actor.localToStageCoordinates(Vector2(actor.width, actor.height))
        val min = container.localToStageCoordinates(Vector2())
        val max = container.localToStageCoordinates(Vector2(container.width, container.height))
        check(bottomLeft.x >= min.x - 0.5f && bottomLeft.y >= min.y - 0.5f && topRight.x <= max.x + 0.5f && topRight.y <= max.y + 0.5f) {
            "$label ($bottomLeft..$topRight) must fit inside ($min..$max)"
        }
    }

    private fun draw(stage: UncivStage, frame: FrameBuffer, width: Int, height: Int, path: String) {
        stage.root.children.forEach { (it as? com.badlogic.gdx.scenes.scene2d.utils.Layout)?.validate() }
        while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
        frame.begin()
        try {
            Gdx.gl.glClearColor(0.07f, 0.1f, 0.11f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            stage.root.act(0f)
            stage.camera.update()
            stage.batch.projectionMatrix = stage.camera.combined
            stage.batch.begin()
            try { stage.root.draw(stage.batch, 1f) }
            finally { stage.batch.end() }
            check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Capture leaked a GL error" }
            val pixels = Pixmap.createFromFrameBuffer(0, 0, width, height)
            try { PixmapIO.writePNG(Gdx.files.absolute(path), pixels, -1, true) }
            finally { pixels.dispose() }
        } finally { frame.end() }
    }

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }
}
