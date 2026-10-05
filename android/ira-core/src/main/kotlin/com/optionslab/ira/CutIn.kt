package com.optionslab.ira

/**
 * Whether Jarvis listens while he talks, so Boss can cut in ([BargeIn]), and how soon into his speech (Boss, 5 Oct:
 * cutting in should just work, but his own voice must never set him off). On by itself only where his voice cannot
 * reach the microphone loud: a headset (his voice is in Boss's ears, not the room) or an echo canceller on the phone
 * (taking his voice out of what is heard). Otherwise off, as before. Boss's own choice on the settings screen always
 * wins; a phone found to go silent while it listens turns the automatic choice off. Even when on, his own words heard
 * back never count as Boss ([BargeIn.cut]), and a cut only ever stops the voice. Pure.
 */
object CutIn {
    /** An echo canceller: none, on the phone but not on a capture of ours (the recognizer's own microphone), or applied by us. */
    enum class Echo { NONE, AVAILABLE, APPLIED }

    /** Why cut-in is on or off (for the diagnostics). */
    enum class Why(val say: String) {
        BOSS_ON("Boss switched it on"),
        BOSS_OFF("Boss switched it off"),
        SILENCES("this phone goes silent when it listens while speaking"),
        HEADSET("a headset is connected"),
        ECHO_APPLIED("echo cancelling is on for my microphone"),
        ECHO_AVAILABLE("the phone has echo cancelling"),
        NO_SHIELD("no headset and no echo cancelling, so my own voice could reach the microphone"),
    }

    data class Decision(val on: Boolean, val why: Why, val startMs: Long)

    /** How soon into his speech listening starts: the old wait, with nothing between his voice and the microphone. */
    const val PLAIN_MS = 900L
    /** The phone's echo canceller, not applied by us (most phones' recognizers use it, not all). */
    const val ECHO_AVAILABLE_MS = 650L
    /** Echo cancelling on our own capture. */
    const val ECHO_APPLIED_MS = 450L
    /** A headset: his voice is in Boss's ears. */
    const val HEADSET_MS = 300L

    private fun startMs(headset: Boolean, echo: Echo) = when {
        headset -> HEADSET_MS
        echo == Echo.APPLIED -> ECHO_APPLIED_MS
        echo == Echo.AVAILABLE -> ECHO_AVAILABLE_MS
        else -> PLAIN_MS
    }

    /**
     * [choice]: Boss's own setting (null: automatic). [silences]: this phone was seen to stop speaking while listening.
     * [headset]: a wired, USB or Bluetooth headset is the output. [echo]: the echo canceller.
     */
    fun decide(choice: Boolean?, silences: Boolean, headset: Boolean, echo: Echo): Decision {
        val ms = startMs(headset, echo)
        return when {
            choice == true -> Decision(true, Why.BOSS_ON, ms)
            choice == false -> Decision(false, Why.BOSS_OFF, ms)
            silences -> Decision(false, Why.SILENCES, ms)
            headset -> Decision(true, Why.HEADSET, ms)
            echo == Echo.APPLIED -> Decision(true, Why.ECHO_APPLIED, ms)
            echo == Echo.AVAILABLE -> Decision(true, Why.ECHO_AVAILABLE, ms)
            else -> Decision(false, Why.NO_SHIELD, ms)
        }
    }

    /** Boss's choice as kept on the phone ("on" / "off"; anything else: automatic). */
    fun load(s: String?): Boolean? = when (s) { "on" -> true; "off" -> false; else -> null }
    fun save(choice: Boolean?): String? = when (choice) { true -> "on"; false -> "off"; null -> null }

    /** For the diagnostics: one line, no words heard. */
    fun say(d: Decision, choice: Boolean?): String =
        "Cut-in: ${if (d.on) "on" else "off"} (${if (choice == null) "automatic" else "Boss's choice"}: ${d.why.say})" +
            if (d.on) " · listening starts ${d.startMs} ms into my speech" else ""
}
