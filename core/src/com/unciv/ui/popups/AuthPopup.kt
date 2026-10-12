package com.unciv.ui.popups

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle

class AuthPopup(stage: Stage, private val authSuccessful: ((Boolean) -> Unit)? = null)
    : Popup(stage) {

    constructor(screen: BaseScreen, authSuccessful: ((Boolean) -> Unit)? = null) : this(screen.stage, authSuccessful)

    private val passwordField: UncivTextField = UncivTextField("Password").apply { isPasswordMode = true }
    private val button: TextButton = "Authenticate".toTextButton()
    private val negativeButtonStyle: TextButton.TextButtonStyle =
        BaseScreen.skin.get("negative", TextButton.TextButtonStyle::class.java)

    init {
        button.onClick {
            Concurrency.run {
                try {
                    UncivGame.Current.onlineMultiplayer.multiplayerServer.authenticate(passwordField.text)
                    Concurrency.runOnGLThread {
                        authSuccessful?.invoke(true)
                        close()
                    }
                } catch (_: Exception) {
                    Concurrency.runOnGLThread {
                        clear()
                        addComponents("Authentication failed")
                    }
                }
            }
        }
        addComponents("Please enter your server password")
    }
    
    private fun addComponents(headerLabelText: String) {
        if (GameUiStyle.isAtlas) {
            innerTable.background = GameUiStyle.panelBackground("AuthPopup/Panel", 1f)
            add(AtlasWidgets.header(headerLabelText)).colspan(2)
                .width((maxPopupWidth - 64f).coerceAtMost(480f)).growX().row()
        } else addGoodSizedLabel(headerLabelText).colspan(2).row()
        add(passwordField).colspan(2).growX().pad(16f, 0f, 16f, 0f).row()
        addCloseButton(style = negativeButtonStyle) {
            authSuccessful?.invoke(false)
        }.growX().padRight(8f)
        if (GameUiStyle.isAtlas) {
            GameUiStyle.apply(button, primary = true)
            button.label.wrap = true
            bottomTable.add(button).growX().minWidth(0f).minHeight(44f).padLeft(8f)
            keyboardFocus = passwordField
        } else add(button).growX().padLeft(8f)
    }
}
