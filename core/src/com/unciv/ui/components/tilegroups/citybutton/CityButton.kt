package com.unciv.ui.components.tilegroups.citybutton

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.centerX
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.cityscreen.CityReligionInfoTable
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.view.ForeignCityView
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import yairm210.purity.annotations.Readonly

/**
 *  The city "button" with decorations on the WorldScreen map.
 *  - Manages moving down when selected ([moveButtonDown], [moveButtonUp]).
 *  - Does not include the garrison / air unit list when the city is selected.
 *
 *  Top to bottom:
 *  - [AirUnitTable] (if there are any)
 *  - [DefenceTable]
 *  - [CityTable]
 *  - [InfluenceTable] (city-states only)
 *  - [StatusTable]
 *  Floating:
 *  - Health bar (if damaged and viewable), positioned just inside the upper border of [CityTable]
 *  - Hidden unit markers (as little triangles just below [CityTable])
 *
 *  The Atlas skin uses a single-row banner instead, see [updateBanner].
 */
class CityButton(val foreignCityView: ForeignCityView, private val tileGroup: TileGroup) : Table(BaseScreen.skin) {
    companion object {
        val ColorConstruction: Color = Color.valueOf("#C48C3E")
        val ColorGrowth: Color = Color.valueOf("#82E14E")
        /** The Atlas banner covers only the lower part of the city tile (its origin is ~27 below the hex centre). */
        private const val BANNER_Y = -6f
    }

    init {
        touchable = Touchable.disabled // setButtonActions will override this
    }

    private lateinit var cityTable: CityTable

    private val listOfHiddenUnitMarkers: MutableList<Actor> = mutableListOf()
    private var isButtonMoved = false
    private var isViewable = true

    val viewingPlayer = foreignCityView.getViewingCiv()

    @Readonly private fun belongsToViewingCiv() = foreignCityView.belongsTo(viewingPlayer)

    fun update(isCityViewable: Boolean) {
        if (GameUiStyle.isAtlas) return updateBanner(isCityViewable)
        val selectedPlayer = foreignCityView.getViewingCiv()
        isViewable = isCityViewable

        clear()
        setButtonActions()

        // Top-to-bottom layout

        // If any air units in the city - add number indicator
        val visibleAirUnits = tileGroup.tileView.getVisibleUnits().filter { it.isAirUnit() }
        if (isCityViewable && visibleAirUnits.isNotEmpty()) {
            add(AirUnitTable(foreignCityView.getCity(), visibleAirUnits.size)).padBottom(5f).row()
        }

        // Add City strength table
        add(DefenceTable(foreignCityView.getCity(), selectedPlayer)).row()

        // Add City main table: pop, name, religion, construction, nation icon
        cityTable = CityTable(foreignCityView.gameView.getCityView(foreignCityView.getCity()))
        add(cityTable).row()

        // If city state - add influence bar
        if (foreignCityView.isCityState() && foreignCityView.civKnows(selectedPlayer)) {
            val diplomacyManager = foreignCityView.getDiplomacyManagerWith(selectedPlayer)!!
            add(InfluenceTable(diplomacyManager.getInfluence(), diplomacyManager.relationshipLevel())).padTop(1f).row()
        }

        // Add statuses: connection, resistance, puppet, raze, WLTKD
        add(StatusTable(foreignCityView.getCity(), selectedPlayer)).padTop(3f)

        pack()

        // If city damaged - add health bar
        if (isCityViewable && foreignCityView.getHealth() < foreignCityView.getMaxHealth().toFloat()) {
            val healthBar = ImageGetter.getHealthBar(foreignCityView.getHealth().toFloat(),
                foreignCityView.getMaxHealth().toFloat(), 100f, 3f)
            addActor(healthBar)
            healthBar.center(this)
            healthBar.y = cityTable.y + cityTable.height - healthBar.height - 1f
        }

        setOrigin(Align.center)
        centerX(tileGroup)

        updateHiddenUnitMarkers(isCityViewable)
    }

    /**
     *  Atlas: the compact one-row [CityTable] pill sits on the lower part of the city tile, so the
     *  city art stays visible. Badges float around it instead of stacking rows:
     *  defence and air units as chips on its top corners, status icons inline to its right,
     *  health as a thin bar along its top inner edge.
     */
    private fun updateBanner(isCityViewable: Boolean) {
        val selectedPlayer = foreignCityView.getViewingCiv()
        val city = foreignCityView.getCity()
        isViewable = isCityViewable

        clear()
        setButtonActions()

        cityTable = CityTable(foreignCityView.gameView.getCityView(city))
        add(cityTable).row()

        if (foreignCityView.isCityState() && foreignCityView.civKnows(selectedPlayer)) {
            val diplomacyManager = foreignCityView.getDiplomacyManagerWith(selectedPlayer)!!
            add(InfluenceTable(diplomacyManager.getInfluence(), diplomacyManager.relationshipLevel(),
                (cityTable.width - 24f).coerceIn(40f, 100f), 3f)).padTop(2f).row()
        }
        pack()

        val top = cityTable.y + cityTable.height
        val isDamaged = isCityViewable && foreignCityView.getHealth() < foreignCityView.getMaxHealth()
        if (isDamaged) {
            val healthBar = ImageGetter.getHealthBar(foreignCityView.getHealth().toFloat(),
                foreignCityView.getMaxHealth().toFloat(), (cityTable.width - 36f).coerceAtLeast(12f), 2f)
            addActor(healthBar)
            healthBar.setPosition(cityTable.x + (cityTable.width - healthBar.width) / 2, top - healthBar.height - 2f)
        }

        if (isDamaged || isDefenceRelevant(city, selectedPlayer)) {
            val defence = DefenceTable(city, selectedPlayer)
            defence.pack()
            addActor(defence)
            defence.setPosition(cityTable.x + 8f, top - 3f)
        }

        val visibleAirUnits = tileGroup.tileView.getVisibleUnits().filter { it.isAirUnit() }
        if (isCityViewable && visibleAirUnits.isNotEmpty()) {
            val air = AirUnitTable(city, visibleAirUnits.size, 11f)
            air.pack()
            addActor(air)
            air.setPosition(cityTable.x + cityTable.width - air.width - 8f, top - 3f)
        }

        val status = StatusTable(city, selectedPlayer, 14f)
        if (status.cells.size > 0) {
            status.pack()
            addActor(status)
            status.setPosition(cityTable.x + cityTable.width + 2f, cityTable.y + (cityTable.height - status.height) / 2)
        }

        setOrigin(Align.center)
        centerX(tileGroup)
        y = BANNER_Y

        updateHiddenUnitMarkers(isCityViewable)
    }

    /** Strength matters when the owner is at war: with the viewer, or with anyone for spectators and the owner itself. */
    private fun isDefenceRelevant(city: City, viewer: Civilization): Boolean = when {
        viewer.isSpectator() || city.civ == viewer -> city.civ.isAtWar()
        else -> city.civ.isAtWarWith(viewer)
    }

    private enum class HiddenUnitMarkerPosition {Left, Center, Right}

    private fun updateHiddenUnitMarkers(isCityViewable: Boolean) {
        // remove obsolete markers
        for (marker in listOfHiddenUnitMarkers)
            cityTable.removeActor(marker)
        listOfHiddenUnitMarkers.clear()

        if (!isCityViewable) return

        // detect civilian in the city center
        if (!isButtonMoved && (tileGroup.tileView.civilianUnit != null))
            insertHiddenUnitMarker(HiddenUnitMarkerPosition.Center)

        for (neighbor in tileGroup.tileView.getVisibleNeighbors()) {
            val direction = tileGroup.tileView.position().minus(neighbor.position())

            if (isButtonMoved) {
                when {
                    // detect civilian left-below the city
                    (neighbor.civilianUnit != null) && direction.x == 0 && direction.eq(0, 1) ->
                        insertHiddenUnitMarker(HiddenUnitMarkerPosition.Left)
                    // detect military under the city
                    neighbor.militaryUnit != null && direction.eq(1, 1) ->
                        insertHiddenUnitMarker(HiddenUnitMarkerPosition.Center)
                    // detect civilian right-below the city
                    (neighbor.civilianUnit != null) && direction.eq(1, 0) ->
                        insertHiddenUnitMarker(HiddenUnitMarkerPosition.Right)
                }
            } else if (neighbor.militaryUnit != null) {
                when {
                    // detect military left from the city
                    direction.eq(0, 1) ->
                        insertHiddenUnitMarker(HiddenUnitMarkerPosition.Left)
                    // detect military right from the city
                    direction.eq(1, 0) ->
                        insertHiddenUnitMarker(HiddenUnitMarkerPosition.Right)
                }
            }
        }
    }

    private fun insertHiddenUnitMarker(pos: HiddenUnitMarkerPosition) {
        // center of the city button +/- size of the 1.5 tiles
        val positionX = cityTable.width / 2 + (pos.ordinal - 1) * 60f

        val indicator = ImageGetter.getTriangle().apply {
            color = foreignCityView.getCivInnerColor()
            setSize(12f, 8f)
            setOrigin(Align.center)
            if (!isButtonMoved) {
                rotation = 180f
                setPosition(positionX - width / 2, -height)
            } else
                setPosition(positionX - width / 2, -height) // height compensation because of asymmetrical icon
        }
        cityTable.addActor(indicator)
        listOfHiddenUnitMarkers.add(indicator)
    }

    private fun setButtonActions() {
        val unitTable = GUI.getUnitTable()

        // So you can click anywhere on the button to go to the city
        touchable = Touchable.childrenOnly

        fun enterCityOrInfoPopup() {
            // second tap on the button will go to the city screen
            // if this city belongs to you and you are not iterating though the air units
            val cityView = foreignCityView.tryGetCityView()
            val isIteratingUnits = tileGroup.tileView.getVisibleUnits().none { it == unitTable.selectedUnit }
            if (cityView != null && isIteratingUnits) {
                GUI.pushScreen{ CityScreen(cityView) }
            }
            else if (foreignCityView.isKnownTo(viewingPlayer))
                foreignCityInfoPopup()
        }

        onClick {
            // clicking swings the button a little down to allow selection of units there.
            // this also allows to target selected units to move to the city tile from elsewhere.
            if (isButtonMoved) {
                enterCityOrInfoPopup()
            } else {
                moveButtonDown()
                if ((unitTable.selectedUnit == null || !unitTable.selectedUnit!!.hasMovement()) && belongsToViewingCiv())
                    unitTable.citySelected(foreignCityView)
            }
        }
        onRightClick(action = ::enterCityOrInfoPopup)

        // when deselected, move city button to its original position
        if (unitTable.selectedCity != foreignCityView && unitTable.selectedUnit?.getTile() != foreignCityView.getCenterTile() && unitTable.selectedSpy == null)
            moveButtonUp()
    }

    fun moveButtonDown() {
        if (isButtonMoved)
            return
        val moveButtonAction = Actions.sequence(
            Actions.moveTo(tileGroup.x, tileGroup.y - height, 0.4f, Interpolation.swingOut),
            Actions.run {
                isButtonMoved = true
                updateHiddenUnitMarkers(isViewable)
            }
        )
        parent.addAction(moveButtonAction) // Move the whole cityButtonLayerGroup down, so the CityButton remains clickable
    }

    fun moveButtonUp() {
        if (!isButtonMoved)
            return
        val moveButtonAction = Actions.sequence(
            Actions.moveTo(tileGroup.x, tileGroup.y, 0.4f, Interpolation.sine),
            Actions.run {
                isButtonMoved = false
                updateHiddenUnitMarkers(isViewable)
            }
        )
        parent.addAction(moveButtonAction)
    }

    private fun foreignCityInfoPopup() {
        fun openDiplomacy() = GUI.pushScreen{ 
            DiplomacyScreen(foreignCityView.gameView.civView, foreignCityView.owningCiv())
        }

        val espionageVisible = foreignCityView.isEspionageEnabled()
                && foreignCityView.spyIsSetUpAtCity(viewingPlayer)

        // If there's nothing to display cuz no Religion - skip popup
        if (!foreignCityView.isReligionEnabled() && !espionageVisible) return openDiplomacy()

        val popup = Popup(GUI.getWorldScreen()).apply {
            name = "ForeignCityInfoPopup"
            add(CityTable(foreignCityView.gameView.getCityView(foreignCityView.getCity()), true)).fillX().padBottom(5f).colspan(3).row()
            if (foreignCityView.isReligionEnabled())
                add(CityReligionInfoTable(foreignCityView, true)).colspan(3).row()
            addOKButton("Diplomacy") { openDiplomacy() }
            if (espionageVisible) addButton("View") { GUI.pushScreen{ 
                CityScreen(GUI.getWorldScreen().selectedGameView.getCityView(foreignCityView.getCity()))
            } }
            add().expandX()
            addCloseButton {
                GUI.getWorldScreen().run { nextTurnButton.update() }
            }
        }
        popup.open()
    }

    // For debugging purposes
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)

    override fun act(delta: Float) {
        return // actions should only be for the CityButtonLayerGroup
    }
}
