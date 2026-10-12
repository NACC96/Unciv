package com.unciv.ui.screens.worldscreen

import com.unciv.logic.GameInfo
import com.unciv.ui.screens.basescreen.BaseScreen

/** A secondary screen whose model belongs to one committed agent snapshot. */
interface AgentSnapshotScreen {
    val snapshotGameInfo: GameInfo
    val snapshotViewerCivId: String

    /** Resolve stable handles in the committed graph, or return null if access was lost. */
    fun recreateForSnapshot(worldScreen: WorldScreen): BaseScreen?
}

/** Called after publication, and on show for screens covered by another screen meanwhile. */
fun BaseScreen.refreshAgentSnapshot() {
    val bound = this as? AgentSnapshotScreen ?: return
    val world = game.worldScreen ?: return
    if (game.screen !== this || world.agentSession == null || game.gameInfo !== world.gameInfo) return
    if (bound.snapshotGameInfo === world.gameInfo && bound.snapshotViewerCivId == world.currentViewerCivId()) return
    // A private screen never carries its drafts or permissions across a viewer/game boundary.
    if (bound.snapshotGameInfo.gameId != world.gameInfo.gameId ||
        bound.snapshotViewerCivId != world.currentViewerCivId()) {
        game.popScreen()
        return
    }
    val replacement = bound.recreateForSnapshot(world)
    // Construction can itself navigate (mandatory choices); never overwrite that navigation.
    if (game.screen !== this) {
        replacement?.dispose()
        return
    }
    if (replacement == null) game.popScreen()
    else game.replaceCurrentScreen { replacement }
}
