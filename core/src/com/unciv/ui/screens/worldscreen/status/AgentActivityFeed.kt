package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.List as GdxList
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Array as GdxArray
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Timer
import com.unciv.UncivGame
import com.unciv.logic.agents.AgentActivityEntry
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.formatAgentRunDuration
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ReadingScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.HudWindow
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import java.io.File

/** Floating transcript, read like a chat: a header per agent turn, the agent's messages as cards,
 *  thinking as a quieter block (collapsed to its headline once a newer thought arrives), runs of
 *  tool calls collapsed to the latest call, and live following that resumes at the bottom. */
class AgentActivityFeed(private val screen: WorldScreen, private val session: AgentMatchSession,
                        private val controlsAnchor: Actor? = null) : Table(BaseScreen.skin), Disposable, AgentReadingText.Reader {
    private val atlas = GameUiStyle.isAtlas
    private val muted = WorldScreenStyle.muted
    private val ink = WorldScreenStyle.ink
    private val entriesTable = Table(skin)
    private val scroll = ReadingScrollPane(entriesTable, skin)
    private val stateLabel = "".toLabel(muted, 12).apply { name = "ActivityState" }
    private val identityLabel = "".toLabel(ink, GameUiStyle.Type.label).apply { name = "ActivityIdentity"; wrap = true }
    private val elapsedLabel = "".toLabel(muted, GameUiStyle.Type.caption).apply { name = "ActivityElapsed" }
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
    // Atlas: Earlier is a quiet row atop the entries, Live a floating "↓ N new" chip, Logs in the window menu.
    private val liveLink = if (atlas) WorldScreenStyle.accentButton("Live", GameUiStyle.ButtonSize.Compact).apply {
        val arrow = ImageGetter.getImage("OtherIcons/ArrowRight").apply {
            color = WorldScreenStyle.surface; setSize(10f, 10f); setOrigin(Align.center); rotation = -90f
        }
        clearChildren()
        add(arrow).size(10f).padRight(GameUiStyle.Space.s)
        add(label)
        pack()
    } else WorldScreenStyle.button("Live").apply { label.setFontSize(11) }
    private val earlier = if (atlas) WorldScreenStyle.quietButton("Earlier", GameUiStyle.ButtonSize.Compact)
        else WorldScreenStyle.button("Earlier").apply { label.setFontSize(11) }
    private val logsLink = WorldScreenStyle.button("Logs").apply { label.setFontSize(11) }
    /** Atlas: one muted line, shown only while the filter shows a nation other than the active one. */
    private val contextLabel = "".toLabel(muted, GameUiStyle.Type.caption).apply { name = "ActivityContext"; setEllipsis(true); isVisible = false }
    private var contextCell: com.badlogic.gdx.scenes.scene2d.ui.Cell<*>? = null
    private val expanded = HashSet<String>()
    private val expandedRuns = HashSet<String>()
    /** Thinking the reader opened (expanded) or folded (collapsed) by hand, overriding the default. */
    private val thinkingOpened = HashSet<String>()
    private val thinkingFolded = HashSet<String>()
    private val bodies = HashMap<String, Pair<String, String>>()
    private val rowAnchors = LinkedHashMap<String, Actor>()
    private var shownEntries = emptyList<AgentActivityEntry>()
    private var visibleLimit = 120
    private var historyTailOffset = 0
    private var viewerId: String? = null
    private var unreadCount = 0
    private var followLive = true
    /** The reader scrolled or dragged since following stopped: reaching the bottom resumes it. */
    private var resumeAtBottom = false
    private var panelWidth = 0f
    private var panelHeight = 0f
    private var disposed = false
    // Declared before init: initializers after it would overwrite what construction sets.
    private var atlasViewport: Rectangle? = null
    private var atlasTabVisible = true
    private var atlasUserVisible = true

    private val window = HudWindow(this, "activity", "Activity", Vector2(260f, 220f),
        defaultBounds = ::defaultBounds,
        show = { setShown(true) }, hide = { setShown(false) },
        onResized = { panelWidth = width; panelHeight = height; rebuild(); if (followLive) jumpToLive() },
        onArranged = { if (atlas) screen.relayoutHud() },
        menuExtras = {
            AgentReadingText.menuRow(this)
            if (session.getActivityDirectory() != null) {
                val logs = WorldScreenStyle.quietButton("Open logs folder", GameUiStyle.ButtonSize.Compact)
                logs.onClick { openLogs() }
                add(logs).padTop(GameUiStyle.Space.m).row()
            }
        })

    /** The user left the feed in its default left-hand column (the HUD flows around it there). */
    val isDocked get() = !window.isPlaced

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
        name = "AgentActivityFeed"
        background = if (atlas) WorldScreenStyle.surface("WorldScreen/AgentActivityFeed", 0.94f)
            else WorldScreenStyle.panelBackground("WorldScreen/AgentActivityFeed")
        touchable = Touchable.enabled
        if (atlas) pad(GameUiStyle.Space.l) else pad(12f, 14f, 12f, 14f)
        val close = AgentPanelLayout.closeButton { setShown(false) }
        val header = Table(skin)
        header.add((if (atlas) "Activity" else "Agent activity").toLabel(ink, if (atlas) GameUiStyle.Type.label + 1 else 17))
            .left().expandX()
        header.add(window.controls())
        header.add(close).size(if (atlas) 28f else 24f).padLeft(if (atlas) GameUiStyle.Space.xs else 8f)
        window.attachHeader(header)
        add(header).growX().row()
        nationPicker.style = SelectBox.SelectBoxStyle(nationPicker.style).apply {
            background = if (atlas) chip("WorldScreen/AgentActivityFeed/Filter", WorldScreenStyle.raised)
                else WorldScreenStyle.background("WorldScreen/AgentActivityFeed/Filter", WorldScreenStyle.raised)
            backgroundOver = if (atlas) chip("WorldScreen/AgentActivityFeed/FilterHover", WorldScreenStyle.hover)
                else WorldScreenStyle.background("WorldScreen/AgentActivityFeed/FilterHover", WorldScreenStyle.hover)
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
        val filterArrow = ImageGetter.getImage("OtherIcons/ArrowRight").apply {
            color = muted
            setSize(10f, 10f)
            setOrigin(Align.center)
            rotation = -90f
            touchable = Touchable.enabled
            onClick { nationPicker.showScrollPane() }
        }
        if (atlas) {
            // The nation chip sits under the title; the card owns state and the timer.
            nationPicker.fontSize = GameUiStyle.Type.caption
            val chipHeight = 28f * UncivGame.Current.settings.fontSizeMultiplier
            val arrowHost = Container(filterArrow).apply { right().padRight(GameUiStyle.Space.l); touchable = Touchable.childrenOnly }
            // SelectBox otherwise takes the longest nation's name as its minimum width.
            add(Stack(nationPicker, arrowHost)).minWidth(0f).growX().height(chipHeight).padTop(GameUiStyle.Space.s).row()
            contextCell = add(contextLabel).minWidth(0f).growX().left().height(0f)
            row()
            entriesTable.top().left()
        } else {
            val filterRow = Table(skin)
            // SelectBox otherwise takes the longest nation's name as its minimum width.
            filterRow.add(nationPicker).minWidth(0f).growX()
            filterRow.add(filterArrow).size(10f).padLeft(8f)
            add(filterRow).minWidth(0f).growX().padTop(8f).row()
            add(identityLabel).minWidth(0f).growX().left().padTop(8f).row()
            add(elapsedLabel).minWidth(0f).growX().left().padTop(3f).row()
            stateLabel.wrap = true
            add(stateLabel).growX().left().padTop(5f).padBottom(10f).row()
            entriesTable.top().left()
        }
        scroll.setScrollingDisabled(true, false)
        scroll.setFadeScrollBars(false)
        scroll.setOverscroll(false, false)
        scroll.setSmoothScrolling(false)
        scroll.setFlingTime(0.2f)
        scroll.addCaptureListener(object : InputListener() {
            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean {
                // A transcript that still fits has nothing to read back to: keep following.
                if (scroll.maxY > 0f) { holdPosition(); resumeAtBottom = true }
                return false
            }
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                // Dragging back down resumes following too; expanding an entry (its click holds again) does not.
                holdPosition(); resumeAtBottom = true
                return false
            }
        })
        add(scroll).grow().padTop(if (atlas) GameUiStyle.Space.m else 0f).row()
        earlier.onClick {
            holdPosition()
            if (visibleLimit < 600) visibleLimit += 120 else historyTailOffset = minOf(historyTailOffset + 120, (shownEntries.size - visibleLimit).coerceAtLeast(0))
            rebuild()
        }
        liveLink.onClick {
            followLive = true; unreadCount = 0; historyTailOffset = 0; visibleLimit = 120
            rebuild(); refresh(); jumpToLive(); updateFooter()
        }
        logsLink.onClick { openLogs() }
        if (atlas) {
            // Floats over the bottom of the transcript; positioned in layout(), never a cell.
            liveLink.isVisible = false
            addActor(liveLink)
        } else {
            val footer = Table(skin)
            footer.add(earlier).left()
            footer.add(liveLink).expandX().center()
            footer.add(logsLink).right()
            add(footer).growX().padTop(12f)
        }
        screen.stage.addActor(this)
        // Left minimized last time: start hidden. Its pill appears once the HUD gives the feed its spot.
        if (window.startsMinimized()) { atlasUserVisible = false; isVisible = false }
        refresh()
        if (!atlas) window.setPillShown(true)
        Timer.schedule(refreshTask, 0.35f, 0.35f)
    }

    private fun openLogs() {
        session.getActivityDirectory()?.let { Gdx.net.openURI(File(it).toURI().toString()) }
    }

    /** A compact pill whose right inset leaves room for the dropdown arrow drawn over it. */
    private fun chip(path: String, color: Color) = WorldScreenStyle.pill(path, color).also {
        if (it is BaseDrawable) it.rightWidth = 30f
    }

    override fun layout() {
        super.layout()
        if (!atlas) return
        liveLink.pack()
        liveLink.setPosition(((width - liveLink.width) / 2f).coerceAtLeast(0f), padBottom + GameUiStyle.Space.m)
        liveLink.toFront()
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (!isVisible) return
        // Scrolling back down to the newest entry resumes following, as in chat apps.
        if (!followLive && resumeAtBottom && historyTailOffset == 0 && !scroll.isDragging &&
            scroll.scrollY >= scroll.maxY - 1f) {
            followLive = true; resumeAtBottom = false; unreadCount = 0
            updateFooter()
        }
        if (followLive) {
            // Layout/streaming growth must never leave Live accumulating a scroll backlog.
            validate()
            scroll.validate()
            scroll.scrollY = scroll.maxY
            scroll.updateVisualScroll()
        }
    }

    /** A collapsed viewport keeps filter, reading position and the user's visibility preference. */
    fun setAtlasViewport(bounds: Rectangle, shown: Boolean) {
        atlasViewport = Rectangle(bounds)
        atlasTabVisible = shown
        isVisible = atlasUserVisible && shown
        // A compact tab or a hidden HUD hides the pill and the window menu with the feed.
        window.setPillShown(shown)
        if (!isVisible) window.closeMenu()
        if (isVisible) refresh()
    }

    fun show() = setShown(true)
    fun toggle() = setShown(if (atlasViewport != null) !atlasUserVisible else !isVisible)
    fun hide() {
        setShown(false); nationPicker.hideScrollPane()
        if (viewerId != screen.viewingCiv.civID) refresh()
    }

    private fun setShown(shown: Boolean) {
        val wasVisible = isVisible
        atlasUserVisible = shown
        isVisible = shown && (atlasViewport == null || atlasTabVisible)
        if (!shown && screen.stage.scrollFocus?.isDescendantOf(this) == true) screen.stage.scrollFocus = screen.mapHolder
        if (shown) { window.onShown(); toFront(); refresh(); if (followLive) jumpToLive() }
        else window.onHidden()
        // The left column and the selection band reflow around the sheet.
        if (atlas && !disposed && wasVisible != isVisible) screen.relayoutHud()
        Gdx.graphics.requestRendering()
    }

    private fun holdPosition() {
        followLive = false
        resumeAtBottom = false
        scroll.velocityY = 0f
        updateFooter()
    }

    /** The default spot: the HUD's left column on Atlas, else below the tech buttons on the left. */
    private fun defaultBounds(): Rectangle {
        atlasViewport?.let { return Rectangle(it) }
        val width = minOf(300f, maxOf(230f, screen.stage.width * 0.26f), screen.stage.width - 20f)
        val controls = controlsAnchor?.takeIf { it.stage != null && it.height > 0f }
        val controlsBottom = controls?.localToStageCoordinates(Vector2())
        val overlapsControls = controls != null && controlsBottom != null && controlsBottom.x < 10f + width && controlsBottom.x + controls.width > 10f
        val top = minOf(screen.techPolicyAndDiplomacy.y - 8f,
            if (overlapsControls) controlsBottom.y - 8f else Float.MAX_VALUE)
        val height = (top - 16f).coerceAtLeast(160f).coerceAtMost(screen.stage.height - 32f)
        return Rectangle(10f, (top - height).coerceAtLeast(8f), width, height)
    }

    override fun onReadingSizeChanged() { if (!disposed) { rebuild(); if (followLive) jumpToLive() } }

    fun refresh() {
        if (disposed) return
        val viewer = screen.viewingCiv
        if (viewerId != viewer.civID) {
            // Authority changes discard every cached private actor before the new view draws.
            viewerId = viewer.civID
            bodies.clear(); expanded.clear(); expandedRuns.clear(); rowAnchors.clear()
            thinkingOpened.clear(); thinkingFolded.clear()
            entriesTable.clearChildren(); shownEntries = emptyList()
            effectiveFilterKey = null; effectiveCivId = null; lastActiveAgentCivId = null
            historyTailOffset = 0; visibleLimit = 120; unreadCount = 0
            followLive = true
            nationPicker.hideScrollPane()
            selectedFilter = activeFilter
        }
        val bounds = window.bounds()
        val resized = bounds.width != panelWidth || bounds.height != panelHeight
        if (resized) { panelWidth = bounds.width; panelHeight = bounds.height }
        window.place()
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
            historyTailOffset = 0
            shownEntries = emptyList()
        }
        if (atlas) setContext(if (selectedFilter.mode != FilterMode.ALL && viewedCiv != null && viewedCiv != active)
            "$viewedCiv · recent activity" else null)
        else {
            val state = AgentStatusFormat.label(session.getRunState())
            val activityState = when {
                !followLive -> "Reading"
                viewedCiv == null || viewedCiv == active -> state
                session.getRunState() in setOf(AgentRunState.PAUSED, AgentRunState.PAUSING, AgentRunState.FINISHED) -> state
                else -> "Recent activity"
            }
            identityLabel.setText(if (selectedFilter.mode == FilterMode.ALL) "All nations" else
                viewedCiv?.let { "${agentName(it)} · $it" } ?: "Agent activity")
            identityLabel.setFontColor((viewedCiv ?: active)?.let { AgentActivityAppearance.color(screen.gameInfo, it) } ?: ink)
            stateLabel.setText(if (selectedFilter.mode == FilterMode.ALL) "$state · ${active?.let(::agentName) ?: "Agents"}" else "${viewedCiv ?: "Agent"} activity · $activityState")
            val timing = session.getActiveRunTiming()?.takeIf { (spectator || it.civId == viewer.civID) && (viewedCiv == null || it.civId == viewedCiv) }
            elapsedLabel.setText(timing?.let { "Elapsed ${formatAgentRunDuration(it.elapsedMillis)}" } ?: "No active provider run")
        }
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
                val previousLimit = visibleLimit
                visibleLimit = (visibleLimit + added).coerceAtMost(600)
                historyTailOffset += added - (visibleLimit - previousLimit)
                unreadCount += added
            }
            shownEntries = latest
        }
        if (changed || resized || switched) rebuild()
        if (followLive && (changed || resized || switched)) jumpToLive()
        updateFooter()
    }

    private fun updateNationPicker(activity: List<AgentActivityEntry>, activeCivId: String?) {
        val roster = com.unciv.ui.screens.worldscreen.topbar.NationTurnRoster.read(screen.gameInfo, screen.viewingCiv, activeCivId)
        val majorNations = roster.map { it.civ }
        val majorIds = majorNations.map { it.civID }.toSet()
        val agentNations = majorIds.filter { session.getAgentProfile(it) != null }
        val historicalNations = activity.map { it.civId }.toSet().filter { it in majorIds }
        val selectedNation = selectedFilter.civId?.takeIf { it in majorIds }?.let { setOf(it) }.orEmpty()
        val included = (agentNations + historicalNations + selectedNation).toSet()
        val nations = majorNations.mapIndexedNotNull { index, civ ->
            val id = civ.civID
            if (id !in included) return@mapIndexedNotNull null
            val name = agentName(id)
            val identity = if (name == id) id else "$id · $name"
            val marker = roster[index].marker.takeIf { it.isNotEmpty() && it != "Next in order" }?.let { "$it · " }.orEmpty()
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
        // The floating chip appears only while reading; following needs no control.
        if (atlas) liveLink.isVisible = !followLive
        WorldScreenStyle.setEnabled(earlier, shownEntries.size > visibleLimit + historyTailOffset)
        WorldScreenStyle.setEnabled(logsLink, session.getActivityDirectory() != null)
    }

    private fun setContext(text: String?) {
        val shown = text != null
        if (contextLabel.isVisible == shown && contextLabel.text.toString() == (text ?: "")) return
        contextLabel.isVisible = shown
        contextLabel.setText(text ?: "")
        contextCell?.height(if (shown) contextLabel.prefHeight else 0f)?.padTop(if (shown) GameUiStyle.Space.s else 0f)
        invalidateHierarchy()
    }

    private fun agentName(civId: String) = session.getAgentProfile(civId)?.displayName?.takeIf { it.isNotBlank() } ?: civId

    private fun body(entry: AgentActivityEntry): String {
        val cached = bodies[entry.id]
        if (cached?.first == entry.timestamp) return cached.second
        return session.getActivityDetails(entry.id).also { bodies[entry.id] = entry.timestamp to it }
    }

    private fun size(base: Int) = AgentReadingText.size(base)

    /** A wrapped label already [width] wide, so its first measured height is its real one. */
    private fun text(value: String, color: Color, size: Int, width: Float? = null) = plain(value)
        .toLabel(color, size, hideIcons = true).apply {
            wrap = true; setAlignment(Align.topLeft)
            if (width != null) { this.width = width; invalidate() }
        }

    // Translation normalizes numeric tokens (01:05 -> 1:5); journal durations are fixed-format text.
    private fun runText(value: String, color: Color, size: Int, width: Float? = null) =
        text(value, color, size, width).apply { setText(plain(value)) }

    /** Markdown emphasis markers read as noise in a plain label. */
    private fun plain(value: String) = value.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")

    /** Blank-line paragraphs, with "- " bullets drawn as "•". */
    private fun paragraphs(body: String): List<String> = body.trim().split(Regex("\\n\\s*\\n"))
        .map { paragraph -> paragraph.lines().joinToString("\n") { it.replace(Regex("^(\\s*)[-*] "), "$1• ") }.trim() }
        .filter { it.isNotEmpty() }

    /** A first paragraph that is entirely **bold** is the thought's headline. */
    private fun headline(paragraphs: List<String>): Pair<String?, List<String>> {
        val first = paragraphs.firstOrNull() ?: return null to paragraphs
        val match = Regex("^\\*\\*(.+?)\\*\\*$").matchEntire(first.trim()) ?: return null to paragraphs
        return match.groupValues[1] to paragraphs.drop(1)
    }

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
        if (atlas) {
            // Older history is one quiet row above the oldest shown entry, present only when it exists.
            val older = shownEntries.size > visibleLimit + historyTailOffset
            earlier.isVisible = older
            entriesTable.add(earlier).left().height(if (older) earlier.prefHeight else 0f)
                .padBottom(if (older) GameUiStyle.Space.m else 0f).row()
        }
        val entries = shownEntries.dropLast(historyTailOffset.coerceAtMost(shownEntries.size)).takeLast(visibleLimit)
        val ids = entries.map { it.id }.toSet()
        bodies.keys.retainAll(ids)
        expanded.retainAll(ids)
        expandedRuns.retainAll(ids)
        thinkingOpened.retainAll(ids)
        thinkingFolded.retainAll(ids)
        val names = entries.map { it.civId }.distinct().associateWith(::agentName)
        // The newest thought of each agent stays open while it is being written.
        val liveThinking = entries.filter { it.kind == "reasoning" }.groupBy { it.civId }.values.map { it.last().id }.toSet()
        var previousTurn = ""
        for (row in groupTools(entries)) {
            val entry = when (row) { is FeedRow.Event -> row.entry; is FeedRow.Tools -> row.entries.first() }
            val turn = "${entry.civId}/${entry.turnSerial}"
            if (turn != previousTurn) {
                addRow(turnHeading(entry, names[entry.civId] ?: entry.civId, rowWidth, first = previousTurn.isEmpty()),
                    "turn/$turn", rowWidth, top = if (previousTurn.isEmpty()) 2f else 14f, bottom = 10f)
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
                        addRow(messageCard(entry, rowWidth), entry.id, rowWidth, bottom = 12f)
                    }
                    "reasoning" -> {
                        addSpeaker(entry, "Thinking", rowWidth)
                        addRow(thinkingBlock(entry, rowWidth, entry.id in liveThinking), entry.id, rowWidth, bottom = 12f)
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
                        addRow(runText(entry.title, color, size(12), rowWidth), entry.id, rowWidth, bottom = 4f)
                        if (entry.summary.isNotBlank())
                            addRow(runText(entry.summary, color, size(11), rowWidth), "summary/${entry.id}", rowWidth, bottom = 12f)
                    }
                    else -> {
                        addSpeaker(entry, if (entry.kind == "error" || entry.status == "failed") "Error" else "Tools", rowWidth)
                        renderTool(entry, rowWidth)
                    }
                }
            }
        }
        if (entries.isEmpty()) addRow(text("Waiting for the agent…", muted, size(13), rowWidth), "empty", rowWidth, top = 8f)
        updateFooter()
        invalidateHierarchy()
        validate()
        scroll.layout()
        val retained = anchor?.let { mark -> rowAnchors[mark.key]?.let { rowTop(it) + mark.offset } }
        scroll.scrollY = (retained ?: oldScroll).coerceIn(0f, scroll.maxY)
        scroll.updateVisualScroll()
    }

    /** Portrait, agent name and "Rome · Turn 12", with a hairline above every turn but the first. */
    private fun turnHeading(entry: AgentActivityEntry, name: String, width: Float, first: Boolean): Table {
        val color = AgentActivityAppearance.color(screen.gameInfo, entry.civId)
        val heading = Table()
        if (!first) heading.add(Table().apply { background = hairline() }).colspan(2).growX().height(1f).padBottom(12f).row()
        val civ = screen.gameInfo.civilizations.firstOrNull { it.civID == entry.civId }
        val portrait = 24f
        if (civ != null) heading.add(ImageGetter.getNationPortrait(civ.nation, portrait)).size(portrait).padRight(8f).top()
        val textWidth = width - if (civ != null) portrait + 8f else 0f
        val identity = Table()
        identity.add(text(name, color, size(14), textWidth)).width(textWidth).left().row()
        identity.add(text("${entry.civId} · Turn ${entry.turnSerial}", muted, size(11), textWidth)).width(textWidth).left().padTop(2f)
        heading.add(identity).width(textWidth).left()
        return heading
    }

    private fun hairline() = ImageGetter.getWhiteDotDrawable().tint(Color(ink).apply { a = 0.12f })

    /** The agent's own words: the brightest text, on a quiet card, one label per paragraph. */
    private fun messageCard(entry: AgentActivityEntry, width: Float): Table {
        val card = Table()
        card.background = WorldScreenStyle.control("WorldScreen/AgentActivityFeed/Message", Color(WorldScreenStyle.raised).apply { a = 0.5f })
        val padX = 12f
        card.pad(10f, padX, 11f, padX)
        val inner = width - 2 * padX
        val paragraphs = paragraphs(body(entry)).ifEmpty { listOf("") }
        for ((index, paragraph) in paragraphs.withIndex()) {
            val heading = Regex("^\\*\\*(.+?)\\*\\*$").matchEntire(paragraph)?.groupValues?.get(1)
            val label = if (heading != null) text(heading, ink, size(15), inner) else text(paragraph, ink, size(14), inner)
            card.add(label).width(inner).left().padTop(if (index == 0) 0f else 7f).row()
        }
        return card
    }

    /** Provider reasoning: a muted block behind a thin rule. Older thoughts fold to their headline
     *  and a short preview; a click opens or folds any of them. */
    private fun thinkingBlock(entry: AgentActivityEntry, width: Float, live: Boolean): Table {
        val (title, rest) = headline(paragraphs(body(entry)))
        val open = entry.id in thinkingOpened || (live && entry.id !in thinkingFolded)
        val block = Table()
        block.background = LeftRule(Color(muted).apply { a = 0.35f })
        val inset = 12f
        block.pad(1f, inset, 1f, 0f)
        val inner = width - inset
        if (title != null) block.add(text(title, ink, size(14), inner)).width(inner).left().padBottom(if (rest.isEmpty()) 0f else 4f).row()
        val shown = if (open) rest else rest.take(1).map { preview(it) }
        for ((index, paragraph) in shown.withIndex())
            block.add(text(paragraph, muted, size(13), inner)).width(inner).left().padTop(if (index == 0) 0f else 6f).row()
        val foldable = rest.size > 1 || rest.firstOrNull()?.let { preview(it) != it } == true
        if (foldable) {
            val toggle = (if (open) "Show less" else "Show more").toLabel(WorldScreenStyle.accent, size(11), hideIcons = true)
            block.add(toggle).left().padTop(4f).row()
        }
        if (foldable) {
            block.touchable = Touchable.enabled
            block.onClick {
                holdPosition()
                if (open) { thinkingOpened.remove(entry.id); thinkingFolded.add(entry.id) }
                else { thinkingFolded.remove(entry.id); thinkingOpened.add(entry.id) }
                rebuild()
            }
        }
        return block
    }

    private fun preview(paragraph: String): String {
        if (paragraph.length <= previewChars) return paragraph
        val cut = paragraph.lastIndexOf(' ', previewChars).takeIf { it > previewChars / 2 } ?: previewChars
        return paragraph.substring(0, cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    /** A thin vertical rule along the left edge, marking quoted or secondary text. */
    private class LeftRule(private val color: Color) : BaseDrawable() {
        private val dot = ImageGetter.getWhiteDotDrawable()
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val prior = batch.color.cpy()
            batch.setColor(color.r, color.g, color.b, color.a * prior.a)
            dot.draw(batch, x, y, 2f, height)
            batch.color = prior
        }
    }

    private fun addSpeaker(entry: AgentActivityEntry, kind: String?, width: Float) {
        // Atlas, one nation shown: the turn heading names the speaker once; blocks keep a kind caption.
        if (atlas && selectedFilter.mode != FilterMode.ALL) {
            if (kind != null) addRow(text(kind, muted, size(GameUiStyle.Type.caption), width), "speaker/${entry.id}", width, bottom = 4f)
            return
        }
        val label = agentName(entry.civId) + if (kind == null) "" else " · $kind"
        addRow(text(label, AgentActivityAppearance.color(screen.gameInfo, entry.civId), size(if (kind == null) 13 else 12), width),
            "speaker/${entry.id}", width, bottom = 5f)
    }

    private fun toolButton(title: String, entry: AgentActivityEntry, opened: Boolean, action: () -> Unit): Button {
        val failed = entry.status == "failed" || entry.kind == "error"
        val color = if (failed) WorldScreenStyle.error else muted
        val button = Button(BaseScreen.skin).apply {
            style = Button.ButtonStyle().apply {
                over = WorldScreenStyle.control("WorldScreen/AgentActivityFeed/ToolHover", WorldScreenStyle.raised)
                down = WorldScreenStyle.control("WorldScreen/AgentActivityFeed/ToolDown", WorldScreenStyle.pressed)
                checked = WorldScreenStyle.control("WorldScreen/AgentActivityFeed/ToolOpen", Color(WorldScreenStyle.raised).apply { a = 0.7f })
                focused = GameUiStyle.outlinedBackground("WorldScreen/AgentActivityFeed/ToolFocused",
                    WorldScreenStyle.raised, WorldScreenStyle.accent)
            }
            isChecked = opened
            pad(5f, 4f, 5f, 6f)
        }
        val icon = when {
            failed -> "OtherIcons/ExclamationMark"
            entry.status == "running" -> "OtherIcons/Loading"
            else -> "OtherIcons/Checkmark"
        }
        button.add(ImageGetter.getImage(icon).apply { this.color = color }).size(12f).padRight(7f)
        button.add(title.toLabel(if (failed) color else ink.cpy().lerp(muted, 0.35f), size(12), hideIcons = true).apply { setEllipsis(true) })
            .minWidth(0f).growX().left()
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
            val countLabel = text(count, muted, size(11)).apply {
                touchable = Touchable.enabled
                onClick { holdPosition(); toggleRun(); rebuild() }
            }
            addRow(countLabel, "count/${run.first().id}", width, bottom = 8f, inset = 23f)
        }
    }

    private fun renderTool(entry: AgentActivityEntry, width: Float, inset: Float = 0f) {
        val opened = entry.id in expanded
        addRow(toolButton(entry.title, entry, opened) {
            if (!expanded.add(entry.id)) expanded.remove(entry.id)
        }, entry.id, width, inset = inset)
        if (entry.summary.isNotBlank()) {
            val color = if (entry.status == "failed" || entry.kind == "error") WorldScreenStyle.error else muted
            addRow(text(entry.summary, color, size(11), width - inset - 23f), "summary/${entry.id}", width, bottom = 6f, inset = inset + 23f)
        }
        if (opened) {
            val details = Table().apply {
                background = WorldScreenStyle.control("WorldScreen/AgentActivityFeed/Details", Color(WorldScreenStyle.inset).apply { a = 0.75f })
                pad(8f, 10f, 8f, 10f)
            }
            val inner = width - inset - 20f
            details.add(text(body(entry), ink, size(11), inner)).width(inner).left().row()
            val copy = WorldScreenStyle.quietButton("Copy details", GameUiStyle.ButtonSize.Compact)
            copy.onClick { Gdx.app.clipboard.contents = body(entry) }
            details.add(copy).left().padTop(6f)
            addRow(details, "details/${entry.id}", width, top = 2f, bottom = 8f, inset = inset)
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
        window.dispose()
        remove()
    }

    private companion object {
        /** Characters of a folded thought's first paragraph. */
        const val previewChars = 180
    }
}
