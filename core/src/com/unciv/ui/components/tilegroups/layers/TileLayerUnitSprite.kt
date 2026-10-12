package com.unciv.ui.components.tilegroups.layers

import com.unciv.UncivGame
import com.unciv.view.CivView
import com.unciv.view.ForeignMapUnitView
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.ui.components.NonTransformGroup
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.images.ImageGetter

class UnitSpriteSlot {
    val spriteGroup = NonTransformGroup()
    var currentImageLocation = ""
    var unitId: Int? = null
}

class TileLayerUnitSprite(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    // Slots are only filled if units exist, and images for those units exist
    private var civilianSlot: UnitSpriteSlot? = null
    private var militarySlot: UnitSpriteSlot? = null
    private val visualMasks = HashMap<Int, Int>()
    private var maskGeneration = 0L

    fun visibleUnitIds(): List<Int> = listOfNotNull(civilianSlot?.unitId, militarySlot?.unitId)

    /** Hide only this committed sprite slot. Unit flags, tile hit targets and selection remain authoritative. */
    fun maskForVisual(unitId: Int): AutoCloseable? {
        if (civilianSlot?.unitId != unitId && militarySlot?.unitId != unitId) return null
        val generation = maskGeneration
        visualMasks[unitId] = (visualMasks[unitId] ?: 0) + 1
        applyVisualMasks()
        var released = false
        return AutoCloseable {
            if (!released) {
                released = true
                if (generation == maskGeneration) {
                    val remaining = (visualMasks[unitId] ?: 1) - 1
                    if (remaining <= 0) visualMasks.remove(unitId) else visualMasks[unitId] = remaining
                    applyVisualMasks()
                }
            }
        }
    }

    private fun applyVisualMasks() {
        for (slot in listOfNotNull(civilianSlot, militarySlot))
            slot.spriteGroup.isVisible = isVisible && (visualMasks[slot.unitId] ?: 0) == 0
    }


    fun getSpriteSlot(unit: MapUnit) = if (unit.isCivilian()) civilianSlot else militarySlot

    private fun updateSlot(currentSlot: UnitSpriteSlot?, unitView: ForeignMapUnitView?, isShown: Boolean): UnitSpriteSlot? {

        currentSlot?.unitId = unitView?.id
        var location = ""
        var nationName = ""

        if (unitView != null && isShown && UncivGame.Current.settings.showPixelUnits) {
            location = strings.getUnitImageLocation(unitView)
            nationName = "${unitView.civName}-"
        }

        if (currentSlot == null && location == "") return null // No-op - had none, has none
        if (currentSlot?.currentImageLocation == "$nationName$location") return currentSlot // No-op - had, has

        if (location == "" || !ImageGetter.imageExists(location)){
            currentSlot?.spriteGroup?.let { removeOwnedActor(it) }
            return null
        }

        val slot = currentSlot ?: UnitSpriteSlot().apply {
            // Position the container at the tile origin so children use tile-local coords.
            // This keeps spriteGroup visible to viewport culling and preserves the
            // tile-local child positions that WorldMapHolder's animation code relies on.
            spriteGroup.setPosition(tileX, tileY)
            spriteGroup.setSize(size, size)
            addOwnedActor(spriteGroup)
        }
        slot.unitId = unitView!!.id
        slot.currentImageLocation = "$nationName$location"
        slot.spriteGroup.clear()

        val civView = unitView!!.civ()
        val pixelUnitImages = ImageGetter.getLayeredImageColored(
            location,
            null,
            civView.getInnerColor(),
            civView.getOuterColor()
        )
        for (pixelUnitImage in pixelUnitImages) {
            slot.spriteGroup.addActor(pixelUnitImage)
            // tileLocal=true: spriteGroup is already at (tileX,tileY) so children only need
            // the hex-local offset (hexagonImagePosition), not the full absolute position.
            pixelUnitImage.setHexagonSize(tileLocal = true)
        }
        return slot
    }

    fun dim() {
        forEachOwnedActor { it.color.a = 0.5f }
    }

    override fun doUpdate(viewingCiv: CivView?) {

        val isPixelUnitsEnabled = UncivGame.Current.settings.showPixelUnits
        val isViewable = isViewable(viewingCiv)

        val isCivilianSlotShown = isPixelUnitsEnabled && isViewable
        val isMilitarySlotShown = isPixelUnitsEnabled && isViewable

        civilianSlot = updateSlot(civilianSlot, tileGroup.tileView.civilianUnit, isShown = isCivilianSlotShown)
        militarySlot = updateSlot(militarySlot, tileGroup.tileView.militaryUnit, isShown = isMilitarySlotShown)
    }

    override fun determineVisibility() {
        isVisible = civilianSlot != null || militarySlot != null
        applyVisualMasks()
    }

    fun reset() {
        maskGeneration++
        visualMasks.clear()
        civilianSlot?.spriteGroup?.let { removeOwnedActor(it) }
        militarySlot?.spriteGroup?.let { removeOwnedActor(it) }
        civilianSlot = null
        militarySlot = null
    }
}
