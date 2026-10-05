package com.optionslab.ira

/**
 * The paper record's graduation note: the first time Jarvis's own trades (or Solo's) pass their paper test
 * ([JarvisTrades.proven]: [JarvisTrades.PROVEN_TRADES] closed on paper and a net profit), Jarvis says once what that
 * means and what Boss would do to let them go live - the "AI trades go live" switch (his fingerprint), the app in Live
 * with Zerodha logged in, and his fingerprint again for each order. Words only: nothing is switched, armed or placed;
 * the record stays on paper until Boss himself acts. Pure.
 */
object Graduation {
    enum class Who(val key: String, val whose: String) { JARVIS("jarvis", "my own trades"), SOLO("solo", "Solo's trades") }

    /** Who to tell now: proven now ([provenNow]) and not told before ([told]: [Who.key]s). Told once each, ever. */
    fun due(told: Set<String>, provenNow: Map<Who, Boolean>): List<Who> =
        Who.values().filter { provenNow[it] == true && it.key !in told }

    /**
     * The note for [who] on [record] (its closed trades; only the paper ones count, as in [JarvisTrades.proven]).
     * [aiLiveOn]: the "AI trades go live" switch; [appLive]: the app in Live mode.
     */
    fun say(who: Who, record: List<JarvisTrades.Closed>, aiLiveOn: Boolean, appLive: Boolean): String {
        val paper = record.filter { !it.live }
        val head = "Boss, ${who.whose} passed their paper test: ${paper.size} closed on paper, " +
            "net ${AppFacts.rs(paper.sumOf { it.rupees })}. That means the record is good enough for real money - not that it will stay good."
        val steps = when {
            aiLiveOn && appLive -> " \"AI trades go live\" is on and the app is in Live, so the next one comes to you as a real Zerodha order - it goes only if you approve it with your fingerprint."
            aiLiveOn -> " \"AI trades go live\" is already on; they stay on paper while the app is in Paper. In Live, with Zerodha logged in, each one would come to you first and go only with your fingerprint."
            else -> " To let them go live you would switch on \"AI trades go live\" in Jarvis settings, What Jarvis does by itself (with your fingerprint), keep the app in Live with Zerodha logged in - and each trade still comes to you first and goes only with your fingerprint."
        }
        return head + steps + " If the paper record turns down, they go back to paper. I have switched nothing."
    }
}
