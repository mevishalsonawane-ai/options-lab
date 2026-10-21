package com.optionslab.app.security

/**
 * 18 Oct, review: one rule for every change that loosens the Zerodha account guard - the kill switch cleared (the Account
 * guard page, Home's Strategies card, Jarvis), a limit raised or switched off, a protection switched off. It asks for the
 * app PIN or fingerprint first when a PIN is set and the app is in Live, or Zerodha is set up (else the guard is loosened
 * on Paper, then Live is one tap away). Tightening (the kill switch on) never asks. With no app PIN there is nothing to ask.
 */
object GuardPin {
    /** The rule itself, from plain facts (tests). */
    fun asks(looser: Boolean, pinSet: Boolean, live: Boolean, zerodha: Boolean): Boolean = looser && pinSet && (live || zerodha)

    /** The rule as the app is now: [live] is the settings' Live switch. */
    fun asks(looser: Boolean, live: Boolean): Boolean =
        looser && asks(true, PinLock.isSet, live, runCatching { com.optionslab.app.data.Broker.configured }.getOrDefault(true))

    /** Clearing the kill switch (always a loosening). */
    fun clearKillAsks(live: Boolean): Boolean = asks(true, live)

    const val CLEAR_KILL_WHY = "Enter your app PIN to clear the kill switch on Zerodha."
}
