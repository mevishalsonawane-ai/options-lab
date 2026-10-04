package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewsAnalystTest {
    private fun h(t: String) = News.parse("<item><title>$t</title><link>https://e.com/${t.hashCode()}</link></item>", "ET").single()

    @Test fun whatMattersAndWhatItMeansForMe() {
        val bad = h("Bank Nifty slumps as banks tumble on bad loan fears")
        assertTrue(NewsAnalyst.matters(bad))
        val t = NewsAnalyst.take(bad, mapOf("Liquidity 15+5" to Market.BANKNIFTY), setOf(Market.BANKNIFTY))
        assertTrue(t.title.startsWith("News: bad for BankNifty"), t.title)
        assertTrue(t.text.contains("Liquidity 15+5 trades BankNifty: run the trade check") && t.text.contains("You hold BankNifty positions"), t.text)
        val rbi = h("RBI keeps repo rate unchanged, monetary policy stance neutral")
        assertTrue(NewsAnalyst.matters(rbi))
        assertTrue(NewsAnalyst.take(rbi, emptyMap(), emptySet()).text.contains("Policy news moves the whole market"))
        assertFalse(NewsAnalyst.matters(h("Company X appoints new chief marketing officer")))
        val gold = h("Gold rises as war fears lift safe haven demand")
        assertTrue(NewsAnalyst.take(gold, emptyMap(), emptySet()).title.contains("good for Gold"))
    }
}
