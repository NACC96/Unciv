package com.unciv.ui.screens.worldscreen.overview

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Timer
import com.unciv.models.translations.tr
import com.unciv.logic.agents.AgentRunState
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.status.AgentActivityAppearance
import com.unciv.view.GameView

/**
 * THESIS: Compare civilizations without leaving the live map.
 * OWN-WORLD: Inherit the world HUD's charcoal surface, off-white type and stable agent accents.
 * STORY: Sort nations, select one, inspect accurate stats; preserve human information limits.
 * FIRST VIEWPORT: Movable right-side panel, compact comparison above selected-nation details.
 * FORM: Local HUD extension, specified floating panel; no new visual identity.
 */
class CivilizationOverviewPanel(private val screen: WorldScreen) : Table(BaseScreen.skin), Disposable {
    private enum class Filter(val title: String) { MAJORS("Nations"), MINORS("City-states"), ALL("All") }
    private enum class Sort(val title: String, val value: (CivilizationSummary) -> Int?) {
        SCORE("Score", { it.score }), CITIES("Cities", { it.cities }), POPULATION("Pop.", { it.population }),
        GOLD("Gold", { it.gold }), SCIENCE("Science", { it.science })
    }
    private val status = "".toLabel(WorldScreenStyle.muted, 11).apply { wrap = true }
    private val rows = Table(skin)
    private val details = Table(skin)
    private val rowScroll = AutoScrollPane(rows, skin)
    private val detailScroll = AutoScrollPane(details, skin)
    private val search = UncivTextField("Find a civilization or agent").apply {
        style = TextField.TextFieldStyle(style).apply {
            background = WorldScreenStyle.background("WorldScreen/Overview/Search", WorldScreenStyle.raised)
            fontColor = WorldScreenStyle.ink
            messageFontColor = WorldScreenStyle.muted
            focusedBackground = WorldScreenStyle.background("WorldScreen/Overview/SearchFocused", WorldScreenStyle.pressed)
        }
    }
    private val filterButtons = Table(skin)
    private var filter = Filter.MAJORS
    private var sort = Sort.SCORE
    private var ascending = false
    private var selectedId: String? = null
    private var data: CivilizationOverviewData? = null
    private var initializedPosition = false
    private var disposed = false
    private var compact = false
    private val refreshTask = object : Timer.Task() {
        override fun run() {
            if (disposed) { cancel(); return }
            if (isVisible) refresh()
        }
    }

    init {
        background = WorldScreenStyle.panelBackground("WorldScreen/Overview")
        touchable = Touchable.enabled
        pad(14f)
        val header = Table(skin).apply { touchable = Touchable.enabled }
        header.add("Civilizations".toLabel(WorldScreenStyle.ink, 17)).left().expandX()
        header.add("Drag to move".toLabel(WorldScreenStyle.muted, 10)).padRight(12f)
        val close = Button(skin).apply {
            WorldScreenStyle.apply(this)
            pad(4f)
            add(ImageGetter.getImage("OtherIcons/Close")).size(12f)
            onClick { setShown(false) }
        }
        header.add(close).size(26f)
        header.addListener(object : InputListener() {
            private val origin = Vector2()
            private val pointerStart = Vector2()
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (event.target.isDescendantOf(close)) return false
                origin.set(this@CivilizationOverviewPanel.x, this@CivilizationOverviewPanel.y)
                pointerStart.set(event.stageX, event.stageY)
                toFront()
                return true
            }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                setPosition(origin.x + event.stageX - pointerStart.x, origin.y + event.stageY - pointerStart.y)
                clampPosition()
                Gdx.graphics.requestRendering()
            }
        })
        add(header).growX().row()
        add(status).growX().left().padTop(3f).padBottom(12f).row()
        for (option in Filter.entries) {
            val button = WorldScreenStyle.button(option.title)
            button.onClick { filter = option; rebuild() }
            filterButtons.add(button).padRight(4f)
        }
        add(filterButtons).growX().left().row()
        add(search).growX().height(32f).padTop(8f).padBottom(10f).row()
        search.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: com.badlogic.gdx.scenes.scene2d.Actor) { rebuild() }
        })
        for (scroll in listOf(rowScroll, detailScroll)) {
            scroll.setScrollingDisabled(true, false)
            scroll.setFadeScrollBars(false)
            scroll.setOverscroll(false, false)
        }
        rows.top().left()
        details.top().left()
        add(rowScroll).growX().row()
        add(detailScroll).grow().padTop(12f).row()
        screen.stage.addActor(this)
        refresh()
        Timer.schedule(refreshTask, 0.5f, 0.5f)
    }

    fun toggle() = setShown(!isVisible)
    fun hide() = setShown(false)

    private fun setShown(shown: Boolean) {
        isVisible = shown
        if (shown) { toFront(); refresh() }
        else {
            if (screen.stage.scrollFocus?.isDescendantOf(this) == true) screen.stage.scrollFocus = screen.mapHolder
            if (screen.stage.keyboardFocus?.isDescendantOf(this) == true) screen.stage.keyboardFocus = null
        }
        Gdx.graphics.requestRendering()
    }

    /** Uses the GL-thread publication, never the coordinator's mutable engine state. */
    fun refresh() {
        val panelWidth = minOf(660f, screen.stage.width * 0.68f).coerceAtLeast(300f).coerceAtMost(screen.stage.width - 16f)
        val panelHeight = minOf(580f, screen.stage.height - 140f).coerceAtLeast(260f).coerceAtMost(screen.stage.height - 16f)
        val resized = width != panelWidth || height != panelHeight
        compact = panelWidth < 520f
        setSize(panelWidth, panelHeight)
        if (!initializedPosition) {
            setPosition(screen.stage.width - width - 8f, screen.stage.height - height - 122f)
            initializedPosition = true
        }
        clampPosition()
        getCell(rowScroll).height((height * 0.34f).coerceAtLeast(86f))
        val latest = CivilizationOverviewData.read(screen.gameInfo, screen.viewingCiv, screen.agentSession)
        if (latest != data || resized) {
            data = latest
            val year = if (latest.year < 0) "${-latest.year} BC" else "${latest.year} AD"
            val active = latest.civilizations.firstOrNull { it.active }?.id
            status.setText("Turn ${latest.turn} · $year${active?.let { " · $it · ${stateName()}" } ?: ""}")
            rebuild()
        }
    }

    private fun clampPosition() {
        setPosition(x.coerceIn(8f, maxOf(8f, screen.stage.width - width - 8f)),
            y.coerceIn(8f, maxOf(8f, screen.stage.height - height - 8f)))
    }

    private fun filtered(): List<CivilizationSummary> {
        val query = search.text.trim()
        return data?.civilizations.orEmpty().filter {
            (filter == Filter.ALL || it.cityState == (filter == Filter.MINORS)) &&
                (query.isBlank() || it.id.contains(query, true) || it.agentName?.contains(query, true) == true)
        }.sortedWith { a, b ->
            val av = sort.value(a)
            val bv = sort.value(b)
            when {
                av == null && bv != null -> 1
                av != null && bv == null -> -1
                av != null && bv != null && av != bv -> if (ascending) av.compareTo(bv) else bv.compareTo(av)
                else -> a.id.compareTo(b.id)
            }
        }
    }

    private fun label(text: String, color: Color = WorldScreenStyle.ink, size: Int = 12): Label =
        text.toLabel(color, size, hideIcons = true)

    private fun rebuild() {
        val rowPosition = rowScroll.scrollY
        val detailPosition = detailScroll.scrollY
        val visible = filtered()
        if (visible.none { it.id == selectedId }) selectedId = visible.firstOrNull()?.id
        filterButtons.children.filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.TextButton>().forEach {
            it.isChecked = it.text.toString() == filter.title.tr()
        }
        rows.clearChildren()
        val columns = if (compact) listOf(Sort.SCORE, Sort.SCIENCE) else Sort.entries.toList()
        val metricWidth = if (compact) 55f else 59f
        val nameWidth = (width - 44f - columns.size * metricWidth).coerceAtLeast(90f)
        val tableWidth = nameWidth + columns.size * metricWidth
        val headings = Table(skin)
        headings.add(label("${visible.size} civilizations", WorldScreenStyle.muted, 10)).width(nameWidth).left()
        for (column in columns) {
            val title = column.title + if (sort == column) (if (ascending) " ↑" else " ↓") else ""
            val heading = WorldScreenStyle.button(title).apply { pad(6f, 2f, 6f, 2f) }
            heading.label.setFontSize(11)
            heading.onClick { if (sort == column) ascending = !ascending else { sort = column; ascending = false }; rebuild() }
            headings.add(heading).width(metricWidth).height(26f)
        }
        rows.add(headings).width(tableWidth).row()
        for (civ in visible) {
            val button = Button(skin).apply {
                WorldScreenStyle.apply(this)
                isChecked = civ.id == selectedId
                pad(6f, 0f, 6f, 0f)
                onClick { selectedId = civ.id; detailScroll.scrollY = 0f; rebuild() }
            }
            val identity = Table(skin)
            screen.gameInfo.civilizations.firstOrNull { it.civID == civ.id }?.let {
                identity.add(ImageGetter.getNationPortrait(it.nation, 25f)).size(25f).padRight(8f)
            }
            val names = Table(skin)
            val color = AgentActivityAppearance.color(screen.gameInfo, civ.id)
            names.add(label(civ.id, color, 13).apply { setEllipsis(true) }).growX().left().row()
            val subtitle = when { civ.defeated -> "Defeated"; civ.active -> "${stateName()} · ${civ.agentName ?: civ.controller}"; else -> civ.agentName ?: civ.controller }
            names.add(label(subtitle, WorldScreenStyle.muted, 10).apply { setEllipsis(true) }).growX().left()
            identity.add(names).growX()
            button.add(identity).width(nameWidth).left()
            for (column in columns) {
                val color = when {
                    column.value(civ) == null -> WorldScreenStyle.muted
                    column == Sort.SCIENCE -> WorldScreenStyle.accent
                    else -> WorldScreenStyle.ink
                }
                button.add(label(sortValue(civ, column), color)).width(metricWidth - 8f).right().padRight(8f)
            }
            rows.add(button).width(tableWidth).padTop(3f).row()
        }
        if (visible.isEmpty()) rows.add(label("No civilizations match this filter.", WorldScreenStyle.muted)).width(tableWidth).padTop(18f)
        rebuildDetails(visible.firstOrNull { it.id == selectedId })
        validate()
        rowScroll.validate(); detailScroll.validate()
        rowScroll.scrollY = minOf(rowPosition, rowScroll.maxY)
        detailScroll.scrollY = minOf(detailPosition, detailScroll.maxY)
        rowScroll.updateVisualScroll(); detailScroll.updateVisualScroll()
        Gdx.graphics.requestRendering()
    }

    private fun sortValue(civ: CivilizationSummary, column: Sort): String = column.value(civ)?.toString() ?: "—"

    private fun rebuildDetails(civ: CivilizationSummary?) {
        details.clearChildren()
        if (civ == null) return
        val w = width - 40f
        details.add(ImageGetter.getWhiteDot().apply { color = WorldScreenStyle.hover }).width(w).height(1f)
            .padBottom(12f).row()
        val accent = AgentActivityAppearance.color(screen.gameInfo, civ.id)
        fun line(text: String, color: Color = WorldScreenStyle.muted, size: Int = 12, top: Float = 4f) {
            details.add(label(text, color, size).apply { wrap = true }).width(w).left().padTop(top).row()
        }
        line(civ.id, accent, 18, 0f)
        line(civ.agentName?.let { "$it · ${civ.controller}" } ?: civ.controller, WorldScreenStyle.muted, 11)
        if (civ.gold == null) {
            line("Private economy, research and unit details are hidden in human games.", top = 12f)
            line("Military strength: ${civ.strength ?: "—"} · Technologies: ${civ.technologies ?: "—"}")
            return
        }
        val metrics = Table(skin)
        val values = listOf("Gold" to "${civ.gold} (${signed(civ.income)}/turn)", "Science" to "${civ.science}/turn",
            "Culture" to "${civ.culture}/turn", "Production" to "${civ.production}/turn",
            "Happiness" to "${civ.happiness}", "Faith" to "${civ.faith}/turn")
        val count = if (compact) 2 else 3
        for ((index, metric) in values.withIndex()) {
            val cell = Table(skin)
            cell.add(label(metric.first, WorldScreenStyle.muted, 10)).left().row()
            cell.add(label(metric.second, WorldScreenStyle.ink, 13)).left().padTop(2f)
            metrics.add(cell).width(w / count).left().padTop(10f)
            if ((index + 1) % count == 0) metrics.row()
        }
        details.add(metrics).width(w).left().row()
        line("${civ.era} · ${civ.technologies} technologies · Strength ${civ.strength}", top = 14f)
        line(civ.research?.let { "Researching $it · ${civ.researchProgress}/${civ.researchCost} science · ${civ.researchTurns} turns" }
            ?: "No research selected", WorldScreenStyle.ink)
        line("${civ.cities} cities · ${civ.population} population · ${civ.units?.values?.sum()} units · ${civ.storedCulture} culture saved")
        line("Resources", WorldScreenStyle.ink, 13, 14f)
        line(civ.resources?.entries?.joinToString(" · ") { "${it.key} ${it.value}" }?.takeIf { it.isNotEmpty() } ?: "No available resources")
        line("Cities & production", WorldScreenStyle.ink, 13, 14f)
        for (city in civ.cityDetails.orEmpty()) line("${city.name} · Pop. ${city.population} · ${city.production.ifBlank { "Nothing queued" }}")
        if (civ.cityDetails.isNullOrEmpty()) line("No cities founded")
        line("Units", WorldScreenStyle.ink, 13, 14f)
        line(civ.units?.entries?.joinToString(" · ") { "${it.value} ${it.key}" }?.takeIf { it.isNotEmpty() } ?: "No units")
        if (!civ.pendingStartingUnits.isNullOrEmpty()) {
            line("Pending starting units", WorldScreenStyle.ink, 13, 14f)
            line(civ.pendingStartingUnits.entries.joinToString(" · ") { "${it.value} ${it.key}" })
            line("Waiting for a city with free air-unit slots")
        }
        line("At war with ${civ.wars?.joinToString(", ")?.takeIf { it.isNotEmpty() } ?: "no civilizations"}", top = 12f)
        val empire = WorldScreenStyle.button("Empire details")
        empire.onClick {
            val target = screen.gameInfo.civilizations.firstOrNull { it.civID == civ.id } ?: return@onClick
            val view = GameView(screen.gameInfo, screen.viewingCiv, screen.viewingCiv.isSpectator()).getCivView(target)
            screen.game.pushScreen { EmpireOverviewScreen(view) }
        }
        details.add(empire).left().padTop(12f)
    }

    private fun signed(value: Int?) = if (value != null && value > 0) "+$value" else "$value"

    private fun stateName() = when (data?.runState) {
        AgentRunState.AGENT_DECIDING -> "Thinking"
        AgentRunState.PAUSED -> "Paused"
        AgentRunState.PAUSING -> "Pausing…"
        AgentRunState.NEEDS_ATTENTION -> "Needs attention"
        AgentRunState.FINISHED -> "Finished"
        AgentRunState.STARTING -> "Starting"
        null -> "Current turn"
        else -> "Playing"
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        refreshTask.cancel()
        remove()
    }
}
