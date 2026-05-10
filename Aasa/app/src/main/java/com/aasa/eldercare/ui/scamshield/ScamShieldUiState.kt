package com.aasa.eldercare.ui.scamshield

import com.aasa.eldercare.ui.home.RiskCopy
import com.aasa.eldercare.ui.home.ScamRiskCopy

/**
 * Single source of truth for the dedicated Scam & Fraud Shield screen.
 *
 * The screen is explicitly elder-friendly:
 *   - [inputText]            – the suspicious message being checked.
 *   - [isAnalyzing]          – disables Analyze while a turn is in flight.
 *   - [scamRisk]             – LOW / MEDIUM / HIGH from [com.aasa.eldercare.tools.ScamShieldTool].
 *   - [scamSignals]          – ordered list of warning labels for the result card.
 *   - [safeAction]           – the recommended next step.
 *   - [assistantExplanation] – Gemma's gentle explanation (or the tool message).
 *   - [contactName] / [phoneNumber] – primary trusted contact, used by the
 *     "Call <name>" button. Null when none is configured.
 *   - [errorMessage]         – non-null when the network/tool failed.
 *
 * [riskCopy] is derived so the composable doesn't have to know about
 * raw risk strings. [hasResult] gates whether the result card is shown.
 */
data class ScamShieldUiState(
    val inputText: String = "",
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null,

    val analyzedMessageText: String? = null,
    val scamRisk: String? = null,
    val scamSignals: List<String> = emptyList(),
    val safeAction: String? = null,
    val assistantExplanation: String? = null,

    val contactName: String? = null,
    val phoneNumber: String? = null
) {
    val hasResult: Boolean
        get() = !scamRisk.isNullOrBlank()

    val riskCopy: ScamRiskCopy?
        get() = scamRisk?.let { ScamRiskCopy.fromRaw(it) }

    val riskLevel: RiskCopy.RiskLevel?
        get() = riskCopy?.level

    val canCallContact: Boolean
        get() = !phoneNumber.isNullOrBlank() && !contactName.isNullOrBlank()
}

/**
 * Demo messages exposed as one-tap buttons on the shield screen so the
 * demo can showcase the gift-card and bank-link patterns without
 * typing.
 */
object ScamShieldDemoMessages {
    const val GIFT_CARD: String =
        "Hi Grandma, I'm in trouble and need help now. Please don't call anyone. " +
            "Buy two Apple gift cards worth \$500 and send me the codes quickly."

    const val BANK: String =
        "Your bank account is locked. Click this link immediately and verify your password."
}
