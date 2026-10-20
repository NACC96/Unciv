package com.unciv.ui.screens

import com.unciv.ui.components.extensions.center
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

class GameStartScreen : BaseScreen() {
    init {
        val logoImage = ImageGetter.getExternalImage("banner.png")
        // This screen appears before atlases/skins/fonts are ready. Fit the original startup mark
        // without introducing a theme dependency or changing native window/resolution settings.
        val scale = minOf(stage.width * 0.72f / logoImage.width, stage.height * 0.42f / logoImage.height, 1f)
        logoImage.setSize(logoImage.width * scale, logoImage.height * scale)
        logoImage.center(stage)
        stage.addActor(logoImage)
    }
}
