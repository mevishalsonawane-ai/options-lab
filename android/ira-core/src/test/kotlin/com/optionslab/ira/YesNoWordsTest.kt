package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals

/** Voice round 25: Boss's yes or no to a waiting request, as he says it. Unclear is never a yes; any no wins. */
class YesNoWordsTest {
    @Test fun theHindiPleaseAfterDoItIsNotANo() {
        for (s in listOf("kar do na", "haan kar do na", "Jarvis le lo na", "chalo na", "kardo naa", "kar dijiye na"))
            assertEquals(true, Wake.yesNo(s), s)
        // Any other "na" is still a no, and a no after the please still wins.
        for (s in listOf("na", "na na", "haan na", "kar do na... nahi", "nahi na", "mat karo na", "na kar do"))
            assertEquals(false, Wake.yesNo(s), s)
    }

    @Test fun moreWaysToSayYesAndNo() {
        for (s in listOf("go for it", "absolutely", "of course Boss", "definitely", "please do", "alright", "zaroor",
                "haan ji, kar dijiye", "chalega", "certainly", "correct"))
            assertEquals(true, Wake.yesNo(s), s)
        for (s in listOf("absolutely not", "of course not", "definitely not", "chhod do", "rehne dijiye", "ruk jao",
                "nahee", "not correct", "go for it... no, leave it"))
            assertEquals(false, Wake.yesNo(s), s)
    }

    @Test fun aYesWithANoWordInIt() {
        for (s in listOf("why not", "Why not, Jarvis?", "no problem", "no problem, go ahead", "not a problem boss",
                "no worries", "no issues, kar do"))
            assertEquals(true, Wake.yesNo(s), s)
        // With a no beside it: a no. With other words: unclear, never a yes.
        for (s in listOf("no problem, leave it", "why not... no, skip it", "no worries, nahi", "no no problem"))
            assertEquals(false, Wake.yesNo(s), s)
        for (s in listOf("why not the other one", "no problem with nifty"))
            assertEquals(null, Wake.yesNo(s), s)
    }

    @Test fun bossDoingItHimselfIsNotAYes() {
        // The idiom then Boss as the one who acts: unclear at most, never a yes (review, round 26).
        for (s in listOf("no problem, I will do it myself", "no worries, I will take it from here",
                "no problem, I will place it myself", "no worries boss, I will buy it myself",
                "no problem, I will do it manually", "no problem I'll handle it", "no problem, main khud kar lunga",
                "why not, I'll buy it later myself"))
            assertEquals(true, Wake.yesNo(s) != true, s)
        // A "no" broken off by a mark is a no, not the idiom.
        for (s in listOf("no, problem", "No. Problem.", "No! Worries", "why. not"))
            assertEquals(true, Wake.yesNo(s) != true, s)
        // Bare yes words after the idiom still say yes.
        for (s in listOf("no problem", "no problem, go ahead", "why not", "no problem, ok go ahead", "no worries, haan kar do",
                "no problem Jarvis, do it"))
            assertEquals(true, Wake.yesNo(s), s)
    }

    @Test fun earlierAnswersReadAsBefore() {
        assertEquals(true, Wake.yesNo("ji haan, kar do")); assertEquals(false, Wake.yesNo("mat karo"))
        assertEquals(false, Wake.yesNo("haan... nhi")); assertEquals(false, Wake.yesNo("not now"))
        assertEquals(true, Wake.yesNo("approve it")); assertEquals(false, Wake.yesNo("reject it"))
        assertEquals(null, Wake.yesNo("what is nifty doing"))
    }
}
