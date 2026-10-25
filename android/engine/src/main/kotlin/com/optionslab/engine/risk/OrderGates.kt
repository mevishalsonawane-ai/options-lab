package com.optionslab.engine.risk

/**
 * The three gates on a real order (22 Oct 2026, borrowed from Jake Schincariol's day-trader skill, MIT): nothing real goes
 * without all three, and each is shown with its tick so Boss sees which is still shut.
 *
 * Boss's own order: (1) the checks passed and the verdict's fingerprint matches the order as it stands ([OrderFingerprint]),
 * (2) "yes" typed for this one order (Boss's setting, on by default; the PIN stays whatever it says), (3) the PIN or fingerprint.
 * An arm's live trade: (1) its Trade-live switch and "AI trades go live" on, (2) the PIN given when it was armed, (3) the
 * fingerprint matched at the door. Nothing here weakens any gate that already exists: these only say which are open. Pure.
 */
object OrderGates {
    data class Gate(val label: String, val done: Boolean, val note: String? = null) {
        /** "✓ Checks passed" / "✗ Type yes (not yet)". */
        fun line(): String = (if (done) "✓ " else "✗ ") + label + (note?.let { " ($it)" } ?: "")
    }

    /** What must be typed, exactly (case and spaces aside). */
    const val YES = "yes"

    fun yesTyped(typed: String?): Boolean = typed?.trim()?.equals(YES, ignoreCase = true) == true

    /** Boss's own real order: [yesRequired] false (his setting off) counts gate 2 as open, said so. */
    fun manual(checksPassed: Boolean, fingerprintOk: Boolean, yesRequired: Boolean, typed: String?, pinDone: Boolean): List<Gate> = listOf(
        Gate("Checks passed, fingerprint matches", checksPassed && fingerprintOk,
            when { !checksPassed -> "a check refused it"; !fingerprintOk -> "the order changed after its check"; else -> null }),
        if (yesRequired) Gate("\"yes\" typed for this order", yesTyped(typed), if (yesTyped(typed)) null else "type yes below")
        else Gate("\"yes\" typed for this order", true, "off in your settings"),
        Gate("Your PIN or fingerprint", pinDone, if (pinDone) null else "asked when you send"),
    )

    /** An arm's live trade. [fingerprint]: the last fingerprint its door matched today (null: none yet); [refusal]: its last refusal. */
    fun arm(tradeLive: Boolean, aiLive: Boolean?, armedWithPin: Boolean, fingerprint: String?, refusal: String?): List<Gate> = listOf(
        Gate("Trade live and \"AI trades go live\" on", tradeLive && aiLive == true,
            when { !tradeLive -> "its switch is off"; aiLive == null -> "AI trades not read yet"; !aiLive -> "AI trades go live is off"; else -> null }),
        Gate("PIN at arming", armedWithPin, if (armedWithPin) null else "armed only with your PIN"),
        Gate("Order fingerprint at the door", fingerprint != null && refusal == null,
            when { refusal != null -> "last send refused: $refusal"; fingerprint == null -> "checked on each live entry; none sent yet"; else -> "last matched $fingerprint" }),
    )

    fun allOpen(gates: List<Gate>): Boolean = gates.all { it.done }

    /** "2 of 3 gates open". */
    fun summary(gates: List<Gate>): String = "${gates.count { it.done }} of ${gates.size} gates open"
}
