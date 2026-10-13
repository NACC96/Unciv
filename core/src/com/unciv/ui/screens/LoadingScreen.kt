package com.unciv.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageWithCustomSize
import com.unciv.ui.popups.popups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.popups.LoadingPopup

/** Atlas uses a lightweight loading card; other skins retain their screenshot-based transition. */
class LoadingScreen(
    previousScreen: BaseScreen? = null
) : BaseScreen() {
    private val screenshot: Texture?
    private var loadingPopup: LoadingPopup? = null

    init {
        if (skinStrings.isAtlas) {
            screenshot = null
            val card = Table().apply {
                name = "AtlasLoadingCard"
                background = GameUiStyle.panelBackground("Atlas/Loading/Card", 1f)
                pad(28f)
                add("Unciv".toLabel(GameUiStyle.accent, GameUiStyle.Type.display)).row()
                add(Constants.loading.toLabel(GameUiStyle.ink, GameUiStyle.Type.title).apply { wrap = true })
                    .minWidth(0f).growX().padTop(16f)
            }
            stage.addActor(Table().apply {
                setFillParent(true)
                background = GameUiStyle.background("Atlas/Loading/Background", GameUiStyle.inset)
                pad(24f)
                add(card).width((this@LoadingScreen.stage.width - 48f).coerceAtMost(360f))
            })
        } else {
            screenshot = takeScreenshot(previousScreen)
            val image = ImageWithCustomSize(
                TextureRegion(
                    screenshot,
                    0,
                    screenshot.height,
                    screenshot.width,
                    -screenshot.height
                )
            )
            image.width = stage.width
            image.height = stage.height
            stage.addActor(image)
            stage.addAction(Actions.sequence(
                Actions.delay(1000f),
                Actions.run {
                    loadingPopup = LoadingPopup(this)
                }
            ))
        }
    }

    private fun takeScreenshot(previousScreen: BaseScreen?): Texture {
        if (previousScreen != null) {
            for (popup in previousScreen.popups) popup.isVisible = false
            previousScreen.render(Gdx.graphics.deltaTime)
        }
        val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        val screenshot = Texture(pixmap)
        pixmap.dispose()

        if (previousScreen != null) {
            for (popup in previousScreen.popups) popup.isVisible = true
        }
        return screenshot
    }


    override fun dispose() {
        screenshot?.dispose()
        stage.root.clearActions() // super.dispose does that too, but prevent race condition
        loadingPopup?.close() // Prevent leak due to EventReceiver reference
        super.dispose()
    }
}
