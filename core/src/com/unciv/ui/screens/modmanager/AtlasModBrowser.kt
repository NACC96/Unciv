package com.unciv.ui.screens.modmanager

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick

/** Keeps each browser's scroll position while exchanging columns for tabs on a small window. */
internal class AtlasModBrowser(
    installed: Actor, online: Actor, details: Actor,
    installedHeader: Label, onlineHeader: Label, fontScale: Float
) : Table() {
    private val scale = fontScale
    private val panels = listOf(
        panel(installedHeader, installed), panel(onlineHeader, online),
        panel("Mod info and options".toLabel(), details)
    )
    private var selected = 0
    private var narrow: Boolean? = null
    private val tabs = Table()
    private val tabButtons = listOf("Current mods", "Downloadable mods", "Details").mapIndexed { index, title ->
        GameUiStyle.button(title).apply {
            label.wrap = true
            onClick { selected = index; rebuild() }
            tabs.add(this).growX().minWidth(0f).pad(2f)
        }
    }
    private val body = Table()

    init { pad(8f) }

    private fun panel(header: Label, content: Actor) = Table().apply {
        background = GameUiStyle.panelBackground("Atlas/Mods/Panel", 1f)
        pad(12f)
        header.wrap = true
        header.color = GameUiStyle.accent
        add(header).growX().minWidth(0f).padBottom(12f).row()
        add(content).grow().minWidth(0f).minHeight(0f)
        GameUiStyle.applyWidget(this)
    }

    fun showDetails() { if (narrow == true) { selected = 2; rebuild() } }

    private fun rebuild() {
        clearChildren()
        body.clearChildren()
        tabButtons.forEachIndexed { index, button -> button.isChecked = selected == index }
        if (narrow == true) {
            add(tabs).growX().minWidth(0f).padBottom(8f).row()
            body.add(panels[selected]).grow().minWidth(0f).minHeight(0f)
        } else {
            panels.forEachIndexed { index, panel ->
                body.add(panel).grow().minWidth(0f).minHeight(0f).padRight(if (index < 2) 8f else 0f)
            }
        }
        add(body).grow().minWidth(0f).minHeight(0f)
        invalidateHierarchy()
    }

    override fun layout() {
        val shouldStack = width < 1050f * scale
        if (narrow != shouldStack) { narrow = shouldStack; rebuild() }
        if (!shouldStack) panels.forEachIndexed { index, panel ->
            body.getCell(panel).width(((width - 32f).coerceAtLeast(1f)) * if (index == 2) 0.4f else 0.3f)
        }
        super.layout()
    }

    override fun getMinWidth() = 0f
    override fun getPrefWidth() = 0f
    override fun getMinHeight() = 0f
    override fun getPrefHeight() = 0f
}
