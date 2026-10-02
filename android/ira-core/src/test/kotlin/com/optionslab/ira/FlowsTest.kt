package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowsTest {
    @Test fun nseFlowsRead() {
        val json = """[{"buyValue":"25420.04","category":"DII","date":"01-Oct-2026","netValue":"10041.84","sellValue":"15378.2"},{"buyValue":"12000","category":"FII/FPI","date":"01-Oct-2026","netValue":"-4321.5","sellValue":"16321.5"}]"""
        val f = Flows.parse(json)
        assertEquals(listOf("DII", "FII"), f.map { it.who })
        val l = Flows.lines(f)
        assertEquals("Institutional flows (01-Oct-2026, NSE): FIIs net -Rs 4,322 crore, DIIs net +Rs 10,042 crore.", l[0])
        assertTrue(l[1].startsWith("Foreign funds sold and domestic funds bought: domestic buying more than covered it."))
        assertEquals(setOf(Section.FLOWS), AppAnswers.sections("what did fii and dii do yesterday"))
        assertEquals(listOf("No FII/DII figures from NSE just now."), Flows.lines(emptyList()))
    }
}
