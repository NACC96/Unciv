package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.widgets.AutoScrollPane

/** Reparents authoritative actors; no legal offer, callback, shortcut or amount is recreated. */
internal object AtlasDiplomacyLayout {
    fun prepare(actor: Actor, width: Float) {
        if (!GameUiStyle.isAtlas) return
        GameUiStyle.applyWidget(actor)
        if (actor is Table) for (cell in actor.cells) {
            val child = cell.actor ?: continue
            if (child is Label && (child.text.length > 32 || '\n' in child.text.toString())) {
                val preferred = child.prefWidth.coerceAtMost(width.coerceAtLeast(80f))
                child.wrap = true
                cell.minWidth(0f).prefWidth(preferred).maxWidth(width.coerceAtLeast(80f))
            }
            if (child is TextButton) {
                child.label.wrap = true
                cell.minWidth(0f).minHeight(GameUiStyle.standardControlHeight)
                    .prefWidth(child.prefWidth.coerceAtMost(width)).maxWidth(width.coerceAtLeast(80f))
            }
        }
        if (actor is Group && actor !is TextButton)
            actor.children.forEach { prepare(it, width) }
    }

    fun panel(content: Table, primary: TextButton?, width: Float): Table {
        if (!GameUiStyle.isAtlas) return content
        content.background = GameUiStyle.panelBackground("Atlas/Diplomacy/Conversation", 1f)
        content.pad(GameUiStyle.Space.l)
        prepare(content, width)
        primary?.let { content.getCell(it)?.clearActor() }
        return Table().apply {
            name = "AtlasDiplomacyPanel"
            val scroll = AutoScrollPane(content).apply { setScrollingDisabled(true, false) }
            add(scroll).grow().minWidth(0f).minHeight(0f).row()
            if (primary != null) {
                GameUiStyle.apply(primary, primary = true)
                primary.label.wrap = true
                add(primary).growX().minWidth(0f).minHeight(GameUiStyle.standardControlHeight)
                    .pad(GameUiStyle.Space.m)
            }
        }
    }
}
