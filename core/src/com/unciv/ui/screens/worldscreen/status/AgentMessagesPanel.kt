package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
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
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Array as GdxArray
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.Timer
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentModelCatalog
import com.unciv.logic.agents.DiplomaticMessage
import com.unciv.logic.agents.orderedDiplomaticMessages
import com.unciv.logic.agents.MessageImageClipboard
import com.unciv.logic.agents.MessageImageDraft
import com.unciv.logic.agents.DiplomaticMessageLimits
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.GameUiStyle
import com.unciv.UncivGame
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.ReadingScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.HudWindow
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/** Modeless, viewer-scoped diplomatic mailbox, read like a chat: your dispatches on the right,
 *  other senders on the left beside their portrait, each acknowledgement as the recipient's reply.
 *  The session authenticates all sends. */
class AgentMessagesPanel(
    private val screen: WorldScreen,
    private val session: AgentMatchSession,
    private val imageClipboard: MessageImageClipboard? = Gdx.app.clipboard as? MessageImageClipboard
) : Table(BaseScreen.skin), Disposable, AgentReadingText.Reader {
    private data class NationOption(val id: String?, val label: String) {
        override fun toString() = label
    }
    private data class Mail(val id: String, val sender: String, val target: String, val text: String,
                            val sent: Int, val delivered: Int?, val origin: String,
                            val acknowledged: Int?, val acknowledgementText: String?, val acknowledgedRevision: Int?,
                            val images: List<Attachment>)
    private data class Attachment(val id: String, val mimeType: String, val width: Int, val height: Int, val bytes: Int)
    private val history = Table(skin).apply { top().left() }
    private val scroll = ReadingScrollPane(history, skin).apply { name = "MessageReadingPane" }
    private val composer = Table(skin).apply { name = "MessageComposer"; top().left() }
    private val composerScroll = AutoScrollPane(composer, skin).apply { name = "MessageComposerPane" }
    private val footer = Table(skin).apply { name = "MessageFooter"; top().left() }
    private val composerToggle = WorldScreenStyle.quietButton("Write message", GameUiStyle.ButtonSize.Compact)
        .apply { name = "MessageComposerToggle" }
    private val draftIndicator = "".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption).apply { name = "MessageDraftIndicator" }
    private val composerControls = Table(skin)
    private var composerExpanded = false
    private val header = Table(skin).apply { touchable = Touchable.enabled }
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
    private val removeImage = (if (GameUiStyle.isAtlas) WorldScreenStyle.quietButton("Remove image", GameUiStyle.ButtonSize.Compact)
        else WorldScreenStyle.button("Remove image").apply { label.setFontSize(11) }).apply { name = "MessageRemoveImage" }
    private val imagePreview = Table(skin)
    private val visionWarning = "".toLabel(WorldScreenStyle.warning, 11).apply { name = "MessageVisionWarning"; wrap = true }
    private var imageDraft: MessageImageDraft? = null
    private var draftTexture: Texture? = null
    private data class HistoryTexture(val texture: Texture, val evict: () -> Unit)
    private val historyTextures = ArrayList<HistoryTexture>()
    private var clipboardPending = false
    private var clipboardGeneration = 0
    private var historyGeneration = 0
    private data class ScrollAnchor(val position: Float, val atEnd: Boolean, val id: String? = null, val offset: Float = 0f)
    private val historyAnchors = LinkedHashMap<String, Actor>()
    private var pendingScrollAnchor: ScrollAnchor? = null
    private var imagesHidden = true
    private val status = "".toLabel(WorldScreenStyle.muted, 11).apply { name = "MessageStatus"; wrap = true }
    private val statusPane = AutoScrollPane(status, skin).apply {
        name = "MessageStatusPane"
        setScrollingDisabled(true, false); setOverscroll(false, false); setFadeScrollBars(false)
    }
    private val identity = "".toLabel(WorldScreenStyle.muted, 11).apply { wrap = true }
    private val count = "".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)
    private val allConversations = NationOption(null, "All conversations")
    private var mail = emptyList<Mail>()
    private var options = emptyList<NationOption>()
    private var updating = false
    private var sending = false
    private var disposed = false
    private val window = HudWindow(this, "messages", "Messages",
        Vector2(320f, 360f * UncivGame.Current.settings.fontSizeMultiplier),
        defaultBounds = ::defaultBounds,
        show = { setShown(true) }, hide = { setShown(false) },
        // Message rows wrap to the content width, so rebuild them once at the final size.
        onResized = { rememberScrollPosition(); rebuildHistory() },
        menuExtras = { AgentReadingText.menuRow(this) })
    private val pickerHeight get() = if (GameUiStyle.isAtlas) 28f * UncivGame.Current.settings.fontSizeMultiplier else 30f
    private var viewerId: String? = null
    /** A queued message awaiting the second click that deletes it, and the one being deleted. */
    private var confirmDeleteId: String? = null
    private var deletingId: String? = null
    private var generation = 0
    private var visibleLimit = 100
    private var historyTailOffset = 0
    private val task = object : Timer.Task() {
        override fun run() { if (disposed) cancel() else if (isVisible) refresh() }
    }

    init {
        name = "AgentMessagesPanel"
        background = if (GameUiStyle.isAtlas) WorldScreenStyle.surface("WorldScreen/Messages", 0.94f)
            else WorldScreenStyle.panelBackground("WorldScreen/Messages")
        touchable = Touchable.enabled
        pad(if (GameUiStyle.isAtlas) GameUiStyle.Space.l else 14f)
        val close = AgentPanelLayout.closeButton { setShown(false) }
        header.add("Messages".toLabel(WorldScreenStyle.ink, if (GameUiStyle.isAtlas) GameUiStyle.Type.label + 1 else 17)).expandX().left()
        header.add(window.controls())
        header.add(close).size(if (GameUiStyle.isAtlas) 28f else 26f).padLeft(GameUiStyle.Space.xs)
        window.attachHeader(header)
        add(header).growX().row()
        add(identity).growX().left().padTop(2f).padBottom(8f).row()
        for (picker in listOf(recipient, conversation)) {
            picker.style = SelectBox.SelectBoxStyle(picker.style).apply {
                fontColor = WorldScreenStyle.ink
                background = if (GameUiStyle.isAtlas) WorldScreenStyle.pill("WorldScreen/Messages/Select", WorldScreenStyle.raised)
                    else WorldScreenStyle.background("WorldScreen/Messages/Select", WorldScreenStyle.raised)
                backgroundOver = if (GameUiStyle.isAtlas) WorldScreenStyle.pill("WorldScreen/Messages/SelectOver", WorldScreenStyle.hover)
                    else WorldScreenStyle.background("WorldScreen/Messages/SelectOver", WorldScreenStyle.hover)
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
            if (GameUiStyle.isAtlas) picker.fontSize = GameUiStyle.Type.label
        }
        add(conversation).minWidth(0f).growX().height(pickerHeight).padBottom(8f).row()
        scroll.setScrollingDisabled(true, false)
        scroll.setFadeScrollBars(false)
        scroll.setOverscroll(false, false)
        add(scroll).grow().row()
        composerControls.add(composerToggle).minWidth(0f).left()
        composerControls.add(draftIndicator).minWidth(0f).expandX().right().padLeft(8f)
        add(composerControls).growX().padTop(GameUiStyle.Space.xs).row()
        if (GameUiStyle.isAtlas) {
            // A draft card: recipient chip, the text, then its attachments and warnings.
            composer.background = WorldScreenStyle.control("WorldScreen/Messages/Draft",
                Color(WorldScreenStyle.inset).apply { a = 0.8f }, Color(WorldScreenStyle.ink).apply { a = 0.1f })
            composer.pad(8f)
            val to = Table()
            to.add("To".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)).padRight(GameUiStyle.Space.m)
            to.add(recipient).minWidth(0f).growX().height(pickerHeight)
            composer.add(to).minWidth(0f).growX().padBottom(6f).row()
            composer.add(body).growX().height(82f).row()
            composer.add(count).minWidth(0f).growX().right().padTop(4f).row()
            composer.add(imagePreview).growX().row()
            composer.add(visionWarning).growX().left().row()
            WorldScreenStyle.apply(paste, GameUiStyle.Emphasis.Quiet, GameUiStyle.ButtonSize.Compact)
            WorldScreenStyle.apply(send, GameUiStyle.Emphasis.Accent, GameUiStyle.ButtonSize.Compact)
            footer.add(paste).minWidth(0f).left()
            footer.add().growX()
            footer.add(send).minWidth(96f).right().row()
        } else {
            composer.add("To".toLabel(WorldScreenStyle.muted, 11)).left().row()
            composer.add(recipient).minWidth(0f).growX().height(32f).padTop(4f).padBottom(6f).row()
            composer.add(body).growX().height(82f).row()
            footer.add(count).left().expandX()
            footer.add(paste).right().padRight(8f)
            footer.add(send).right()
            composer.add(imagePreview).growX().row()
            composer.add(visionWarning).growX().left().row()
        }
        composerScroll.setScrollingDisabled(true, false)
        composerScroll.setFadeScrollBars(false); composerScroll.setOverscroll(false, false)
        add(composerScroll).minHeight(0f).growX().row()
        add(footer).minHeight(0f).growX().row()
        add(statusPane).minHeight(0f).growX().row()
        composerScroll.isVisible = false; footer.isVisible = false
        composerToggle.onClick { setComposerExpanded(!composerExpanded) }
        conversation.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                if (!updating) { visibleLimit = 100; historyTailOffset = 0; rebuildHistory(jump = true) }
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
    private fun setComposerExpanded(expanded: Boolean) {
        if (composerExpanded == expanded) return
        rememberScrollPosition()
        composerExpanded = expanded
        composerScroll.isVisible = expanded; footer.isVisible = expanded
        composerToggle.setText(if (expanded) "Hide message box" else "Write message")
        if (!expanded) {
            recipient.hideScrollPane()
            fun belongsToComposer(actor: Actor?) = actor?.let {
                it.isDescendantOf(composerScroll) || it.isDescendantOf(footer)
            } == true
            if (belongsToComposer(screen.stage.keyboardFocus)) screen.stage.keyboardFocus = null
            if (belongsToComposer(screen.stage.scrollFocus)) screen.stage.scrollFocus = null
        }
        invalidateHierarchy()
        Gdx.graphics.requestRendering()
    }
    private fun setShown(shown: Boolean) {
        isVisible = shown
        if (shown) {
            window.onShown()
            imagesHidden = false
            screen.hideCivilizationOverview()
            screen.stage.root.children.filterIsInstance<AgentNationSettingsPanel>().forEach { it.hide() }
            toFront()
            val priorHistory = historyGeneration
            refresh(); rebuildDraftPreview()
            if (historyGeneration == priorHistory) rebuildHistory()
        }
        else {
            window.onHidden()
            pendingScrollAnchor = null
            imagesHidden = true; clipboardGeneration++
            if (clipboardPending) { clipboardPending = false; setStatus("") }
            releaseDraftTexture(); releaseHistoryTextures(); imagePreview.clearChildren()
            recipient.hideScrollPane(); conversation.hideScrollPane()
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
            confirmDeleteId = null; deletingId = null
            clipboardGeneration++; clipboardPending = false; imageDraft = null; releaseDraftTexture(); imagePreview.clearChildren()
            releaseHistoryTextures()
            body.text = ""; setStatus(""); options = emptyList(); mail = emptyList()
            history.clearChildren(); historyAnchors.clear()
            recipient.setItems(GdxArray<NationOption>())
            visibleLimit = 100; historyTailOffset = 0
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
        val bounds = window.bounds()
        val resized = bounds.width != width || bounds.height != height
        if (resized && !window.isInteracting) rememberScrollPosition()
        window.place()
        composer.getCell(body).height(if (GameUiStyle.isAtlas || height < 500f) 54f else 82f)
        val latest = orderedDiplomaticMessages(session.getMessages()).filter { spectator || it.senderCivId == id || it.targetCivId == id }.map { Mail(it.messageId, it.senderCivId, it.targetCivId, it.text,
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
        } else if (history.children.size == 0 || resized && !window.isInteracting) rebuildHistory()
        updateSendState()
        invalidateHierarchy()
        Gdx.graphics.requestRendering()
    }

    /** Atlas: a tall, narrow reading sheet beside the right-hand HUD column. Elsewhere a card at the top right. */
    private fun defaultBounds(): Rectangle {
        val stage = screen.stage
        val maxW = (stage.width - 16f).coerceAtLeast(1f)
        val w = minOf(if (GameUiStyle.isAtlas) 420f else 480f, maxW)
        if (GameUiStyle.isAtlas) return AgentPanelLayout.tallSheet(stage, w)
        val h = minOf(650f, stage.height - 130f).coerceAtLeast(1f)
        return Rectangle((stage.width - w - 8f).coerceAtLeast(8f), (stage.height - h - 116f).coerceIn(8f, maxOf(8f, stage.height - h - 8f)), w, h)
    }

    override fun onReadingSizeChanged() { if (!disposed && isVisible) { rememberScrollPosition(); rebuildHistory() } }

    private fun updateSendState() {
        count.setText("${body.text.length}/${DiplomaticMessageLimits.MAX_BODY_CHARS}")
        draftIndicator.setText(if (body.text.isNotEmpty() || imageDraft != null) "Draft" else "")
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
        historyTextures.forEach { it.texture.dispose() }; historyTextures.clear()
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
        imagePreview.add("${draft.width} × ${draft.height}".toLabel(WorldScreenStyle.muted, 11).apply { setEllipsis(true) })
            .minWidth(0f).expandX().left().padLeft(8f)
        imagePreview.add(removeImage).right().padLeft(8f)
        invalidateHierarchy()
    }

    private fun addHistoryImage(message: Mail, attachment: Attachment, contentWidth: Float, preload: Boolean, parent: Table = history) {
        val container = Table(skin)
        val loading = "Loading image…".toLabel(WorldScreenStyle.muted, 11).apply { wrap = true }
        parent.add(container).width(contentWidth).left().padTop(4f).padBottom(8f).row()
        if (imagesHidden || disposed) {
            container.add("Image · ${attachment.width} × ${attachment.height}".toLabel(WorldScreenStyle.muted, 11))
                .width(contentWidth).left()
            return
        }
        val requestHistory = historyGeneration
        val requestViewer = generation
        var requested = false
        lateinit var showLoadButton: () -> Unit
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
                    historyTextures.add(HistoryTexture(texture) {
                        container.clearChildren()
                        requested = false
                        showLoadButton()
                    })
                    while (historyTextures.size > 16) {
                        val old = historyTextures.removeAt(0)
                        old.evict()
                        old.texture.dispose()
                    }
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
        showLoadButton = {
            val view = WorldScreenStyle.button("View image").apply { name = "MessageViewImage"; label.setFontSize(11) }
            view.onClick { requestPreview() }
            container.add(view).left()
        }
        if (preload) requestPreview() else showLoadButton()
    }

    private fun rebuildHistory(jump: Boolean = false) {
        rememberScrollPosition(forceEnd = jump)
        releaseHistoryTextures()
        history.clearChildren(); historyAnchors.clear()
        val selected = conversation.selected?.id
        val filtered = mail.filter { selected == null || it.sender == selected || it.target == selected }
        val contentWidth = (width - 40f).coerceAtLeast(80f)
        if (filtered.isEmpty()) history.add("No messages yet. Replies arrive on the recipient’s next turn."
            .toLabel(WorldScreenStyle.muted, AgentReadingText.size(12), Align.center).apply { wrap = true })
            .width(contentWidth).padTop(GameUiStyle.Space.xl).row()
        if (filtered.size > visibleLimit + historyTailOffset) {
            val earlier = if (GameUiStyle.isAtlas) WorldScreenStyle.quietButton("Earlier messages", GameUiStyle.ButtonSize.Compact)
                else WorldScreenStyle.button("Earlier messages").apply { label.setFontSize(11) }
            earlier.onClick {
                if (visibleLimit < 600) visibleLimit += 100 else historyTailOffset = minOf(historyTailOffset + 100, (filtered.size - visibleLimit).coerceAtLeast(0))
                rebuildHistory()
            }
            history.add(earlier).padBottom(10f).row()
        }
        val shown = filtered.dropLast(historyTailOffset.coerceAtMost(filtered.size)).takeLast(visibleLimit)
        // Bound automatic byte reads and GPU uploads even after "Earlier messages" grows the history.
        val recentImages = shown.flatMap { message -> message.images.map { message.id to it.id } }.takeLast(4).toSet()
        var previousTurn: Int? = null
        for (message in shown) {
            if (message.sent != previousTurn) {
                history.add(turnDivider(message.sent, contentWidth)).width(contentWidth)
                    .padTop(if (previousTurn == null) 0f else 6f).padBottom(10f).row()
                previousTurn = message.sent
            }
            val outgoing = message.sender == viewerId
            val bubbleWidth = bubbleWidth(contentWidth, outgoing)
            val card = dispatchBubble(message, outgoing, bubbleWidth, recentImages)
            historyAnchors[message.id] = card
            history.add(chatRow(card, message.sender, outgoing, bubbleWidth)).width(contentWidth)
                .padBottom(if (message.acknowledged != null) 6f else 12f).row()
            if (message.acknowledged != null) {
                val replyWidth = bubbleWidth(contentWidth, false)
                history.add(chatRow(acknowledgementBubble(message, replyWidth), message.target, false, replyWidth))
                    .width(contentWidth).padBottom(12f).row()
            }
        }
        history.invalidateHierarchy(); scroll.invalidate()
    }

    private fun bubbleWidth(contentWidth: Float, outgoing: Boolean) =
        (contentWidth * 0.88f).coerceAtLeast(minOf(contentWidth, 220f)) - if (outgoing) 0f else portraitSize + portraitGap

    /** Your dispatches sit on the right; everyone else's on the left beside their portrait. */
    private fun chatRow(bubble: Table, civId: String, outgoing: Boolean, bubbleWidth: Float) = Table().apply {
        if (outgoing) {
            add().growX()
            add(bubble).width(bubbleWidth).top()
        } else {
            add(portrait(civId)).size(portraitSize).top().padRight(portraitGap)
            add(bubble).width(bubbleWidth).top()
            add().growX()
        }
    }

    private fun portrait(civId: String): Actor {
        val civ = screen.gameInfo.civilizations.firstOrNull { it.civID == civId }
        return if (civ != null) ImageGetter.getNationPortrait(civ.nation, portraitSize)
            else ImageGetter.getCircle(AgentActivityAppearance.color(screen.gameInfo, civId), portraitSize)
    }

    /** "Turn 12" between hairlines, where the sending turn changes. */
    private fun turnDivider(turn: Int, width: Float) = Table().apply {
        val rule = ImageGetter.getWhiteDotDrawable().tint(Color(WorldScreenStyle.ink).apply { a = 0.12f })
        add(Table().apply { background = rule }).growX().height(1f)
        add("Turn $turn".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)).padLeft(GameUiStyle.Space.m).padRight(GameUiStyle.Space.m)
        add(Table().apply { background = rule }).growX().height(1f)
        this.width = width
    }

    private fun bubbleBackground(outgoing: Boolean) = if (outgoing)
        WorldScreenStyle.control("WorldScreen/Messages/Outgoing", Color(WorldScreenStyle.surface).lerp(WorldScreenStyle.accent, 0.2f).apply { a = 0.92f })
        else WorldScreenStyle.control("WorldScreen/Messages/Incoming", Color(WorldScreenStyle.raised).apply { a = 0.82f })

    private fun bubble(name: String, outgoing: Boolean) = Table(skin).apply {
        this.name = name; top().left()
        background = bubbleBackground(outgoing)
        pad(bubblePadY, bubblePadX, bubblePadY, bubblePadX)
    }

    /** Message text as written (a plain Label, untranslated) at the reader's size. */
    private fun messageText(text: String, size: Int) = Label(text, Label.LabelStyle(BaseScreen.skin.get(Label.LabelStyle::class.java))).apply {
        setFontSize(AgentReadingText.size(size)); color = WorldScreenStyle.ink; wrap = true
    }

    private fun dispatchBubble(message: Mail, outgoing: Boolean, bubbleWidth: Float, recentImages: Set<Pair<String, String>>): Table {
        val card = bubble("MessageRow/${message.id}", outgoing)
        val inner = bubbleWidth - 2 * bubblePadX
        val sender = if (message.origin == "SPECTATOR") "Spectator" else message.sender
        val senderColor = AgentActivityAppearance.color(screen.gameInfo, message.sender)
        card.add("$sender → ${message.target}".toLabel(senderColor, AgentReadingText.size(12), hideIcons = true).apply { wrap = true })
            .width(inner).left().row()
        card.add(messageText(message.text, 14)).width(inner).left().padTop(4f).padBottom(6f).row()
        for (attachment in message.images) addHistoryImage(message, attachment, inner,
            (message.id to attachment.id) in recentImages, card)
        val state = message.acknowledged?.let { "Acknowledged · turn $it" }
            ?: message.delivered?.let { "Delivered · turn $it" } ?: "Queued · next turn"
        // Receipt colours read as progress; amber stays reserved for real problems.
        val stateColor = when {
            message.acknowledged != null -> WorldScreenStyle.positive
            message.delivered != null -> WorldScreenStyle.ink
            else -> WorldScreenStyle.muted
        }
        val receipt = Table(skin)
        val narrow = inner < 300f
        receipt.add("Sent turn ${message.sent}".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)).minWidth(0f).left().expandX()
        if (narrow) receipt.row()
        receipt.add(state.toLabel(stateColor, GameUiStyle.Type.caption).apply { wrap = true; setAlignment(if (narrow) Align.left else Align.right) })
            .minWidth(0f).growX().padLeft(if (narrow) 0f else 8f).padTop(if (narrow) 2f else 0f)
        if (message.delivered == null && message.sender == viewerId) {
            receipt.row()
            receipt.add(deleteButton(message.id)).colspan(if (narrow) 1 else 2).minHeight(GameUiStyle.compactControlHeight).right().padTop(4f)
        }
        card.add(receipt).width(inner).left().row()
        return card
    }

    /** The recipient's saved acknowledgement, as its reply. */
    private fun acknowledgementBubble(message: Mail, bubbleWidth: Float): Table {
        val card = bubble("MessageAck/${message.id}", false)
        val inner = bubbleWidth - 2 * bubblePadX
        card.add("${message.target} acknowledged receipt".toLabel(
            AgentActivityAppearance.color(screen.gameInfo, message.target), AgentReadingText.size(11), hideIcons = true).apply { wrap = true })
            .width(inner).left().row()
        card.add(messageText(message.acknowledgementText.orEmpty(), 13)).width(inner).left().padTop(4f).row()
        return card
    }

    /** Only the sender's still-queued mail is deletable; the second click confirms. */
    private fun deleteButton(messageId: String): Button {
        val text = when (messageId) {
            deletingId -> "Deleting…"
            confirmDeleteId -> "Confirm delete"
            else -> "Delete"
        }
        val button = if (GameUiStyle.isAtlas) WorldScreenStyle.ghostButton(text, GameUiStyle.ButtonSize.Compact)
            else WorldScreenStyle.button(text, size = GameUiStyle.ButtonSize.Compact)
        return button.apply {
            name = "MessageDelete"
            if (messageId == confirmDeleteId) label.color = WorldScreenStyle.error
            WorldScreenStyle.setEnabled(this, deletingId == null)
            onClick { requestDelete(messageId) }
        }
    }

    private fun requestDelete(messageId: String) {
        if (deletingId != null) return
        if (confirmDeleteId != messageId) {
            confirmDeleteId = messageId
            rebuildHistory()
            return
        }
        confirmDeleteId = null; deletingId = messageId
        val requestGeneration = generation
        rebuildHistory()
        session.deleteMessage(messageId) { error ->
            if (disposed || requestGeneration != generation) return@deleteMessage
            deletingId = null
            if (error == null) { status.color = WorldScreenStyle.muted; setStatus("Message deleted.") }
            else { status.color = WorldScreenStyle.error; setStatus(error) }
            refresh()
            rebuildHistory()
        }
    }

    private fun rememberScrollPosition(forceEnd: Boolean = false) {
        // Keep the position from the first mutation; later callbacks may see intermediate bounds.
        if (forceEnd || pendingScrollAnchor == null) {
            val mark = historyAnchors.entries.firstOrNull { (_, actor) -> rowTop(actor) + actor.height > scroll.scrollY }
            pendingScrollAnchor = ScrollAnchor(scroll.scrollY, forceEnd || scroll.maxY - scroll.scrollY < 24f,
                mark?.key, mark?.value?.let { scroll.scrollY - rowTop(it) } ?: 0f)
        }
    }

    private fun setStatus(text: String) {
        if (status.text.toString() == text) return
        rememberScrollPosition()
        status.setText(text)
    }

    private fun rowTop(actor: Actor) = history.height - actor.localToAscendantCoordinates(history, Vector2()).y - actor.height

    override fun layout() {
        val fontScale = UncivGame.Current.settings.fontSizeMultiplier
        val availableWidth = (width - padLeft - padRight).coerceAtLeast(1f)
        // Active feedback belongs to the mailbox, including while its draft is collapsed.
        // Empty feedback has no layout reservation; long errors remain scrollable in two lines.
        val statusHeight = if (status.text.length == 0) 0f else 28f * fontScale
        val statusGap = if (statusHeight == 0f) 0f else 4f
        statusPane.isVisible = statusHeight > 0f
        getCell(statusPane).height(statusHeight).padTop(statusGap)
        val composerGap = if (composerExpanded) GameUiStyle.Space.xs else 0f
        val chrome = padTop + padBottom + header.prefHeight + identity.prefHeight + 2f + 8f +
            pickerHeight + 8f + composerControls.prefHeight + GameUiStyle.Space.xs + statusHeight + statusGap + composerGap
        val area = (height - chrome).coerceAtLeast(1f)
        val regions = AgentPanelLayout.calculate(availableWidth, area, fontScale, composerExpanded)
        val composerHeight = if (composerExpanded)
            composer.prefHeight.coerceIn(regions.composer.height, maxOf(regions.composer.height, area * 0.42f)) else 0f
        val footerHeight = if (composerExpanded) footer.prefHeight.coerceAtMost(area * 0.24f) else 0f
        getCell(scroll).height((area - composerHeight - footerHeight).coerceAtLeast(0f)).minHeight(0f)
        getCell(composerScroll).height(composerHeight).padTop(composerGap)
        getCell(footer).height(footerHeight)
        val inputWidth = (availableWidth - composer.padLeft - composer.padRight -
            (composerScroll.style.vScroll?.minWidth ?: 0f)).coerceAtLeast(1f)
        composer.cells.forEach { it.width(inputWidth) }
        super.layout()
        // Parent bounds and wrapped labels must settle before calculating the final scroll range.
        scroll.validate()
        val anchor = pendingScrollAnchor
        pendingScrollAnchor = null
        if (anchor != null) scroll.scrollY = if (anchor.atEnd) scroll.maxY else
            (anchor.id?.let { historyAnchors[it] }?.let { rowTop(it) + anchor.offset } ?: anchor.position).coerceIn(0f, scroll.maxY)
        // Wrapped rows may require a second layout pass after the anchor was consumed.
        // ScrollPane clamps its target then, but retains the old visual offset until explicitly synced.
        scroll.updateVisualScroll()
    }

    private companion object {
        const val portraitSize = 24f
        const val portraitGap = 8f
        const val bubblePadX = 12f
        const val bubblePadY = 9f
    }

    override fun dispose() {
        if (disposed) return
        pendingScrollAnchor = null
        disposed = true; generation++; clipboardGeneration++; clipboardPending = false
        releaseDraftTexture(); releaseHistoryTextures(); imageDraft = null
        imagePreview.clearChildren(); history.clearChildren()
        task.cancel(); recipient.hideScrollPane(); conversation.hideScrollPane()
        if (screen.stage.scrollFocus?.isDescendantOf(this) == true) screen.stage.scrollFocus = null
        if (screen.stage.keyboardFocus?.isDescendantOf(this) == true) screen.stage.keyboardFocus = null
        window.dispose()
        remove()
    }
}
