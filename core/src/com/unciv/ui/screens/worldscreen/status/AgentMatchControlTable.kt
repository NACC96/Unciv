package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.Timer
import com.unciv.UncivGame
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.formatAgentRunDuration
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldHudLayout
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/** Compact match controls, independent of human turn authority. On Atlas this is the one agent
 * card in the turn slot: state, model, Pause/Resume/Retry (or the human's turn action) and the
 * Activity, Messages and Settings sheets. */
class AgentMatchControlTable(
    private val session: AgentMatchSession,
    private val screen: WorldScreen
) : Table(BaseScreen.skin) {
    private val atlas = GameUiStyle.isAtlas
    private val dot = WorldScreenStyle.dot(WorldScreenStyle.muted)
    private val status = "".toLabel(WorldScreenStyle.ink, GameUiStyle.Type.label).apply { if (atlas) setEllipsis(true) else wrap = true }
    private val model = "".toLabel(WorldScreenStyle.muted, if (atlas) GameUiStyle.Type.caption else 12).apply { setEllipsis(true) }
    private val elapsed = "Elapsed 00:00".toLabel(if (atlas) WorldScreenStyle.muted else WorldScreenStyle.accent, 12)
        .apply { name = "AgentRunElapsed"; setEllipsis(true) }
    /** A hidden timer gives its row width back to the status; the tick only re-lays out this row. */
    private val elapsedWidth = object : Value() {
        override fun get(context: Actor?) = if (elapsed.isVisible) elapsed.prefWidth else 0f
    }
    private val elapsedGap = object : Value() {
        override fun get(context: Actor?) = if (elapsed.isVisible) GameUiStyle.Space.m else 0f
    }
    private var disposed = false
    private val timingRefreshTask = object : Timer.Task() {
        override fun run() {
            if (disposed) { cancel(); return }
            if (updateTiming()) Gdx.graphics.requestRendering()
        }
    }
    private val compact = GameUiStyle.ButtonSize.Compact
    private val actionButton = if (atlas) WorldScreenStyle.accentButton("", compact) else WorldScreenStyle.button("", primary = true)
    private val activityButton = if (atlas) WorldScreenStyle.quietButton("Activity", compact) else WorldScreenStyle.button("Activity")
    private val activityFeed = AgentActivityFeed(screen, session, this)
    private val messagesButton = if (atlas) WorldScreenStyle.quietButton("Messages", compact) else WorldScreenStyle.button("Messages")
    private val messagesPanel = AgentMessagesPanel(screen, session)

    private val settingsButton = if (atlas) WorldScreenStyle.quietButton("Settings", compact) else WorldScreenStyle.button("Settings")
    // Atlas sheets sit below the header, so Settings no longer hangs from this card.
    private val settingsPanel = AgentNationSettingsPanel(screen, session, if (atlas) null else this)

    /** Atlas only: the turn action a human may take now, and the secondary status buttons beside it. */
    private var turnAction: Actor? = null
    private var turnExtras: List<Actor> = emptyList()
    private var lastWidth = -1f
    private var lastHeight = -1f
    /** Called when an update outside the host's own pass changes this card's size. */
    var onResize: (() -> Unit)? = null

    fun dispose() {
        disposed = true
        timingRefreshTask.cancel()
        activityFeed.dispose(); messagesPanel.dispose(); settingsPanel.dispose()
    }

    init {
        name = "AgentMatchControlTable"
        if (atlas) {
            pad(GameUiStyle.Space.l)
            background = WorldScreenStyle.surface("WorldScreen/AgentCard")
        } else {
            pad(8f)
            background = WorldScreenStyle.background("WorldScreen/AgentControls")
        }
        activityButton.onClick { activityFeed.toggle(); update() }
        messagesButton.onClick { messagesPanel.toggle(); update() }
        settingsButton.onClick { settingsPanel.toggle(); update() }
        actionButton.onClick {
            when {
                session.canPause() -> session.pause()
                session.canResume() -> session.resume()
                session.canRetry() -> session.retry()
            }
            update()
        }
        update()
    }

    /** Atlas: [primary] (the NextTurnButton while a human may act) takes the primary cell. */
    fun setTurnControls(primary: Actor?, extras: List<Actor>) {
        turnAction = primary
        turnExtras = extras
    }

    /** Only update the caption: no feed polling, screen invalidation or map snapshot publication. */
    private fun updateTiming(): Boolean {
        val timing = session.getActiveRunTiming()
        val viewer = screen.viewingCiv
        val permitted = timing?.takeIf { viewer.isSpectator() || it.civId == viewer.civID }
        val visible = permitted != null
        val caption = permitted?.let { "Elapsed ${formatAgentRunDuration(it.elapsedMillis)} · ${it.civId}" } ?: "Elapsed 00:00"
        val changed = elapsed.isVisible != visible || elapsed.text.toString() != caption
        if (changed) {
            if (elapsed.isVisible != visible) { elapsed.isVisible = visible; elapsed.invalidateHierarchy() }
            elapsed.setText(caption)
        }
        // Timer posts GL runnables; don't keep waking a retained screen after its run disappears.
        if (!visible || disposed) timingRefreshTask.cancel()
        else if (!timingRefreshTask.isScheduled) Timer.schedule(timingRefreshTask, 1f, 1f)
        return changed
    }

    fun update() {
        updateTiming()
        val state = session.getRunState()
        status.setText(AgentStatusFormat.status(state, session.getActiveCivId()))
        model.setText(if (state == AgentRunState.NEEDS_ATTENTION) session.getFailureReason() ?: "Provider failure"
            else session.getActiveAgentProfile()?.let(AgentStatusFormat::model) ?: "")
        val action = AgentStatusFormat.actionLabel(session, state)
        actionButton.setText(action ?: "")
        WorldScreenStyle.setEnabled(actionButton, action != null && state != AgentRunState.PAUSING)
        activityButton.isChecked = activityFeed.isVisible
        messagesButton.isChecked = messagesPanel.isVisible
        settingsButton.isChecked = settingsPanel.isVisible
        if (atlas) layoutCard(state, action) else layoutLegacy(state, action)
        if (width != lastWidth || height != lastHeight) {
            val resized = lastWidth >= 0f
            lastWidth = width; lastHeight = height
            if (resized) onResize?.invoke()
        }
    }

    private fun layoutLegacy(state: AgentRunState?, action: String?) {
        status.color = when (state) {
            AgentRunState.NEEDS_ATTENTION -> WorldScreenStyle.error
            AgentRunState.PAUSING, AgentRunState.PAUSED -> WorldScreenStyle.warning
            else -> WorldScreenStyle.ink
        }
        val width = minOf(390f, screen.stage.width - 20f)
        clearChildren()
        val text = Table()
        text.add(status).minWidth(0f).growX().left().row()
        text.add(model).minWidth(0f).growX().left().padTop(4f).row()
        // Keep the caption row stable while the elapsed timer ticks.
        text.add(elapsed).minWidth(0f).growX().left().padTop(4f)
        val buttons = Table()
        if (action != null) buttons.add(actionButton).padRight(6f)
        buttons.add(activityButton).padRight(6f)
        buttons.add(messagesButton).padRight(6f)
        buttons.add(settingsButton)
        if (buttons.prefWidth > width - 16f) {
            buttons.clearChildren()
            val visibleButtons = listOfNotNull(actionButton.takeIf { action != null }, activityButton, messagesButton, settingsButton)
            for ((index, button) in visibleButtons.withIndex()) {
                if (index > 0 && index % 2 == 0) buttons.row()
                buttons.add(button).padRight(6f).padTop(if (index >= 2) 6f else 0f)
            }
        }
        val narrow = width - buttons.prefWidth - 26f < 120f
        if (narrow) {
            add(text).width(width - 16f).row()
            add(buttons).right().padTop(8f)
        } else {
            add(text).width((width - buttons.prefWidth - 26f).coerceAtLeast(80f)).left().padRight(10f)
            add(buttons).right()
        }
        pack()
    }

    private fun preferredWidth(actor: Actor) = (actor as? Layout)?.prefWidth ?: actor.width

    /** One card: status row, model caption, then the primary action and the quiet sheet toggles.
     * Width follows the right column, widening (up to 320) only to keep its buttons on one row. */
    private fun layoutCard(state: AgentRunState?, action: String?) {
        dot.color = AgentStatusFormat.dotColor(state)
        val gap = 6f
        val primary = turnAction
        val flow = listOfNotNull(actionButton.takeIf { action != null }) +
            (if (primary == null) turnExtras else emptyList()) + listOf(activityButton, messagesButton, settingsButton)
        val chrome = padLeft + padRight
        val stage = screen.stage
        val column = WorldHudLayout.calculate(stage.width, stage.height, UncivGame.Current.settings.fontSizeMultiplier, true).turnAction.width
        val oneRow = flow.sumOf { preferredWidth(it).toDouble() }.toFloat() + gap * (flow.size - 1) + chrome
        val width = minOf(maxOf(minOf(column, 320f), minOf(oneRow, 320f)), stage.width - 20f).coerceAtLeast(chrome + 1f)
        val content = width - chrome
        clearChildren()
        val head = Table()
        head.add(dot).size(8f).padRight(GameUiStyle.Space.m)
        head.add(status).minWidth(0f).growX().left()
        head.add(elapsed).prefWidth(elapsedWidth).minWidth(0f).padLeft(elapsedGap).right()
        add(head).width(content).row()
        // The caption lines up under the status text, past the dot.
        val indent = 8f + GameUiStyle.Space.m
        if (model.text.isNotEmpty()) add(model).width(content - indent).left().padLeft(indent).padTop(GameUiStyle.Space.xs).row()
        var top = GameUiStyle.Space.m + GameUiStyle.Space.xs
        if (primary != null) {
            val turnRow = Table()
            turnRow.add(primary).minWidth(0f).growX()
            for (extra in turnExtras) turnRow.add(extra).padLeft(gap)
            add(turnRow).width(content).padTop(top).row()
            top = gap
        }
        // Greedy rows: one row when everything fits, otherwise wrap rather than overflow.
        var buttons = Table()
        var used = 0f
        for (actor in flow) {
            var first = buttons.cells.size == 0
            if (!first && used + gap + preferredWidth(actor) > content) {
                add(buttons).width(content).padTop(top).row()
                top = gap; buttons = Table(); used = 0f; first = true
            }
            buttons.add(actor).minWidth(0f).growX().padLeft(if (first) 0f else gap)
            used += preferredWidth(actor) + if (first) 0f else gap
        }
        add(buttons).width(content).padTop(top).row()
        pack()
    }
}
