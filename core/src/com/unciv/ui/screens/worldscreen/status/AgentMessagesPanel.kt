package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.List as GdxList
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Array as GdxArray
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Timer
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.DiplomaticMessageLimits
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/** Modeless, viewer-scoped diplomatic mailbox. The session authenticates all sends. */
class AgentMessagesPanel(private val screen: WorldScreen, private val session: AgentMatchSession) : Table(BaseScreen.skin), Disposable {
    private data class NationOption(val id: String?, val label: String) {
        override fun toString() = label
    }
    private data class Mail(val id: String, val sender: String, val target: String, val text: String,
                            val sent: Int, val delivered: Int?, val origin: String,
                            val acknowledged: Int?, val acknowledgementText: String?)
    private val history = Table(skin).apply { top().left() }
    private val scroll = AutoScrollPane(history, skin)
    private val recipient = AgentNationSelectBox<NationOption>().apply { name = "MessageRecipient" }
    private val conversation = AgentNationSelectBox<NationOption>().apply { name = "MessageConversation" }
    private val body = TextArea("", skin).apply {
        name = "MessageBody"
        messageText = "Write a message for their next turn…"
        maxLength = DiplomaticMessageLimits.MAX_BODY_CHARS
        addListener(object : InputListener() {
            override fun keyDown(event: InputEvent, keycode: Int): Boolean {
                // Let this editor handle typing, but keep its keys out of world shortcuts.
                // Key-up still bubbles so a map pan started before focus can stop normally.
                event.stop()
                return false
            }
        })
        style = TextField.TextFieldStyle(style).apply {
            background = WorldScreenStyle.background("WorldScreen/Messages/Composer", WorldScreenStyle.raised)
            focusedBackground = WorldScreenStyle.background("WorldScreen/Messages/ComposerFocused", WorldScreenStyle.pressed)
            fontColor = WorldScreenStyle.ink
            messageFontColor = WorldScreenStyle.muted
        }
    }
    private val send = WorldScreenStyle.button("Send", primary = true).apply { name = "MessageSend" }
    private val status = "".toLabel(WorldScreenStyle.muted, 11).apply { wrap = true }
    private val identity = "".toLabel(WorldScreenStyle.muted, 11).apply { wrap = true }
    private val count = "".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)
    private val allConversations = NationOption(null, "All conversations")
    private var mail = emptyList<Mail>()
    private var options = emptyList<NationOption>()
    private var updating = false
    private var sending = false
    private var disposed = false
    private var initializedPosition = false
    private var viewerId: String? = null
    private var generation = 0
    private var visibleLimit = 100
    private val task = object : Timer.Task() {
        override fun run() { if (disposed) cancel() else if (isVisible) refresh() }
    }

    init {
        name = "AgentMessagesPanel"
        background = WorldScreenStyle.panelBackground("WorldScreen/Messages")
        touchable = Touchable.enabled
        pad(14f)
        val close = Button(skin).apply {
            WorldScreenStyle.apply(this); pad(4f)
            add(ImageGetter.getImage("OtherIcons/Close")).size(12f)
            onClick { setShown(false) }
        }
        val header = Table(skin).apply { touchable = Touchable.enabled }
        header.add("Messages".toLabel(WorldScreenStyle.ink, 17)).expandX().left()
        header.add(WorldScreenStyle.dragGrip()).padRight(8f)
        header.add(close).size(26f)
        header.addListener(object : InputListener() {
            private val origin = Vector2()
            private val pointerOrigin = Vector2()
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (event.target.isDescendantOf(close)) return false
                origin.set(this@AgentMessagesPanel.x, this@AgentMessagesPanel.y)
                pointerOrigin.set(event.stageX, event.stageY)
                toFront()
                return true
            }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                setPosition(origin.x + event.stageX - pointerOrigin.x, origin.y + event.stageY - pointerOrigin.y)
                clampPosition(); Gdx.graphics.requestRendering()
            }
        })
        add(header).growX().row()
        add(identity).growX().left().padTop(4f).padBottom(10f).row()
        for (picker in listOf(recipient, conversation)) {
            picker.style = SelectBox.SelectBoxStyle(picker.style).apply {
                fontColor = WorldScreenStyle.ink
                background = WorldScreenStyle.background("WorldScreen/Messages/Select", WorldScreenStyle.raised)
                backgroundOver = WorldScreenStyle.background("WorldScreen/Messages/SelectOver", WorldScreenStyle.hover)
                backgroundOpen = backgroundOver
                listStyle = GdxList.ListStyle(listStyle).apply {
                    fontColorSelected = WorldScreenStyle.accent
                    fontColorUnselected = WorldScreenStyle.ink
                    background = WorldScreenStyle.background("WorldScreen/Messages/SelectList")
                    selection = WorldScreenStyle.background("WorldScreen/Messages/Selection", WorldScreenStyle.pressed)
                }
                scrollStyle = ScrollPane.ScrollPaneStyle(scrollStyle).apply { background = listStyle.background }
            }
            picker.setMaxListCount(7)
        }
        add(conversation).minWidth(0f).growX().height(30f).padBottom(8f).row()
        scroll.setScrollingDisabled(true, false)
        scroll.setFadeScrollBars(false)
        scroll.setOverscroll(false, false)
        add(scroll).grow().row()
        add("To".toLabel(WorldScreenStyle.muted, 11)).left().padTop(12f).row()
        add(recipient).minWidth(0f).growX().height(32f).padTop(4f).padBottom(6f).row()
        add(body).growX().height(82f).row()
        val footer = Table(skin)
        footer.add(count).left().expandX()
        footer.add(send).right()
        add(footer).growX().padTop(6f).row()
        add(status).growX().left().padTop(5f).row()
        conversation.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                if (!updating) { visibleLimit = 100; rebuildHistory(jump = true) }
            }
        })
        body.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) = updateSendState()
        })
        send.onClick { sendCurrentDraft() }
        screen.stage.addActor(this)
        isVisible = false
        refresh()
        Timer.schedule(task, 0.5f, 0.5f)
    }

    fun toggle() = setShown(!isVisible)
    fun hide() = setShown(false)
    private fun setShown(shown: Boolean) {
        isVisible = shown
        if (shown) { screen.hideCivilizationOverview(); toFront(); refresh() }
        else {
            recipient.hideList(); conversation.hideList()
            if (screen.stage.scrollFocus?.isDescendantOf(this) == true) screen.stage.scrollFocus = null
            if (screen.stage.keyboardFocus?.isDescendantOf(this) == true) screen.stage.keyboardFocus = null
        }
        Gdx.graphics.requestRendering()
    }

    fun refresh() {
        if (disposed) return
        val id = screen.currentViewerCivId()
        if (viewerId != id) {
            viewerId = id; generation++; sending = false
            body.text = ""; status.setText(""); options = emptyList(); mail = emptyList()
            history.clearChildren()
            recipient.setItems(GdxArray<NationOption>())
            visibleLimit = 100
            updating = true; conversation.setItems(GdxArray(arrayOf(allConversations))); updating = false
        }
        val spectator = screen.viewingCiv.isSpectator()
        identity.setText(if (spectator) "Spectator · all conversations · sends as Spectator"
            else "${screen.viewingCiv.civID} · private sent and received messages")
        val contacts = session.getMessageContacts().filter { it.isAgent }.map {
            NationOption(it.civId, if (it.displayName.isBlank() || it.displayName == it.civId) it.civId else "${it.civId} · ${it.displayName}")
        }
        if (contacts != options) {
            val selected = recipient.selected?.id
            options = contacts
            updating = true
            recipient.setItems(GdxArray(options.toTypedArray()))
            options.firstOrNull { it.id == selected }?.let { recipient.selected = it }
            updating = false
        }
        val latest = session.getMessages().map { Mail(it.messageId, it.senderCivId, it.targetCivId, it.text,
            it.sentTurnSerial, it.deliveredTurnSerial, it.origin,
            it.acknowledgedTurnSerial, it.acknowledgementText) }
        if (latest != mail) {
            mail = latest
            val prior = conversation.selected?.id
            val nations = (mail.flatMap { listOf(it.sender, it.target) } + options.mapNotNull { it.id })
                .distinct().sorted().map { NationOption(it, it) }
            updating = true
            conversation.setItems(GdxArray((listOf(allConversations) + nations).toTypedArray()))
            conversation.items.firstOrNull { it.id == prior }?.let { conversation.selected = it }
            updating = false
            rebuildHistory()
        } else if (history.children.size == 0) rebuildHistory()
        val w = minOf(480f, screen.stage.width - 16f).coerceAtLeast(1f)
        val h = minOf(650f, screen.stage.height - 130f).coerceAtLeast(1f)
        if (w != width || h != height) { setSize(w, h); rebuildHistory() }
        if (!initializedPosition) {
            setPosition(screen.stage.width - width - 8f, screen.stage.height - height - 116f)
            initializedPosition = true
        }
        clampPosition()
        updateSendState()
        invalidateHierarchy()
        Gdx.graphics.requestRendering()
    }

    private fun updateSendState() {
        count.setText("${body.text.length}/${DiplomaticMessageLimits.MAX_BODY_CHARS}")
        WorldScreenStyle.setEnabled(send, !sending && options.isNotEmpty() && body.text.isNotBlank())
        recipient.isDisabled = sending || options.isEmpty()
        body.isDisabled = sending || options.isEmpty()
        send.setText(if (sending) "Saving…" else "Send")
        if (options.isEmpty() && !sending) {
            status.color = WorldScreenStyle.muted
            status.setText("No eligible agent nations to message yet.")
        }
    }

    private fun sendCurrentDraft() {
        if (send.isDisabled) return
        val target = recipient.selected?.id ?: return
        val draft = body.text
        val requestGeneration = generation
        sending = true; status.color = WorldScreenStyle.muted; status.setText("Saving your message…"); updateSendState()
        session.sendMessage(target, draft) { error ->
            if (disposed || requestGeneration != generation) return@sendMessage
            sending = false
            if (error == null) {
                body.text = ""
                status.color = WorldScreenStyle.accent
                status.setText("Queued for $target’s next turn.")
                refresh()
            } else {
                status.color = WorldScreenStyle.error
                status.setText(error)
                updateSendState()
            }
        }
    }

    private fun rebuildHistory(jump: Boolean = false) {
        val oldPosition = scroll.scrollY
        val atEnd = scroll.maxY - oldPosition < 24f
        history.clearChildren()
        val selected = conversation.selected?.id
        val filtered = mail.filter { selected == null || it.sender == selected || it.target == selected }
        val contentWidth = (width - 40f).coerceAtLeast(80f)
        if (filtered.isEmpty()) history.add("No messages yet. Replies arrive on the recipient’s next turn."
            .toLabel(WorldScreenStyle.muted, 12).apply { wrap = true }).width(contentWidth).left().row()
        if (filtered.size > visibleLimit) {
            val earlier = WorldScreenStyle.button("Earlier messages").apply { label.setFontSize(11) }
            earlier.onClick { visibleLimit += 100; rebuildHistory() }
            history.add(earlier).left().padBottom(10f).row()
        }
        for (message in filtered.takeLast(visibleLimit)) {
            val sender = if (message.origin == "SPECTATOR") "Spectator" else message.sender
            val senderColor = AgentActivityAppearance.color(screen.gameInfo, message.sender)
            history.add("$sender → ${message.target}".toLabel(senderColor, 13, hideIcons = true).apply { wrap = true })
                .width(contentWidth).left().row()
            val state = message.acknowledged?.let { "Acknowledged · turn $it" }
                ?: message.delivered?.let { "Delivered · turn $it" } ?: "Queued · next turn"
            // Receipt colours read as progress; amber stays reserved for real problems.
            val stateColor = when {
                message.acknowledged != null -> WorldScreenStyle.positive
                message.delivered != null -> WorldScreenStyle.ink
                else -> WorldScreenStyle.muted
            }
            val receipt = Table(skin)
            receipt.add("Sent turn ${message.sent}".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)).left().expandX()
            receipt.add(state.toLabel(stateColor, 11)).right().padLeft(8f)
            history.add(receipt).width(contentWidth).left().padTop(3f).row()
            val text = Label(message.text, Label.LabelStyle(BaseScreen.skin.get(Label.LabelStyle::class.java))).apply {
                setFontSize(13); color = WorldScreenStyle.ink; wrap = true
            }
            history.add(text).width(contentWidth).left().padTop(5f)
                .padBottom(if (message.acknowledged == null) 16f else 8f).row()
            if (message.acknowledged != null) {
                history.add("${message.target} acknowledged receipt".toLabel(
                    AgentActivityAppearance.color(screen.gameInfo, message.target), 11, hideIcons = true).apply { wrap = true })
                    .width(contentWidth).left().row()
                val acknowledgement = Label(message.acknowledgementText.orEmpty(),
                    Label.LabelStyle(BaseScreen.skin.get(Label.LabelStyle::class.java))).apply {
                    setFontSize(12); color = WorldScreenStyle.ink; wrap = true
                }
                history.add(acknowledgement).width(contentWidth).left().padTop(3f).padBottom(16f).row()
            }
        }
        history.invalidateHierarchy(); scroll.invalidate(); scroll.layout()
        scroll.scrollY = if (jump || atEnd) scroll.maxY else oldPosition.coerceAtMost(scroll.maxY)
        scroll.updateVisualScroll()
    }

    private fun clampPosition() {
        setPosition(x.coerceIn(8f, maxOf(8f, screen.stage.width - width - 8f)),
            y.coerceIn(8f, maxOf(8f, screen.stage.height - height - 8f)))
    }

    override fun dispose() {
        disposed = true; task.cancel(); recipient.hideList(); conversation.hideList()
        remove()
    }
}
