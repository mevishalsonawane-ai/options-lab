package com.optionslab.ira

/**
 * Jarvis reviews himself at the end of the day: what his own results changed, and what he will do differently
 * tomorrow - the bar he now needs to act alone, the hours and kinds of idea he now leaves alone, the conditions his
 * scored ideas say he is weak in ([SelfCalibration]), the goals at risk,
 * the strongest lesson and the paper tests decided. Said in a few sentences in the 15:35 wrap-up. Pure.
 */
object SelfReview {
    data class Facts(
        val bar: Int, val barYesterday: Int?,
        /** Hours ("09:00-10:00") and kinds ("pattern hammer") his own trades lost in (he leaves them alone). */
        val badHours: List<String>, val badKinds: List<String>,
        /** Goals broken or close, in words. */ val goalsAtRisk: List<String>,
        val lesson: String?, val heldUp: List<String>, val failed: List<String>,
        /** What his scored ideas say about the conditions he is weak in ([SelfCalibration.review]). */
        val calibration: List<String> = emptyList(),
        /** The kinds of answer Boss marks wrong, where he now asks to be checked ([SelfDoubt.review]). */
        val doubts: List<String> = emptyList(),
        /** The kinds of unasked alert he now says aloud less often, Boss rarely following them up ([AlertSense.review]). */
        val alerts: List<String> = emptyList(),
    )

    fun say(f: Facts): String? {
        val now = ArrayList<String>()
        if (f.barYesterday != null && f.bar != f.barYesterday)
            now += if (f.bar > f.barYesterday) "my lower-confidence trades lost, so I now act alone only at ${barText(f.bar)}"
                else "my record improved, so I act alone again from ${barText(f.bar)}"
        if (f.badHours.isNotEmpty()) now += "I leave ${f.badHours.joinToString(", ")} alone - my trades lost then"
        if (f.badKinds.isNotEmpty()) now += "I take no ${f.badKinds.joinToString(", ")} ideas by myself - they lost"
        now += (f.calibration + f.doubts + f.alerts).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        val tomorrow = ArrayList<String>()
        f.goalsAtRisk.firstOrNull()?.let { tomorrow += "watch your goal: ${it.trimEnd('.')}" }
        f.lesson?.let { tomorrow += it.trimEnd('.').replaceFirstChar { c -> c.lowercase() } }
        if (f.heldUp.isNotEmpty()) tomorrow += "${f.heldUp.joinToString(", ")} held up on paper - yours to take live if you want"
        if (f.failed.isNotEmpty()) tomorrow += "${f.failed.joinToString(", ")} failed on paper"
        if (now.isEmpty() && tomorrow.isEmpty()) return null
        return "My own review: " + (if (now.isEmpty()) "nothing in my record changed today" else now.joinToString("; ")) + "." +
            (if (tomorrow.isEmpty()) "" else " For tomorrow: " + tomorrow.joinToString("; ") + ".")
    }

    private fun barText(bar: Int) = if (bar > 5) "no level (I only ask now)" else "$bar/5 or more"
}
