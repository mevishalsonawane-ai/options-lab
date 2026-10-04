package com.optionslab.ira

/**
 * The static-IP relay watched (Boss, 4 Oct: the relay server did not answer from 13:00 to 17:46 and the Zerodha login
 * failed with no word why). Two failed checks in a row: Boss is told once; told again when it answers. Pure.
 */
object RelayWatch {
    /** Checked every [EVERY_MIN] minutes from 08:30 to 15:30 on a trading day. */
    const val EVERY_MIN = 3
    const val FROM = 8 * 60 + 30
    const val TO = 15 * 60 + 30
    const val FAILS = 2

    data class State(val fails: Int = 0, val told: Boolean = false)

    fun due(minute: Int): Boolean = minute in FROM..TO

    /** The next state and what to tell Boss (null: nothing). */
    fun next(s: State, answered: Boolean): Pair<State, String?> = when {
        answered && s.told -> State() to "Boss, the relay server is answering again: Zerodha login and live orders work."
        answered -> State() to null
        s.fails + 1 >= FAILS && !s.told -> State(s.fails + 1, true) to
            "Boss, the relay server is not answering: the Zerodha login and live orders will fail until it is back. Paper trading is fine. Check the server is running."
        else -> State(s.fails + 1, s.told) to null
    }
}
