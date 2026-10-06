package com.optionslab.ira.dhan

import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GithubPackTest {
    private val shaA = "a".repeat(64)
    private val shaB = "B".repeat(64)

    private fun index(parts: String = """[{"name":"IraAlgo-dhan-pack-02.zip","size":200,"sha256":"$shaB"},{"name":"IraAlgo-dhan-pack-01.zip","size":100,"sha256":"$shaA"}]""",
                      total: String = "300", version: String = "1", from: String = "\"2020-01-01\"", to: String = "\"2026-09-30\"",
                      created: String = "\"2026-10-01\""): String =
        """{"version":$version,"created":$created,"parts":$parts,"totalBytes":$total,"from":$from,"to":$to}"""

    @Test fun aGoodIndexParses() {
        val i = GithubPack.parseIndex(index())
        assertEquals(1, i.version)
        assertEquals(LocalDate.of(2026, 10, 1), i.created)
        assertEquals(listOf("IraAlgo-dhan-pack-01.zip", "IraAlgo-dhan-pack-02.zip"), i.parts.map { it.name })
        assertEquals("b".repeat(64), i.parts[1].sha256)          // lowercased
        assertEquals(300L, i.totalBytes)
        assertEquals(LocalDate.of(2020, 1, 1), i.from)
        assertEquals("2 parts, 0.00 GB, dates 2020-01-01 to 2026-09-30", i.say())
    }

    @Test fun aBadIndexIsRefused() {
        val bad = listOf(
            "", "not json", "[]", "{}",
            index(version = "2"), index(version = "\"1\""),
            index(created = "\"2026-13-01\""), index(from = "\"2026-10-01\"", to = "\"2026-01-01\""), index(to = "null"), index(from = "\"20200101\""),
            index(parts = "[]"), index(parts = "{}"),
            index(parts = """[{"name":"../etc/passwd","size":1,"sha256":"$shaA"}]""", total = "1"),
            index(parts = """[{"name":"IraAlgo-dhan-pack-1.zip","size":1,"sha256":"$shaA"}]""", total = "1"),
            index(parts = """[{"name":"IraAlgo-dhan-pack-01.zip.exe","size":1,"sha256":"$shaA"}]""", total = "1"),
            index(parts = """[{"name":"IraAlgo-dhan-pack-01.zip","size":1,"sha256":"$shaA"},{"name":"IraAlgo-dhan-pack-01.zip","size":1,"sha256":"$shaA"}]""", total = "2"),
            index(parts = """[{"name":"IraAlgo-dhan-pack-01.zip","size":0,"sha256":"$shaA"}]""", total = "0"),
            index(parts = """[{"name":"IraAlgo-dhan-pack-01.zip","size":${GithubPack.MAX_PART_BYTES + 1},"sha256":"$shaA"}]""", total = "${GithubPack.MAX_PART_BYTES + 1}"),
            index(parts = """[{"name":"IraAlgo-dhan-pack-01.zip","size":1.5,"sha256":"$shaA"}]""", total = "1"),
            index(parts = """[{"name":"IraAlgo-dhan-pack-01.zip","size":1,"sha256":"xyz"}]""", total = "1"),
            index(parts = """[{"name":"IraAlgo-dhan-pack-01.zip","size":1}]""", total = "1"),
            index(total = "301"), index(total = "\"300\""),
            "{\"pad\":\"" + "x".repeat(GithubPack.MAX_INDEX_BYTES) + "\"}",
        )
        for (b in bad) assertFailsWith<GithubPack.Bad>(b.take(120)) { GithubPack.parseIndex(b) }
    }

    @Test fun theWholePackIsCapped() {
        // 99 parts of 100 MB is under 12 GB; the cap is the sum's, checked after the per-part one.
        val n = 99
        val parts = (1..n).joinToString(",", "[", "]") { "{\"name\":\"IraAlgo-dhan-pack-%02d.zip\",\"size\":${GithubPack.MAX_PART_BYTES},\"sha256\":\"$shaA\"}".format(it) }
        val i = GithubPack.parseIndex(index(parts = parts, total = "${n * GithubPack.MAX_PART_BYTES}"))
        assertEquals(n, i.parts.size)
        assertTrue(i.totalBytes <= GithubPack.MAX_TOTAL_BYTES)
        val tooMany = (0..n).joinToString(",", "[", "]") { "{\"name\":\"IraAlgo-dhan-pack-%02d.zip\",\"size\":1,\"sha256\":\"$shaA\"}".format(it) }
        assertFailsWith<GithubPack.Bad> { GithubPack.parseIndex(index(parts = tooMany, total = "${n + 1}")) }
    }

    @Test fun onlyThePacksOwnUrls() {
        assertEquals("https://raw.githubusercontent.com/mevishalsonawane-ai/options-lab/dhan-data/pack/pack-index.json", GithubPack.INDEX_URL)
        assertTrue(GithubPack.urlAllowed(GithubPack.INDEX_URL))
        assertTrue(GithubPack.urlAllowed(GithubPack.urlFor("IraAlgo-dhan-pack-07.zip")))
        for (bad in listOf(
            "http://raw.githubusercontent.com/mevishalsonawane-ai/options-lab/dhan-data/pack/pack-index.json",
            "https://raw.githubusercontent.com.evil.example/mevishalsonawane-ai/options-lab/dhan-data/pack/pack-index.json",
            "https://raw.githubusercontent.com/mevishalsonawane-ai/options-lab/main/pack/pack-index.json",
            GithubPack.BASE + "../x.zip", GithubPack.BASE + "IraAlgo-dhan-pack-07.zip?x=1", GithubPack.BASE + "sub/IraAlgo-dhan-pack-07.zip",
            GithubPack.BASE, "https://api.dhan.co/v2/charts/intraday",
        )) assertFalse(GithubPack.urlAllowed(bad), bad)
        assertFailsWith<GithubPack.Bad> { GithubPack.urlFor("../../secret") }
        assertTrue(GithubPack.partNameOk("IraAlgo-dhan-pack-99.zip"))
        assertFalse(GithubPack.partNameOk("IraAlgo-dhan-pack-100.zip") || GithubPack.partNameOk("iraalgo-dhan-pack-01.zip") ||
            GithubPack.partNameOk("IraAlgo-dhan-pack-01.zip\n") || GithubPack.partNameOk("x/IraAlgo-dhan-pack-01.zip"))
    }

    @Test fun resumingAPart() {
        assertEquals(0L, GithubPack.resumeFrom(0, 100))
        assertEquals(40L, GithubPack.resumeFrom(40, 100))
        assertEquals(100L, GithubPack.resumeFrom(100, 100))
        assertEquals(0L, GithubPack.resumeFrom(101, 100))
        assertEquals(0L, GithubPack.resumeFrom(-1, 100))
        assertNull(GithubPack.rangeHeader(0))
        assertEquals("bytes=40-", GithubPack.rangeHeader(40))
        assertTrue(GithubPack.contentRangeOk("bytes 40-99/100", 40, 100))
        assertTrue(GithubPack.contentRangeOk("bytes 40-99/*", 40, 100))
        assertFalse(GithubPack.contentRangeOk("bytes 0-99/100", 40, 100))
        assertFalse(GithubPack.contentRangeOk("bytes 40-98/100", 40, 100))
        assertFalse(GithubPack.contentRangeOk("bytes 40-99/200", 40, 100))
        assertFalse(GithubPack.contentRangeOk(null, 40, 100))
        assertFalse(GithubPack.contentRangeOk("junk", 40, 100))
    }

    @Test fun spaceAndBackoff() {
        val i = GithubPack.parseIndex(index())
        assertEquals(300L + 300 + 30 + GithubPack.SPARE_BYTES, GithubPack.spaceNeeded(i, 0))
        assertEquals(0L + 300 + 30 + GithubPack.SPARE_BYTES, GithubPack.spaceNeeded(i, 300))
        assertEquals(0L + 300 + 30 + GithubPack.SPARE_BYTES, GithubPack.spaceNeeded(i, 10_000))
        assertEquals(2_000L, GithubPack.backoffMs(0))
        assertEquals(4_000L, GithubPack.backoffMs(1))
        assertEquals(60_000L, GithubPack.backoffMs(20))
        assertEquals(2_500L, GithubPack.backoffMs(0, 1.0))
    }

    @Test fun checksumsAndStrays() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", GithubPack.sha256(ByteArrayInputStream("abc".toByteArray())))
        val dir = kotlin.io.path.createTempDirectory("ghpack").toFile()
        val f = File(dir, "IraAlgo-dhan-pack-01.zip").apply { writeText("abc") }
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(GithubPack.partOk(f, GithubPack.Part(f.name, 3, sha)))
        assertFalse(GithubPack.partOk(f, GithubPack.Part(f.name, 4, sha)))
        assertFalse(GithubPack.partOk(f, GithubPack.Part(f.name, 3, shaA)))
        assertFalse(GithubPack.partOk(File(dir, "missing"), GithubPack.Part(f.name, 3, sha)))
        val i = GithubPack.parseIndex(index())
        assertEquals(listOf("IraAlgo-dhan-pack-03.zip", "x.tmp"), GithubPack.strays(listOf("IraAlgo-dhan-pack-01.zip", "IraAlgo-dhan-pack-03.zip", "x.tmp"), i))
        dir.deleteRecursively()
    }
}
