package com.aasa.eldercare.tools

/**
 * Deterministic scam / fraud signal detection used by [ScamShieldTool]
 * and the [com.aasa.eldercare.agent.AgentOrchestrator] override.
 *
 * Each [ScamSignal] pairs a human-friendly label (the elder will see
 * this verbatim in the result card) with a list of lowercase substring
 * triggers and a coarse risk weight.
 *
 * Risk aggregation rule lives in [classifyRisk]:
 *  - Any HIGH-weighted signal hit  → HIGH.
 *  - Any MEDIUM-weighted signal hit → MEDIUM.
 *  - Combination of urgency + secrecy → HIGH (impersonation pattern
 *    even when no money keyword fires explicitly).
 *  - Otherwise LOW.
 *
 * Substrings are intentionally aggressive on demo phrases (e.g. "gift
 * card") so a single Gemma misclassification can never let a textbook
 * scam slip through into a "LOW" verdict.
 */
object ScamKeywords {

    enum class Weight { LOW, MEDIUM, HIGH }

    data class ScamSignal(
        val label: String,
        val triggers: List<String>,
        val weight: Weight
    )

    // ----------------------------------------------------------------
    // Signal catalog. Order matters only for stable de-duplication of
    // the visible signal list (we keep insertion order).
    // ----------------------------------------------------------------

    private val GIFT_CARD = ScamSignal(
        label = "Gift card request",
        weight = Weight.HIGH,
        triggers = listOf(
            "gift card",
            "gift cards",
            "apple gift card",
            "google play card",
            "google play gift",
            "amazon card",
            "itunes card",
            "steam card"
        )
    )

    private val CODES_REQUEST = ScamSignal(
        label = "Request for codes",
        weight = Weight.HIGH,
        triggers = listOf(
            "send me the code",
            "send me the codes",
            "send the code",
            "send the codes",
            "send codes",
            "code on the card",
            "scratch off",
            "card number on the back",
            "share the code"
        )
    )

    private val MONEY_TRANSFER = ScamSignal(
        label = "Money transfer request",
        weight = Weight.HIGH,
        triggers = listOf(
            "wire transfer",
            "western union",
            "moneygram",
            "zelle",
            "venmo",
            "cash app",
            "cashapp",
            "send money",
            "send $",
            "send me money",
            "transfer money",
            "bitcoin",
            "crypto"
        )
    )

    private val OTP_CREDENTIALS = ScamSignal(
        label = "Password / OTP request",
        weight = Weight.HIGH,
        triggers = listOf(
            "otp",
            "one-time code",
            "one time code",
            "one-time password",
            "verification code",
            "your password",
            "send your password",
            "share your password",
            "share your pin",
            "your pin",
            "social security",
            "ssn",
            "credit card number"
        )
    )

    private val SECRECY = ScamSignal(
        label = "Secrecy",
        weight = Weight.MEDIUM,
        triggers = listOf(
            "don't tell",
            "do not tell",
            "don't call anyone",
            "do not call anyone",
            "please don't call",
            "keep this secret",
            "keep it secret",
            "between us",
            "don't tell mom",
            "don't tell dad",
            "don't tell the family"
        )
    )

    private val URGENCY = ScamSignal(
        label = "Urgency",
        weight = Weight.MEDIUM,
        triggers = listOf(
            "right now",
            "immediately",
            "act now",
            "act fast",
            "in trouble",
            "emergency",
            "urgent",
            "as soon as possible",
            "asap",
            "hurry",
            "quickly",
            "time is running out",
            "before it's too late"
        )
    )

    private val AUTHORITY_THREAT = ScamSignal(
        label = "Authority / threat language",
        weight = Weight.HIGH,
        triggers = listOf(
            "irs",
            "tax warrant",
            "tax agency",
            "warrant for your arrest",
            "you will be arrested",
            "police are coming",
            "court case",
            "legal action",
            "social security suspended",
            "deportation"
        )
    )

    private val SUSPICIOUS_LINK = ScamSignal(
        label = "Suspicious link / account verification",
        weight = Weight.MEDIUM,
        triggers = listOf(
            "click this link",
            "click here",
            "click the link",
            "tap the link",
            "verify your account",
            "verify your password",
            "verify your identity",
            "account locked",
            "account suspended",
            "bank account locked",
            "your account is locked",
            "your account is suspended",
            "log in here",
            "sign in to confirm",
            "http://",
            "https://",
            "bit.ly/",
            "tinyurl"
        )
    )

    // Triggers here are deliberately specific — generic openings like
    // "hi grandma" are too common to flag on their own. The phrases
    // below are the actual hallmarks of the family-impersonation scam.
    private val FAMILY_IMPERSONATION = ScamSignal(
        label = "Family impersonation",
        weight = Weight.MEDIUM,
        triggers = listOf(
            "it's me your grandson",
            "it's me your granddaughter",
            "i lost my phone",
            "this is my new number",
            "i'm using a friend's phone"
        )
    )

    val ALL: List<ScamSignal> = listOf(
        GIFT_CARD,
        CODES_REQUEST,
        MONEY_TRANSFER,
        OTP_CREDENTIALS,
        AUTHORITY_THREAT,
        SUSPICIOUS_LINK,
        SECRECY,
        URGENCY,
        FAMILY_IMPERSONATION
    )

    /**
     * Quick yes / no check used by the orchestrator override to decide
     * whether to force-route a turn into [ScamShieldTool] regardless
     * of what Gemma classified.
     *
     * Intentionally conservative: we only auto-route on HIGH-weighted
     * signals (gift cards, OTP requests, money transfers, authority
     * threats). Lone medium signals like a single "click here" or a
     * generic urgency word are not enough to override Gemma's intent.
     */
    fun looksLikeScamMessage(text: String): Boolean {
        val lower = text.lowercase()
        return ALL.any { signal ->
            signal.weight == Weight.HIGH &&
                signal.triggers.any { trigger -> lower.contains(trigger) }
        }
    }

    /**
     * True when the user is *explicitly asking* Aasa to analyze a
     * suspicious message. Used by the orchestrator to route to the
     * shield tool even when the surrounding text is benign.
     */
    fun isScamAnalysisRequest(text: String): Boolean {
        val lower = text.lowercase()
        return SCAM_ANALYSIS_TRIGGERS.any { lower.contains(it) }
    }

    /**
     * Strip the leading "analyze this suspicious message:" wrapper if
     * the orchestrator (or screen) prepended one, leaving just the
     * pasted text the elder actually wants checked.
     */
    fun extractMessageText(text: String): String {
        val cleaned = text.trim()
        for (prefix in SCAM_ANALYSIS_TRIGGERS) {
            val idx = cleaned.lowercase().indexOf(prefix)
            if (idx >= 0) {
                return cleaned.substring(idx + prefix.length)
                    .trimStart(':', '-', ' ', '\n', '\t')
                    .trim()
            }
        }
        return cleaned
    }

    /**
     * Run every signal against [text] and return the labels that
     * matched, preserving [ALL] insertion order so the UI stays
     * stable across runs.
     */
    fun detectSignals(text: String): List<ScamSignal> {
        val lower = text.lowercase()
        return ALL.filter { signal ->
            signal.triggers.any { trigger -> lower.contains(trigger) }
        }
    }

    /**
     * Aggregate detected signals (plus an optional Gemma-supplied risk
     * level) into the final risk band the UI shows the elder. We never
     * downgrade Gemma's verdict — we only escalate.
     */
    fun classifyRisk(
        detected: List<ScamSignal>,
        baseRisk: String?
    ): String {
        val base = baseRisk?.trim()?.uppercase().orEmpty()
        if (detected.any { it.weight == Weight.HIGH }) return RISK_HIGH

        val hasMedium = detected.any { it.weight == Weight.MEDIUM }
        val hasUrgency = detected.any { it.label == URGENCY.label }
        val hasSecrecy = detected.any { it.label == SECRECY.label }

        // Urgency + secrecy together is a textbook impersonation
        // pattern — escalate even though each is MEDIUM individually.
        if (hasUrgency && hasSecrecy) return RISK_HIGH

        return when {
            base == RISK_HIGH -> RISK_HIGH
            hasMedium -> RISK_MEDIUM
            base == RISK_MEDIUM -> RISK_MEDIUM
            else -> RISK_LOW
        }
    }

    private val SCAM_ANALYSIS_TRIGGERS: List<String> = listOf(
        "analyze this suspicious message",
        "analyse this suspicious message",
        "check this suspicious message",
        "is this message a scam",
        "is this a scam",
        "is this scam",
        "scam check"
    )

    const val RISK_HIGH: String = "HIGH"
    const val RISK_MEDIUM: String = "MEDIUM"
    const val RISK_LOW: String = "LOW"
}
