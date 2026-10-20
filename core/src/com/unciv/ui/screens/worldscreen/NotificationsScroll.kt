package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.logic.agents.ControllerKind
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.MayaCalendar.toMayaNumerals
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.removeFontColorFromStatIcons
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.images.IconCircleGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.BaseScreen.Companion.skinStrings
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.utils.withHash
import yairm210.purity.annotations.Pure
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/*TODO
 *  Un-hiding the notifications when new ones arrive is a little pointless due to Categories:
 *      * try to scroll new into view? Very complicated as the "old" state is only "available" in the Widgets
 *      * Don't unless a new user option disables categories, then scroll to top?
 */

class NotificationsScroll(
    private val worldScreen: WorldScreen
) : ScrollPane(null) {
    enum class UserSetting(val static: Boolean = false) {
        Disabled(true), Hidden, Visible, Permanent(true);
        companion object { @Pure fun default() = Visible }
    }

    private companion object {
        /** Limit width by wrapping labels to this percentage of the stage */
        const val maxWidthOfStage = 0.333f
        /** Logical size of the notification icons */
        const val iconSize = 30f
        /** Atlas notification icon size, drawn at stage scale */
        const val atlasIconSize = 20f
        /** Logical font size used in notification and category labels */
        const val fontSize = 30
        /** This is the spacing between categories and also the spacing between the next turn button and the first header */
        const val categoryTopPad = 15f
        /** Spacing between rightmost Label edge and right Screen limit */
        const val rightPadToScreenEdge = 10f
        /** Extra right padding when there's a selection */
        const val selectionExtraRightPad = 12f
        /** Padding within ListItem, included in clickable area, except to the right */
        const val listItemPad = 3f
        /** Top/Bottom padding within ListItem replaces [listItemPad] for the [highlightNotification] */
        const val selectedListItemPad = 15f
        /** Extra spacing between the outer edges of the category header decoration lines and
         *  the left and right edges of the widest notification label - this is the background's
         *  edge radius and looks (subjectively) nice. */
        const val categoryHorizontalPad = 13f
        /** Size of the restore button */
        const val restoreButtonSize = 42f
        /** Distance of restore button to TileInfoTable and screen edge */
        const val restoreButtonPad = 12f
        /** The outer size of the number+circle within the restore button */
        const val restoreButtonNumbersSize = 0.5f * restoreButtonSize
        /** Font size for the "count" number label overlaid on the restore button */
        const val restoreButtonNumberFontSize = 13
        /** The x/y coords of the center of the number+circle within the restore button */
        const val restoreButtonNumbersCenter = restoreButtonSize - restoreButtonNumbersSize / 2
        /** Atlas bell: a quiet rounded icon button */
        const val atlasBellSize = 36f
        /** Atlas drawer margin from the screen edge and the covered header / minimap */
        const val atlasEdgeMargin = 8f
        /** Atlas drawer room for the surface halo inside this clipping pane */
        const val atlasHaloMargin = 18f
        /** Background tint for [oneTimeNotification] */
        private val oneTimeNotificationColor get() =
            skinStrings.getUIColor("NotificationScroll/OneTimeNotificationColor", Color.valueOf("#fceea8"))
    }

    //region private fields
    /** Atlas shows one drawer at stage scale on a rounded surface; other skins keep the half-scale list. */
    private val atlas = GameUiStyle.isAtlas
    /** Scale the entire ScrollPane by this factor */
    private val scaleFactor = if (atlas) 1f else 0.5f
    /** Complement of [scaleFactor] because multiplication is cheaper */
    private val inverseScaleFactor = 1f / scaleFactor

    private var notificationsHash: Int = 0
    /** What the list showed at the last update. Agent snapshots reload unchanged notifications
     *  as new objects (Notification has no equality contract), so identity alone means nothing new. */
    private var contentSignature: Int = 0
    /** News arriving while another nation acts remains unread until its viewer can act. */
    private val pendingContentViewers = HashSet<String>()
    /** Turn number and current player at the last update, to notice the viewer's own turn starting. */
    private var lastTurnKey: String? = null

    private var notificationsTable = Table()
    private var topSpacerCell: Cell<Actor?>? = null
    private var bottomSpacerCell: Cell<Actor?>? = null

    private val maxEntryWidth = (if (atlas) (if (worldScreen.stage.width < 960f) worldScreen.stage.width - 32f else minOf(260f, worldScreen.stage.width * .25f)) else worldScreen.stage.width * maxWidthOfStage) * inverseScaleFactor

    /** For category header decoration lines */
    private val minCategoryLineWidth = worldScreen.stage.width * 0.075f
    /** Show restoreButton when less than this much of the pane is left */
    private val scrolledAwayEpsilon = maxEntryWidth * 0.3f // yes should include inverseScaleFactor

    private val restoreButton = RestoreButton()

    private lateinit var userSetting: UserSetting
    private var userSettingChanged = false
    private var enlargeHighlight = false

    /** onClick sets this to request highlighting on the next update (which it then triggers) */
    private var clickedNotification: Notification? = null
    /** Set _only_ during updateContent to draw the actual highlighting */
    private var highlightNotification: Notification? = null
    /** Set _only_ during updateContent to draw the highlighted entry with a colored background */
    private var coloredHighlight = false
    /** Used once after updateContent to scroll the highlighted notification into view */
    private var selectedCell: Cell<out Table>? = null
    //endregion

    /** Display one additional notification once, to re-show an entry from the history in overview */
    var oneTimeNotification: Notification? = null

    init {
        actor = notificationsTable.right()
        touchable = Touchable.childrenOnly
        setOverscroll(false, true)
        setScale(scaleFactor)
        height = worldScreen.stage.height * inverseScaleFactor
    }

    /** Compact inspection tabs temporarily occlude this layer without changing its saved setting. */
    fun setAtlasLayerVisible(visible: Boolean) {
        isVisible = visible && (!::userSetting.isInitialized || userSetting != UserSetting.Disabled)
        restoreButton.isVisible = visible
    }

    /**
     * If a Gdx ScrollPane has content larger than its size on both dimensions (if only one axis is
     * scrollable, the wheel will always scroll that axis), it will prefer mapping the mouse wheel
     * to *horizontal* scrolling, which is not quite the best choice for our notifications.
     *
     * The intuitive approach might be to change the listener (by overriding [addScrollListener]),
     * but luckily this works too.
     */
    override fun getMouseWheelX() = 0f

    /** Access to hidden "state" - writing it will ensure this is fully visible or hidden and the
     *  restore button shown as needed - with animation. */
    @Suppress("MemberVisibilityCanBePrivate")  // API for future use
    var isHidden: Boolean
        get () = scrollX <= scrolledAwayEpsilon
        set(value) {
            restoreButton.unblock()
            scrollX = if (value) 0f else maxX
        }

    /**
     * Update widget contents if necessary and recalculate layout.
     *
     * The 'covered' parameters are used to make sure we can scroll up or down far enough so the bottom or top entry is visible.
     *
     * @param notifications Data to display
     * @param coveredNotificationsTop Height of the portion that may be covered on the bottom w/o any padding
     * @param coveredNotificationsBottom Height of the portion that may be covered on the bottom w/o any padding
     */
    internal fun update(
        notifications: List<Notification>,
        coveredNotificationsTop: Float,
        coveredNotificationsBottom: Float
    ) {
        getUserSetting()
        if (userSetting == UserSetting.Disabled) {
            restoreButton.place(coveredNotificationsTop, coveredNotificationsBottom)
            applyUserSettingChange()
            restoreButton.updateCount(notifications.size)
            return
        }

        enlargeHighlight = GUI.getSettings().enlargeSelectedNotification

        // Hidden by the user or the Hidden setting: a rebuild must never drag it back into view.
        val keptHidden = restoreButton.isActive && !userSettingChanged
        val newSignature = notifications.contentSignature()
        val hasNewContent = newSignature != contentSignature
        contentSignature = newSignature
        val gameInfo = worldScreen.gameInfo
        val turnKey = "${gameInfo.turns}/${gameInfo.currentPlayer}"
        val viewer = worldScreen.viewingCiv
        val session = worldScreen.agentSession
        val humanViewer = !viewer.isSpectator() && if (session == null) viewer.isHuman() else
            session.getNationSettings().any { it.civId == viewer.civID && it.kind == ControllerKind.HUMAN }
        val ownTurnStarted = turnKey != lastTurnKey && humanViewer &&
            viewer.civID == gameInfo.currentPlayer
        lastTurnKey = turnKey
        if (keptHidden && hasNewContent && humanViewer)
            pendingContentViewers.add(viewer.civID)
        val hasPendingContent = viewer.civID in pendingContentViewers

        // Remember scroll position _relative to topRight_
        val previousScrollXinv = when {
            isScrollingDisabledX -> 0f  // switching from Permanent - scrollX and maxX are 0
            userSetting.static -> 0f  // Permanent: fully visible
            notificationsTable.hasChildren() -> maxX - scrollX  // save current scroll
            else -> maxX  // Swiching Hidden to Dynamic - animate "in" only
        }
        val previousScrollY = scrollY

        restoreButton.block()  // For the update, since ScrollPane may layout and change scrollX
        val contentChanged = updateContent(notifications, coveredNotificationsTop, coveredNotificationsBottom)
        if (contentChanged) {
            updateLayout()
        } else {
            updateSpacers(coveredNotificationsTop, coveredNotificationsBottom)
        }

        scrollX = if (keptHidden) 0f else maxX - previousScrollXinv
        scrollY = if (selectedCell == null) {
            previousScrollY
        } else selectedCell!!.let {
            /** Note: This now works for [ListItem]'s wrapped 0 or 1 times. If the hierarchy of the
                selection *relative to* [notificationsTable] changes again, this will need patching.
            */
            val trueActorY = if (atlas) it.actor.localToAscendantCoordinates(notificationsTable, Vector2()).y
                else it.actorY + (if (it.table == notificationsTable) 0f else it.table.y)
            val actualBottom = (trueActorY + notificationsTable.y) * scaleFactor
            val actualTop = (trueActorY + it.actorHeight + notificationsTable.y) * scaleFactor
            val fullyVisible = actualBottom >= coveredNotificationsBottom && actualTop <= stage.height - coveredNotificationsTop
            val centeredBottom = (stage.height - coveredNotificationsTop + coveredNotificationsBottom - it.actorHeight * scaleFactor) / 2
            val centeredScrollY = centeredBottom * inverseScaleFactor - trueActorY + maxY
            if (fullyVisible) previousScrollY else centeredScrollY
        }
        updateVisualScroll()

        applyUserSettingChange()

        if (!userSetting.static) {
            restoreButton.unblock()
            if (!userSettingChanged && isHidden && mayReveal(hasNewContent || hasPendingContent, keptHidden, ownTurnStarted))
                isHidden = false
        }
        if (!isHidden) pendingContentViewers.remove(viewer.civID)

        // Do the positioning here since WorldScreen may also call update when just its geometry changed
        setPosition(stage.width - width * scaleFactor, 0f)
        restoreButton.place(coveredNotificationsTop, coveredNotificationsBottom)
    }

    /**
     * New notifications reopen a hidden list, except in agent matches: their many snapshots per turn
     * (diplomacy between other nations included) would keep reopening it. There a list the user hid
     * reopens only when the viewer's own human turn starts, including news received during another
     * nation's turn; the bell still counts and flashes. Spectators never accumulate pending news.
     */
    private fun mayReveal(hasNewContent: Boolean, keptHidden: Boolean, ownTurnStarted: Boolean): Boolean = when {
        !hasNewContent -> false
        worldScreen.agentSession == null || !keptHidden -> true
        else -> ownTurnStarted
    }

    private fun List<Notification>.contentSignature(): Int =
        map { Triple(it.category, it.text, it.icons) }.hashCode()

    private fun updateContent(
        notifications: List<Notification>,
        coveredNotificationsTop: Float,
        coveredNotificationsBottom: Float
    ): Boolean {
        selectedCell = null

        // Detect what to draw and if there's any changes part 1
        if (oneTimeNotification == null && clickedNotification != null)
            oneTimeNotification = clickedNotification  // reselecting can keep a "one-time" in the list
        val newHash = notifications.hashCode().withHash(oneTimeNotification.hashCode())

        // Determine highlight
        coloredHighlight = false
        var additionalNotification = emptySequence<Notification>()
        highlightNotification = if (oneTimeNotification == null) clickedNotification
        else oneTimeNotification!!.apply {
                if (this !in notifications) {
                    additionalNotification = sequenceOf(this)
                    coloredHighlight = true
                }
                oneTimeNotification = null
            }
        clickedNotification = null

        // Detect change part 2 - early exit if no re-render needed
        // Note no change detection for highlightNotification - if there's a selection we always
        // need the redraw to determine the selectedCell, to enable scroll-into-view
        if (notificationsHash == newHash && highlightNotification == null) return false
        notificationsHash = newHash

        // Inform the Bell Button about the count - ignoring oneTimeNotification
        restoreButton.updateCount(notifications.size)

        // Rebuild the notifications list
        notificationsTable.clear()
        notificationsTable.pack()  // forget last width!
        if (notifications.isEmpty() && additionalNotification.none()) return true

        val categoryHeaders = mutableListOf<CategoryHeader>()
        val itemWidths = mutableListOf<Float>()

        topSpacerCell = notificationsTable.add()
            .height(topSpace(coveredNotificationsTop))
        notificationsTable.row()

        if (atlas) {
            addAtlasDrawer(additionalNotification + notifications, notifications.size)
            bottomSpacerCell = notificationsTable.add().height(bottomSpace(coveredNotificationsBottom)).expandY()
            notificationsTable.row()
            highlightNotification = null
            return true
        }

        val backgroundDrawable = if (com.unciv.ui.components.GameUiStyle.isAtlas)
            com.unciv.ui.components.GameUiStyle.panelBackground("Atlas/World/Notifications")
        else BaseScreen.skinStrings.getUiBackground("WorldScreen/Notification", BaseScreen.skinStrings.roundedEdgeRectangleShape)

        // Sliding the list sideways also hides it, but nothing on screen says so.
        if (!userSetting.static) notificationsTable.add(HideButton(backgroundDrawable)).right().row()

        val orderedNotifications = (additionalNotification + notifications)
            .groupBy { it.category }
            .toSortedMap()  // This sorts by Category ordinal, so far intentional - the order of the grouped lists are unaffected
        for ((category, categoryNotifications) in orderedNotifications) {
            val header: CategoryHeader?
            if (category == NotificationCategory.General) {
                notificationsTable.add().padTop(categoryTopPad)
                    .row()  // Make sure category w/o header gets same spacing
                header = null
            }
            else {
                header = CategoryHeader(category, backgroundDrawable)
                categoryHeaders.add(header)
                notificationsTable.add(header).right().row()
            }

            val notificationCategoryTable = Table()

            fun fillNotificationCategoryTable() {
                for (notification in categoryNotifications) {
                    val item = ListItem(notification, backgroundDrawable)
                    itemWidths.add(item.itemWidth)
                    val itemCell = notificationCategoryTable.add(item)
                    if (notification == highlightNotification) selectedCell = itemCell
                    itemCell.right().row()
                }
            }

            fillNotificationCategoryTable()
            notificationsTable.add(notificationCategoryTable).right().row()

            header?.onClick {
                if (notificationCategoryTable.hasChildren()) {
                    notificationCategoryTable.clear()
                    notificationCategoryTable.pack()
                } else {
                    fillNotificationCategoryTable()
                    notificationCategoryTable.pack()
                }
            }
        }

        // I think average looks better than max or non-equalized
        // (Note: if itemWidths ever were empty, average would return Double.NaN!)
        val newHeaderWidth = itemWidths.average().toFloat()
        for (header in categoryHeaders) {
            header.increaseWidthTo(newHeaderWidth)
        }

        bottomSpacerCell = notificationsTable.add()
            .height(bottomSpace(coveredNotificationsBottom)).expandY()
        notificationsTable.row()
        highlightNotification = null  // no longer needed
        return true
    }

    /**
     *  Atlas: one drawer surface. A header row ("Notifications · N" and Hide), muted category
     *  captions without rules, and plain rows with 20-unit icons; no per-item cards.
     */
    private fun addAtlasDrawer(entries: Sequence<Notification>, count: Int) {
        val contentWidth = maxEntryWidth - 2 * GameUiStyle.Space.l
        val drawer = Table().apply {
            name = "AtlasNotificationsDrawer"
            background = WorldScreenStyle.surface("Atlas/World/Notifications")
            pad(GameUiStyle.Space.m, GameUiStyle.Space.l, GameUiStyle.Space.m, GameUiStyle.Space.l)
            touchable = Touchable.enabled // stop clicks going through to the map
        }
        val header = Table()
        header.add("${"Notifications".tr()} · ${count.tr()}".toLabel(WorldScreenStyle.ink, GameUiStyle.Type.label, hideIcons = true))
            .left().expandX()
        // Sliding the list sideways also hides it, but nothing on screen says so.
        if (!userSetting.static)
            header.add(WorldScreenStyle.ghostButton("Hide", GameUiStyle.ButtonSize.Compact).apply { onClick { isHidden = true } })
                .right()
        drawer.add(header).width(contentWidth).minHeight(GameUiStyle.compactControlHeight).row()

        val ordered = entries.groupBy { it.category }.toSortedMap()
        for ((category, categoryNotifications) in ordered) {
            val rows = Table()
            fun fillRows() {
                for (notification in categoryNotifications) {
                    val cell = rows.add(AtlasListItem(notification, contentWidth)).width(contentWidth)
                    if (notification == highlightNotification) selectedCell = cell
                    cell.row()
                }
            }
            if (category != NotificationCategory.General) {
                val caption = Table().apply {
                    touchable = Touchable.enabled
                    add(category.name.toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)).left().expandX()
                    onClick {
                        if (rows.hasChildren()) rows.clear() else fillRows()
                        rows.pack()
                    }
                }
                drawer.add(caption).width(contentWidth).padTop(GameUiStyle.Space.m).padBottom(GameUiStyle.Space.xs).row()
            }
            fillRows()
            drawer.add(rows).width(contentWidth).row()
        }
        notificationsTable.add(drawer).right().pad(0f, atlasHaloMargin, 0f, atlasEdgeMargin).row()
    }

    private inner class AtlasListItem(notification: Notification, rowWidth: Float) : Table() {
        init {
            val isSelected = notification === highlightNotification  // Notification does not implement equality contract
            if (isSelected) background = WorldScreenStyle.control("Atlas/World/NotificationSelected",
                WorldScreenStyle.raised, if (coloredHighlight) WorldScreenStyle.warning else WorldScreenStyle.accent)
            pad(GameUiStyle.Space.s, GameUiStyle.Space.s, GameUiStyle.Space.s, GameUiStyle.Space.s)
            val icons = Table()
            notification.addNotificationIconsTo(icons, worldScreen.gameInfo.ruleset, atlasIconSize)
            // Silhouettes authored for light plates (e.g. unit icons) read as ink on the dark drawer.
            for (icon in icons.children)
                if (icon is Image && icon.color.r * .299f + icon.color.g * .587f + icon.color.b * .114f < .35f)
                    icon.color = WorldScreenStyle.ink
            val iconsWidth = if (icons.hasChildren()) icons.prefWidth + GameUiStyle.Space.xs else 0f
            val label = notification.text.toLabel(WorldScreenStyle.ink, GameUiStyle.Type.body, hideIcons = true)
            label.removeFontColorFromStatIcons()
            label.wrap = true
            if (icons.hasChildren()) add(icons).top().padRight(GameUiStyle.Space.xs)
            add(label).width((rowWidth - iconsWidth - 2 * GameUiStyle.Space.s).coerceAtLeast(40f)).left()
            touchable = Touchable.enabled
            onClick {
                notification.execute(worldScreen)
                clickedNotification = notification
                GUI.setUpdateWorldOnNextRender()
            }
        }
    }

    private inner class CategoryHeader(
        category: NotificationCategory,
        backgroundDrawable: Drawable
    ) : Table() {
        private val leftLineCell: Cell<Image>
        private val rightLineCell: Cell<Image>
        private val captionWidth: Float

        init {
            touchable = Touchable.enabled // stop clicks going through to map
            add().padTop(categoryTopPad).colspan(3).row()
            leftLineCell = add(ImageGetter.getWhiteDot())
                .minHeight(2f).width(minCategoryLineWidth)
            add(Table().apply {
                background = backgroundDrawable
                val label = ColorMarkupLabel(category.name, if (com.unciv.ui.components.GameUiStyle.isAtlas) WorldScreenStyle.ink else ImageGetter.CHARCOAL, fontSize = fontSize)
                add(label)
                captionWidth = prefWidth  // of this wrapper including background rims
                captionWidth
            }).pad(listItemPad)
            val rightPad = categoryHorizontalPad + rightPadToScreenEdge + (
                    if (!enlargeHighlight || highlightNotification == null) 0f
                    else selectionExtraRightPad
                )
            rightLineCell = add(ImageGetter.getWhiteDot())
                .minHeight(2f).width(minCategoryLineWidth)
                .padRight(rightPad)
        }

        /** Equalizes width by adjusting length of the decoration lines.
         *  Does nothing if that would leave the lines shorter than [minCategoryLineWidth].
         *  @param newWidth maximum notification label width including background padding
         */
        fun increaseWidthTo(newWidth: Float) {
            val lineLength = (newWidth - captionWidth - 6f) * 0.5f - categoryHorizontalPad
            if (lineLength <= minCategoryLineWidth) return
            leftLineCell.width(lineLength)
            rightLineCell.width(lineLength)
        }
    }

    private inner class HideButton(backgroundDrawable: Drawable) : Table() {
        init {
            val pill = Table()
            pill.background = backgroundDrawable
            val ink = if (com.unciv.ui.components.GameUiStyle.isAtlas) WorldScreenStyle.ink else ImageGetter.CHARCOAL
            pill.add(ImageGetter.getImage("OtherIcons/Close").apply { color = ink })
                .size(iconSize * 0.6f).padRight(8f)
            pill.add("Hide".toLabel(ink, fontSize))
            add(pill).pad(listItemPad, listItemPad, listItemPad, rightPadToScreenEdge)
            touchable = Touchable.enabled
            onClick { isHidden = true }
        }
    }

    private inner class ListItem(
        notification: Notification,
        backgroundDrawable: Drawable
    ) : Table() {
        /** Returns width of the visible Notification including background padding but not
         *  including outer touchable area padding */
        val itemWidth: Float

        init {
            val isSelected = notification === highlightNotification  // Notification does not implement equality contract
            val isEnlarged = isSelected && enlargeHighlight
            val labelFontSize = if (isEnlarged) fontSize + fontSize / 2 else fontSize
            val itemIconSize = if (isEnlarged) iconSize * 1.5f else iconSize
            val topBottomPad = if (isEnlarged) selectedListItemPad else listItemPad

            val listItem = Table()
            listItem.background = if (!isSelected || !coloredHighlight) backgroundDrawable else if (com.unciv.ui.components.GameUiStyle.isAtlas) {
                com.unciv.ui.components.GameUiStyle.outlinedBackground("Atlas/World/NotificationSelected", WorldScreenStyle.surface, WorldScreenStyle.warning)
            } else {
                BaseScreen.skinStrings.getUiBackground("WorldScreen/Notification", BaseScreen.skinStrings.roundedEdgeRectangleShape, oneTimeNotificationColor)
            }

            val maxLabelWidth = maxEntryWidth - (itemIconSize + 5f) * notification.icons.size - 10f
            val label = WrappableLabel(notification.text, maxLabelWidth, if (com.unciv.ui.components.GameUiStyle.isAtlas) WorldScreenStyle.ink else ImageGetter.CHARCOAL, labelFontSize, hideIcons = true)
            label.setAlignment(Align.center)
            label.removeFontColorFromStatIcons()
            if (label.prefWidth > maxLabelWidth * scaleFactor) {  // can't explain why the comparison needs scaleFactor
                label.wrap = true
                listItem.add(label).maxWidth(label.optimizePrefWidth()).padRight(10f)
            } else {
                listItem.add(label).padRight(10f)
            }

            notification.addNotificationIconsTo(listItem, worldScreen.gameInfo.ruleset, itemIconSize)
            if (com.unciv.ui.components.GameUiStyle.isAtlas) {
                // Notification icons are authored for a light background (including black
                // unit silhouettes). Retain that local backing within the dark Atlas card.
                for (icon in listItem.children.filter { it !== label }) {
                    val cell = listItem.getCell(icon)
                    val plate = Table().apply {
                        name = "AtlasNotificationIcon"
                        background = ImageGetter.getWhiteDotDrawable().tint(WorldScreenStyle.ink)
                        add(icon).size(itemIconSize)
                    }
                    cell.setActor(plate)
                }
            }

            itemWidth = listItem.prefWidth  // includes the background NinePatch's leftWidth+rightWidth

            // using a large click area with no gap in between each message item.
            // this avoids accidentally clicking in between the messages, resulting in a map click
            add(listItem).pad(topBottomPad, listItemPad, topBottomPad, rightPadToScreenEdge)
            touchable = Touchable.enabled
            onClick {
                notification.execute(worldScreen)
                clickedNotification = notification
                GUI.setUpdateWorldOnNextRender()
            }
        }
    }

    /** Should only be called when updateContent just rebuilt the notificationsTable */
    private fun updateLayout() {
        // size this ScrollPane to content
        width = notificationsTable.packIfNeeded().width

        // Allow scrolling content out of the screen to the right
        topSpacerCell?.width(2 * width)
        notificationsTable.invalidate() // topSpacerCell.width _should_ have done that
        notificationsTable.pack()  // again so new topSpacerCell size is taken into account

        layout()  // This calculates maxXY so setScrollXY clamps correctly
    }

    private fun updateSpacers(coveredNotificationsTop: Float, coveredNotificationsBottom: Float) {
        topSpacerCell?.height(topSpace(coveredNotificationsTop))
        bottomSpacerCell?.height(bottomSpace(coveredNotificationsBottom))
        layout()
    }

    private fun topSpace(covered: Float) = covered * inverseScaleFactor + if (atlas) atlasEdgeMargin else 0f
    private fun bottomSpace(covered: Float) = covered * inverseScaleFactor + if (atlas) atlasEdgeMargin else 0f

    override fun scrollX(pixelsX: Float) {
        super.scrollX(pixelsX)
        if (maxX < 5f) return
        restoreButton.checkScrollX(pixelsX)
    }

    inner class RestoreButton : Container<Group>() {
        private var blockCheck = true
        private var blockAct = true
        private var active = false
        /** The list is hidden and this bell stands in for it. */
        val isActive get() = active
        private var shownCount = -1
        private val countLabel: Label
        private val bellIcon = ImageGetter.getImage("OtherIcons/Notifications")
        private val labelInnerCircle: Image
        private val labelOuterCircle: Image

        init {
            if (atlas) {
                // A quiet rounded icon button with a small brass count badge.
                actor = Group().apply { setSize(atlasBellSize, atlasBellSize) }
                bellIcon.color = WorldScreenStyle.ink
                bellIcon.setBounds((atlasBellSize - 20f) / 2, (atlasBellSize - 20f) / 2, 20f, 20f)
                actor.addActor(bellIcon)
                setBackground(WorldScreenStyle.control("Atlas/World/NotificationBell",
                    WorldScreenStyle.raised.cpy().apply { a = 0.94f }), false)
                size(atlasBellSize)
                countLabel = "".toLabel(WorldScreenStyle.surface, GameUiStyle.Type.caption, Align.center)
                labelInnerCircle = ImageGetter.getCircle(WorldScreenStyle.accent, 16f)
                labelOuterCircle = ImageGetter.getCircle(WorldScreenStyle.surface, 18f)
            } else {
                actor = bellIcon
                    .surroundWithCircle(restoreButtonSize * 0.9f, color = BaseScreen.skinStrings.skinConfig.baseColor)
                    .surroundWithCircle(restoreButtonSize, resizeActor = false)
                size(restoreButtonSize)

                countLabel = "".toLabel(ImageGetter.CHARCOAL, restoreButtonNumberFontSize, Align.center)
                // not using surroundWithCircle for the count, as the centering will break if positioned within another IconCircleGroup (why?)
                labelInnerCircle = ImageGetter.getCircle(Color.WHITE, restoreButtonNumbersSize * 0.9f)
                labelOuterCircle = ImageGetter.getCircle(ImageGetter.CHARCOAL, restoreButtonNumbersSize)
            }
            labelInnerCircle.centerAtNumberPosition()
            labelOuterCircle.centerAtNumberPosition()
            actor.addActor(labelOuterCircle)
            actor.addActor(labelInnerCircle)
            actor.addActor(countLabel)

            color = color.cpy()  // So we don't mutate a skin element while fading
            color.a = 0f  // for first fade-in
            onClick(::clicked)
            pack()  // `this` needs to adopt the size of `actor`, won't happen automatically (surprisingly)
        }

        /** Set RestoreButton position - relative to the screen as parent is stage.root.
         *  Atlas anchors it right-aligned just under the header, clear of the map's unit tokens. */
        fun place(coveredNotificationsTop: Float, coveredNotificationsBottom: Float) {
            val stage = this@NotificationsScroll.stage
            if (atlas) setPosition(stage.width - atlasEdgeMargin,
                stage.height - coveredNotificationsTop - atlasEdgeMargin, Align.topRight)
            else setPosition(stage.width - restoreButtonPad, coveredNotificationsBottom + restoreButtonPad, Align.bottomRight)
        }

        private fun Actor.centerAtNumberPosition() =
            if (atlas) setPosition(atlasBellSize - 6f, atlasBellSize - 6f, Align.center)
            else setPosition(restoreButtonNumbersCenter, restoreButtonNumbersCenter, Align.center)

        /** Atlas hides the bell while there is nothing to count. */
        private val isEmptyAtlasBell get() = atlas && shownCount <= 0
        override fun draw(batch: Batch, parentAlpha: Float) { if (!isEmptyAtlasBell) super.draw(batch, parentAlpha) }
        override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = if (isEmptyAtlasBell) null else super.hit(x, y, touchable)

        fun updateCount(count: Int) {
            if (count == shownCount) return
            shownCount = count
            val countText = when {
                count >= 100 -> "+"
                worldScreen.selectedCiv.isLongCountDisplay() -> count.toMayaNumerals()
                else -> count.tr()
            }
            countLabel.setText(countText) // should we use Maya numerals for the Maya?
            countLabel.pack()
            countLabel.centerAtNumberPosition()
            if (active) flash()
        }

        fun block() {
            blockCheck = true
            blockAct = true
        }
        fun unblock() {
            blockCheck = false
            blockAct = false
        }

        fun show() {
            active = true
            clearActions()
            updateUserSetting(UserSetting.Hidden)
            if (parent == null)
                // `addActorAfter` to stay behind any popups
                worldScreen.stage.root.addActorAfter(this@NotificationsScroll, this)
            blockAct = false
            addAction(Actions.fadeIn(1f))
            if (userSetting == UserSetting.Disabled) flash()
        }

        fun hide() {
            active = false
            clearActions()
            updateUserSetting(UserSetting.Visible)
            if (parent == null) return
            blockAct = false
            addAction(
                Actions.sequence(
                    Actions.fadeOut(0.333f),
                    Actions.run {
                        remove()
                    }
                )
            )
        }

        fun checkScrollX(scrollX: Float) {
            if (blockCheck) return
            if (active && scrollX >= scrolledAwayEpsilon * 1.2f)
                hide()
            if (!active && scrollX <= scrolledAwayEpsilon)
                show()
        }

        fun clicked() {
            if (userSetting == UserSetting.Disabled) {
                worldScreen.openEmpireOverview(EmpireOverviewCategories.Notifications)
            } else {
                scrollX = maxX
                hide()
            }
        }

        fun flash() {
            addAction(ButtonFlashAction(bellIcon))
        }

        override fun act(delta: Float) {
            // Actions are blocked while update() is rebuilding the UI elements - to be safe from unexpected state changes
            if (!blockAct) super.act(delta)
        }
    }

    private class ButtonFlashAction(private val icon: Actor) : TemporalAction(4f) {
        val tempColor = Color()
        override fun update(percent: Float) {
            val shift = (1f - MathUtils.cos(12f * percent * MathUtils.PI)) / 2f
            tempColor.set(Color.WHITE).lerp(Color.RED, shift)
            icon.color.set(tempColor)
        }
    }

    private fun getUserSetting() {
        val settingString = GUI.getSettings().notificationScroll
        val setting = UserSetting.entries.firstOrNull { it.name == settingString }
            ?: UserSetting.default()
        if (::userSetting.isInitialized && setting == userSetting) {
            userSettingChanged = false
            return
        }

        userSetting = setting
        userSettingChanged = true

        notificationsTable.clear()
        notificationsHash = 0
        scrollX = 0f
        updateVisualScroll()
        setScrollingDisabled(x = false, y = false)
        isVisible = false
    }

    private fun applyUserSettingChange() {
        if (!userSettingChanged) return
        // Here the rebuild of content and restoring of scrollX/Y already happened
        restoreButton.block()
        val fromPermanent = isScrollingDisabledX
        setScrollingDisabled(userSetting.static, false)
        if (fromPermanent) {
            validate()
            scrollX = maxX
            updateVisualScroll()
        }
        when (userSetting) {
            UserSetting.Hidden -> {
                if (!isHidden) isHidden = true
                restoreButton.show()
            }
            UserSetting.Visible -> {
                if (isHidden) isHidden = false
                restoreButton.hide()
            }
            UserSetting.Permanent -> {
                restoreButton.hide()
            }
            UserSetting.Disabled -> {
                restoreButton.show()
                return // Leave the NotificationsScroll invisible
            }
        }
        isVisible = true
    }

    private fun updateUserSetting(newSetting: UserSetting) {
        if (newSetting == userSetting || userSetting.static) return
        userSetting = newSetting
        GUI.getSettings().notificationScroll = newSetting.name
    }
}
