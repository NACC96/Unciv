package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.widgets.UnitIconGroup
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.padTopDescent
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.pickerscreens.CityRenamePopup

/** Widget for the City Screen -
 *  the panel at bottom center showing the city name and offering arrows to cycle through the cities. */
class CityScreenCityPickerTable(private val cityScreen: CityScreen) : Table() {

    fun update() {
        val cityView = cityScreen.cityView
        val civView = cityView.owningCiv()
        background = BaseScreen.skinStrings.getUiBackground("CityScreen/CityPickerTable", BaseScreen.skinStrings.roundedEdgeRectangleShape, civView.getOuterColor())
        if (GameUiStyle.isAtlas) background = null
        clear()

        if (cityScreen.viewableCities.size > 1) {
            val prevCityButton = Table() // so we get a wider clickable area than just the image itself
            val image = ImageGetter.getImage("OtherIcons/BackArrow")
            image.color = if (GameUiStyle.isAtlas) GameUiStyle.accent else civView.getInnerColor()
            prevCityButton.add(image).size(25f).pad(10f)
            prevCityButton.touchable = Touchable.enabled
            prevCityButton.onClick { cityScreen.page(-1) }
            add(prevCityButton).pad(if (GameUiStyle.isAtlas) 0f else 10f)
        } else add()

        val cityNameTable = Table()

        if (cityView.isBeingRazed()) {
            val fireImage = ImageGetter.getImage("OtherIcons/Fire")
            cityNameTable.add(fireImage).size(20f).padRight(5f)
        }

        if (cityView.isPuppet()) {
            val starImage = ImageGetter.getImage("OtherIcons/Puppet").apply { color = Color.LIGHT_GRAY }
            cityNameTable.add(starImage).size(20f).padRight(5f)
        }

        if (cityView.isInResistance()) {
            val resistanceImage = ImageGetter.getImage("StatIcons/Resistance")
            cityNameTable.add(resistanceImage).size(20f).padRight(5f)
        }

        if (cityView.isCapital()) {
            val starImage = ImageGetter.getImage("OtherIcons/Star").apply { color = Color.LIGHT_GRAY }
            cityNameTable.add(starImage).size(20f).padRight(5f)
        }

        val currentCityLabel = cityView.name
            .toLabel(fontSize = if (GameUiStyle.isAtlas) GameUiStyle.Type.display else 30, fontColor = if (GameUiStyle.isAtlas) GameUiStyle.ink else civView.getInnerColor(), hideIcons = true)
        if (cityScreen.canChangeState) currentCityLabel.onClick {
            CityRenamePopup(
                screen = cityScreen,
                cityView = cityView,
                actionOnClose = {
                    cityScreen.game.replaceCurrentScreen { cityScreen.recreate() }
                }
            )
        }

        currentCityLabel.setEllipsis(true)
        cityNameTable.add(currentCityLabel).growX().minWidth(0f).padTopDescent()

        val currentCityPop = " (${cityView.getPopulationCount()})"
            .toLabel(fontSize = if (GameUiStyle.isAtlas) GameUiStyle.Type.display else 30, fontColor = if (GameUiStyle.isAtlas) GameUiStyle.ink else civView.getInnerColor(), hideIcons = true)
        cityNameTable.add(currentCityPop).padTopDescent()

        val garrison = cityView.getGarrison()
        if (garrison != null) {
            cityNameTable.add(UnitIconGroup(garrison, 30f)).padLeft(5f)
        }

        if (GameUiStyle.isAtlas) add(cityNameTable).growX().minWidth(0f)
        else {
            val width = if (cityScreen.isCrampedPortrait()) stage.width / 3 else stage.width / 4
            add(cityNameTable).width(width)
        }

        if (cityScreen.viewableCities.size > 1) {
            val nextCityButton = Table() // so we gt a wider clickable area than just the image itself
            val image = ImageGetter.getImage("OtherIcons/BackArrow")
            image.setSize(25f, 25f)
            image.setOrigin(Align.center)
            image.rotation = 180f
            image.color = if (GameUiStyle.isAtlas) GameUiStyle.accent else civView.getInnerColor()
            nextCityButton.add(image).size(25f).pad(10f)
            nextCityButton.touchable = Touchable.enabled
            nextCityButton.onClick { cityScreen.page(1) }
            add(nextCityButton).pad(if (GameUiStyle.isAtlas) 0f else 10f)
        } else add()

        pack()
    }

}
