package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertTrue

class LoginNudgeTest {
    @Test fun onlyBeforeTheOpenWhenSomethingNeedsZerodha() {
        assertTrue(LoginNudge.due(9 * 60 + 6, configured = true, loggedIn = false, needsZerodha = true))
        assertTrue(!LoginNudge.due(9 * 60 + 6, configured = true, loggedIn = true, needsZerodha = true))
        assertTrue(!LoginNudge.due(9 * 60 + 6, configured = true, loggedIn = false, needsZerodha = false))
        assertTrue(!LoginNudge.due(9 * 60 + 6, configured = false, loggedIn = false, needsZerodha = true))
        assertTrue(!LoginNudge.due(9 * 60 + 20, configured = true, loggedIn = false, needsZerodha = true))
        assertTrue(LoginNudge.say(9 * 60 + 14).contains("in 1 minute:"))
    }
}
