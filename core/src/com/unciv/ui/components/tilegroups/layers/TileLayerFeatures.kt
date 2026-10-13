package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.view.CivView
import com.unciv.view.TileView
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.tilegroups.TileGroup
import kotlin.math.atan2


private class RoadImage {
    var roadStatus: RoadStatus = RoadStatus.None
    var image: Image? = null
}

class TileLayerFeatures(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    private val roadImages = HashMap<TileView, RoadImage>()

    private fun updateRoadImages(viewingCiv: CivView?) {

        if (tileGroup.isForMapEditorIcon)
            return

        val tileView = tileGroup.tileView
        val tileMapView = tileView.getTileMap()
        // Roads are map information. MAP reveals them without widening unit sight or game visibility.
        val revealsMap = viewingCiv?.gameView?.revealMap == true
        val isTileVisible = viewingCiv == null || revealsMap || viewingCiv.canSeeTile(tileView)
        val visibleNeighbors = tileView.getVisibleNeighbors().toList()

        // A view change may hide previously revealed neighbors; they no longer enter the update loop.
        val staleRoads = roadImages.entries.iterator()
        while (staleRoads.hasNext()) {
            val entry = staleRoads.next()
            if (entry.key in visibleNeighbors) continue
            entry.value.image?.let { removeOwnedActor(it) }
            staleRoads.remove()
        }

        for (neighbor in visibleNeighbors) {
            var roadImage = roadImages[neighbor]
            val currentStatus = roadImage?.roadStatus ?: RoadStatus.None

            val roadStatus = when {
                !isTileVisible && !viewingCiv.canSeeTile(neighbor) -> RoadStatus.None // fog keeps real sight
                tileView.roadStatus == RoadStatus.None || neighbor.roadStatus === RoadStatus.None -> RoadStatus.None
                tileView.roadStatus == RoadStatus.Road || neighbor.roadStatus === RoadStatus.Road -> RoadStatus.Road
                else -> RoadStatus.Railroad
            }
            if (currentStatus == roadStatus) continue // the image is correct

            if (roadImage == null) { // create when missing
                roadImage = RoadImage().also { roadImages[neighbor] = it }
                roadImages[neighbor] = roadImage
            }

            roadImage.roadStatus = roadStatus

            if (roadImage.image != null) {
                removeOwnedActor(roadImage.image!!)
                roadImage.image = null
            }
            if (roadStatus == RoadStatus.None) continue // no road image

            val image = ImageGetter.getImage(strings.orFallback { roadsMap[roadStatus]!! })
            roadImage.image = image

            val relativeWorldPosition = tileMapView.getNeighborTilePositionAsWorldCoords(tileView, neighbor)

            // This is some crazy voodoo magic so I'll explain.
            // Roads start at the tile origin; we offset them to the tile centre then toward the neighbor.
            image.setPosition(
                tileX + 25f - relativeWorldPosition.x * 0.8f * 25f,
                tileY + 25f - relativeWorldPosition.y * 0.8f * 25f
            )

            image.setSize(10f, 6f)
            image.setOrigin(0f, 3f) // This is so that the rotation is calculated from the middle of the road and not the edge

            image.rotation = (180 / Math.PI * atan2(relativeWorldPosition.y.toDouble(),relativeWorldPosition.x.toDouble())).toFloat()

            addOwnedActor(image)
        }

    }

    override fun doUpdate(viewingCiv: CivView?) {
        updateRoadImages(viewingCiv)
    }

    fun dim() {
        forEachOwnedActor { it.color.a = 0.5f }
    }

}
