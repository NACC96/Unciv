package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.ExpanderTab

/** Layout helpers wrap authoritative actors; they do not replace their listeners or shortcuts. */
object AtlasWidgets {
    fun header(title: String, subtitle: String? = null): Table = Table().apply {
        name = "AtlasHeader"
        pad(GameUiStyle.Space.l)
        add(title.toLabel(GameUiStyle.ink, GameUiStyle.Type.title).apply { wrap = true }).growX().left().row()
        if (subtitle != null)
            add(subtitle.toLabel(GameUiStyle.muted, GameUiStyle.Type.body).apply { wrap = true })
                .growX().left().padTop(GameUiStyle.Space.s).row()
    }

    fun stat(label: String, value: String, tint: Color? = null): Table = Table().apply {
        name = "AtlasStat"
        pad(GameUiStyle.Space.m)
        add(label.toLabel(GameUiStyle.muted, GameUiStyle.Type.small).apply { wrap = true }).growX().minWidth(0f).left().row()
        add(value.toLabel(tint?.cpy() ?: GameUiStyle.ink, GameUiStyle.Type.title).apply { wrap = true }).growX().minWidth(0f).left().row()
    }

    fun tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): Table = Table().apply {
        require(labels.isEmpty() || selected in labels.indices)
        name = "AtlasTabs"
        val buttons = labels.map { GameUiStyle.button(it).apply { label.wrap = true; label.setAlignment(Align.center) } }
        fun showSelection(index: Int) {
            buttons.forEachIndexed { i, button ->
                button.isChecked = i == index
                // Textual selection survives grayscale and theme changes.
                button.setText(if (i == index) "✓ ${labels[i]}" else labels[i])
            }
        }
        buttons.forEachIndexed { index, button ->
            button.onActivation { showSelection(index); onSelect(index) }
            add(button).minWidth(0f).minHeight(GameUiStyle.standardControlHeight).uniformX().growX().padRight(GameUiStyle.Space.s)
        }
        showSelection(selected)
    }

    fun field(label: String, input: Actor): Table = Table().apply {
        name = "AtlasField"
        GameUiStyle.applyWidget(input)
        add(label.toLabel(GameUiStyle.muted, GameUiStyle.Type.body).apply { wrap = true }).growX().left().row()
        add(input).growX().minWidth(0f).minHeight(GameUiStyle.standardControlHeight).padTop(GameUiStyle.Space.s).row()
    }

    fun disclosure(title: String, body: Actor, expanded: Boolean): Table {
        return ExpanderTab(title, fontSize = GameUiStyle.Type.label, startsOutOpened = expanded,
            headerPad = GameUiStyle.Space.m, expanderHeight = GameUiStyle.standardControlHeight) {
            it.add(body).growX()
        }.apply { name = "AtlasDisclosure"; GameUiStyle.applyWidget(this) }
    }

    fun footer(primary: Actor, secondary: List<Actor>): Table = Table().apply {
        name = "AtlasFooter"
        pad(GameUiStyle.Space.m)
        secondary.forEach {
            GameUiStyle.applyWidget(it)
            add(it).minHeight(GameUiStyle.standardControlHeight).growX().padRight(GameUiStyle.Space.m)
        }
        if (primary is Button) GameUiStyle.apply(primary, primary = true) else GameUiStyle.applyWidget(primary)
        add(primary).minHeight(GameUiStyle.standardControlHeight).growX()
    }
}
