package com.optionslab.ira

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The knowledge index built once, loaded at once (memory audit, 14 Oct 2026, item 3): the file read back gives the SAME
 * answers as the index built from the pack at start (a golden comparison on a fixed set of Boss's questions - the hits, their
 * scores, the words said and the model's notes, locked and not), the same shared knowledge, a file for another pack or a
 * damaged one is never used, and the start-up numbers before and after.
 */
class KnowledgeIndexFileTest {
    private val packFile: File = listOf("../app/src/main/assets/knowledge/pack.bin", "android/app/src/main/assets/knowledge/pack.bin")
        .map(::File).first { it.isFile }

    private companion object {
        @Volatile var cached: Triple<ByteArray, Knowledge.Index, ByteArray>? = null
    }

    /** (the pack's bytes, the index built from it, the index file). */
    private val built: Triple<ByteArray, Knowledge.Index, ByteArray>
        get() = cached ?: synchronized(KnowledgeIndexFileTest::class) {
            cached ?: run {
                val bytes = packFile.readBytes()
                val file = ByteArrayOutputStream().also { KnowledgeIndexFile.build(bytes, it) }.toByteArray()
                Triple(bytes, Knowledge.Index(Knowledge.read(bytes.inputStream())), file)
            }.also { cached = it }
        }

    private fun fromFile(): Knowledge.Index = assertNotNull(KnowledgeIndexFile.read(ByteArrayInputStream(built.third), KnowledgeIndexFile.packId(built.first)))

    private val QUESTIONS = listOf("how does banknifty behave on expiry after a gap down","how does banknifty behave after a gap down","how does nifty behave after a gap up","how often does the gap fill on banknifty according to research","what did research say about ORB","what did research find about ORB re-entries","is liquidity profitable","research kya kehta hai liquidity ke baare me","what is the edge of liquidity 15+5","is selling straddles profitable","is buying straddles profitable","what did the research find about vix","does the supertrend strategy make money","what did the study find about big candles","how does the market behave on expiry day","does machine learning work for option buying","what did research say about majority rule","what does the research say about gann and fibonacci","is expiry hero profitable","does the night strategy work","what did the research say about overnight trades","what did the research say about FII flows","does VWAP work as a filter","research me mcx ke baare me kya hai","is gold liquidity profitable","what did research say about candlestick patterns","does order flow work","what does research say about costs and spread","is orb fresh profitable","is range fade profitable","does orb sweep work","is the vix divergence strategy profitable","what did the research say about the trade manager","what did research find about pyramiding liquidity","how does banknifty behave in the first 30 minutes","does the profit lock help orb according to research",
        "zxqv plorb", "the and of", "kya liquidity munafa deta hai", "is nifty kaha hai", "what did research say about my trades today")

    @Test fun theFileGivesTheSameAnswersAsTheIndexBuiltAtStart() {
        val (_, runtime, _) = built
        val file = fromFile()
        assertEquals(runtime.size, file.size)
        assertEquals(runtime.avg, file.avg)
        assertContentEquals(runtime.length, file.length)
        assertEquals(runtime.postings.keys, file.postings.keys)
        for ((w, p) in runtime.postings) assertContentEquals(p, file.postings.getValue(w), w)
        assertEquals(runtime.titles, file.titles)
        assertEquals(runtime.pack.facts, file.pack.facts)
        assertEquals(runtime.pack.version, file.pack.version)
        assertEquals(runtime.pack.builtFrom, file.pack.builtFrom)
        assertTrue(QUESTIONS.size >= 35)
        for (q in QUESTIONS) for (locked in listOf(false, true)) for (k in listOf(3, 5)) {
            val a = Knowledge.search(runtime, q, k, locked); val b = Knowledge.search(file, q, k, locked)
            assertEquals(a.map { it.fact.id to it.score }, b.map { it.fact.id to it.score }, "\"$q\" locked=$locked k=$k")
            assertEquals(Knowledge.say(a), Knowledge.say(b), q)
        }
        for (q in QUESTIONS) assertEquals(Knowledge.forModel(runtime, q), Knowledge.forModel(file, q), q)
    }

    @Test fun theSharedKnowledgeIsTheSameFromTheFile() {
        val (_, runtime, _) = built
        val file = fromFile()
        val day = java.time.LocalDate.of(2026, 10, 14)
        assertNotNull(runtime.pack.shared)
        val a = SharedKnowledge.build(runtime.pack, null, FindingsTally(), day)
        val b = SharedKnowledge.build(file.pack, null, FindingsTally(), day)
        assertEquals(a.items, b.items)
        assertEquals(a.version, b.version)
        assertEquals(a.baselines, b.baselines)
        // The file's head alone gives the same, without reading the facts.
        val (shared, version) = assertNotNull(KnowledgeIndexFile.readShared(ByteArrayInputStream(built.third), KnowledgeIndexFile.packId(built.first)))
        assertEquals(runtime.pack.version, version)
        assertEquals(a.items, SharedKnowledge.build(shared, null, FindingsTally(), day, version).items)
    }

    @Test fun aFileForAnotherPackOrDamagedIsNeverUsed() {
        val (bytes, _, file) = built
        val other = KnowledgeIndexFile.packId(bytes.copyOf(bytes.size - 1))
        assertNull(KnowledgeIndexFile.read(ByteArrayInputStream(file), other))
        assertNull(KnowledgeIndexFile.readShared(ByteArrayInputStream(file), other))
        assertNull(KnowledgeIndexFile.read(ByteArrayInputStream(file.copyOf(file.size / 2)), null), "cut short")
        assertNull(KnowledgeIndexFile.read(ByteArrayInputStream(ByteArray(0)), null), "empty")
        assertNull(KnowledgeIndexFile.read(ByteArrayInputStream("not an index".toByteArray()), null))
        val wrongVersion = file.copyOf().also { it[7] = (KnowledgeIndexFile.VERSION + 1).toByte() }
        assertNull(KnowledgeIndexFile.read(ByteArrayInputStream(wrongVersion), null))
        val noEnd = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(KnowledgeIndexFile.read(ByteArrayInputStream(noEnd), null), "the end mark")
        // The same pack always gives the same file.
        assertContentEquals(file, ByteArrayOutputStream().also { KnowledgeIndexFile.build(bytes, it) }.toByteArray())
    }

    @Test fun anEmptyPackRoundTrips() {
        val json = "{\"schema\":1,\"version\":\"v\",\"built_from\":\"2026-10-14\",\"facts\":[]}".toByteArray()
        val out = ByteArrayOutputStream().also { KnowledgeIndexFile.build(json, it) }.toByteArray()
        val ix = assertNotNull(KnowledgeIndexFile.read(ByteArrayInputStream(out), KnowledgeIndexFile.packId(json)))
        assertEquals(0, ix.size)
        assertTrue(Knowledge.search(ix, "is liquidity profitable").isEmpty())
        assertNull(ix.pack.shared)
        assertEquals(null to "v", KnowledgeIndexFile.readShared(ByteArrayInputStream(out), null))
    }

    /** Before / after (the release note): the start's time and memory to have the index, built from the pack vs from the file. */
    @Test fun theNumbersBeforeAndAfter() {
        val (bytes, _, file) = built
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(20) }; return rt.totalMemory() - rt.freeMemory() }
        fun <T> timed(f: () -> T): Pair<T, Long> { val t = System.nanoTime(); val x = f(); return x to (System.nanoTime() - t) / 1_000_000 }
        // Warm both paths once (class loading), then measure.
        Knowledge.Index(Knowledge.read(bytes.inputStream())); fromFile()
        val base = used()
        val (a, msBuilt) = timed { Knowledge.Index(Knowledge.read(bytes.inputStream())) }
        val heldBuilt = used() - base
        val base2 = used()
        val (b, msFile) = timed { fromFile() }
        val heldFile = used() - base2
        println("knowledge index: built from the pack $msBuilt ms, ~${heldBuilt / 1_048_576} MB held; from the file $msFile ms, ~${heldFile / 1_048_576} MB held; " +
            "file ${file.size / 1024} KB for a ${bytes.size / 1024} KB pack (${a.size} = ${b.size} facts)")
        assertEquals(a.size, b.size)
    }
}
