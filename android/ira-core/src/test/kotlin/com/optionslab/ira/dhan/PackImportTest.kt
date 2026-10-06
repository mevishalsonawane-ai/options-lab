package com.optionslab.ira.dhan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackImportTest {
    private fun store(): Files = Files(File(kotlin.io.path.createTempDirectory("pack").toFile(), "dhan"))

    private fun gz(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()
    private fun manifest(vararg files: Pair<String, ByteArray>, sizeFix: Long = 0): ByteArray =
        ("{\"format\":\"IraAlgo-dhan-pack\",\"version\":1,\"files\":[" + files.joinToString(",") { (p, b) ->
            "{\"path\":\"$p\",\"size\":${b.size + sizeFix},\"sha256\":\"${sha(b)}\"}"
        } + "]}").toByteArray()

    private fun candles(vararg t: Long) = gz(Files.CANDLE_HEADER + "\n" + t.joinToString("") { "$it,1,2,0.5,1.5,10,0\n" })

    private fun run(f: Files, vararg parts: ByteArray, limits: PackImport.Limits = PackImport.Limits()): PackImport.Report {
        val imp = PackImport(f, limits)
        imp.begin()
        try {
            parts.forEachIndexed { i, p -> imp.readPart(ByteArrayInputStream(p), i + 1, parts.size) }
        } catch (e: Exception) { imp.abort(); throw e }
        return imp.finish()
    }

    // ---- paths -----------------------------------------------------------------------------------------------------------

    @Test fun onlyTheStoresOwnPathsPass() {
        for (ok in listOf("dhan/master.csv", "dhan/expiries/NIFTY.csv", "dhan/chain/BANKNIFTY/2026-10-05.json.gz",
            "dhan/idx/NIFTY/day/2000-01-01.csv.gz", "dhan/fut/NIFTY/min/2026-08-01.csv.gz", "dhan/eq/M_M/min/2026-01-01.csv.gz",
            "dhan/opt/NIFTY/WEEK/2026-09-01/CE+10.csv.gz", "dhan/opt/NIFTY/WEEK/2026-09-01/PE-3.csv.gz")) {
            assertEquals(ok.removePrefix("dhan/"), PackImport.storePath(ok))
        }
        for (bad in listOf(
            "../dhan/master.csv", "dhan/../../etc/passwd", "dhan/idx/../../shared_prefs/x.csv.gz", "dhan/./master.csv",
            "/data/data/com.optionslab.app/files/dhan/master.csv", "/dhan/master.csv", "C:/dhan/master.csv", "dhan\\idx\\a.csv.gz",
            "dhan//master.csv", "master.csv", "other/idx/NIFTY/day/2000-01-01.csv.gz", "__MACOSX/dhan/._master.csv",
            "dhan/idx/NIFTY/day/2000-01-01.csv", "dhan/idx/NIFTY/day/2000-01-01.csv.gz.exe", "dhan/idx/NIFTY/day/x.sh",
            "dhan/idx/NIFTY/day/2000-13-45.csv.gz", "dhan/idx/nifty/day/2000-01-01.csv.gz", "dhan/token.json",
            "dhan/shared_prefs/vault.xml", "dhan/idx/NIFTY/day/2000-01-01.csv.gz\u0000.png", "dhan/opt/NIFTY/WEEK/2026-09-01/XX+1.csv.gz",
            "~/dhan/master.csv", "")) {
            assertFailsWith<PackImport.Refused>(bad) { PackImport.storePath(bad) }
        }
        assertTrue(PackImport.dirOk("dhan/") && PackImport.dirOk("dhan/idx/NIFTY/"))
        assertFalse(PackImport.dirOk("dhan/../") || PackImport.dirOk("x/") || PackImport.dirOk("dhan/a\\b/"))
    }

    @Test fun aZipSlipEntryIsRefusedAndNothingIsWritten() {
        val f = store()
        val outside = File(f.root.parentFile, "evil.csv.gz")
        for (name in listOf("dhan/../evil.csv.gz", "../evil.csv.gz", "/tmp/evil.csv.gz", "dhan/idx/../../evil.csv.gz")) {
            val p = zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to candles(1), name to candles(2))
            assertFailsWith<PackImport.Refused>(name) { run(f, p) }
            assertFalse(outside.exists())
            assertFalse(f.has("idx/NIFTY/day/2000-01-01.csv.gz"), "a refused pack leaves the store untouched")
            assertFalse(PackImport(f).staging.exists())
        }
    }

    @Test fun aLinkEntryCannotBecomeALink() {
        // A zipped symlink is an entry whose content is the target path: it is written nowhere as a link, and fails the check.
        val f = store()
        assertFailsWith<PackImport.Refused> { run(f, zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to "/data/data/x/shared_prefs".toByteArray())) }
        assertFailsWith<PackImport.Refused> { run(f, zip("dhan/expiries/NIFTY.csv" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) }
        // A gzip that is not the store's CSV is refused too.
        assertFailsWith<PackImport.Refused> { run(f, zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to gz("#!/bin/sh\n"))) }
        assertFailsWith<PackImport.Refused> { run(f, zip("dhan/opt/NIFTY/WEEK/2026-09-01/CE+1.csv.gz" to candles(1))) }
        assertFalse(f.root.exists() && f.bytes() > 0)
    }

    // ---- caps -------------------------------------------------------------------------------------------------------------

    @Test fun theCapsCountInflatedBytes() {
        val f = store()
        val big = gz(Files.CANDLE_HEADER + "\n" + "1,1,1,1,1,1,1\n".repeat(20_000))
        // A highly compressible entry: the zip is small but the bytes inflated pass the per-file cap.
        val bomb = ByteArray(5_000_000) { 0x20 }
        assertFailsWith<PackImport.Refused> { run(f, zip("dhan/master.csv" to bomb), limits = PackImport.Limits(maxTextBytes = 1_000_000)) }
        assertFailsWith<PackImport.Refused> {
            run(f, zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to big), limits = PackImport.Limits(maxEntryBytes = big.size - 1L))
        }
        val e = assertFailsWith<PackImport.Refused> {
            run(f, zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to big), zip("dhan/idx/NIFTY/day/2005-01-01.csv.gz" to big),
                limits = PackImport.Limits(maxTotalBytes = big.size * 2L - 1))
        }
        assertTrue(e.message!!.contains("unpacks to more"))
        assertFailsWith<PackImport.Refused> {
            run(f, zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to candles(1), "dhan/idx/NIFTY/day/2005-01-01.csv.gz" to candles(2)),
                limits = PackImport.Limits(maxEntries = 1))
        }
        assertFalse(f.has("idx/NIFTY/day/2000-01-01.csv.gz"))
        // Within the caps it goes in.
        assertEquals(2, run(f, zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to big), zip("dhan/idx/NIFTY/day/2005-01-01.csv.gz" to big),
            limits = PackImport.Limits(maxTotalBytes = big.size * 2L)).added)
    }

    @Test fun notAZipAndDuplicatesAreRefused() {
        val f = store()
        assertFailsWith<PackImport.Refused> { run(f, "hello".toByteArray()) }
        assertFailsWith<PackImport.Refused> { run(f, zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to candles(1)), zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to candles(1))) }
        assertFailsWith<PackImport.Refused> { run(f, zip("readme.txt" to "hi".toByteArray())) }
        assertFailsWith<PackImport.Refused> { run(f, zip(PackImport.MANIFEST to manifest())) }
    }

    // ---- the manifest -----------------------------------------------------------------------------------------------------

    @Test fun theManifestsChecksumsAreChecked() {
        val a = candles(100, 160); val b = candles(200)
        val pa = "dhan/idx/NIFTY/min/2026-08-01.csv.gz"; val pb = "dhan/idx/NIFTY/min/2026-10-25.csv.gz"
        // Manifest first, in part 1; the second file in part 2: both checked.
        val f = store()
        val r = run(f, zip(PackImport.MANIFEST to manifest(pa to a, pb to b), pa to a), zip(pb to b))
        assertEquals(2, r.added); assertTrue(r.checked); assertEquals(0, r.missing)
        assertEquals(listOf("100,1,2,0.5,1.5,10,0", "160,1,2,0.5,1.5,10,0"), f.lines(pa.removePrefix("dhan/")).toList())

        // A checksum mismatch (a damaged or altered file) refuses the whole pack, whichever comes first.
        val g = store()
        val tampered = candles(100, 999)
        val m = assertFailsWith<PackImport.Refused> { run(g, zip(PackImport.MANIFEST to manifest(pa to a), pa to tampered)) }
        assertTrue(m.message!!.contains("checksum"))
        assertFailsWith<PackImport.Refused> { run(g, zip(pa to tampered, PackImport.MANIFEST to manifest(pa to a))) }
        assertFailsWith<PackImport.Refused> { run(g, zip(PackImport.MANIFEST to manifest(pa to a, sizeFix = 1), pa to a)) }
        // A file the manifest does not list is refused.
        assertFailsWith<PackImport.Refused> { run(g, zip(PackImport.MANIFEST to manifest(pa to a), pa to a, pb to b)) }
        // Two parts with different manifests are two packs.
        assertFailsWith<PackImport.Refused> { run(g, zip(PackImport.MANIFEST to manifest(pa to a), pa to a), zip(PackImport.MANIFEST to manifest(pb to b), pb to b)) }
        assertFalse(g.has(pa.removePrefix("dhan/")))

        // A part not chosen: what is there goes in, and the report says what is missing.
        val h = store()
        val partial = run(h, zip(PackImport.MANIFEST to manifest(pa to a, pb to b), pa to a))
        assertEquals(1, partial.added); assertEquals(1, partial.missing)
        assertTrue(partial.say().contains("other parts"))

        // A manifest's own paths are store paths, and its format is checked.
        assertFailsWith<PackImport.Refused> { PackImport.parseManifest("{\"files\":[{\"path\":\"dhan/../x.csv.gz\",\"size\":1,\"sha256\":\"${"0".repeat(64)}\"}]}") }
        assertFailsWith<PackImport.Refused> { PackImport.parseManifest("{\"format\":\"other\",\"files\":[]}") }
        assertFailsWith<PackImport.Refused> { PackImport.parseManifest("{\"files\":[{\"path\":\"dhan/master.csv\",\"size\":1,\"sha256\":\"abc\"}]}") }
        assertFailsWith<PackImport.Refused> { PackImport.parseManifest("not json") }
        assertEquals(setOf("master.csv"), PackImport.parseManifest("{\"files\":[{\"path\":\"master.csv\",\"size\":3,\"sha256\":\"${"a".repeat(64)}\"}]}").keys)
        assertNull(PackImport.checkListed("master.csv", 3, "a".repeat(64), PackImport.parseManifest("{\"files\":[{\"path\":\"master.csv\",\"size\":3,\"sha256\":\"${"A".repeat(64)}\"}]}")))
    }

    // ---- merging ----------------------------------------------------------------------------------------------------------

    @Test fun theMergeRules() {
        val s = PackImport.Stat(1000, 10)
        assertEquals(PackImport.Action.ADD, PackImport.decide("idx/NIFTY/min/2026-08-01.csv.gz", false, null, s))
        assertEquals(PackImport.Action.KEEP, PackImport.decide("idx/NIFTY/min/2026-08-01.csv.gz", true, s, PackImport.Stat(900, 50)), "never older over newer")
        assertEquals(PackImport.Action.KEEP, PackImport.decide("idx/NIFTY/min/2026-08-01.csv.gz", true, s, s), "the same chunk: kept")
        assertEquals(PackImport.Action.REPLACE, PackImport.decide("idx/NIFTY/min/2026-08-01.csv.gz", true, s, PackImport.Stat(1060, 11)))
        assertEquals(PackImport.Action.REPLACE, PackImport.decide("idx/NIFTY/min/2026-08-01.csv.gz", true, s, PackImport.Stat(1000, 12)))
        assertEquals(PackImport.Action.REPLACE, PackImport.decide("opt/NIFTY/WEEK/2026-09-01/CE+1.csv.gz", true, PackImport.Stat(null, 0), s), "header only on the phone")
        assertEquals(PackImport.Action.KEEP, PackImport.decide("opt/NIFTY/WEEK/2026-09-01/CE+1.csv.gz", true, s, PackImport.Stat(null, 0)))
        assertEquals(PackImport.Action.REPLACE, PackImport.decide("idx/NIFTY/day/2000-01-01.csv.gz", true, null, s), "the phone's file unreadable")
        assertEquals(PackImport.Action.KEEP, PackImport.decide("chain/NIFTY/2026-10-05.json.gz", true, null, null))
        assertEquals(PackImport.Action.KEEP, PackImport.decide("master.csv", true, null, null))
        assertEquals(PackImport.Action.ADD, PackImport.decide("master.csv", false, null, null))
        assertEquals(PackImport.Action.UNION, PackImport.decide("expiries/NIFTY.csv", true, null, null))
        assertEquals("2026-09-30\n2026-10-07\n2026-10-14\n", PackImport.unionExpiries("2026-10-07\n2026-09-30\n", "2026-10-14\n2026-10-07\nx\n"))
    }

    @Test fun anImportMergesWithTheStoreAndCountsAsDone() {
        val f = store()
        val today = java.time.LocalDate.of(2026, 10, 6)
        // On the phone: a closed minute window (complete), an open one fetched mid-window, the chain, the master, the expiries.
        f.writeText("idx/NIFTY/min/2026-05-08.csv.gz", Files.CANDLE_HEADER + "\n500,1,1,1,1,1,0\n600,1,1,1,1,1,0\n")
        f.writeText("idx/NIFTY/min/2026-08-01.csv.gz", Files.CANDLE_HEADER + "\n100,1,1,1,1,1,0\n")
        f.writeText("chain/NIFTY/2026-10-05.json.gz", "{\"phone\":1}")
        f.writeText("master.csv", "phone\n", gzip = false)
        f.addExpiries("NIFTY", listOf(java.time.LocalDate.of(2026, 10, 7)))
        val old = candles(500)                        // an older copy of the closed window
        val newer = candles(100, 160, 220)            // the open window, fetched later on the computer
        val fresh = candles(1, 2)
        val r = run(f, zip(
            "dhan/" to ByteArray(0),
            "dhan/idx/NIFTY/min/2026-05-08.csv.gz" to old,
            "dhan/idx/NIFTY/min/2026-08-01.csv.gz" to newer,
            "dhan/idx/NIFTY/day/2025-01-24.csv.gz" to fresh,
            "dhan/chain/NIFTY/2026-10-05.json.gz" to gz("{\"pack\":1}"),
            "dhan/master.csv" to "pack\n".toByteArray(),
            "dhan/expiries/NIFTY.csv" to "2026-09-30\n2026-10-07\n".toByteArray(),
        ))
        assertEquals(1, r.added); assertEquals(1, r.replaced); assertEquals(3, r.kept); assertEquals(1, r.merged)
        assertEquals(listOf("500,1,1,1,1,1,0", "600,1,1,1,1,1,0"), f.lines("idx/NIFTY/min/2026-05-08.csv.gz").toList(), "the newer phone file stays")
        assertEquals(3, f.lines("idx/NIFTY/min/2026-08-01.csv.gz").count(), "the pack's newer copy replaced it")
        assertEquals("{\"phone\":1}", f.readText("chain/NIFTY/2026-10-05.json.gz"))
        assertEquals("phone\n", f.readText("master.csv"))
        assertEquals(listOf("2026-09-30", "2026-10-07"), f.listedExpiries("NIFTY").map { it.toString() })
        assertFalse(f.root.walkTopDown().any { it.name.endsWith(".part") })
        assertFalse(PackImport(f).staging.exists())
        // The imported closed chunk is done: the downloader's plan skips it.
        val t = Plan.Task(Plan.Kind.DAY, Plan.Group.IDX, "NIFTY", "13", "IDX_I", "INDEX", java.time.LocalDate.of(2025, 1, 24), java.time.LocalDate.of(2026, 1, 23))
        assertEquals(emptyList(), Plan.todo(listOf(t), today) { f.has(it) })
        // Importing the same pack again changes nothing.
        val again = run(f, zip("dhan/idx/NIFTY/min/2026-08-01.csv.gz" to newer))
        assertEquals(1, again.kept)
    }

    @Test fun aStoppedImportLeavesTheStoreAsItWas() {
        val f = store()
        val imp = PackImport(f)
        imp.begin()
        var n = 0
        assertFailsWith<PackImport.Cancelled> {
            imp.readPart(ByteArrayInputStream(zip("dhan/idx/NIFTY/day/2000-01-01.csv.gz" to candles(1), "dhan/idx/NIFTY/day/2005-01-01.csv.gz" to candles(2))),
                1, 1, cancelled = { ++n > 2 })
        }
        imp.abort()
        assertFalse(imp.staging.exists())
        assertFalse(f.has("idx/NIFTY/day/2000-01-01.csv.gz"))
    }
}
