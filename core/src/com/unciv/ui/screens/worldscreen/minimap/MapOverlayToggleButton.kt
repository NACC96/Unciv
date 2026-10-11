package com.unciv.ui.screens.worldscreen.minimap

import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.GUI
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
    init {
        WorldScreenStyle.apply(this)
        pad(6f)
        add(icon).size(iconSize)
        onClick(::toggle)
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
}
