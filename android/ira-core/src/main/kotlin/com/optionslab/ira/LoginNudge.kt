package com.optionslab.ira

/**
 * Zerodha not logged in a few minutes before the open while something needs it (Live mode, or an arm trading Zerodha):
 * Jarvis says so once (the morning notification alone was easy to miss). Words only - he never logs in. Pure.
 */
object LoginNudge {
    const val FROM = 9 * 60 + 5
    const val TO = 9 * 60 + 14

    fun due(minute: Int, configured: Boolean, loggedIn: Boolean, needsZerodha: Boolean): Boolean =
        minute in FROM..TO && configured && !loggedIn && needsZerodha

    fun say(minute: Int): String {
        val left = 9 * 60 + 15 - minute
        return "Boss, Zerodha isn't logged in and the market opens in $left minute${if (left == 1) "" else "s"}: log in now (Zerodha screen), or your live trades won't go through. Paper is fine."
    }
}
