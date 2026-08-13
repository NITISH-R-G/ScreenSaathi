package com.screensaathi.session

/**
 * What ScreenSaathi is allowed to do with a proposed action.
 *
 * **The model proposes; this decides.** A planner — local or frontier — can
 * suggest anything, including things it should not. Policy is evaluated here,
 * deterministically, after the model has spoken and before the user sees a
 * highlight. There is no path by which a model's own confidence, phrasing or
 * tool call can raise its own permission level.
 *
 * Deliberately pure: no Android types, no model call, no network. Same
 * reasoning as [SafetyGuard] — production and the eval harness must run
 * identical logic, and both must be unit-testable.
 *
 * ### Current capability, stated plainly
 *
 * ScreenSaathi does **not** perform gestures today. The accessibility service
 * deliberately omits `canPerformGestures` (see
 * `res/xml/accessibility_service_config.xml`), so every "action" currently
 * resolves to *pointing at a control and waiting for the user to tap it*.
 * That makes the user the irreversible step, which is the strongest safety
 * property this design has.
 *
 * This policy therefore exists **ahead of** the capability it governs. It is
 * written now so that adding gesture execution later is a change guarded by an
 * existing, tested policy rather than an afterthought bolted on once something
 * has already gone wrong.
 */
object ActionPolicy {

    /**
     * Ordered least to most restrictive. [BLOCKED_ACTION] is never performed
     * and never confirmable — a confirmation dialog for something that should
     * not happen at all just trains users to tap "yes".
     */
    enum class Level {
        /** Read something out. No state changes. */
        SAFE_READ,

        /** Point at a control. The user does the acting. Today's normal case. */
        SAFE_GUIDE,

        /** Consequential but legitimate — ask before proceeding. */
        USER_CONFIRMATION_REQUIRED,

        /**
         * Irreversible and expensive to get wrong: money, deletion, sending
         * something to another person. Explicit confirmation naming the
         * specific action, never a generic "continue?".
         */
        HIGH_RISK_ACTION,

        /** Refused outright regardless of what the model or user asked for. */
        BLOCKED_ACTION,
    }

    data class Ruling(
        val level: Level,
        /** Shown to the user when confirming, and logged for every decision. */
        val reason: String,
        /** The matched policy term, for eval reporting. Empty when none. */
        val trigger: String = "",
    ) {
        val requiresConfirmation: Boolean
            get() = level == Level.USER_CONFIRMATION_REQUIRED || level == Level.HIGH_RISK_ACTION

        val isPermitted: Boolean get() = level != Level.BLOCKED_ACTION
    }

    /**
     * Money leaving the user's account, or anything equally irreversible.
     *
     * Matched against the *element label* the agent is about to point at, not
     * the user's request — the user saying "pay my bill" is a normal request;
     * a highlight landing on a button that reads "Pay ₹1,240 now" is the
     * consequential moment.
     */
    private val HIGH_RISK = listOf(
        "pay now", "pay ₹", "confirm payment", "confirm & pay", "place order",
        "buy now", "purchase", "checkout", "transfer", "send money", "proceed to pay",
        "delete account", "close account", "deactivate",
        "भुगतान करें", "अभी भुगतान", "ऑर्डर करें", "खाता हटाएँ",
        "பணம் செலுத்து", "ஆர்டர் செய்", "கணக்கை நீக்கு",
    )

    /** Consequential, reversible, or affecting someone else. */
    private val CONFIRM = listOf(
        "send", "submit", "confirm", "book now", "apply", "accept", "agree",
        "post", "share", "invite", "cancel booking", "cancel order",
        "भेजें", "जमा करें", "पुष्टि करें", "बुक करें",
        "அனுப்பு", "சமர்ப்பி", "உறுதிப்படுத்து",
    )

    /**
     * Never guided, at any confidence, in any language.
     *
     * These are places where a wrong tap costs the user their account or their
     * device's security posture, and where an assistant has no business
     * steering at all. Permission grants are included deliberately: an
     * accessibility tool talking someone through granting *more* permissions
     * to an app is a social-engineering pattern, not a feature.
     */
    private val BLOCKED = listOf(
        "grant permission", "allow permission", "device admin", "accessibility service",
        "install unknown", "unknown sources", "disable security", "turn off protection",
        "developer options", "usb debugging", "factory reset", "erase all data",
        "seed phrase", "recovery phrase", "private key",
        "अनुमति दें", "फ़ैक्टरी रीसेट", "डेवलपर विकल्प",
        "அனுமதி வழங்கு", "தொழிற்சாலை மீட்டமை",
    )

    /**
     * Classify a proposed guidance action.
     *
     * @param elementLabel visible text of the control about to be highlighted.
     * @param isExecuting true if ScreenSaathi would perform the tap itself
     *   rather than pointing. False everywhere today; the parameter exists so
     *   the policy already distinguishes the two when execution is added.
     */
    fun evaluate(elementLabel: String, isExecuting: Boolean = false): Ruling {
        val label = elementLabel.lowercase().trim()
        if (label.isEmpty()) {
            return Ruling(Level.SAFE_GUIDE, "no label to assess; pointing only")
        }

        BLOCKED.firstOrNull { label.contains(it) }?.let {
            return Ruling(
                Level.BLOCKED_ACTION,
                "\"$elementLabel\" touches device security, permissions or recovery credentials — " +
                    "ScreenSaathi does not guide these",
                it,
            )
        }

        HIGH_RISK.firstOrNull { label.contains(it) }?.let {
            return Ruling(
                Level.HIGH_RISK_ACTION,
                "\"$elementLabel\" looks irreversible — confirm before continuing",
                it,
            )
        }

        CONFIRM.firstOrNull { label.contains(it) }?.let {
            return Ruling(
                Level.USER_CONFIRMATION_REQUIRED,
                "\"$elementLabel\" is consequential — confirm before continuing",
                it,
            )
        }

        // Nothing matched. Pointing is safe; executing is not, because an
        // unrecognised label is not evidence of harmlessness — it is absence
        // of evidence, and those differ when the action cannot be undone.
        return if (isExecuting) {
            Ruling(
                Level.USER_CONFIRMATION_REQUIRED,
                "unrecognised action and ScreenSaathi would be tapping it, not the user",
            )
        } else {
            Ruling(Level.SAFE_GUIDE, "pointing at \"$elementLabel\"; the user taps it")
        }
    }

    /** Reading a selection aloud changes nothing and is always allowed. */
    fun evaluateRead(): Ruling = Ruling(Level.SAFE_READ, "reading on-screen content aloud")
}
