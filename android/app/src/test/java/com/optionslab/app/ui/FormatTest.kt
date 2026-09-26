package com.optionslab.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM: the money and number formats every screen uses (always English digits and grouping). */
class FormatTest {
    @Test fun rupeesRoundToWholeRupeesWithGrouping() {
        assertEquals("Rs 1,234,567", rs(1_234_567.0))
        assertEquals("Rs -2,500", rs(-2_500.0))
        assertEquals("Rs 0", rs(0.2))
    }

    @Test fun signedRupeesAlwaysShowTheSign() {
        assertEquals("Rs +1,500", rs(1_500.4, sign = true))
        assertEquals("Rs -1,501", rs(-1_500.6, sign = true))
    }

    @Test fun rupeesToOneDecimalAreSignedByDefault() {
        assertEquals("Rs -12.3", rs1(-12.34))
        assertEquals("Rs +0.0", rs1(0.0))
        assertEquals("Rs 7.5", rs1(7.5, sign = false))
        assertEquals("Rs +12,345.7", rs1(12_345.66))
    }

    @Test fun percentagesAreFractionsTimesHundred() {
        assertEquals("12.34%", pct(0.1234))
        assertEquals("50%", pct(0.5, digits = 0))
        assertEquals("-0.25%", pct(-0.0025))
    }

    @Test fun plainNumbers() {
        assertEquals("1,234.6", num(1_234.56))
        assertEquals("2", num(2.0, digits = 0))
        assertEquals("0.050", num(0.05, digits = 3))
    }
}
