package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.List as GdxList
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils
import com.badlogic.gdx.utils.Array as GdxArray
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.Timer
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentModelCatalog
import com.unciv.logic.agents.DiplomaticMessage
import com.unciv.logic.agents.MessageImageClipboard
import com.unciv.logic.agents.MessageImageDraft
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

/** Stable fallback keeps old saves readable when their acknowledgement revision was not retained. */
fun orderedDiplomaticMessages(messages: List<DiplomaticMessage>): List<DiplomaticMessage> =
    messages.sortedWith(compareBy<DiplomaticMessage> { it.acknowledgedTurnSerial == null }
        .thenBy { it.acknowledgedTurnSerial ?: Int.MAX_VALUE }
        .thenBy { it.acknowledgedRevision ?: Int.MIN_VALUE })

/** Modeless, viewer-scoped diplomatic mailbox. The session authenticates all sends. */
class AgentMessagesPanel(
    private val screen: WorldScreen,
    private val session: AgentMatchSession,
    private val imageClipboard: MessageImageClipboard? = Gdx.app.clipboard as? MessageImageClipboard
) : Table(BaseScreen.skin), Disposable {
    private data class NationOption(val id: String?, val label: String) {
        override fun toString() = label
    }
    private data class Mail(val id: String, val sender: String, val target: String, val text: String,
                            val sent: Int, val delivered: Int?, val origin: String,
                            val acknowledged: Int?, val acknowledgementText: String?, val acknowledgedRevision: Int?,
                            val images: List<Attachment>)
    private data class Attachment(val id: String, val mimeType: String, val width: Int, val height: Int, val bytes: Int)
    private val history = Table(skin).apply { top().left() }
    private val scroll = AutoScrollPane(history, skin)
    private val recipient = AgentNationSelectBox<NationOption>().apply { name = "MessageRecipient" }
    private val conversation = AgentNationSelectBox<NationOption>().apply { name = "MessageConversation" }
    private val body = TextArea("", skin).apply {
        name = "MessageBody"
        messageText = "Write a message for their next turn…"
        maxLength = DiplomaticMessageLimits.MAX_BODY_CHARS
        addCaptureListener(object : InputListener() {
            override fun keyDown(event: InputEvent, keycode: Int): Boolean {
                if (keycode != Input.Keys.V || !UIUtils.ctrl() || imageClipboard?.hasImageContents() != true) return false
                if (!isDisabled) pasteImage()
                event.cancel()
                return true
            }
        })
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
    private val paste = WorldScreenStyle.button("Paste image").apply { name = "MessagePasteImage"; label.setFontSize(11) }
    private val removeImage = WorldScreenStyle.button("Remove image").apply { name = "MessageRemoveImage"; label.setFontSize(11) }
    private val imagePreview = Table(skin)
    private val visionWarning = "".toLabel(WorldScreenStyle.warning, 11).apply { name = "MessageVisionWarning"; wrap = true }
    private var imageDraft: MessageImageDraft? = null
    private var draftTexture: Texture? = null
    private val historyTextures = ArrayList<Texture>()
    private var clipboardPending = false
    private var clipboardGeneration = 0
    private var historyGeneration = 0
    private data class ScrollAnchor(val position: Float, val atEnd: Boolean)
    private var pendingScrollAnchor: ScrollAnchor? = null
    private var imagesHidden = true
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
        footer.add(paste).right().padRight(8f)
        footer.add(send).right()
        add(imagePreview).growX().row()
        add(visionWarning).growX().left().row()
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
        recipient.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) { if (!updating) updateSendState() }
        })
        paste.onClick { pasteImage() }
        removeImage.onClick { if (!sending) {
            clipboardGeneration++; clipboardPending = false; imageDraft = null
            setStatus(""); rebuildDraftPreview(); updateSendState()
        } }
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
        if (shown) {
            imagesHidden = false
            screen.hideCivilizationOverview(); toFront()
            val priorHistory = historyGeneration
            refresh(); rebuildDraftPreview()
            if (historyGeneration == priorHistory) rebuildHistory()
        }
        else {
            pendingScrollAnchor = null
            imagesHidden = true; clipboardGeneration++
            if (clipboardPending) { clipboardPending = false; setStatus("") }
            releaseDraftTexture(); releaseHistoryTextures(); imagePreview.clearChildren()
            recipient.hideList(); conversation.hideList()
            if (screen.stage.scrollFocus?.isDescendantOf(this) == true) screen.stage.scrollFocus = null
            if (screen.stage.keyboardFocus?.isDescendantOf(this) == true) screen.stage.keyboardFocus = null
            pendingScrollAnchor = null
        }
        Gdx.graphics.requestRendering()
    }

    fun refresh() {
        if (disposed) return
        val id = screen.currentViewerCivId()
        if (viewerId != id) {
            pendingScrollAnchor = null
            viewerId = id; generation++; sending = false
            clipboardGeneration++; clipboardPending = false; imageDraft = null; releaseDraftTexture(); imagePreview.clearChildren()
            releaseHistoryTextures()
            body.text = ""; setStatus(""); options = emptyList(); mail = emptyList()
            history.clearChildren()
            recipient.setItems(GdxArray<NationOption>())
            visibleLimit = 100
            updating = true; conversation.setItems(GdxArray(arrayOf(allConversations))); updating = false
            pendingScrollAnchor = ScrollAnchor(0f, true)
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
        val w = minOf(480f, screen.stage.width - 16f).coerceAtLeast(1f)
        val h = minOf(650f, screen.stage.height - 130f).coerceAtLeast(1f)
        val resized = w != width || h != height
        if (resized) { rememberScrollPosition(); setSize(w, h) }
        getCell(body).height(if (h < 500f) 54f else 82f)
        val latest = orderedDiplomaticMessages(session.getMessages()).map { Mail(it.messageId, it.senderCivId, it.targetCivId, it.text,
            it.sentTurnSerial, it.deliveredTurnSerial, it.origin,
            it.acknowledgedTurnSerial, it.acknowledgementText, it.acknowledgedRevision,
            it.attachments.map { attachment -> Attachment(attachment.attachmentId, attachment.mimeType,
                attachment.width, attachment.height, attachment.byteLength) }) }
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
        } else if (history.children.size == 0 || resized) rebuildHistory()
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
        WorldScreenStyle.setEnabled(send, !sending && !clipboardPending && options.isNotEmpty() && (body.text.isNotBlank() || imageDraft != null))
        recipient.isDisabled = sending || options.isEmpty()
        body.isDisabled = sending || options.isEmpty()
        WorldScreenStyle.setEnabled(paste, !sending && options.isNotEmpty() && imageClipboard != null)
        WorldScreenStyle.setEnabled(removeImage, !sending)
        send.setText(if (sending) "Saving…" else "Send")
        val target = recipient.selected?.id
        val profile = target?.let { session.getAgentProfile(it) }
        val vision = profile?.let { AgentModelCatalog.findModel(it.provider, it.modelId).supportsImages }
        val warning = if (imageDraft == null || vision == true) "" else if (vision == false)
            "This model does not support images. You can still send this image."
            else "Image support is unknown for this model. You can still send this image."
        if (visionWarning.text.toString() != warning) { rememberScrollPosition(); visionWarning.setText(warning) }
        if (options.isEmpty() && !sending) {
            status.color = WorldScreenStyle.muted
            setStatus("No eligible agent nations to message yet.")
        }
    }

    private fun sendCurrentDraft() {
        if (send.isDisabled) return
        val target = recipient.selected?.id ?: return
        val draft = body.text
        val requestGeneration = generation
        sending = true; status.color = WorldScreenStyle.muted; setStatus("Saving your message…"); updateSendState()
        clipboardGeneration++
        val complete: (String?) -> Unit = complete@{ error ->
            if (disposed || requestGeneration != generation || screen.currentViewerCivId() != viewerId) return@complete
            sending = false
            if (error == null) {
                body.text = ""; imageDraft = null; rebuildDraftPreview()
                status.color = WorldScreenStyle.accent
                setStatus("Queued for $target’s next turn.")
                refresh()
            } else {
                status.color = WorldScreenStyle.error
                setStatus(error)
                updateSendState()
            }
        }
        val image = imageDraft
        if (image == null) session.sendMessage(target, draft, complete)
        else session.sendMessage(target, draft, image.copy(), complete)
    }

    private fun pasteImage() {
        val clipboard = imageClipboard ?: return
        if (sending || options.isEmpty() || disposed || imagesHidden) return
        val requestGeneration = ++clipboardGeneration
        val requestViewer = generation
        clipboardPending = true
        status.color = WorldScreenStyle.muted; setStatus("Preparing image…"); updateSendState()
        try { clipboard.readImage { draft, error ->
            if (disposed || imagesHidden || requestGeneration != clipboardGeneration || requestViewer != generation || screen.currentViewerCivId() != viewerId) return@readImage
            clipboardPending = false
            if (error != null || draft == null) {
                status.color = WorldScreenStyle.error
                setStatus(error ?: "The clipboard does not contain an image.")
            } else {
                // Keep the old image intact if decoding or texture upload fails.
                val ownedDraft = draft.copy()
                val texture = try { textureFor(ownedDraft) } catch (_: Exception) {
                    status.color = WorldScreenStyle.error
                    setStatus("The clipboard image preview could not be displayed.")
                    updateSendState(); invalidateHierarchy(); Gdx.graphics.requestRendering()
                    return@readImage
                }
                releaseDraftTexture(); imageDraft = ownedDraft; draftTexture = texture
                rebuildDraftPreview()
                setStatus("")
            }
            updateSendState(); invalidateHierarchy(); Gdx.graphics.requestRendering()
        } } catch (ex: Exception) {
            if (disposed || requestGeneration != clipboardGeneration || requestViewer != generation) return
            clipboardPending = false
            status.color = WorldScreenStyle.error
            setStatus(ex.message ?: "The clipboard image could not be read.")
            updateSendState(); invalidateHierarchy(); Gdx.graphics.requestRendering()
        }
    }

    private fun textureFor(draft: MessageImageDraft): Texture {
        val source = Pixmap(draft.bytes, 0, draft.bytes.size)
        var preview = source
        try {
            val scale = minOf(1f, 512f / maxOf(source.width, source.height))
            if (scale < 1f) {
                preview = Pixmap((source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1), Pixmap.Format.RGBA8888)
                preview.setFilter(Pixmap.Filter.BiLinear)
                preview.drawPixmap(source, 0, 0, source.width, source.height, 0, 0, preview.width, preview.height)
            }
            val texture = Texture(preview)
            try {
                texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                return texture
            } catch (ex: Exception) { texture.dispose(); throw ex }
        } finally {
            if (preview !== source) preview.dispose()
            source.dispose()
        }
    }

    private fun releaseDraftTexture() { draftTexture?.dispose(); draftTexture = null }
    private fun releaseHistoryTextures() {
        historyGeneration++
        historyTextures.forEach { it.dispose() }; historyTextures.clear()
    }

    private fun rebuildDraftPreview() {
        rememberScrollPosition()
        imagePreview.clearChildren()
        val draft = imageDraft
        if (draft == null || imagesHidden || disposed) { releaseDraftTexture(); return }
        if (draftTexture == null) {
            try { draftTexture = textureFor(draft) } catch (_: Exception) {
                status.color = WorldScreenStyle.error; setStatus("The image preview could not be displayed.")
                return
            }
        }
        imagePreview.add(Image(draftTexture).apply { name = "MessageImagePreview"; setScaling(Scaling.fit) })
            .size(96f, 64f).left().padTop(6f)
        imagePreview.add("${draft.width} × ${draft.height}".toLabel(WorldScreenStyle.muted, 11))
            .minWidth(0f).expandX().left().padLeft(8f)
        imagePreview.add(removeImage).right().padLeft(8f)
        invalidateHierarchy()
    }

    private fun addHistoryImage(message: Mail, attachment: Attachment, contentWidth: Float, preload: Boolean) {
        val container = Table(skin)
        val loading = "Loading image…".toLabel(WorldScreenStyle.muted, 11).apply { wrap = true }
        history.add(container).width(contentWidth).left().padTop(4f).padBottom(8f).row()
        if (imagesHidden || disposed) {
            container.add("Image · ${attachment.width} × ${attachment.height}".toLabel(WorldScreenStyle.muted, 11))
                .width(contentWidth).left()
            return
        }
        val requestHistory = historyGeneration
        val requestViewer = generation
        var requested = false
        fun requestPreview() {
            if (requested || disposed || imagesHidden || requestHistory != historyGeneration ||
                requestViewer != generation || screen.currentViewerCivId() != viewerId) return
            requested = true
            rememberScrollPosition()
            container.clearChildren(); container.add(loading).width(contentWidth).left()
            session.readMessageImage(message.id, attachment.id) { draft, error ->
                if (disposed || imagesHidden || requestHistory != historyGeneration || requestViewer != generation ||
                    screen.currentViewerCivId() != viewerId) return@readMessageImage
                rememberScrollPosition()
                if (error != null || draft == null) {
                    loading.setText(error ?: "This image is unavailable.")
                    loading.color = WorldScreenStyle.error
                } else {
                    val texture = try { textureFor(draft) } catch (_: Exception) {
                        loading.setText("This image preview could not be displayed."); loading.color = WorldScreenStyle.error
                        return@readMessageImage
                    }
                    historyTextures.add(texture)
                    val image = Image(texture).apply { name = "MessageHistoryImage"; setScaling(Scaling.fit) }
                    container.clearChildren()
                    val aspect = draft.height.toFloat() / draft.width.coerceAtLeast(1)
                    val smallHeight = minOf(100f, contentWidth * aspect)
                    val cell = container.add(image).width(contentWidth).height(smallHeight).left()
                    var expanded = false
                    image.onClick {
                        rememberScrollPosition()
                        expanded = !expanded
                        cell.height(if (expanded) minOf(360f, contentWidth * aspect) else smallHeight)
                        history.invalidateHierarchy(); Gdx.graphics.requestRendering()
                    }
                }
                history.invalidateHierarchy(); scroll.invalidate(); Gdx.graphics.requestRendering()
            }
        }
        if (preload) requestPreview()
        else {
            val view = WorldScreenStyle.button("View image").apply { name = "MessageViewImage"; label.setFontSize(11) }
            view.onClick { requestPreview() }
            container.add(view).left()
        }
    }

    private fun rebuildHistory(jump: Boolean = false) {
        rememberScrollPosition(forceEnd = jump)
        releaseHistoryTextures()
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
        val shown = filtered.takeLast(visibleLimit)
        // Bound automatic byte reads and GPU uploads even after "Earlier messages" grows the history.
        val recentImages = shown.flatMap { message -> message.images.map { message.id to it.id } }.takeLast(4).toSet()
        for (message in shown) {
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
            for (attachment in message.images) addHistoryImage(message, attachment, contentWidth,
                (message.id to attachment.id) in recentImages)
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
        history.invalidateHierarchy(); scroll.invalidate()
    }

    private fun rememberScrollPosition(forceEnd: Boolean = false) {
        // Keep the position from the first mutation; later callbacks may see intermediate bounds.
        if (forceEnd || pendingScrollAnchor == null)
            pendingScrollAnchor = ScrollAnchor(scroll.scrollY, forceEnd || scroll.maxY - scroll.scrollY < 24f)
    }

    private fun setStatus(text: String) {
        if (status.text.toString() == text) return
        rememberScrollPosition()
        status.setText(text)
    }

    override fun layout() {
        super.layout()
        // Parent bounds and wrapped labels must settle before calculating the final scroll range.
        scroll.validate()
        val anchor = pendingScrollAnchor ?: return
        pendingScrollAnchor = null
        scroll.scrollY = if (anchor.atEnd) scroll.maxY else anchor.position.coerceAtMost(scroll.maxY)
        scroll.updateVisualScroll()
    }

    private fun clampPosition() {
        setPosition(x.coerceIn(8f, maxOf(8f, screen.stage.width - width - 8f)),
            y.coerceIn(8f, maxOf(8f, screen.stage.height - height - 8f)))
    }

    override fun dispose() {
        if (disposed) return
        pendingScrollAnchor = null
        disposed = true; generation++; clipboardGeneration++; clipboardPending = false
        releaseDraftTexture(); releaseHistoryTextures(); imageDraft = null
        imagePreview.clearChildren(); history.clearChildren()
        task.cancel(); recipient.hideList(); conversation.hideList()
        if (screen.stage.scrollFocus?.isDescendantOf(this) == true) screen.stage.scrollFocus = null
        if (screen.stage.keyboardFocus?.isDescendantOf(this) == true) screen.stage.keyboardFocus = null
        remove()
    }
}
