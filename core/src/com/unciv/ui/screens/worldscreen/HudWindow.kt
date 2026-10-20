package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Cursor
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.TimeUtils
import com.unciv.UncivGame
import com.unciv.models.UncivSound
import com.unciv.models.metadata.HudWindowLayout
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.abs

/** Makes a world-screen panel a floating window the user arranges; the arrangement is saved
 *  per [id] in GameSettings.hudWindows.
 *
 *  - Drag the header (outside its buttons) to move; double-click it to return to the default spot.
 *  - Drag any edge or corner to resize, between [minSize] and the stage.
 *  - Moving and resizing snap to the screen margins, the world header, the right-hand HUD column
 *    and the other windows.
 *  - Minimize collapses the window to a title pill; clicking the pill restores it.
 *  - The options menu fades the window's frosted surface, toggles frosted glass and resets the spot.
 *
 *  The panel keeps its content and visibility. It calls [place] to sit where it should and
 *  [onShown]/[onHidden] when it opens or closes; [show]/[hide] are its own open/close routines. */
class HudWindow(
    private val panel: Table,
    val id: String,
    private val title: String,
    private val minSize: Vector2,
    private val defaultBounds: () -> Rectangle,
    private val show: () -> Unit,
    private val hide: () -> Unit,
    /** The user finished resizing: rebuild anything laid out for the old width. */
    private val onResized: () -> Unit = {},
    /** The window moved, resized or returned to its default spot (e.g. to reflow the HUD around it). */
    private val onArranged: () -> Unit = {},
    /** Extra rows for the options menu, between the frosted glass toggle and Reset position. */
    private val menuExtras: (Table.() -> Unit)? = null,
) {
    private val settings get() = UncivGame.Current.settings
    private val saved: HudWindowLayout? get() = settings.hudWindows[id]
    private fun layout() = settings.hudWindows.getOrPut(id) { HudWindowLayout() }

    /** The user moved or resized this window away from its default spot. */
    val isPlaced get() = saved?.placed == true
    var isMinimized = false
        private set
    val opacity get() = saved?.opacity ?: HudWindowLayout.DEFAULT_OPACITY

    private val frame = ResizeFrame()
    private var pill: Table? = null
    private var menu: Table? = null
    private val outsideMenuTouch = object : InputListener() {
        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            val open = menu ?: return false
            if (event.target?.isDescendantOf(open) != true) closeMenu()
            return false
        }
    }

    init {
        panel.addActor(frame)
        applyOpacity()
        windows.add(this)
    }

    /** The user is dragging or resizing the window right now. */
    var isInteracting = false
        private set

    /** Where the panel sits: the user's arrangement fitted to the stage, else [defaultBounds].
     *  While the user drags or resizes, it is wherever the pointer put it. */
    fun bounds(): Rectangle {
        if (isInteracting) return Rectangle(panel.x, panel.y, panel.width, panel.height)
            .also { current -> panel.stage?.let { fit(current, it) } }
        val stage = panel.stage
        val layout = saved?.takeIf { it.placed }
        if (stage == null || layout == null) return defaultBounds()
        val maxWidth = (stage.width - 2 * margin).coerceAtLeast(1f)
        val maxHeight = (stage.height - 2 * margin).coerceAtLeast(1f)
        val width = layout.width.coerceIn(minOf(minSize.x, maxWidth), maxWidth)
        val height = layout.height.coerceIn(minOf(minSize.y, maxHeight), maxHeight)
        val x = if (layout.fromRight) stage.width - layout.x - width else layout.x
        val y = if (layout.fromTop) stage.height - layout.y - height else layout.y
        return Rectangle(x, y, width, height).also { clamp(it, stage) }
    }

    /** Puts the panel at [bounds]. @return whether its size changed. */
    fun place(): Boolean {
        if (frame.parent !== panel) panel.addActor(frame)
        frame.toFront()
        GameUiStyle.setSurfaceOpacity(panel.background, opacity)
        val target = bounds()
        val resized = panel.width != target.width || panel.height != target.height
        panel.setBounds(target.x, target.y, target.width, target.height)
        return resized
    }

    /** Makes [header] the window's handle. Its buttons, pickers and fields keep their own input. */
    fun attachHeader(header: Actor) {
        header.touchable = Touchable.enabled
        header.addListener(object : InputListener() {
            private val start = Vector2()
            private val pointerStart = Vector2()
            private var dragged = false
            private var lastPress = 0L
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (pointer != 0 || isControl(event.target, header)) return false
                val now = TimeUtils.millis()
                if (now - lastPress < doubleClickMillis) {
                    lastPress = 0L
                    resetPosition()
                    return false
                }
                lastPress = now
                start.set(panel.x, panel.y)
                pointerStart.set(event.stageX, event.stageY)
                dragged = false
                panel.toFront()
                return true
            }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                val stage = panel.stage ?: return
                if (!dragged && pointerStart.dst(event.stageX, event.stageY) < dragSlop) return
                dragged = true
                isInteracting = true
                val moved = Rectangle(start.x + event.stageX - pointerStart.x, start.y + event.stageY - pointerStart.y,
                    panel.width, panel.height)
                snapMove(moved, stage)
                clamp(moved, stage)
                panel.setPosition(moved.x, moved.y)
                Gdx.graphics.requestRendering()
            }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                isInteracting = false
                if (dragged) { lastPress = 0L; remember() }
            }
        })
    }

    /** Options and minimize buttons, for the panel header just before its close button. */
    fun controls(): Table = Table().apply {
        val options = iconButton(dots(), "Window options")
        options.onClick { if (menu != null) closeMenu() else openMenu(options) }
        val minimize = iconButton(dash(), "Minimize")
        minimize.onClick { minimize() }
        add(options).size(controlSize).padRight(GameUiStyle.Space.xs)
        add(minimize).size(controlSize).padRight(GameUiStyle.Space.xs)
    }

    /** The panel opened (by its own button or by restoring the pill). */
    fun onShown() {
        isMinimized = false
        saved?.minimized = false
        pill?.remove()
        place()
    }

    /** The panel closed. A minimized window keeps its pill. */
    fun onHidden() {
        closeMenu()
        if (!isMinimized) pill?.remove()
    }

    /** Collapses to the title pill (remembered across launches for windows that open by themselves). */
    fun minimize() {
        val stage = panel.stage ?: return
        isMinimized = true
        layout().minimized = true
        settings.save()
        val anchor = Rectangle(panel.x, panel.y, panel.width, panel.height)
        hide()
        showPill(stage, anchor)
    }

    /** Opens the window minimized when the user left it that way. @return whether it did. */
    fun restoreMinimizedState(): Boolean {
        val stage = panel.stage ?: return false
        if (saved?.minimized != true) return false
        isMinimized = true
        hide()
        showPill(stage, bounds())
        return true
    }

    fun restore() {
        isMinimized = false
        saved?.minimized = false
        settings.save()
        pill?.remove()
        show()
    }

    fun dispose() {
        closeMenu()
        pill?.remove()
        windows.remove(this)
        setCursor(0)
    }

    private fun resetPosition() {
        saved?.placed = false
        settings.save()
        val old = Vector2(panel.width, panel.height)
        place()
        if (old.x != panel.width || old.y != panel.height) onResized()
        onArranged()
        Gdx.graphics.requestRendering()
    }

    private fun remember() {
        val stage = panel.stage ?: return
        val layout = layout()
        layout.placed = true
        layout.width = panel.width
        layout.height = panel.height
        layout.fromRight = panel.x + panel.width / 2f > stage.width / 2f
        layout.fromTop = panel.y + panel.height / 2f > stage.height / 2f
        layout.x = if (layout.fromRight) stage.width - panel.x - panel.width else panel.x
        layout.y = if (layout.fromTop) stage.height - panel.y - panel.height else panel.y
        settings.save()
        onArranged()
    }

    private fun applyOpacity() {
        GameUiStyle.setSurfaceOpacity(panel.background, opacity)
        Gdx.graphics.requestRendering()
    }

    //region Snapping

    /** Lines an edge may snap to: screen margins, the header, the HUD column and other windows. */
    private fun snapLines(stage: Stage): Pair<List<Float>, List<Float>> {
        val xs = arrayListOf(margin, stage.width - margin)
        val ys = arrayListOf(margin, stage.height - margin)
        if (GameUiStyle.isAtlas) {
            ys += com.unciv.ui.screens.worldscreen.status.AgentPanelLayout.headerBottom(stage) - WorldHudLayout.gap
            listOf("AtlasMinimap", "AtlasTileInfo", "AtlasTurnAction")
                .mapNotNull { stage.root.findActor<Actor>(it)?.takeIf { pane -> pane.isVisible } }
                .minOfOrNull { it.x + AtlasWorldHud.halo }?.let { xs += it - WorldHudLayout.gap }
        }
        for (other in windows) {
            val actor = other.panel
            if (other === this || actor.stage !== stage || !actor.isVisible) continue
            xs += listOf(actor.x, actor.x + actor.width, actor.x - WorldHudLayout.gap, actor.x + actor.width + WorldHudLayout.gap)
            ys += listOf(actor.y, actor.y + actor.height, actor.y - WorldHudLayout.gap, actor.y + actor.height + WorldHudLayout.gap)
        }
        return xs to ys
    }

    /** The smallest shift (within [snapDistance]) that puts one of [edges] on a line, else 0. */
    private fun snapShift(edges: List<Float>, lines: List<Float>): Float {
        var best = 0f
        var bestDistance = snapDistance
        for (edge in edges) for (line in lines) {
            val distance = abs(line - edge)
            if (distance < bestDistance) { bestDistance = distance; best = line - edge }
        }
        return best
    }

    private fun snapMove(bounds: Rectangle, stage: Stage) {
        val (xs, ys) = snapLines(stage)
        bounds.x += snapShift(listOf(bounds.x, bounds.x + bounds.width), xs)
        bounds.y += snapShift(listOf(bounds.y, bounds.y + bounds.height), ys)
    }

    private fun snapEdge(edge: Float, lines: List<Float>) = edge + snapShift(listOf(edge), lines)

    /** Shrinks [bounds] to the stage if needed, then [clamp]s it. */
    private fun fit(bounds: Rectangle, stage: Stage) {
        bounds.width = bounds.width.coerceAtMost((stage.width - 2 * margin).coerceAtLeast(1f))
        bounds.height = bounds.height.coerceAtMost((stage.height - 2 * margin).coerceAtLeast(1f))
        clamp(bounds, stage)
    }

    private fun clamp(bounds: Rectangle, stage: Stage) {
        bounds.x = bounds.x.coerceIn(margin, maxOf(margin, stage.width - bounds.width - margin))
        bounds.y = bounds.y.coerceIn(margin, maxOf(margin, stage.height - bounds.height - margin))
    }

    //endregion
    //region Resizing

    /** Invisible, hit only within reach of the panel's edges, so the panel's own content keeps
     *  every other touch. Never outside the panel: the map keeps every touch there. */
    private inner class ResizeFrame : Actor() {
        private var zone = 0
        private val start = Rectangle()
        private val pointerStart = Vector2()
        private var resizing = false

        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun mouseMoved(event: InputEvent, x: Float, y: Float): Boolean {
                    if (!resizing) setCursor(zoneAt(x, y))
                    return false
                }
                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (!resizing && pointer == -1) setCursor(0)
                }
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    zone = zoneAt(x, y)
                    if (zone == 0 || pointer != 0) return false
                    start.set(panel.x, panel.y, panel.width, panel.height)
                    pointerStart.set(event.stageX, event.stageY)
                    resizing = true
                    isInteracting = true
                    panel.toFront()
                    event.stop()
                    return true
                }
                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    val stage = panel.stage ?: return
                    resize(stage, event.stageX - pointerStart.x, event.stageY - pointerStart.y)
                }
                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    resizing = false
                    isInteracting = false
                    setCursor(zoneAt(x, y))
                    remember()
                    onResized()
                }
            })
        }

        override fun hit(x: Float, y: Float, touchable: Boolean): Actor? {
            if (touchable && this.touchable != Touchable.enabled || !isVisible) return null
            return if (zoneAt(x, y) != 0) this else null
        }

        fun zoneAt(x: Float, y: Float): Int {
            val width = panel.width
            val height = panel.height
            if (x < 0f || y < 0f || x > width || y > height) return 0
            val corner = cornerReach
            var zone = 0
            if (x < edgeReach || (x < corner && (y < corner || y > height - corner))) zone = zone or LEFT
            else if (x > width - edgeReach || (x > width - corner && (y < corner || y > height - corner))) zone = zone or RIGHT
            if (y < edgeReach || (y < corner && (x < corner || x > width - corner))) zone = zone or BOTTOM
            else if (y > height - edgeReach || (y > height - corner && (x < corner || x > width - corner))) zone = zone or TOP
            return zone
        }

        private fun resize(stage: Stage, dx: Float, dy: Float) {
            val (xs, ys) = snapLines(stage)
            var left = start.x
            var right = start.x + start.width
            var bottom = start.y
            var top = start.y + start.height
            val minWidth = minOf(minSize.x, stage.width - 2 * margin)
            val minHeight = minOf(minSize.y, stage.height - 2 * margin)
            if (zone and LEFT != 0) left = snapEdge(left + dx, xs).coerceIn(margin, right - minWidth)
            if (zone and RIGHT != 0) right = snapEdge(right + dx, xs).coerceIn(left + minWidth, stage.width - margin)
            if (zone and BOTTOM != 0) bottom = snapEdge(bottom + dy, ys).coerceIn(margin, top - minHeight)
            if (zone and TOP != 0) top = snapEdge(top + dy, ys).coerceIn(bottom + minHeight, stage.height - margin)
            panel.setBounds(left, bottom, right - left, top - bottom)
            panel.invalidateHierarchy()
            Gdx.graphics.requestRendering()
        }
    }

    private fun setCursor(zone: Int) {
        if (Gdx.app?.type != com.badlogic.gdx.Application.ApplicationType.Desktop) return
        val cursor = when (zone) {
            LEFT or TOP, RIGHT or BOTTOM -> Cursor.SystemCursor.NWSEResize
            RIGHT or TOP, LEFT or BOTTOM -> Cursor.SystemCursor.NESWResize
            LEFT, RIGHT -> Cursor.SystemCursor.HorizontalResize
            TOP, BOTTOM -> Cursor.SystemCursor.VerticalResize
            else -> Cursor.SystemCursor.Arrow
        }
        if (cursor == currentCursor) return
        currentCursor = cursor
        try { Gdx.graphics.setSystemCursor(cursor) } catch (_: Exception) { }
    }

    //endregion
    //region Pill and menu

    private fun showPill(stage: Stage, window: Rectangle) {
        val pill = pill ?: buildPill().also { pill = it }
        pill.pack()
        val fromRight = window.x + window.width / 2f > stage.width / 2f
        val x = if (fromRight) window.x + window.width - pill.width else window.x
        pill.setPosition(x.coerceIn(margin, maxOf(margin, stage.width - pill.width - margin)),
            (window.y + window.height - pill.height).coerceIn(margin, maxOf(margin, stage.height - pill.height - margin)))
        stage.addActor(pill)
        pill.toFront()
        Gdx.graphics.requestRendering()
    }

    /** A small frosted chip: the window's title, restore and close. Drag it to move the window. */
    private fun buildPill(): Table = Table(BaseScreen.skin).apply {
        name = "HudWindowPill/$id"
        background = GameUiStyle.surface("WorldScreen/WindowPill")
        touchable = Touchable.enabled
        pad(GameUiStyle.Space.s, GameUiStyle.Space.l, GameUiStyle.Space.s, GameUiStyle.Space.s)
        add(title.toLabel(GameUiStyle.ink, GameUiStyle.Type.label)).padRight(GameUiStyle.Space.m)
        val restore = iconButton(ImageGetter.getImage("OtherIcons/Increase").apply { color = GameUiStyle.muted }, "Restore")
        restore.onClick { restore() }
        val close = iconButton(ImageGetter.getImage("OtherIcons/Close").apply { color = GameUiStyle.muted }, "Close")
        close.onClick { isMinimized = false; saved?.minimized = false; settings.save(); remove() }
        add(restore).size(controlSize)
        add(close).size(controlSize).padLeft(GameUiStyle.Space.xs)
        val pill = this
        addListener(object : InputListener() {
            private val start = Vector2()
            private val pointerStart = Vector2()
            private var dragged = false
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (pointer != 0 || isControl(event.target, pill)) return false
                start.set(pill.x, pill.y); pointerStart.set(event.stageX, event.stageY); dragged = false
                return true
            }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                val stage = pill.stage ?: return
                if (!dragged && pointerStart.dst(event.stageX, event.stageY) < dragSlop) return
                dragged = true
                pill.setPosition((start.x + event.stageX - pointerStart.x).coerceIn(margin, maxOf(margin, stage.width - pill.width - margin)),
                    (start.y + event.stageY - pointerStart.y).coerceIn(margin, maxOf(margin, stage.height - pill.height - margin)))
                Gdx.graphics.requestRendering()
            }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (!dragged) { restore(); return }
                // The window reopens with its top edge at the pill, on the pill's side.
                val stage = pill.stage ?: return
                val window = bounds()
                val fromRight = pill.x + pill.width / 2f > stage.width / 2f
                window.x = if (fromRight) pill.x + pill.width - window.width else pill.x
                window.y = pill.y + pill.height - window.height
                clamp(window, stage)
                panel.setBounds(window.x, window.y, window.width, window.height)
                remember()
            }
        })
    }

    private fun openMenu(anchor: Actor) {
        val stage = panel.stage ?: return
        val layout = layout()
        val content = Table(BaseScreen.skin).apply {
            name = "HudWindowMenu/$id"
            background = GameUiStyle.surface("WorldScreen/WindowMenu", 0.96f)
            touchable = Touchable.enabled
            pad(GameUiStyle.Space.l)
            defaults().left().growX()
            add("Opacity".toLabel(GameUiStyle.muted, GameUiStyle.Type.caption)).padBottom(GameUiStyle.Space.s).row()
            add(UncivSlider(HudWindowLayout.MIN_OPACITY, 1f, 0.05f, plusMinus = false, initial = layout.opacity,
                sound = UncivSound.Silent, tipType = UncivSlider.TipType.None) {
                layout.opacity = it
                applyOpacity()
            }).minWidth(180f).padBottom(GameUiStyle.Space.m).row()
            val glass = CheckBox(" Frosted glass", BaseScreen.skin).apply {
                isChecked = settings.frostedGlass
                GameUiStyle.applyWidget(this)
                onChange { settings.frostedGlass = isChecked; Gdx.graphics.requestRendering() }
            }
            add(glass).padBottom(GameUiStyle.Space.s).row()
            menuExtras?.invoke(this)
            val reset = GameUiStyle.button("Reset position", GameUiStyle.Emphasis.Quiet, GameUiStyle.ButtonSize.Compact)
            reset.onClick { closeMenu(); resetPosition() }
            add(reset).padTop(GameUiStyle.Space.s).row()
        }
        content.pack()
        val corner = anchor.localToStageCoordinates(Vector2(anchor.width, 0f))
        content.setPosition((corner.x - content.width).coerceIn(margin, maxOf(margin, stage.width - content.width - margin)),
            (corner.y - content.height - GameUiStyle.Space.s).coerceIn(margin, maxOf(margin, stage.height - content.height - margin)))
        stage.addActor(content)
        content.toFront()
        menu = content
        stage.removeCaptureListener(outsideMenuTouch)
        stage.addCaptureListener(outsideMenuTouch)
        Gdx.graphics.requestRendering()
    }

    private fun closeMenu() {
        val open = menu ?: return
        menu = null
        open.stage?.removeCaptureListener(outsideMenuTouch)
        open.remove()
        settings.save()
        Gdx.graphics.requestRendering()
    }

    private fun iconButton(icon: Actor, tooltip: String) = Button(BaseScreen.skin).apply {
        GameUiStyle.apply(this, GameUiStyle.Emphasis.Ghost, GameUiStyle.ButtonSize.Icon)
        pad(0f)
        add(icon).center()
        addTooltip(tooltip, always = true)
    }

    /** A "more" glyph from three dots. */
    private fun dots() = Table().apply {
        repeat(3) { add(ImageGetter.getCircle(GameUiStyle.muted, 3.5f)).size(3.5f).pad(1.25f) }
    }

    /** A minimize glyph: one short bar. */
    private fun dash() = ImageGetter.getWhiteDot().apply { color = GameUiStyle.muted; setSize(11f, 1.75f) }
        .let { Table().apply { add(it).size(11f, 1.75f).padTop(5f) } }

    //endregion

    companion object {
        const val margin = 8f
        private const val snapDistance = 10f
        private const val edgeReach = 7f
        private const val cornerReach = 16f
        private const val dragSlop = 3f
        private const val doubleClickMillis = 350L
        private const val controlSize = 26f
        private const val LEFT = 1
        private const val RIGHT = 2
        private const val BOTTOM = 4
        private const val TOP = 8
        private val windows = ArrayList<HudWindow>()
        private var currentCursor: Cursor.SystemCursor? = null

        /** Is [target] (up to [root]) a control with its own input, which must not start a drag? */
        private fun isControl(target: Actor?, root: Actor): Boolean {
            var actor = target
            while (actor != null && actor !== root) {
                if (actor is Button || actor is SelectBox<*> || actor is TextField || actor is UncivSlider) return true
                actor = actor.parent
            }
            return false
        }

        /** Forgets every arrangement; open windows return to their default spots. */
        fun resetAll() {
            val settings = UncivGame.Current.settings
            settings.hudWindows.clear()
            settings.save()
            for (window in windows.toList()) {
                window.applyOpacity()
                if (window.panel.isVisible) window.resetPosition()
            }
        }
    }
}
