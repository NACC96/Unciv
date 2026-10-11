package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.agents.ProposeTradeCommand
import com.unciv.logic.agents.RetractTradeCommand
import com.unciv.logic.agents.TradeOfferDto
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView

class TradeTable(
    private val civ: CivView,
    private val otherCivilization: ForeignCivView,
    diplomacyScreen: DiplomacyScreen
): Table(BaseScreen.skin) {
    internal val tradeView = civ.getTradeView(otherCivilization)
    internal val offerColumnsTable = OfferColumnsTable(tradeView, diplomacyScreen, civ, otherCivilization) { onChange() }
    // This is so that after a trade has been traded, we can switch out the offersToDisplay to start anew - this is the easiest way
    private val offerColumnsTableWrapper = Table()

    val offerTradeText = "{Offer trade}\n({They'll decide on their turn})"
    private val offerButton = offerTradeText.toTextButton()

    private fun retractOffer() {
        // Agent matches retract through the coordinator (§3.2).
        val worldScreen = UncivGame.Current.worldScreen
        if (worldScreen != null && worldScreen.routeThroughAgentSession {
                listOf({ r: Int ->
                    RetractTradeCommand(civ.civID, "ui-${System.nanoTime()}", r, otherCivilization.civID)
                })
            }) {
            offerButton.setText(offerTradeText.tr())
            return
        }
        tradeView.tryRetractOffer()
        offerButton.setText(offerTradeText.tr())
    }

    init {
        offerColumnsTableWrapper.add(offerColumnsTable)
        add(offerColumnsTableWrapper).row()

        val lowerTable = Table().apply { defaults().pad(10f) }

        if (tradeView.tryLoadOurPendingOffer())
            offerColumnsTable.update()

        if (tradeView.hasPendingOfferFromUs()) offerButton.setText("Retract offer".tr())
        else offerButton.apply { isEnabled = false }.setText(offerTradeText.tr())

        offerButton.onClick {
            if (tradeView.hasPendingOfferFromUs()) {
                retractOffer()
                return@onClick
            }
            // If there is a research agreement trade, make sure both civilizations should be able to pay for it.
            // If not lets add an extra gold offer to satisfy this.
            // There must be enough gold to add to the offer to satisfy this, otherwise the research agreement button would be disabled
            if (tradeView.ourStagedOffers().any { it.name == Constants.researchAgreement}) {
                val researchCost = civ.getResearchAgreementCost(otherCivilization)
                val currentPlayerOfferedGold = tradeView.ourStagedOffers().firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
                val otherCivOfferedGold = tradeView.theirStagedOffers().firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
                val newCurrentPlayerGold = civ.gold + otherCivOfferedGold - researchCost
                val newOtherCivGold = otherCivilization.gold + currentPlayerOfferedGold - researchCost
                // Check if we require more gold from them
                if (newCurrentPlayerGold < 0) {
                    offerColumnsTable.addOffer( tradeView.theirAvailableOffers().first { it.type == TradeOfferType.Gold }
                            .copy(amount = -newCurrentPlayerGold), tradeView.theirStagedOffers(), tradeView.ourStagedOffers())
                }
                // Check if they require more gold from us
                if (newOtherCivGold < 0) {
                    offerColumnsTable.addOffer( tradeView.ourAvailableOffers().first { it.type == TradeOfferType.Gold }
                            .copy(amount = -newOtherCivGold), tradeView.ourStagedOffers(), tradeView.theirStagedOffers())
                }
            }

            // Agent matches propose through the coordinator (§3.2).
            val worldScreen = UncivGame.Current.worldScreen
            if (worldScreen != null && worldScreen.routeThroughAgentSession {
                    listOf({ r: Int ->
                        ProposeTradeCommand(civ.civID, "ui-${System.nanoTime()}", r, otherCivilization.civID,
                            ourOffers = tradeView.ourStagedOffers().map {
                                TradeOfferDto(it.name, it.type, it.amount)
                            },
                            theirOffers = tradeView.theirStagedOffers().map {
                                TradeOfferDto(it.name, it.type, it.amount)
                            })
                    })
                }) return@onClick
            tradeView.tryProposeStagedTrade()
            offerButton.setText("Retract offer".tr())
        }

        lowerTable.add(offerButton)

        lowerTable.pack()
        lowerTable.y = 10f
        add(lowerTable)
        pack()
    }

    private fun onChange() {
        offerColumnsTable.update()
        retractOffer()
        offerButton.isEnabled = !(tradeView.theirStagedOffers().size == 0 && tradeView.ourStagedOffers().size == 0)
    }

    fun enableOfferButton(isEnabled: Boolean) {
        offerButton.isEnabled = isEnabled
    }
}
