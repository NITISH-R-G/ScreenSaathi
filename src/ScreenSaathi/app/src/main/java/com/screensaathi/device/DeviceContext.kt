package com.screensaathi.device

/**
 * A read-only snapshot of what this phone can be *proven* to have.
 *
 * Screen context answers "what is visible?"; this answers "what exists?".
 * The planner had neither before — it was asked to know what was installed,
 * so it answered from world knowledge and said "launching instagram app" on a
 * phone that had no Instagram.
 *
 * The hard constraint this type exists to encode: with targetSdk 36 and only a
 * handful of packages declared in <queries>, PackageManager does NOT give us a
 * complete inventory. Not seeing an app is therefore almost never evidence that
 * it is absent — see [Availability].
 */

/** Where a device-level claim came from. No claim is allowed without one. */
enum class Evidence {
    PACKAGE_MANAGER,
    LAUNCH_INTENT_RESOLUTION,
    CURRENT_SCREEN,
    USER_PROVIDED,
    UNKNOWN,
}

enum class Availability {
    /** Seen by PackageManager. Safe to act on. */
    KNOWN_PRESENT,

    /**
     * Genuinely absent — and we are entitled to say so, because this package IS
     * declared in <queries>, so if it were installed we would have seen it.
     */
    KNOWN_ABSENT_WITHIN_VISIBLE_SET,

    /**
     * We cannot see it and we are not allowed to conclude anything. Android
     * package visibility hides everything outside <queries>, so "not in our
     * results" and "not on the phone" are different statements.
     *
     * Say "I couldn't verify that app", never "you don't have it".
     */
    UNKNOWN_DUE_TO_PACKAGE_VISIBILITY,
}

data class DeviceApp(
    val label: String,
    val packageName: String,
    val launchable: Boolean,
    val enabled: Boolean = true,
)

data class AppResolution(
    val query: String,
    val availability: Availability,
    val matches: List<DeviceApp>,
    val evidence: Evidence,
) {
    val single: DeviceApp? get() = matches.singleOrNull()
    val isAmbiguous: Boolean get() = matches.size > 1

    /** Phrasing that never overstates what we know. */
    fun humanStatus(): String = when (availability) {
        Availability.KNOWN_PRESENT ->
            if (isAmbiguous) "several apps match \"$query\"" else "\"$query\" is installed"
        Availability.KNOWN_ABSENT_WITHIN_VISIBLE_SET -> "\"$query\" is not installed"
        Availability.UNKNOWN_DUE_TO_PACKAGE_VISIBILITY -> "I couldn't verify whether \"$query\" is installed"
    }
}

/**
 * Deterministic and constructible in tests — no Android types in the model, so
 * the resolution policy can be unit-tested without a device.
 */
data class DeviceContext(
    val apps: List<DeviceApp>,
    /**
     * Packages this build has AUTHORITATIVE knowledge about — declared in the
     * manifest's <queries> and explicitly checked, present or not. This must
     * NOT be derived from [apps]: a package that was checked and found absent
     * is, by definition, not in [apps], so building this set from [apps] alone
     * makes absence unrepresentable (the bug this field exists to prevent —
     * see DeviceContextProvider, which checks each declared package by name
     * rather than only enumerating what queryIntentActivities happens to find).
     */
    val visiblePackages: Set<String>,
    val evidenceSource: Evidence,
    val timestampMs: Long,
    /**
     * True when the underlying PackageManager query itself failed (permission
     * revoked, transient binder error) rather than legitimately finding zero
     * apps. Both cases produce apps=[], so this is the only way to tell them
     * apart — see DeviceContextProvider.
     */
    val discoveryFailed: Boolean = false,
    /**
     * Label -> package for the declared set, so a spoken name ("Uber") can be
     * matched against [visiblePackages] to determine authoritative absence.
     * Lives on the context itself, not as a per-call parameter: a caller that
     * forgets to pass it silently loses the entire absence code path, which is
     * exactly how this went unnoticed in production before.
     */
    val knownLabelToPackage: Map<String, String> = emptyMap(),
    /**
     * True when [apps] is the complete launchable inventory rather than a
     * visibility-limited subset. Under QUERY_ALL_PACKAGES enumeration is
     * complete, so an app the user names that is not in [apps] genuinely is not
     * installed — absence becomes evidence instead of a visibility gap.
     *
     * False keeps the old conservative behaviour: unmatched resolves to UNKNOWN.
     */
    val inventoryIsComplete: Boolean = false,
) {
    /**
     * Resolve a spoken app name against real evidence.
     *
     * Exact-label matches take precedence over substring matches: "Uber" must
     * resolve to the app literally named "Uber" even when "Uber Eats" is also
     * installed, rather than being reported as ambiguous between the two.
     * Substring matching is a fallback for the case with no exact hit, and can
     * still surface genuine ambiguity when a request matches more than one
     * app by substring alone (e.g. a bare capability request).
     *
     * Deliberately conservative: anything not matched and not inside the
     * visible allow-list returns UNKNOWN rather than "absent".
     */
    fun resolveApp(query: String, knownLabelToPackage: Map<String, String> = this.knownLabelToPackage): AppResolution {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            return AppResolution(query, Availability.UNKNOWN_DUE_TO_PACKAGE_VISIBILITY, emptyList(), Evidence.UNKNOWN)
        }

        val exact = apps.filter { it.label.lowercase() == q }
        if (exact.isNotEmpty()) {
            return AppResolution(query, Availability.KNOWN_PRESENT, exact, evidenceSource)
        }

        val substring = apps.filter { it.label.lowercase().contains(q) || q.contains(it.label.lowercase()) }
        if (substring.isNotEmpty()) {
            return AppResolution(query, Availability.KNOWN_PRESENT, substring, evidenceSource)
        }

        // Absence is only a fact for packages we were allowed to look for.
        val expectedPackage = knownLabelToPackage.entries
            .firstOrNull { (label, _) -> label.lowercase() == q || label.lowercase().contains(q) }?.value
        if (expectedPackage != null && expectedPackage in visiblePackages) {
            return AppResolution(query, Availability.KNOWN_ABSENT_WITHIN_VISIBLE_SET, emptyList(), Evidence.PACKAGE_MANAGER)
        }

        // A complete enumeration makes "not found" authoritative: we saw every
        // launchable app and this was not among them.
        if (inventoryIsComplete) {
            return AppResolution(query, Availability.KNOWN_ABSENT_WITHIN_VISIBLE_SET, emptyList(), Evidence.PACKAGE_MANAGER)
        }

        return AppResolution(query, Availability.UNKNOWN_DUE_TO_PACKAGE_VISIBILITY, emptyList(), Evidence.UNKNOWN)
    }

    /** Compact evidence block for the planner. Never a raw PackageManager dump. */
    fun toPromptText(): String = buildString {
        append("Device evidence (source=").append(evidenceSource).append("):\n")
        if (apps.isEmpty()) {
            append("- no apps are visible to this app\n")
        } else {
            apps.take(MAX_IN_PROMPT).forEach {
                append("- ").append(it.label)
                if (!it.launchable) append(" (not launchable)")
                if (!it.enabled) append(" (disabled)")
                append("\n")
            }
        }
        append("Only these are verified. Any other app is UNVERIFIABLE on this device — ")
        append("do not state that it is installed, and do not state that it is missing.\n")
    }

    companion object {
        private const val MAX_IN_PROMPT = 30
        fun empty() = DeviceContext(emptyList(), emptySet(), Evidence.UNKNOWN, 0L)
    }
}
