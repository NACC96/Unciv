package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.ui.components.GameUiStyle

/** Applies on creation/rebuild, including detached fixed headers and expander bodies. */
internal object AtlasEmpireUi {
    fun prepare(actor: Actor) {
        if (!GameUiStyle.isAtlas) return
        GameUiStyle.applyWidget(actor)
        if (actor is Table) for (cell in actor.cells) {
            when (val child = cell.actor) {
                is Label -> {
                    if (child.style.fontColor == Color.RED) child.color = GameUiStyle.error
                    if (child.text.length > 32) {
                        val preferred = child.prefWidth.coerceAtMost(300f)
                        child.wrap = true
                        cell.minWidth(0f).prefWidth(preferred).maxWidth(300f)
                    }
                }
                is TextButton -> {
                    child.label.wrap = true
                    cell.minHeight(GameUiStyle.standardControlHeight)
                }
            }
        }
        if (actor is Group && actor !is TextButton) actor.children.forEach(::prepare)
    }

    fun panel(table: Table, id: String) {
        if (!GameUiStyle.isAtlas) return
        table.background = GameUiStyle.panelBackground("Atlas/Empire/$id", 1f)
        table.pad(GameUiStyle.Space.l)
        prepare(table)
    }

    fun grid(header: Table, details: Table, totals: Table) {
        if (!GameUiStyle.isAtlas) return
        panel(header, "GridHeader")
        panel(totals, "GridTotals")
        details.background = GameUiStyle.background("Atlas/Empire/GridRows", GameUiStyle.inset)
        prepare(details)
    }
}
