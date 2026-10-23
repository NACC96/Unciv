package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.UncivGame
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldHudLayout
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/** Local panel regions only. Input actors and session authority remain with their owners. */
data class PanelRegions(val content: Rectangle, val composer: Rectangle, val footer: Rectangle)

object AgentPanelLayout {
    fun calculate(width: Float, height: Float, fontScale: Float, composerExpanded: Boolean = true): PanelRegions {
        require(width.isFinite() && height.isFinite() && fontScale.isFinite())
        require(width > 0f && height > 0f && fontScale > 0f)
        // Reading is the main job: the composer and its actions keep only what they need.
        val footerHeight = if (composerExpanded) minOf(36f * fontScale, height * .16f) else 0f
        val composerHeight = if (composerExpanded) minOf(150f * fontScale, height * .34f) else 0f
        return PanelRegions(
            Rectangle(0f, footerHeight + composerHeight, width, height - footerHeight - composerHeight),
            Rectangle(0f, footerHeight, width, composerHeight),
            Rectangle(0f, 0f, width, footerHeight))
    }

    /** The same X on every agent sheet: a ghost icon button on Atlas, the outlined control elsewhere. */
    fun closeButton(onClose: () -> Unit): Button = Button(BaseScreen.skin).apply {
        if (GameUiStyle.isAtlas) WorldScreenStyle.apply(this, GameUiStyle.Emphasis.Ghost, GameUiStyle.ButtonSize.Icon)
        else WorldScreenStyle.apply(this)
        pad(if (GameUiStyle.isAtlas) 8f else 4f)
        add(ImageGetter.getImage("OtherIcons/Close").apply { if (GameUiStyle.isAtlas) color = WorldScreenStyle.muted }).size(12f)
        onClick { onClose() }
    }

    /** Bottom edge of the world header: sheets open below it rather than at a fixed offset. */
    fun headerBottom(stage: Stage): Float =
        WorldHudLayout.calculate(stage.width, stage.height, UncivGame.Current.settings.fontSizeMultiplier, true).header.y

    /** A tall Atlas reading sheet of [width]: from under the header to the bottom margin, just left of
     * the right-hand HUD column (minimap, tile info, turn action) so those stay visible. Screens too
     * narrow for that put it at the right edge; compact screens give it the full height. */
    fun tallSheet(stage: Stage, width: Float): Rectangle {
        val gap = GameUiStyle.Space.m
        val regions = WorldHudLayout.calculate(stage.width, stage.height, UncivGame.Current.settings.fontSizeMultiplier, true)
        val top = (if (regions.compact) stage.height else regions.header.y) - gap
        val height = (top - gap).coerceAtLeast(1f)
        // Panes report what the HUD actually placed; their bounds include the card halo.
        fun cardLeft(name: String) = stage.root.findActor<com.badlogic.gdx.scenes.scene2d.Actor>(name)
            ?.takeIf { it.isVisible }?.let { it.x + com.unciv.ui.screens.worldscreen.AtlasWorldHud.halo }
        val columnLeft = listOfNotNull(cardLeft("AtlasMinimap"), cardLeft("AtlasTileInfo"), cardLeft("AtlasTurnAction")).minOrNull()
            ?: regions.turnAction.x
        var x = columnLeft - gap - width
        if (regions.compact || x < gap) x = stage.width - width - gap
        return Rectangle(x.coerceAtLeast(0f), top - height, width, height)
    }

    /** An Atlas right-side sheet of [width], below the header. It stays above the minimap when
     * [minHeight] fits there, else extends down to the turn action, and covers the header only
     * when even that is too short. Compact screens give the sheet the full height. */
    fun rightSheet(stage: Stage, width: Float, minHeight: Float, maxHeight: Float): Rectangle {
        val gap = GameUiStyle.Space.m
        val regions = WorldHudLayout.calculate(stage.width, stage.height, UncivGame.Current.settings.fontSizeMultiplier, true)
        val x = (stage.width - width - gap).coerceAtLeast(0f)
        if (regions.compact) {
            val height = minOf(maxHeight, stage.height - 2 * gap).coerceAtLeast(1f)
            return Rectangle(x, (stage.height - gap - height).coerceAtLeast(0f), width, height)
        }
        // Panes report what the HUD actually placed; the layout is the fallback before it exists.
        fun pane(name: String) = stage.root.findActor<com.badlogic.gdx.scenes.scene2d.Actor>(name)?.takeIf { it.isVisible }
        val turnTop = pane("AtlasTurnAction")?.let { it.y + it.height } ?: (regions.turnAction.y + regions.turnAction.height)
        val minimapTop = pane("AtlasMinimap")?.let { it.y + it.height } ?: (regions.minimap.y + regions.minimap.height)
        var top = regions.header.y - gap
        var bottom = maxOf(minimapTop, turnTop) + gap
        if (top - bottom < minHeight) bottom = maxOf(turnTop + gap, top - minHeight)
        if (top - bottom < minHeight) top = minOf(stage.height - gap, bottom + minHeight)
        val height = minOf(maxHeight, top - bottom).coerceAtLeast(1f)
        return Rectangle(x, top - height, width, height)
    }
}
