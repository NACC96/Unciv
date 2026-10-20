package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.List as GdxList
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.Array as GdxArray
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Timer
import com.unciv.logic.agents.*
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.GameUiStyle
import com.unciv.UncivGame
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/** Modeless local controls. Only the host may commit these copied, per-nation drafts. */
class AgentNationSettingsPanel(private val screen: WorldScreen, private val session: AgentMatchSession, private val controlsAnchor: Actor? = null) :
    Table(BaseScreen.skin), Disposable {
    private data class NationChoice(val id: String, val label: String) { override fun toString() = label }
    private data class ModelChoice(val spec: ModelSpec, val customEntry: Boolean = false) {
        override fun toString() = if (customEntry) "Custom model…" else spec.label
    }
    private data class EffortChoice(val wireValue: String?, val label: String) { override fun toString() = label }
    private data class VisionChoice(val vision: MatchVision) { override fun toString() = vision.label }
    private val nation = AgentNationSelectBox<NationChoice>().apply { name = "NationPicker" }
    private val provider = AgentNationSelectBox<String>().apply { name = "NationProvider" }
    private val model = AgentNationSelectBox<ModelChoice>().apply { name = "NationModel" }
    private val reasoning = AgentNationSelectBox<EffortChoice>().apply { name = "NationReasoning" }
    private val vision = AgentNationSelectBox<VisionChoice>().apply { name = "MatchVision" }
    private var changingVision = false
    private val displayName = TextField("", skin).apply { name = "NationDisplayName"; maxLength = 256 }
    private val customModel = TextField("", skin).apply { name = "NationCustomModel"; maxLength = 256 }
    private val personality = TextArea("", skin).apply { name = "NationPersonality"; maxLength = 16 * 1024 }
    private val controller = "".toLabel(WorldScreenStyle.ink, GameUiStyle.Type.body).apply { name = "NationController"; wrap = true }
    private var previousPending: String? = null
    private val actionFooter = Table(skin).apply { name = "NationActionFooter" }
    private val identity = "".toLabel(WorldScreenStyle.muted, 11).apply { wrap = true }
    private val status = "".toLabel(WorldScreenStyle.muted, 12).apply { name = "NationStatus"; wrap = true }
    private val form = Table(skin).apply { top().left(); defaults().growX().minWidth(0f) }
    private val scroll = AutoScrollPane(form, skin).apply { name = "NationSettingsScroll" }
    private val customLabel = "Custom model ID".toLabel(WorldScreenStyle.muted, 11)
    private val save = WorldScreenStyle.button("Save agent settings", primary = true).apply { name = "NationSave" }
    private val play = WorldScreenStyle.button("Play as").apply { name = "NationPlay" }
    private val handback = WorldScreenStyle.button("Hand to agent").apply { name = "NationHandback" }
    private val spectate = WorldScreenStyle.button("Spectate").apply { name = "NationSpectate" }
    private val drafts = HashMap<String, AgentProfile>()
    private var settings = emptyList<NationSetting>()
    private var choices = emptyList<NationChoice>()
    private var selectedId: String? = null
    private var updating = false
    private var requesting = false
    private var disposed = false
    private val window = com.unciv.ui.screens.worldscreen.HudWindow(this, "nation-settings", "Nation settings",
        Vector2(320f, 320f), defaultBounds = ::defaultBounds,
        show = { if (!isVisible) toggle() }, hide = { hide() },
        onResized = { refresh() })
    private val task = object : Timer.Task() {
        override fun run() { if (disposed) cancel() else if (isVisible) refresh() }
    }

    init {
        name = "AgentNationSettingsPanel"
        background = if (GameUiStyle.isAtlas) WorldScreenStyle.surface("WorldScreen/NationSettings", 0.94f)
            else WorldScreenStyle.panelBackground("WorldScreen/NationSettings")
        touchable = Touchable.enabled; pad(14f)
        // Atlas sheets share the Messages X; other skins keep their text button.
        val close = if (GameUiStyle.isAtlas) AgentPanelLayout.closeButton { hide() }
            else WorldScreenStyle.button("Close").apply { onClick { hide() } }
        val header = Table(skin)
        header.add("Nation settings".toLabel(WorldScreenStyle.ink, if (GameUiStyle.isAtlas) GameUiStyle.Type.label + 1 else 17)).minWidth(0f).growX().left()
        header.add(window.controls())
        if (GameUiStyle.isAtlas) header.add(close).size(28f).padLeft(GameUiStyle.Space.xs) else header.add(close)
        window.attachHeader(header)
        add(header).growX().row()
        add(identity).growX().left().padTop(6f).padBottom(8f).row()
        val visionField = Table(skin).apply {
            name = "MatchVisionField"
            if (GameUiStyle.isAtlas) {
                background = WorldScreenStyle.background("WorldScreen/NationSettings/Vision", WorldScreenStyle.inset)
                pad(GameUiStyle.Space.m)
            }
            add("Map vision · all players".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption))
                .minWidth(0f).growX().left().padBottom(GameUiStyle.Space.s).row()
            add(vision).minWidth(0f).growX().minHeight(if (GameUiStyle.isAtlas) GameUiStyle.standardControlHeight else 32f).row()
        }
        add(visionField).minWidth(0f).growX().padBottom(GameUiStyle.Space.m).row()
        add(nation).minWidth(0f).growX().height(32f).row()
        add(controller).minWidth(0f).growX().left().padTop(6f).row()
        add(status).minWidth(0f).growX().left().padTop(6f).padBottom(6f).row()
        scroll.setScrollingDisabled(true, false); scroll.setFadeScrollBars(false); scroll.setOverscroll(false, false)
        add(scroll).grow().row()
        fun field(label: String, actor: Actor, height: Float = 32f) {
            form.add(label.toLabel(WorldScreenStyle.muted, 11)).left().padTop(12f).padBottom(4f).row()
            form.add(actor).height(height).row()
        }
        field("Display name", displayName)
        field("Provider", provider)
        field("Model", model)
        form.add(customLabel).left().padTop(8f).padBottom(4f).row()
        form.add(customModel).height(32f).row()
        field("Thinking level / reasoning effort", reasoning)
        field("Personality instructions", personality, 86f)
        if (GameUiStyle.isAtlas) {
            actionFooter.add(save).minWidth(0f).growX().minHeight(GameUiStyle.standardControlHeight)
            add(actionFooter).growX().padTop(8f).row()
        } else form.add(save).height(34f).padTop(16f).row()
        form.add(play).height(34f).padTop(6f).row()
        form.add(handback).height(34f).padTop(6f).row()
        form.add(spectate).height(34f).padTop(6f).row()
        form.add(("Play as takes over immediately, keeping moves already made. Hand to agent returns control immediately. " +
            "Spectate changes your view; human nations wait for you until explicitly handed to an agent. " +
            "Map vision applies to every player; agents use it from their next observation.")
            .toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption).apply { wrap = true })
            .padTop(10f).padBottom(8f).row()
        for (picker in listOf(vision, nation, provider, model, reasoning)) stylePicker(picker)
        // Programmatic selection fires change events; only the user's pick may commit.
        updating = true
        vision.setItems(GdxArray(MatchVision.entries.map { VisionChoice(it) }.toTypedArray()))
        vision.selected = vision.items.firstOrNull { it.vision == session.getVision() }
        updating = false
        vision.onChange { if (!updating) vision.selected?.let { changeVision(it.vision) } }
        for (editor in listOf(displayName, customModel, personality)) {
            editor.style = TextField.TextFieldStyle(editor.style).apply {
                background = WorldScreenStyle.background("WorldScreen/NationSettings/Field", WorldScreenStyle.raised)
                focusedBackground = WorldScreenStyle.background("WorldScreen/NationSettings/Focused", WorldScreenStyle.pressed)
                fontColor = WorldScreenStyle.ink; messageFontColor = WorldScreenStyle.muted
            }
            editor.addListener(object : InputListener() {
                override fun keyDown(event: InputEvent, keycode: Int): Boolean { event.stop(); return false }
            })
        }
        provider.setItems(GdxArray(AgentModelCatalog.SUPPORTED_PROVIDERS.toTypedArray()))
        nation.onChange { if (!updating && !requesting) loadDraft(nation.selected?.id) }
        provider.onChange {
            if (!updating) currentDraft()?.let {
                val oldProvider = it.provider; val oldModel = it.modelId
                it.provider = provider.selected
                val spec = AgentModelCatalog.getDefaultModel(it.provider)
                it.modelId = spec.id; it.reasoning = spec.defaultThinkingLevel.wireValue; populateModels(it)
                syncDisplayName(it, oldProvider, oldModel)
            }
        }
        model.onChange {
            if (!updating) currentDraft()?.let {
                val choice = model.selected ?: return@let
                val oldModel = it.modelId
                it.modelId = if (choice.customEntry) customModel.text.trim() else choice.spec.id
                showCustom(choice.customEntry || choice.spec.isCustom); populateReasoning(it)
                syncDisplayName(it, it.provider, oldModel)
            }
        }
        reasoning.onChange { if (!updating) currentDraft()?.reasoning = reasoning.selected?.wireValue }
        displayName.onChange { if (!updating) currentDraft()?.displayName = displayName.text }
        personality.onChange { if (!updating) currentDraft()?.personality = personality.text }
        customModel.onChange {
            if (!updating && model.selected?.customEntry == true) currentDraft()?.let {
                val oldModel = it.modelId
                it.modelId = customModel.text.trim()
                populateReasoning(it)
                syncDisplayName(it, it.provider, oldModel)
            }
        }
        save.onClick { request(NationControlChange.SAVE_AGENT_SETTINGS) }
        play.onClick { request(NationControlChange.PLAY_AS) }
        handback.onClick { request(NationControlChange.HAND_TO_AGENT) }
        spectate.onClick { request(NationControlChange.SPECTATE) }
        screen.stage.addActor(this); isVisible = false; refresh()
        Timer.schedule(task, 0.5f, 0.5f)
    }

    private fun stylePicker(picker: SelectBox<*>) {
        picker.setMaxListCount(7)
        if (GameUiStyle.isAtlas) {
            GameUiStyle.applyWidget(picker)
            return
        }
        picker.style = SelectBox.SelectBoxStyle(picker.style).apply {
            fontColor = WorldScreenStyle.ink
            background = WorldScreenStyle.background("WorldScreen/NationSettings/Select", WorldScreenStyle.raised)
            backgroundOver = WorldScreenStyle.background("WorldScreen/NationSettings/SelectOver", WorldScreenStyle.hover)
            backgroundOpen = backgroundOver
            listStyle = GdxList.ListStyle(listStyle).apply {
                fontColorSelected = WorldScreenStyle.accent; fontColorUnselected = WorldScreenStyle.ink
                background = WorldScreenStyle.background("WorldScreen/NationSettings/SelectList")
                selection = WorldScreenStyle.background("WorldScreen/NationSettings/Selection", WorldScreenStyle.pressed)
            }
            scrollStyle = ScrollPane.ScrollPaneStyle(scrollStyle).apply { background = listStyle.background }
        }
    }
    private fun currentDraft() = selectedId?.let { drafts[it] }
    /** A default display name follows the model; a custom one is kept. */
    private fun syncDisplayName(draft: AgentProfile, oldProvider: String, oldModelId: String) {
        val civ = settings.firstOrNull { it.civId == selectedId }?.nationName ?: selectedId.orEmpty()
        val name = AgentSlotLogic.displayNameAfterModelChange(draft.displayName, civ, oldProvider, oldModelId, draft.provider, draft.modelId)
        if (name == draft.displayName) return
        draft.displayName = name
        val wasUpdating = updating; updating = true
        try { displayName.text = name } finally { updating = wasUpdating }
    }
    private fun loadDraft(id: String?) {
        selectedId = id
        val setting = settings.firstOrNull { it.civId == id }
        updating = true
        try {
            if (setting != null) {
                val draft = drafts.getOrPut(setting.civId) { setting.profile?.copy() ?: AgentProfile.defaultFor(setting.nationName) }
                displayName.text = draft.displayName; personality.text = draft.personality
                val providers = (AgentModelCatalog.SUPPORTED_PROVIDERS + draft.provider).distinct()
                provider.setItems(GdxArray(providers.toTypedArray()))
                provider.selected = draft.provider; customModel.text = draft.modelId
                populateModels(draft)
            } else { displayName.text = ""; personality.text = "" }
        } finally { updating = false }
        scroll.scrollY = 0f; scroll.updateVisualScroll(); updateController(); updateEnabled()
    }
    private fun populateModels(draft: AgentProfile) {
        val wasUpdating = updating; updating = true
        try {
            val models = AgentModelCatalog.getModelsForProvider(draft.provider).toMutableList()
            if (models.none { it.id == draft.modelId }) models.add(0, AgentModelCatalog.findModel(draft.provider, draft.modelId))
            val options = models.map { ModelChoice(it) } + ModelChoice(AgentModelCatalog.customModel("", draft.provider), true)
            model.setItems(GdxArray(options.toTypedArray()))
            model.selected = options.first { !it.customEntry && it.spec.id == draft.modelId }
            customModel.text = draft.modelId; showCustom(model.selected.customEntry || model.selected.spec.isCustom); populateReasoning(draft, preserveSaved = true)
        } finally { updating = wasUpdating }
    }
    private fun populateReasoning(draft: AgentProfile, preserveSaved: Boolean = false) {
        val wasUpdating = updating; updating = true
        try {
            val spec = AgentModelCatalog.findModel(draft.provider, draft.modelId)
            val levels = spec.supportedThinkingLevels.ifEmpty { listOf(ThinkingLevel.PROVIDER_DEFAULT) }
            val options = levels.map { EffortChoice(it.wireValue, it.label) }.toMutableList()
            // Viewing an old save must not silently rewrite a retired effort or provider.
            // Deliberately picking a new model revalidates against its current capabilities.
            val value = if (preserveSaved) draft.reasoning else
                AgentModelCatalog.revalidateThinkingLevel(spec, ThinkingLevel.fromWireValue(draft.reasoning)).wireValue
            if (options.none { it.wireValue == value }) options.add(EffortChoice(value, "$value (Saved)"))
            reasoning.setItems(GdxArray(options.toTypedArray())); reasoning.selected = options.first { it.wireValue == value }
            if (!preserveSaved) draft.reasoning = value
        } finally { updating = wasUpdating }
    }
    private fun showCustom(shown: Boolean) {
        customLabel.isVisible = shown; customModel.isVisible = shown
        form.getCell(customLabel).height(if (shown) customLabel.prefHeight else 0f).padTop(if (shown) 8f else 0f).padBottom(if (shown) 4f else 0f)
        form.getCell(customModel).height(if (shown) 32f else 0f)
        form.invalidateHierarchy()
    }

    fun toggle() { if (isVisible) hide() else {
        isVisible = true; screen.hideCivilizationOverview()
        screen.stage.root.children.filterIsInstance<AgentMessagesPanel>().forEach { it.hide() }
        window.onShown()
        refresh(); toFront(); Gdx.graphics.requestRendering()
    } }
    fun hide() {
        isVisible = false
        window.onHidden()
        for (picker in listOf(vision, nation, provider, model, reasoning)) picker.hideScrollPane()
        if (screen.stage.scrollFocus?.isDescendantOf(this) == true) screen.stage.scrollFocus = null
        if (screen.stage.keyboardFocus?.isDescendantOf(this) == true) screen.stage.keyboardFocus = null
        Gdx.graphics.requestRendering()
    }
    fun refresh() {
        if (disposed) return
        settings = session.getNationSettings()
        val nextChoices = settings.map {
            NationChoice(it.civId, "${it.nationName} · ${if (it.kind == ControllerKind.HUMAN) "Human" else "Agent"}${if (it.isActive) " · Current turn" else ""}")
        }
        if (nextChoices != choices) {
            choices = nextChoices; updating = true
            nation.setItems(GdxArray(choices.toTypedArray()))
            choices.firstOrNull { it.id == selectedId }?.let { nation.selected = it }
            updating = false
            if (nation.selected?.id != selectedId) loadDraft(nation.selected?.id)
        }
        identity.setText("Viewing ${screen.viewingCiv.civID} · changes apply immediately")
        updateController()
        if (!changingVision && vision.selected?.vision != session.getVision()) {
            updating = true
            vision.selected = vision.items.firstOrNull { it.vision == session.getVision() }
            updating = false
        }
        val pending = session.getPendingNationChange()
        if (pending != null) { status.setText(pending); status.color = WorldScreenStyle.warning }
        else if (previousPending != null) { status.setText(""); status.color = WorldScreenStyle.muted }
        previousPending = pending
        // Controls are not mounted/laid out during hidden construction: place only once shown.
        if (isVisible) window.place()
        else if (!isVisible) defaultBounds().let { setSize(it.width, it.height) }
        // Bound wrapped labels to the scroll pane; long identities must never drive its preferred width.
        val contentWidth = (width - 46f).coerceAtLeast(1f)
        form.cells.forEach { it.width(contentWidth) }
        if (GameUiStyle.isAtlas) {
            // The form scrolls independently; the save action remains reachable at every size.
            getCell(actionFooter).height(48f * UncivGame.Current.settings.fontSizeMultiplier)
        }
        updateEnabled(); invalidateHierarchy(); Gdx.graphics.requestRendering()
    }

    /** Atlas: the right-side sheet below the header, like Messages. Elsewhere hanging below the controls. */
    private fun defaultBounds(): com.badlogic.gdx.math.Rectangle {
        val w = minOf(480f, screen.stage.width - 16f).coerceAtLeast(1f)
        val anchorTop = controlsAnchor?.takeIf { it.stage != null }?.localToStageCoordinates(Vector2())?.y?.minus(8f)
        if (GameUiStyle.isAtlas && controlsAnchor == null)
            return AgentPanelLayout.rightSheet(screen.stage, w, 400f * UncivGame.Current.settings.fontSizeMultiplier, 650f)
        val h = minOf(650f, screen.stage.height - if (GameUiStyle.isAtlas) 24f else 130f, anchorTop?.minus(8f) ?: Float.MAX_VALUE).coerceAtLeast(1f)
        val x = (screen.stage.width - w - 8f).coerceIn(0f, maxOf(0f, screen.stage.width - w))
        val y = ((anchorTop ?: (screen.stage.height - 116f)) - h).coerceIn(0f, maxOf(0f, screen.stage.height - h))
        return com.badlogic.gdx.math.Rectangle(x, y, w, h)
    }
    private fun updateController() {
        val selected = settings.firstOrNull { it.civId == selectedId }
        controller.setText(selected?.let {
            "Controller: ${if (it.kind == ControllerKind.HUMAN) "Human" else "Agent"} · ${it.nationName}${if (it.isActive) " · Current turn" else ""}"
        } ?: "No nation selected")
    }
    private fun updateEnabled() {
        val busy = requesting || session.getPendingNationChange() != null
        val eligible = selectedId != null
        nation.isDisabled = busy || choices.isEmpty()
        vision.isDisabled = changingVision
        provider.isDisabled = busy || !eligible; model.isDisabled = busy || !eligible; reasoning.isDisabled = busy || !eligible
        displayName.isDisabled = busy || !eligible; customModel.isDisabled = busy || !eligible; personality.isDisabled = busy || !eligible
        for (button in listOf(save, play, handback)) WorldScreenStyle.setEnabled(button, !busy && eligible)
        WorldScreenStyle.setEnabled(spectate, !busy && session.canSpectate())
        if (!eligible && !busy) { status.setText("No eligible nations to manage."); status.color = WorldScreenStyle.muted }
    }
    private fun request(change: NationControlChange) {
        if (disposed || requesting || session.getPendingNationChange() != null) return
        if (change == NationControlChange.SPECTATE && !session.canSpectate()) return
        val id = if (change == NationControlChange.SPECTATE) null else selectedId ?: return
        val profile = if (change in setOf(NationControlChange.SAVE_AGENT_SETTINGS, NationControlChange.HAND_TO_AGENT)) currentDraft()?.copy() else null
        if (profile != null) {
            val error = AgentModelCatalog.validateProfile(profile).joinToString("\n")
            if (error.isNotEmpty()) { status.setText(error); status.color = WorldScreenStyle.error; return }
        }
        // Play as cuts off an active agent; settings for one finish its current turn unchanged.
        val agentMidTurn = change != NationControlChange.PLAY_AS &&
            settings.any { it.civId == id && it.isActive && it.kind == ControllerKind.EXTERNAL_AGENT }
        requesting = true; status.setText("Applying…"); status.color = WorldScreenStyle.warning; updateEnabled()
        session.requestNationChange(id, change, profile) { error ->
            if (!disposed) {
                requesting = false; previousPending = null
                status.setText(error ?: if (agentMidTurn) "Saved. The agent finishes this turn with its previous settings." else "Applied.")
                status.color = if (error == null) WorldScreenStyle.accent else WorldScreenStyle.error
                refresh()
            }
        }
    }
    private fun changeVision(choice: MatchVision) {
        if (disposed || changingVision || choice == session.getVision()) return
        changingVision = true; status.setText("Applying…"); status.color = WorldScreenStyle.warning; updateEnabled()
        session.setVision(choice) { error ->
            if (!disposed) {
                changingVision = false
                status.setText(error ?: "Map vision: ${choice.label}. Agents see it from their next observation.")
                status.color = if (error == null) WorldScreenStyle.accent else WorldScreenStyle.error
                refresh()
            }
        }
    }
    override fun dispose() { if (disposed) return; disposed = true; task.cancel(); hide(); window.dispose(); drafts.clear(); remove() }
}
