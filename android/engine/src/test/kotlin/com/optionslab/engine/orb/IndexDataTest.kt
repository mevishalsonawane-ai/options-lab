package com.optionslab.engine.orb

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 13 Oct audit fix: a failed index read stops only the books on that index; each index is read once per pass. */
class IndexDataTest {
    /** A book as the arms pass sees it: its key, Hero or not, and a Liquidity book's own index. */
    private data class Book(val source: String, val hero: Boolean = false, val liquidity: String? = null)

    private val books = listOf(
        Book("orb"), Book("orb_sweep"), Book("range_fade"), Book("hero", hero = true),
        Book("liquidity15", liquidity = "BANKNIFTY"), Book("liquidity5", liquidity = "BANKNIFTY"),
        Book("liquidity30_fin", liquidity = "FINNIFTY"), Book("liquidity5_fin", liquidity = "FINNIFTY"),
        Book("liquidity15_mid", liquidity = "MIDCPNIFTY"), Book("liquidity5_mid", liquidity = "MIDCPNIFTY"))

    /**
     * One pass as OrbArms.tickLocked runs it: BANKNIFTY's candles read first (the ORB family's bars), then each book in
     * turn - the ORB family stopped when BANKNIFTY has none, a Liquidity book reading its own index when it decides, Hero
     * never touched. [failing]: the indices whose fetch throws. Returns each book's verdict and the fetches made.
     */
    private fun pass(failing: Set<String>, empty: Set<String> = emptySet()): Pair<Map<String, String>, List<String>> {
        val fetches = ArrayList<String>()
        fun fetch(index: String): List<Double> {
            fetches += index
            if (index in failing) throw IOException("$index feed down")
            return if (index in empty) emptyList() else listOf(1.0, 2.0)
        }
        val reads = IndexData.Reads<List<Double>>()
        val bars = runCatching { reads.readOrThrow(OrbRules.UNDERLYING) { fetch(OrbRules.UNDERLYING) } }.getOrNull()
        val verdicts = books.associate { bk ->
            val none = if (bk.liquidity != null || bars != null) null else IndexData.bookIndex(bk.hero, null)?.let(IndexData::none)
            bk.source to (none ?: when {
                bk.hero -> "hero_decided"
                bk.liquidity != null -> try {
                    reads.readOrThrow(bk.liquidity) { fetch(bk.liquidity) }.let { "decided on ${it.size} ${bk.liquidity} bars" }
                } catch (e: IOException) {
                    IndexData.gate(bk.liquidity, reads) ?: throw e
                }
                else -> "decided on ${bars!!.size} BANKNIFTY bars"
            })
        }
        return verdicts to fetches
    }

    @Test fun aBankniftyFailureLeavesTheFinniftyAndMidcpniftyBooksDeciding() {
        val (v, fetches) = pass(setOf("BANKNIFTY"))
        for (s in listOf("orb", "orb_sweep", "range_fade", "liquidity15", "liquidity5")) assertEquals("no_index_data: BANKNIFTY", v[s], s)
        assertEquals("decided on 2 FINNIFTY bars", v["liquidity30_fin"])
        assertEquals("decided on 2 FINNIFTY bars", v["liquidity5_fin"])
        assertEquals("decided on 2 MIDCPNIFTY bars", v["liquidity15_mid"])
        assertEquals("decided on 2 MIDCPNIFTY bars", v["liquidity5_mid"])
        assertEquals("hero_decided", v["hero"], "Hero reads its own NIFTY data")
        // The failed BANKNIFTY read is not tried again by the Liquidity BANKNIFTY books on the same pass.
        assertEquals(listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), fetches)
    }

    @Test fun aMidcpniftyFailureStopsOnlyTheMidcpniftyBooks() {
        val (v, fetches) = pass(setOf("MIDCPNIFTY"))
        assertEquals("no_index_data: MIDCPNIFTY", v["liquidity15_mid"])
        assertEquals("no_index_data: MIDCPNIFTY", v["liquidity5_mid"])
        for (s in listOf("orb", "orb_sweep", "range_fade", "liquidity15", "liquidity5")) assertEquals("decided on 2 BANKNIFTY bars", v[s], s)
        assertEquals("decided on 2 FINNIFTY bars", v["liquidity30_fin"])
        assertEquals("hero_decided", v["hero"])
        assertEquals(listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), fetches, "each index once, the failed one included")
    }

    @Test fun aFinniftyFailureStopsOnlyTheFinniftyBooks() {
        val (v, _) = pass(setOf("FINNIFTY"))
        assertEquals("no_index_data: FINNIFTY", v["liquidity30_fin"])
        assertEquals("no_index_data: FINNIFTY", v["liquidity5_fin"])
        assertEquals("decided on 2 MIDCPNIFTY bars", v["liquidity5_mid"])
        assertEquals("decided on 2 BANKNIFTY bars", v["liquidity15"])
    }

    @Test fun everyIndexFailingStopsEveryBookButHeroEachNamingItsIndex() {
        val (v, fetches) = pass(setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"))
        for ((s, i) in listOf("orb" to "BANKNIFTY", "orb_sweep" to "BANKNIFTY", "range_fade" to "BANKNIFTY", "liquidity15" to "BANKNIFTY",
            "liquidity5" to "BANKNIFTY", "liquidity30_fin" to "FINNIFTY", "liquidity5_fin" to "FINNIFTY",
            "liquidity15_mid" to "MIDCPNIFTY", "liquidity5_mid" to "MIDCPNIFTY")) {
            assertEquals("no_index_data: $i", v[s], s)
            assertTrue(IndexData.isNone(v[s]))
            assertEquals(i, IndexData.indexOf(v[s]))
        }
        assertEquals("hero_decided", v["hero"])
        assertEquals(3, fetches.size, "no retries within the pass")
    }

    @Test fun nothingFailingEveryBookDecidesOnOneReadPerIndex() {
        val (v, fetches) = pass(emptySet())
        assertTrue(v.values.none { IndexData.isNone(it) }, v.toString())
        assertEquals(listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), fetches)
    }

    @Test fun anEmptyReadIsNotAFailureTheBooksDecideOnNoBarsAsBefore() {
        val (v, _) = pass(emptySet(), empty = setOf("BANKNIFTY", "MIDCPNIFTY"))
        assertEquals("decided on 0 BANKNIFTY bars", v["orb"])
        assertEquals("decided on 0 MIDCPNIFTY bars", v["liquidity5_mid"])
    }

    @Test fun readsMemoriseSuccessAndFailure() {
        val reads = IndexData.Reads<Int>()
        var n = 0
        assertEquals(1, reads.read("A") { ++n })
        assertEquals(1, reads.read("A") { ++n }, "the same answer, no second fetch")
        assertEquals(1, n)
        assertFalse(reads.failed("A"))
        assertFalse(reads.failed("B"), "not fetched is not failed")
        assertNull(IndexData.gate("B", reads))
        assertNull(reads.read("B") { ++n; throw IOException("down") })
        assertTrue(reads.failed("B"))
        val e = assertFailsWith<IOException> { reads.readOrThrow("B") { ++n; 7 } }
        assertEquals("down", e.message, "the first error, again")
        assertEquals(2, n, "a failed index is not fetched again on the pass")
        assertEquals("no_index_data: B", IndexData.gate("B", reads))
        assertNull(IndexData.gate("A", reads))
        assertNull(IndexData.gate(null, reads), "Hero: no index of its own here")
        assertEquals(setOf("A", "B"), reads.fetched)
    }

    @Test fun verdictWords() {
        assertEquals("no_index_data: MIDCPNIFTY", IndexData.none("MIDCPNIFTY"))
        assertTrue(IndexData.isNone("no_index_data"))
        assertTrue(IndexData.isNone("no_index_data: FINNIFTY"))
        assertFalse(IndexData.isNone(null))
        assertFalse(IndexData.isNone("no_index_datax"))
        assertFalse(IndexData.isNone("night_no_index_data"))
        assertFalse(IndexData.isNone(""))
        assertEquals("BANKNIFTY", IndexData.indexOf("no_index_data"), "the plain verdict was only ever BANKNIFTY's")
        assertEquals("FINNIFTY", IndexData.indexOf("no_index_data: FINNIFTY"))
        assertNull(IndexData.indexOf("no_index_data: "))
        assertNull(IndexData.indexOf("holding"))
        assertNull(IndexData.indexOf(null))
    }

    @Test fun eachBookItsIndex() {
        assertNull(IndexData.bookIndex(hero = true, liquidityIndex = null))
        assertNull(IndexData.bookIndex(hero = true, liquidityIndex = "FINNIFTY"))
        assertEquals("BANKNIFTY", IndexData.bookIndex(hero = false, liquidityIndex = null))
        assertEquals("MIDCPNIFTY", IndexData.bookIndex(hero = false, liquidityIndex = "MIDCPNIFTY"))
        // Every Liquidity book's own index (as the app names them).
        assertEquals(listOf("BANKNIFTY", "BANKNIFTY", "FINNIFTY", "FINNIFTY", "MIDCPNIFTY", "MIDCPNIFTY"),
            LiquidityRules.BOOKS.map { IndexData.bookIndex(false, LiquidityRules.underlyingOf(it)) })
    }
}
