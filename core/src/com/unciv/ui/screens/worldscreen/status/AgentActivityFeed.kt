package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.List as GdxList
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Array as GdxArray
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Timer
import com.unciv.logic.agents.AgentActivityEntry
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentRunState
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import java.io.File

/** Floating transcript: compact tools, readable agent identity, and live following. */
class AgentActivityFeed(private val screen: WorldScreen, private val session: AgentMatchSession,
                        private val controlsAnchor: Actor? = null) : Table(BaseScreen.skin), Disposable {
    private val muted = WorldScreenStyle.muted
    private val ink = WorldScreenStyle.ink
    private val entriesTable = Table(skin)
    private val scroll = object : AutoScrollPane(entriesTable, skin) {
        // Gdx's default step depends on pane/content height and can skip whole paragraphs.
        override fun getMouseWheelY() = 24f
    }
    private val stateLabel = "".toLabel(muted, 12)
    private enum class FilterMode { FOLLOW_ACTIVE, ALL, NATION }
    private data class NationFilter(val mode: FilterMode, val label: String, val civId: String? = null) {
        override fun toString() = label
    }
    private val activeFilter = NationFilter(FilterMode.FOLLOW_ACTIVE, "Follow active nation")
    private val allFilter = NationFilter(FilterMode.ALL, "All nations")
    private val nationPicker = AgentNationSelectBox<NationFilter>()
    private var selectedFilter = activeFilter
    private var updatingPicker = false
    private var effectiveCivId: String? = null
    private var lastActiveAgentCivId: String? = null
    private var effectiveFilterKey: String? = null
    private var deliberateFilterSwitch = false
    private val liveLink = WorldScreenStyle.button("Live").apply { label.setFontSize(11) }
    private val earlier = WorldScreenStyle.button("Earlier").apply { label.setFontSize(11) }
    private val logsLink = WorldScreenStyle.button("Logs").apply { label.setFontSize(11) }
    private val expanded = HashSet<String>()
    private val expandedRuns = HashSet<String>()
    private val bodies = HashMap<String, Pair<String, String>>()
    private val rowAnchors = LinkedHashMap<String, Actor>()
    private var shownEntries = emptyList<AgentActivityEntry>()
    private var visibleLimit = 120
    private var unreadCount = 0
    private var followLive = true
    private var panelWidth = 0f
    private var panelHeight = 0f
    private var disposed = false

    private sealed interface FeedRow {
        data class Event(val entry: AgentActivityEntry) : FeedRow
        data class Tools(val entries: List<AgentActivityEntry>) : FeedRow
    }
    private data class ReadingAnchor(val key: String, val offset: Float)

    private val refreshTask = object : Timer.Task() {
        override fun run() {
            if (disposed) { cancel(); return }
            if (isVisible) { refresh(); Gdx.graphics.requestRendering() }
        }
    }

    init {
        background = WorldScreenStyle.panelBackground("WorldScreen/AgentActivityFeed")
        touchable = Touchable.enabled
        pad(12f, 14f, 12f, 14f)
        val header = Table(skin)
        header.add("Agent activity".toLabel(ink, 17)).left().expandX()
        val close = Button(BaseScreen.skin).apply {
            WorldScreenStyle.apply(this)
            pad(4f)
            add(ImageGetter.getImage("OtherIcons/Close")).size(12f)
            onClick { setShown(false) }
        }
        header.add(close).size(24f).padLeft(8f)
        add(header).growX().row()
        nationPicker.style = SelectBox.SelectBoxStyle(nationPicker.style).apply {
            background = WorldScreenStyle.background("WorldScreen/AgentActivityFeed/Filter", WorldScreenStyle.raised)
            backgroundOver = WorldScreenStyle.background("WorldScreen/AgentActivityFeed/FilterHover", WorldScreenStyle.hover)
            backgroundOpen = backgroundOver
            fontColor = ink
            listStyle = GdxList.ListStyle(listStyle).apply {
                background = WorldScreenStyle.background("WorldScreen/AgentActivityFeed/FilterList")
                selection = WorldScreenStyle.background("WorldScreen/AgentActivityFeed/FilterSelected", WorldScreenStyle.pressed)
                fontColorSelected = WorldScreenStyle.accent
                fontColorUnselected = ink
            }
            scrollStyle = ScrollPane.ScrollPaneStyle(scrollStyle)
        }
        nationPicker.setMaxListCount(12)
        nationPicker.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                if (updatingPicker) return
                selectedFilter = nationPicker.selected
                deliberateFilterSwitch = true
                followLive = true
                scroll.velocityY = 0f
                refresh()
            }
        })
        val filterRow = Table(skin)
        // SelectBox otherwise takes the longest nation's name as its minimum width.
        filterRow.add(nationPicker).minWidth(0f).growX()
        filterRow.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply {
            color = muted
            setSize(10f, 10f)
            setOrigin(Align.center)
            rotation = -90f
            touchable = Touchable.enabled
            onClick { nationPicker.showList() }
        }).size(10f).padLeft(8f)
        add(filterRow).minWidth(0f).growX().padTop(8f).row()
        stateLabel.wrap = true
        add(stateLabel).growX().left().padTop(5f).padBottom(10f).row()
        entriesTable.top().left()
        scroll.setScrollingDisabled(true, false)
        scroll.setFadeScrollBars(false)
        scroll.setOverscroll(false, false)
        scroll.setSmoothScrolling(false)
        scroll.setFlingTime(0.2f)
        scroll.addCaptureListener(object : InputListener() {
            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean {
                holdPosition()
                return false
            }
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                holdPosition()
                return false
            }
        })
        add(scroll).grow().row()
        val footer = Table(skin)
        earlier.onClick { visibleLimit += 120; holdPosition(); rebuild() }
        footer.add(earlier).left()
        liveLink.onClick { followLive = true; unreadCount = 0; refresh(); jumpToLive(); updateFooter() }
        footer.add(liveLink).expandX().center()
        logsLink.onClick { session.getActivityDirectory()?.let { Gdx.net.openURI(File(it).toURI().toString()) } }
        footer.add(logsLink).right()
        add(footer).growX().padTop(12f)
        screen.stage.addActor(this)
        refresh()
        Timer.schedule(refreshTask, 0.35f, 0.35f)
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (isVisible && followLive) {
            // Layout/streaming growth must never leave Live accumulating a scroll backlog.
            validate()
            scroll.validate()
            scroll.scrollY = scroll.maxY
            scroll.updateVisualScroll()
        }
    }

    fun toggle() = setShown(!isVisible)
    fun hide() { setShown(false); nationPicker.hideList() }

    private fun setShown(shown: Boolean) {
        isVisible = shown
        if (!shown && screen.stage.scrollFocus?.isDescendantOf(this) == true) screen.stage.scrollFocus = screen.mapHolder
        if (shown) { toFront(); refresh(); if (followLive) jumpToLive() }
        Gdx.graphics.requestRendering()
    }

    private fun holdPosition() {
        followLive = false
        scroll.velocityY = 0f
        updateFooter()
    }

    private fun refresh() {
        val width = minOf(300f, maxOf(230f, screen.stage.width * 0.26f), screen.stage.width - 20f)
        val controls = controlsAnchor?.takeIf { it.stage != null && it.height > 0f }
        val controlsBottom = controls?.localToStageCoordinates(Vector2())
        val overlapsControls = controls != null && controlsBottom != null && controlsBottom.x < 10f + width && controlsBottom.x + controls.width > 10f
        val top = minOf(screen.techPolicyAndDiplomacy.y - 8f,
            if (overlapsControls) controlsBottom!!.y - 8f else Float.MAX_VALUE)
        val height = (top - 16f).coerceAtLeast(160f).coerceAtMost(screen.stage.height - 32f)
        val resized = width != panelWidth || height != panelHeight
        if (resized) { panelWidth = width; panelHeight = height; setSize(width, height) }
        setPosition(10f, (top - height).coerceAtLeast(8f))
        val state = when (session.getRunState()) {
            AgentRunState.AGENT_DECIDING -> "Thinking"
            AgentRunState.PAUSED -> "Paused"
            AgentRunState.PAUSING -> "Pausing…"
            AgentRunState.NEEDS_ATTENTION -> "Needs attention"
            AgentRunState.FINISHED -> "Finished"
            AgentRunState.WAITING_HUMAN -> "Your turn"
            else -> "Playing"
        }
        val viewer = screen.viewingCiv
        val spectator = viewer.isSpectator()
        val active = session.getActiveCivId()?.takeIf { spectator || it == viewer.civID }
        if (active != null && session.getAgentProfile(active) != null) lastActiveAgentCivId = active
        val activity = session.getActivity().filter {
            it.kind in setOf("message", "reasoning", "tool", "error", "run") && (spectator || it.civId == viewer.civID)
        }
        if (!spectator && selectedFilter.mode == FilterMode.NATION && selectedFilter.civId != viewer.civID) {
            selectedFilter = activeFilter
            deliberateFilterSwitch = true
            followLive = true
        }
        updateNationPicker(activity, active)
        val viewedCiv = when (selectedFilter.mode) {
            FilterMode.ALL -> null
            FilterMode.NATION -> selectedFilter.civId
            FilterMode.FOLLOW_ACTIVE -> if (!spectator) viewer.civID else if (followLive || effectiveCivId == null) {
                lastActiveAgentCivId ?: activity.lastOrNull()?.civId
            } else effectiveCivId
        }
        val filterKey = "${selectedFilter.mode}/${viewedCiv.orEmpty()}"
        val switched = deliberateFilterSwitch || filterKey != effectiveFilterKey
        deliberateFilterSwitch = false
        effectiveFilterKey = filterKey
        effectiveCivId = viewedCiv
        if (switched) {
            unreadCount = 0
            visibleLimit = 120
            shownEntries = emptyList()
        }
        val activityState = when {
            !followLive -> "Reading"
            viewedCiv == null || viewedCiv == active -> state
            session.getRunState() in setOf(AgentRunState.PAUSED, AgentRunState.PAUSING, AgentRunState.FINISHED) -> state
            else -> "Recent activity"
        }
        stateLabel.setText(if (selectedFilter.mode == FilterMode.ALL) "$state · ${active?.let(::agentName) ?: "Agents"}"
            else "${viewedCiv ?: "Agent"} activity · $activityState")
        stateLabel.setFontColor((viewedCiv ?: active)?.let { AgentActivityAppearance.color(screen.gameInfo, it) } ?: muted)
        val latest = when {
            selectedFilter.mode == FilterMode.ALL -> activity
            viewedCiv != null -> activity.filter { it.civId == viewedCiv }
            else -> emptyList()
        }
        val changed = latest != shownEntries
        if (changed) {
            if (!followLive) {
                val oldIds = shownEntries.map { it.id }.toSet()
                val added = latest.count { it.id !in oldIds }
                visibleLimit += added
                unreadCount += added
            }
            shownEntries = latest
        }
        if (changed || resized || switched) rebuild()
        if (followLive && (changed || resized || switched)) jumpToLive()
        updateFooter()
    }

    private fun updateNationPicker(activity: List<AgentActivityEntry>, activeCivId: String?) {
        val viewer = screen.viewingCiv
        // Both native and agent turn rotation traverse this saved list, not alphabetical names.
        val majorNations = screen.gameInfo.civilizations.filter {
            it.isMajorCiv() && (viewer.isSpectator() || it.civID == viewer.civID)
        }
        val majorIds = majorNations.map { it.civID }.toSet()
        val agentNations = majorIds.filter { session.getAgentProfile(it) != null }
        val historicalNations = activity.map { it.civId }.toSet().filter { it in majorIds }
        val selectedNation = selectedFilter.civId?.takeIf { it in majorIds }?.let { setOf(it) }.orEmpty()
        val included = (agentNations + historicalNations + selectedNation).toSet()
        val livingNations = majorNations.filterNot { it.isDefeated() }
        val activeIndex = livingNations.indexOfFirst { it.civID == activeCivId }
        // This marks the cyclic roster predecessor, not a claim that a provider ended its turn.
        val previousCivId = if (activeIndex >= 0 && livingNations.size > 1)
            livingNations[(activeIndex + livingNations.size - 1) % livingNations.size].civID else null
        val nations = majorNations.mapIndexedNotNull { index, civ ->
            val id = civ.civID
            if (id !in included) return@mapIndexedNotNull null
            val name = agentName(id)
            val identity = if (name == id) id else "$id · $name"
            val marker = when {
                id == activeCivId -> "Current · "
                civ.isDefeated() -> "Eliminated · "
                id == previousCivId -> "Previous in order · "
                else -> ""
            }
            NationFilter(FilterMode.NATION, "${index + 1}. $marker$identity", id)
        }
        val choices = listOf(activeFilter, allFilter) + nations
        if (nationPicker.items.toList() == choices) return
        updatingPicker = true
        try {
            nationPicker.setItems(GdxArray(choices.toTypedArray()))
            nationPicker.selected = choices.firstOrNull { it.mode == selectedFilter.mode && it.civId == selectedFilter.civId }
                ?: selectedFilter
        } finally { updatingPicker = false }
    }

    private fun updateFooter() {
        liveLink.isChecked = followLive
        liveLink.setText(if (followLive || unreadCount == 0) "Live" else "$unreadCount new")
        WorldScreenStyle.setEnabled(earlier, shownEntries.size > visibleLimit)
        WorldScreenStyle.setEnabled(logsLink, session.getActivityDirectory() != null)
    }

    private fun agentName(civId: String) = session.getAgentProfile(civId)?.displayName?.takeIf { it.isNotBlank() } ?: civId

    private fun body(entry: AgentActivityEntry): String {
        val cached = bodies[entry.id]
        if (cached?.first == entry.timestamp) return cached.second
        return session.getActivityDetails(entry.id).also { bodies[entry.id] = entry.timestamp to it }
    }

    private fun text(value: String, color: Color, size: Int) = value.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        .toLabel(color, size, hideIcons = true).apply { wrap = true; setAlignment(Align.topLeft) }

    // Translation normalizes numeric tokens (01:05 -> 1:5); journal durations are fixed-format text.
    private fun runText(value: String, color: Color, size: Int) = text(value, color, size).apply { setText(value.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")) }

    /** A message, error, turn boundary or different agent ends a run of tool calls. */
    private fun groupTools(entries: List<AgentActivityEntry>): List<FeedRow> {
        val rows = ArrayList<FeedRow>()
        var index = 0
        while (index < entries.size) {
            val entry = entries[index]
            if (entry.kind != "tool" || entry.status == "failed") {
                rows.add(FeedRow.Event(entry)); index++; continue
            }
            val run = ArrayList<AgentActivityEntry>()
            while (index < entries.size) {
                val next = entries[index]
                if (next.kind != "tool" || next.status == "failed" || next.civId != entry.civId || next.turnSerial != entry.turnSerial) break
                run.add(next); index++
            }
            rows.add(FeedRow.Tools(run))
        }
        return rows
    }

    private fun rowTop(actor: Actor): Float = entriesTable.height - actor.localToAscendantCoordinates(entriesTable, Vector2()).y - actor.height

    private fun readingAnchor(): ReadingAnchor? = rowAnchors.entries
        .sortedBy { rowTop(it.value) }
        .firstOrNull { rowTop(it.value) + it.value.height > scroll.scrollY }
        ?.let { ReadingAnchor(it.key, scroll.scrollY - rowTop(it.value)) }

    private fun addRow(actor: Actor, key: String, width: Float, top: Float = 0f, bottom: Float = 0f, inset: Float = 0f) {
        rowAnchors[key] = actor
        entriesTable.add(actor).width(width - inset).left().padTop(top).padBottom(bottom).padLeft(inset).row()
    }

    private fun rebuild() {
        val oldScroll = scroll.scrollY
        val anchor = if (!followLive) readingAnchor() else null
        val rowWidth = (panelWidth - 38f).coerceAtLeast(120f)
        entriesTable.clear()
        rowAnchors.clear()
        val entries = shownEntries.takeLast(visibleLimit)
        val ids = entries.map { it.id }.toSet()
        bodies.keys.retainAll(ids)
        expanded.retainAll(ids)
        expandedRuns.retainAll(ids)
        val names = entries.map { it.civId }.distinct().associateWith(::agentName)
        var previousTurn = ""
        for (row in groupTools(entries)) {
            val entry = when (row) { is FeedRow.Event -> row.entry; is FeedRow.Tools -> row.entries.first() }
            val turn = "${entry.civId}/${entry.turnSerial}"
            if (turn != previousTurn) {
                val color = AgentActivityAppearance.color(screen.gameInfo, entry.civId)
                val heading = Table()
                val civ = screen.gameInfo.civilizations.firstOrNull { it.civID == entry.civId }
                if (civ != null) heading.add(ImageGetter.getNationPortrait(civ.nation, 20f)).size(20f).padRight(8f).top()
                val identity = Table()
                identity.add(text(names[entry.civId] ?: entry.civId, color, 14)).growX().left().row()
                identity.add(text("${entry.civId} · Turn ${entry.turnSerial}", muted, 11)).growX().left().padTop(3f)
                heading.add(identity).growX()
                addRow(heading, "turn/$turn", rowWidth, top = if (previousTurn.isEmpty()) 4f else 18f, bottom = 10f)
                previousTurn = turn
            }
            when (row) {
                is FeedRow.Tools -> {
                    addSpeaker(entry, "Tools", rowWidth)
                    renderToolRun(row.entries, rowWidth)
                }
                is FeedRow.Event -> when (entry.kind) {
                    "message" -> {
                        addSpeaker(entry, null, rowWidth)
                        addRow(text(body(entry), ink, 14), entry.id, rowWidth, bottom = 14f)
                    }
                    "reasoning" -> {
                        addSpeaker(entry, "Thinking", rowWidth)
                        addRow(text(body(entry), muted, GameUiStyle.Type.body), entry.id, rowWidth, bottom = 12f)
                    }
                    "run" -> {
                        val failed = entry.status == "failed"
                        val caption = when (entry.status) {
                            "failed" -> "Run failed"
                            "completed_turn", "completed" -> "Run completed"
                            "incomplete_turn" -> "Run ended without ending turn"
                            "guard_paused" -> "Run paused"
                            "cancelled" -> "Run stopped by takeover"
                            else -> "Run exited"
                        }
                        addSpeaker(entry, caption, rowWidth)
                        val color = if (failed) WorldScreenStyle.error else AgentActivityAppearance.color(screen.gameInfo, entry.civId)
                        addRow(runText(entry.title, color, 12), entry.id, rowWidth, bottom = 4f)
                        if (entry.summary.isNotBlank())
                            addRow(runText(entry.summary, color, 11), "summary/${entry.id}", rowWidth, bottom = 12f)
                    }
                    else -> {
                        addSpeaker(entry, if (entry.kind == "error" || entry.status == "failed") "Error" else "Tools", rowWidth)
                        renderTool(entry, rowWidth)
                    }
                }
            }
        }
        if (entries.isEmpty()) addRow(text("Waiting for the agent…", muted, 13), "empty", rowWidth)
        updateFooter()
        invalidateHierarchy()
        validate()
        scroll.layout()
        val retained = anchor?.let { mark -> rowAnchors[mark.key]?.let { rowTop(it) + mark.offset } }
        scroll.scrollY = (retained ?: oldScroll).coerceIn(0f, scroll.maxY)
        scroll.updateVisualScroll()
    }

    private fun addSpeaker(entry: AgentActivityEntry, kind: String?, width: Float) {
        val label = agentName(entry.civId) + if (kind == null) "" else " · $kind"
        addRow(text(label, AgentActivityAppearance.color(screen.gameInfo, entry.civId), if (kind == null) 13 else 12),
            "speaker/${entry.id}", width, bottom = 5f)
    }

    private fun toolButton(title: String, entry: AgentActivityEntry, opened: Boolean, action: () -> Unit): Button {
        val failed = entry.status == "failed" || entry.kind == "error"
        val color = if (failed) WorldScreenStyle.error else muted
        val button = Button(BaseScreen.skin).apply {
            style = Button.ButtonStyle().apply {
                over = WorldScreenStyle.background("WorldScreen/AgentActivityFeed/ToolHover", WorldScreenStyle.raised)
                down = WorldScreenStyle.background("WorldScreen/AgentActivityFeed/ToolDown", WorldScreenStyle.pressed)
                checked = WorldScreenStyle.background("WorldScreen/AgentActivityFeed/ToolOpen", WorldScreenStyle.raised)
                focused = GameUiStyle.outlinedBackground("WorldScreen/AgentActivityFeed/ToolFocused",
                    WorldScreenStyle.raised, WorldScreenStyle.accent)
            }
            isChecked = opened
            pad(5f, 0f, 5f, 2f)
        }
        val icon = when {
            failed -> "OtherIcons/ExclamationMark"
            entry.status == "running" -> "OtherIcons/Loading"
            else -> "OtherIcons/Checkmark"
        }
        button.add(ImageGetter.getImage(icon).apply { this.color = color }).size(12f).padRight(7f)
        button.add(title.toLabel(color, 12, hideIcons = true).apply { setEllipsis(true) }).minWidth(0f).growX().left()
        button.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply {
            this.color = muted; setSize(9f, 9f); setOrigin(Align.center); rotation = if (opened) -90f else 0f
        }).size(9f).padLeft(6f)
        button.onClick { holdPosition(); action(); rebuild() }
        return button
    }

    private fun renderToolRun(run: List<AgentActivityEntry>, width: Float) {
        if (run.size == 1) { renderTool(run.single(), width); return }
        val opened = run.any { it.id in expandedRuns || it.id in expanded }
        val latest = run.last()
        val title = if (opened) "${run.size} tool calls" else latest.title
        val toggleRun = {
            if (opened) { expandedRuns.removeAll(run.map { it.id }.toSet()); expanded.removeAll(run.map { it.id }.toSet()) }
            else expandedRuns.addAll(run.map { it.id })
            Unit
        }
        val button = toolButton(title, latest, opened, toggleRun)
        addRow(button, "run/${run.first().id}", width)
        if (opened) {
            for (entry in run) renderTool(entry, width, inset = 12f)
        } else {
            val running = run.count { it.status == "running" }
            val count = "${run.size} tool calls · " + if (running > 0) "$running running" else "Expand"
            val countLabel = text(count, muted, 11).apply {
                touchable = Touchable.enabled
                onClick { holdPosition(); toggleRun(); rebuild() }
            }
            addRow(countLabel, "count/${run.first().id}", width, bottom = 8f, inset = 19f)
        }
    }

    private fun renderTool(entry: AgentActivityEntry, width: Float, inset: Float = 0f) {
        val opened = entry.id in expanded
        addRow(toolButton(entry.title, entry, opened) {
            if (!expanded.add(entry.id)) expanded.remove(entry.id)
        }, entry.id, width, inset = inset)
        if (entry.summary.isNotBlank()) {
            val color = if (entry.status == "failed" || entry.kind == "error") WorldScreenStyle.error else muted
            addRow(text(entry.summary, color, 11), "summary/${entry.id}", width, bottom = 6f, inset = inset + 19f)
        }
        if (opened) {
            addRow(text(body(entry), ink, 11), "details/${entry.id}", width, bottom = 6f, inset = inset)
            val copy = WorldScreenStyle.button("Copy details").apply { label.setFontSize(11) }
            copy.onClick { Gdx.app.clipboard.contents = body(entry) }
            entriesTable.add(copy).left().padLeft(inset).padBottom(8f).row()
        }
    }

    private fun jumpToLive() {
        scroll.layout()
        scroll.velocityY = 0f
        scroll.scrollY = scroll.maxY
        scroll.updateVisualScroll()
        Gdx.graphics.requestRendering()
    }

    override fun dispose() {
        disposed = true
        refreshTask.cancel()
        setShown(false)
        remove()
    }
}
