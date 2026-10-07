package com.optionslab.ira

/**
 * "Don't listen" (Boss, 6 Oct: "add a button 'Don't listen', so that he won't listen to all the conversations"): the
 * words that switch Jarvis's microphone off altogether ([Command.Kind.LISTEN_OFF]) - "don't listen", "stop listening to
 * my conversations", "mic off", "mat suno", "sunna band karo", "meri baatein mat suno". Only ever OFF: nothing typed or
 * said here switches listening back on (the microphone is off then anyway); that is Boss's tap on the globe's button or
 * the switch in Settings alone. A question about listening ("are you listening?") is never one. Words in, yes or no. Pure.
 */
object NoListen {
    private const val TAIL = "( please| boss| jarvis| now| ab| abhi| for now| from now)*"
    private const val VERB_HI = "(karo|kar do|kardo|kar dijiye|kijiye|kar dena|kar)"
    private const val TO = " to (me|us|anything|everything|all of it|what (i|we) (say|talk about)|(all |any )?(of )?(the |my |our )?" +
        "(conversations?|calls?|talks?|talking|chats?|discussions?|words))"
    private const val WHAT = "($TO)?"
    private val SAID = Regex("^ (hey |ok |okay )?(jarvis )?(please )?(" +
        "(don t|dont|do not|never) (ever )?listen$WHAT|" +
        // (A bare "stop listening" stays the voice's own "Jarvis, stop listening" - listening's switch off, [Wake] - and is
        // no command in the chat; said with what not to hear, it is this.)
        "(stop|quit) listening$TO|no (more )?listening|listening off|" +
        "(turn|switch|shut|put) off (the |your )?(mic|mike|microphone|listening|ears)|(turn|switch|shut|put) (the |your )?(mic|mike|microphone|listening|ears) off|" +
        "(mic|mike|microphone) off|" +
        "(mat|mut) (suno|sunna|suniye|suno na)|(suno|sunna|suniye) mat|(sunna|sunnaa|sun na|sunana) band $VERB_HI|" +
        "(meri |hamari |humari |sab |sabki |saari |sari |koi )?(baat|baatein|baaten|batein|baate|baaton) (mat|mut) (suno|sunna|suniye)|" +
        "(mic|mike|microphone) band $VERB_HI" +
        ")$TAIL $")

    /** Is [said] Boss asking Jarvis to stop listening altogether? */
    fun asked(said: String): Boolean = SAID.containsMatchIn(Spaced.words(said))

    /** Said back when listening goes off from the chat. */
    const val OFF = "Not listening now, Boss: my microphone is off and I hear nothing - no \"Jarvis\", no follow-ups. " +
        "I still speak and you can still type to me. Only you can switch listening back on: tap the crossed-out ear under the globe, or in Jarvis settings."

    /** Already off. */
    const val ALREADY = "My microphone is already off, Boss. Tap the crossed-out ear under the globe when you want me to hear you."
}
