package com.unciv.ui.screens.worldscreen.minimap

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.DragListener
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileMap
import com.unciv.ui.components.InputDisabling
import com.unciv.ui.components.extensions.addInTable
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder

class MinimapHolder(val mapHolder: WorldMapHolder) : Table() {
    private val worldScreen = mapHolder.worldScreen
    private var minimapSize = Int.MIN_VALUE
    private var maximized = false
    val isMaximized get() = maximized
    private var lastCutoutSetting = false
    private var lastTileMap: TileMap? = null
    private var lastRequestedSize = Int.MIN_VALUE
    private var lastViewportSize = Vector2()
    private var resizeDragListener: ResizeDragListener? = null
    lateinit var minimap: Minimap

    /** Button, next to the minimap, to toggle the unit movement map overlay. */
    val movementsImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleMovement",
        getter = { UncivGame.Current.settings.showUnitMovements },
        setter = { UncivGame.Current.settings.showUnitMovements = it }
    )
    /** Button, next to the minimap, to toggle the tile yield map overlay. */
    val yieldImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleYields",
        // This is a use in the UI that has little to do with the stat… These buttons have more in common with each other than they do with other uses of getStatIcon().
        getter = { UncivGame.Current.settings.showTileYields },
        setter = { UncivGame.Current.settings.showTileYields = it }
    )
    /** Button, next to the minimap, to toggle the worked tiles map overlay. */
    val populationImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleWorkedTiles",
        getter = { UncivGame.Current.settings.showWorkedTiles },
        setter = { UncivGame.Current.settings.showWorkedTiles = it }
    )
    /** Button, next to the minimap, to toggle the resource icons map overlay. */
    val resourceImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleResources",
        getter = { UncivGame.Current.settings.showResourcesAndImprovements },
        setter = { UncivGame.Current.settings.showResourcesAndImprovements = it }
    )
    /** Button, next to the minimap, to toggle the pixel improvements map overlay. */
    val improvementsImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleImprovements",
        getter = { UncivGame.Current.settings.showPixelImprovements },
        setter = { UncivGame.Current.settings.showPixelImprovements = it }
    )
    val buttons = listOf(
        movementsImageButton,
        yieldImageButton,
        populationImageButton,
        resourceImageButton,
        improvementsImageButton
    )

    init {
        // Reuse the translated options labels for these otherwise icon-only controls.
        val labels = listOf("Show unit movement arrows", "Show tile yields", "Show worked tiles",
            "Show resources and improvements", "Show pixel improvements")
        for ((button, label) in buttons.zip(labels)) {
            button.name = label
            button.setLayerTooltip(label)
        }
    }

    private fun rebuildIfSizeChanged(civInfo: Civilization) {
        // For Spectator should not restrict minimap
        val civ: Civilization? = civInfo.takeUnless { GUI.getWorldScreen().selectedGameView.spectatorMode }
        val newMinimapSize = worldScreen.game.settings.minimapSize
        val cutoutSetting = worldScreen.game.settings.androidCutout
        val viewportChanged = com.unciv.ui.components.GameUiStyle.isAtlas &&
            (lastViewportSize.x != worldScreen.stage.width || lastViewportSize.y != worldScreen.stage.height)
        if (!viewportChanged && newMinimapSize == lastRequestedSize && civ?.exploredRegion?.shouldUpdateMinimap() != true
            && cutoutSetting == lastCutoutSetting && lastTileMap === mapHolder.tileMap) return
        lastViewportSize.set(worldScreen.stage.width, worldScreen.stage.height)
        lastCutoutSetting = cutoutSetting
        lastRequestedSize = newMinimapSize
        minimapSize = newMinimapSize
        lastTileMap = mapHolder.tileMap
        maximized = false
        rebuild(civ)
        val fitted = atlasFittedSize(minimapSize)
        if (fitted < minimapSize) { minimapSize = fitted; rebuild(civ) }
    }

    private fun atlasFittedSize(requestedSize: Int): Int {
        if (!com.unciv.ui.components.GameUiStyle.isAtlas) return requestedSize
        val r = com.unciv.ui.screens.worldscreen.WorldHudLayout.calculate(worldScreen.stage.width,
            worldScreen.stage.height, worldScreen.game.settings.fontSizeMultiplier, true).minimap
        // Include the actual skin's layer-button row and borders, plus the host's tile-info
        // gap. A fixed estimate clips the controls when their skin minimum height is larger.
        val chromeHeight = height - minimap.height + 8f
        return minOf(requestedSize, minimap.getClosestMinimapSize(
            Vector2((r.width - 8f).coerceAtLeast(40f), (r.height - chromeHeight).coerceAtLeast(40f)), touchInside = true))
    }

    private fun rebuild(civInfo: Civilization?) {
        this.clear()
        minimap = Minimap(mapHolder, minimapSize, civInfo)
        val wrappedMinimap = getWrappedMinimap()
        if (!com.unciv.ui.components.GameUiStyle.isAtlas) add(getToggleIcons(wrappedMinimap.height)).bottom().padRight(5f)

        val stack = Stack()
        stack.add(wrappedMinimap)
        stack.addInTable(getCornerHandleIcon()).size(20f).pad(8f).top().left()
        val alignment = if (worldScreen.game.settings.androidCutout) Align.topRight else Align.bottomRight
        stack.addInTable(getMaximizeToggleButton(civInfo, alignment)).size(40f).align(alignment) // more click area
        add(stack).bottom()
        if (com.unciv.ui.components.GameUiStyle.isAtlas) { row(); add(getToggleIcons(wrappedMinimap.height)).padTop(4f) }

        pack()
        if (stage != null) x = stage.width - width

        resizeDragListener?.let { removeListener(it) }
        resizeDragListener = ResizeDragListener(civInfo).also { addListener(it) }
    }

    private fun rebuildAndUpdateMap(civInfo: Civilization?) {
        InputDisabling.withInputDisabled {
            rebuild(civInfo) // re-create views
            civInfo?.let { minimap.update(it) } // update map
            minimap.mapHolder.onViewportChanged() // update scroll position
        }
    }

    private fun getMaximizeToggleButton(civInfo: Civilization?, alignment: Int): Actor {

        val toggle = fun() {
            maximized = !maximized
            minimapSize = if (maximized && stage!=null) {
                minimap.getClosestMinimapSize(Vector2(stage.width, stage.height), touchInside = true)
            } else {
                atlasFittedSize(worldScreen.game.settings.minimapSize)
            }
            rebuildAndUpdateMap(civInfo)
            worldScreen.shouldUpdate = true
        }

        // A map-location click also collapses the Atlas host and must restore its fit.
        if (maximized) minimap.onClick {
            if (com.unciv.ui.components.GameUiStyle.isAtlas) toggle() else maximized = false
        }

        val table = Table().apply { name = if (maximized) "MinimapReduce" else "MinimapIncrease" }
        if (shouldShowMapButtons()) {
            // table provides larger click area. we want the resize icon to be small to not cover the map,
            // so only a compact plate behind the icon is drawn.
            val name = if (maximized) "Reduce" else "Increase"
            val image = ImageGetter.getImage("OtherIcons/$name")
            image.color = WorldScreenStyle.ink
            val plate = Table().apply {
                background = WorldScreenStyle.background("WorldScreen/Minimap/ResizePlate",
                    WorldScreenStyle.raised.cpy().apply { a = 0.9f })
                touchable = Touchable.disabled
                add(image).size(16f).pad(5f)
            }
            table.add(plate).expand().size(26f).align(alignment).pad(2f)
            table.touchable = Touchable.enabled
            table.onActivation(toggle)
        } else {
            // map is really small: use whole MinimapHolder as click area to maximize map
            table.isVisible = false
            minimap.touchable = Touchable.disabled
            touchable = Touchable.enabled
            onActivation(toggle)
        }
        return table
    }

    private fun getCornerHandleIcon(): Image {
        return ImageGetter.getImage("OtherIcons/Corner").apply {
            color = WorldScreenStyle.muted
            touchable = Touchable.disabled
            isVisible = !maximized && shouldShowMapButtons()
        }
    }

    private fun shouldShowMapButtons() = minimapSize > 0 &&
        (minimap.width > 100f || minimap.height > 100f)

    private fun getWrappedMinimap(): Table {
        val internalMinimapWrapper = Table()
        internalMinimapWrapper.add(minimap).pad(3f)

        internalMinimapWrapper.background = WorldScreenStyle.background(
            "WorldScreen/Minimap/Background",
            WorldScreenStyle.surface
        )
        internalMinimapWrapper.pack()

        val externalMinimapWrapper = Table()
        externalMinimapWrapper.add(internalMinimapWrapper).pad(1f)
        externalMinimapWrapper.background = WorldScreenStyle.background(
            "WorldScreen/Minimap/Border",
            WorldScreenStyle.hover
        )
        externalMinimapWrapper.pack()

        return externalMinimapWrapper
    }

    /** @return Layout table for the map overlay controls shown to the left of the minimap. */
    private fun getToggleIcons(minimapHeight: Float): Table {
        val toggleIconTable = Table()

        val availableForPadding = minimapHeight - buttons.sumOf { it.height.toDouble() }.toFloat()
        val paddingBetweenElements = (availableForPadding/3).coerceIn(0f, 5f)

        toggleIconTable.defaults().padTop(paddingBetweenElements)

        for (button in buttons) {
            toggleIconTable.add(button)
            if (!com.unciv.ui.components.GameUiStyle.isAtlas) toggleIconTable.row()
        }

        return toggleIconTable
    }

    fun update(civInfo: Civilization) {
        rebuildIfSizeChanged(civInfo)
        isVisible = UncivGame.Current.settings.showMinimap
        if (isVisible) {
            minimap.update(civInfo)
            for (button in buttons) button.update()
        }
    }

    // For debugging purposes
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = super.hit(x, y, touchable)
    override fun act(delta: Float){} // No actions

    inner class ResizeDragListener(val civInfo: Civilization?): DragListener() {
        private var dragged = false
        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            super.touchDragged(event, x, y, pointer)
            if (!isDragging || maximized)
                return
            dragged = true
            val targetSize = Vector2(stage.width - event.stageX, event.stageY)
            val newMinimapSize = minimap.getClosestMinimapSize(targetSize) 
            if (newMinimapSize == minimapSize) return
            
            minimapSize = newMinimapSize
            rebuildAndUpdateMap(civInfo)
        }
        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
            super.touchUp(event, x, y, pointer, button)
            if (dragged) {
                worldScreen.game.settings.minimapSize = minimapSize
                GUI.setUpdateWorldOnNextRender() // full update
            }
        }
    }
}
