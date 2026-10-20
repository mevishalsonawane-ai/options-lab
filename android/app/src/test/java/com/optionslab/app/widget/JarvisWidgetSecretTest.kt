package com.optionslab.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 Oct, review: the Jarvis home-screen widget hid replies about money and the account, but a trade reply ("Done on paper:
 * NIFTY 24500 CE, 2 lots at 118.40, stop 95") showed in full. Trade words and an option's CE / PE now hide it too; a plain
 * reply still shows. Whole words only, so "stopwatch" or "peace" do not hide anything.
 */
class JarvisWidgetSecretTest {
    @Test fun aTradeReplyIsHidden() {
        listOf(
            "Done on paper: NIFTY 24500 CE, 2 lots at 118.40, stop 95",
            "Bought one lot of BANKNIFTY puts.",
            "Sold at the target.",
            "NIFTY26OCT24500PE filled at 80",
            "Entry at 10:05, exit at 10:40.",
            "Qty 75 in the book.",
            "Your P&L today is up.",
            "Rs 1,200 made",
        ).forEach { assertTrue(it, JarvisWidget.secret(it)) }
    }

    @Test fun aPlainReplyIsShown() {
        listOf(
            "Hello Boss. The market opens at 9:15.",
            "NIFTY is at 24,800, up 0.4% today.",
            "Rain expected in Mumbai; peace and quiet at the desk.",
            "The stopwatch app is not mine to open.",
            "Your next exam is on Friday.",
        ).forEach { assertFalse(it, JarvisWidget.secret(it)) }
    }
}
