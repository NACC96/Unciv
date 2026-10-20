package com.unciv.ui.screens.worldscreen.minimap

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.UncivTooltip
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * Class that unifies the behaviour of the map overlay toggle buttons shown next to the minimap.
 *
 * @param iconPath Path to an [Image] to display, will be fetched via [ImageGetter.getImage].
 * @param iconSize inner icon size, with a compact padded hit area.
 * @property getter A function that returns the current backing state of the toggle.
 * @property setter A function for setting the backing state of the toggle.
 */
class MapOverlayToggleButton(
    iconPath: String,
    iconSize: Float = 20f,
    private val getter: () -> Boolean,
    private val setter: (Boolean) -> Unit
) : Button(BaseScreen.skin) {
    private val icon = ImageGetter.getImage(iconPath)
    private var layerTooltip: UncivTooltip<Table>? = null
    init {
        // Atlas: a filled quiet control with a brass tint while on, listed in the minimap's Layers menu.
        if (GameUiStyle.isAtlas) WorldScreenStyle.apply(this, GameUiStyle.Emphasis.Quiet, GameUiStyle.ButtonSize.Icon)
        else WorldScreenStyle.apply(this)
        pad(6f)
        add(icon).size(iconSize)
        onClick(::toggle)
        // Apply the backing flag immediately, including when the controls are rebuilt.
        update()
    }

    /** Toggle overlay. Called on click. */
    override fun toggle() {
        setter(!getter())
        GUI.setUpdateWorldOnNextRender()
        // Setting worldScreen.shouldUpdate implicitly causes this.update() to be called by the WorldScreen on the next update.
    }

    /** Update. Called via [WorldScreen.shouldUpdate] on toggle. */
    fun update() {
        isChecked = getter()
        icon.color = if (isChecked) WorldScreenStyle.accent else WorldScreenStyle.muted
    }

    /** Wrap and place these map-layer labels inside the current viewport, including after resize. */
    internal fun setLayerTooltip(text: String) {
        layerTooltip?.let { it.hide(true); removeListener(it) }
        val label = text.toLabel(WorldScreenStyle.ink, 14, hideIcons = true)
        val preferredWidth = label.prefWidth
        label.wrap = true
        val content = Table().apply {
            background = WorldScreenStyle.panelBackground("WorldScreen/Minimap/LayerTooltip")
            pad(6f, 8f, 6f, 8f)
        }
        val labelCell = content.add(label).width(preferredWidth.coerceAtMost(220f))
        content.pack()
        val offset = Vector2()
        layerTooltip = UncivTooltip(this, content, targetAlign = Align.topLeft,
            tipAlign = Align.bottomRight, offset = offset, contentRefresher = {
                val viewportWidth = stage.width
                val viewportHeight = stage.height
                labelCell.width(preferredWidth.coerceAtMost(220f)
                    .coerceAtMost((viewportWidth - 40f).coerceAtLeast(1f)))
                content.invalidateHierarchy()
                content.pack()
                val anchor = localToStageCoordinates(Vector2(0f, height))
                val left = (anchor.x - content.width).coerceIn(12f,
                    (viewportWidth - content.width - 12f).coerceAtLeast(12f))
                val bottom = (anchor.y + 6f).coerceIn(12f,
                    (viewportHeight - content.height - 12f).coerceAtLeast(12f))
                offset.set(left + content.width - anchor.x, bottom - anchor.y)
                Vector2(content.width, content.height)
            }).also { addListener(it) }
    }
}
