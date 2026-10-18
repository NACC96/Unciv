package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/** Spectator dropdown on the top bar's identity chip: "Follow active nation", then the nations in
 *  turn order. Choosing one only inspects it (WorldScreen.inspectNation); it never calls a session,
 *  controller, takeover or vision API. */
class NationSwitcher(private val worldScreen: WorldScreen) : Table() {
    private var anchor: Actor? = null
    private val outsideTouch = object : InputListener() {
        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            val target = event.target
            if (target != null && (target.isDescendantOf(this@NationSwitcher) || anchor?.let { target.isDescendantOf(it) } == true))
                return false
            close()
            return false
        }
    }

    init {
        name = "NationSwitcher"
        background = WorldScreenStyle.surface("Atlas/World/NationSwitcher", 0.94f)
        pad(6f)
        touchable = Touchable.enabled
        defaults().growX().minWidth(220f)
    }

    val isOpen get() = stage != null && isVisible

    /** Rebuilds the entries from the current roster. Call before showing. */
    fun rebuild() {
        clearChildren()
        val following = worldScreen.selectedCiv === worldScreen.viewingCiv
        add(entry("NationSwitcherFollow", "Follow active nation", null, "", following) {
            worldScreen.clearInspectedNation()
        }).row()
        val roster = NationTurnRoster.read(worldScreen.gameInfo, worldScreen.viewingCiv,
            worldScreen.agentSession?.getActiveCivId() ?: worldScreen.gameInfo.currentPlayer)
        for (rosterEntry in roster) {
            val id = rosterEntry.civ.civID
            add(entry("NationSwitcherEntry:$id", "${rosterEntry.ordinal}. $id", rosterEntry.civ, rosterEntry.marker,
                    !following && rosterEntry.civ === worldScreen.selectedCiv) {
                // Resolve against the current committed snapshot, never retain an old civ instance.
                val current = NationTurnRoster.read(worldScreen.gameInfo, worldScreen.viewingCiv,
                    worldScreen.gameInfo.currentPlayer).firstOrNull { it.civ.civID == id } ?: return@entry
                worldScreen.inspectNation(current.civ.civID)
            }).row()
        }
        pack()
    }

    private fun entry(name: String, text: String, civ: com.unciv.logic.civilization.Civilization?, marker: String,
                      checked: Boolean, action: () -> Unit): Button {
        val button = Button(BaseScreen.skin)
        WorldScreenStyle.apply(button, GameUiStyle.Emphasis.Quiet, GameUiStyle.ButtonSize.Compact)
        button.name = name
        button.isChecked = checked
        if (civ != null) button.add(ImageGetter.getNationPortrait(civ.nation, 20f)).size(20f).padRight(8f)
        val label = text.toLabel(if (checked) WorldScreenStyle.accent else WorldScreenStyle.ink, GameUiStyle.Type.body, hideIcons = true)
        label.setEllipsis(true)
        button.add(label).minWidth(0f).maxWidth(200f).left().growX()
        if (marker.isNotEmpty()) button.add(marker.toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)).padLeft(12f).right()
        button.onActivation {
            close()
            action()
        }
        return button
    }

    /** Opens below [anchor], clamped to the stage; a touch anywhere else closes it. */
    fun open(anchor: Actor) {
        val stage = anchor.stage ?: return
        this.anchor = anchor
        rebuild()
        val corner = anchor.localToStageCoordinates(Vector2(0f, 0f))
        setPosition(corner.x.coerceIn(8f, maxOf(8f, stage.width - width - 8f)),
            (corner.y - height - 6f).coerceAtLeast(8f))
        isVisible = true
        stage.addActor(this)
        toFront()
        stage.removeCaptureListener(outsideTouch)
        stage.addCaptureListener(outsideTouch)
    }

    fun close() {
        stage?.removeCaptureListener(outsideTouch)
        remove()
    }

    fun toggle(anchor: Actor) = if (isOpen) close() else open(anchor)
}
