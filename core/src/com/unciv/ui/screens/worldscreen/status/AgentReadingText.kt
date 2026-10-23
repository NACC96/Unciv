package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import kotlin.math.roundToInt

/** The reading size shared by the Activity and Messages windows (GameSettings.hudTextScale). */
internal object AgentReadingText {
    private const val min = 0.8f
    private const val max = 1.6f
    private const val step = 0.1f

    val scale get() = UncivGame.Current.settings.hudTextScale.coerceIn(min, max)

    /** [base] type size at the reader's scale. */
    fun size(base: Int) = (base * scale).roundToInt()

    /** Windows that lay out reading text rebuild when the size changes. */
    interface Reader { fun onReadingSizeChanged() }

    /** "Text size  A− 100% A+" for a window's options menu. */
    fun menuRow(table: Table) {
        table.add("Text size".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption))
            .padTop(GameUiStyle.Space.s).padBottom(GameUiStyle.Space.s).row()
        val row = Table()
        val value = percent().toLabel(WorldScreenStyle.ink, GameUiStyle.Type.small)
        fun button(text: String, delta: Float) = WorldScreenStyle.quietButton(text, GameUiStyle.ButtonSize.Compact).apply {
            // The menu is on the stage only once shown, so look the stage up when clicked.
            onClick {
                change(stage, delta)
                value.setText(percent())
            }
        }
        row.add(button("A−", -step)).padRight(GameUiStyle.Space.m)
        row.add(value).minWidth(44f).center()
        row.add(button("A+", step)).padLeft(GameUiStyle.Space.m)
        table.add(row).left().row()
    }

    private fun percent() = "${(scale * 100).roundToInt()}%"

    fun change(stage: Stage?, delta: Float) {
        val settings = UncivGame.Current.settings
        val next = ((scale + delta) / step).roundToInt() * step
        if (next < min - 0.001f || next > max + 0.001f) return
        settings.hudTextScale = next.coerceIn(min, max)
        settings.save()
        stage?.root?.children?.filterIsInstance<Reader>()?.forEach { it.onReadingSizeChanged() }
        Gdx.graphics.requestRendering()
    }
}
