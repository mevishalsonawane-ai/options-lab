package com.optionslab.ira.dhan

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The Dhan data pack published on GitHub (the public repo's `dhan-data` branch, folder `pack/`), as "Import from GitHub"
 * reads it: `pack-index.json` lists the zip parts, each exactly a part [PackImport] accepts:
 *
 *   {"version": 1, "created": "yyyy-MM-dd",
 *    "parts": [{"name": "IraAlgo-dhan-pack-01.zip", "size": 94371840, "sha256": "<64 hex>"}, ...],
 *    "totalBytes": N, "from": "yyyy-MM-dd", "to": "yyyy-MM-dd"}
 *
 * Only [BASE] is ever fetched: HTTPS, one fixed host ([HOST]) and folder, the index and part names matching [PART_NAME]
 * alone ([urlAllowed]). It is public data: no token, no credential and no header of the user's goes there. Every part is
 * checked against its listed size and sha256 before the import reads it, and the import checks every file in it again.
 *
 * Pure (parsing, the rules, the resume arithmetic and the checksum of a file), tested on the JVM; the app only moves bytes.
 */
object GithubPack {
    const val HOST = "raw.githubusercontent.com"
    const val BASE = "https://$HOST/mevishalsonawane-ai/options-lab/dhan-data/pack/"
    const val INDEX = "pack-index.json"
    const val INDEX_URL = BASE + INDEX

    /** The index is a short list: anything larger is not it. */
    const val MAX_INDEX_BYTES = 64 * 1024
    /** A part is published at 90 MB at most; 100 MB is the hard cap. */
    const val MAX_PART_BYTES = 100L * 1024 * 1024
    /** The whole pack, as [PackImport.Limits.maxTotalBytes]. */
    const val MAX_TOTAL_BYTES = 12L * 1024 * 1024 * 1024
    /** Two digits: at most 99 parts. */
    const val MAX_PARTS = 99
    /** Room kept free on the phone beyond what the parts and their unpacking need. */
    const val SPARE_BYTES = 200L * 1024 * 1024
    /** Tries per part (a dropped connection resumes where it stopped). */
    const val MAX_ATTEMPTS = 6

    val PART_NAME = Regex("^IraAlgo-dhan-pack-\\d{2}\\.zip$")
    private val SHA = Regex("^[0-9a-f]{64}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** The index or a part is not what it should be: the reason is safe to show. */
    class Bad(reason: String) : IOException(reason)

    data class Part(val name: String, val size: Long, val sha256: String)

    data class Index(val version: Int, val created: LocalDate, val parts: List<Part>, val totalBytes: Long, val from: LocalDate, val to: LocalDate) {
        /** "3 parts, 0.25 GB, dates 2020-01-01 to 2026-09-30". */
        fun say(): String = "${parts.size} part${if (parts.size == 1) "" else "s"}, " +
            "%.2f GB".format(java.util.Locale.ENGLISH, totalBytes / 1e9) + ", dates $from to $to"
    }

    /** Is [name] a part's file name (IraAlgo-dhan-pack-01.zip ... -99.zip)? */
    fun partNameOk(name: String): Boolean = PART_NAME.matches(name)

    /** The URL of the index or of the part [name]; [Bad] for any other name. */
    fun urlFor(name: String): String {
        if (name != INDEX && !partNameOk(name)) throw Bad("not a data pack file name")
        return BASE + name
    }

    /** May the app fetch [url]? Only the index and the part names, under [BASE], exactly. */
    fun urlAllowed(url: String): Boolean {
        if (!url.startsWith(BASE)) return false
        val rest = url.removePrefix(BASE)
        return rest == INDEX || partNameOk(rest)
    }

    private fun date(v: Any?, field: String): LocalDate {
        val s = v as? String ?: throw Bad("pack-index.json has no $field date")
        if (!DATE.matches(s)) throw Bad("pack-index.json has a bad $field date")
        return runCatching { LocalDate.parse(s) }.getOrElse { throw Bad("pack-index.json has a bad $field date") }
    }

    private fun long(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        else -> null
    }

    /**
     * The index, checked whole: version 1, real dates (from on or before to), 1 to [MAX_PARTS] parts with distinct
     * [PART_NAME] names, each 1 byte to [MAX_PART_BYTES] with a 64-hex sha256, and [Index.totalBytes] their sum, at most
     * [MAX_TOTAL_BYTES]. Parts come back in name order. Anything else is [Bad].
     */
    fun parseIndex(text: String): Index {
        if (text.length > MAX_INDEX_BYTES) throw Bad("pack-index.json is too large")
        val root = runCatching { com.optionslab.engine.strategy.Json.parse(text) }.getOrNull() as? Map<*, *>
            ?: throw Bad("pack-index.json is not readable")
        val version = long(root["version"]) ?: throw Bad("pack-index.json has no version")
        if (version != 1L) throw Bad("pack-index.json is version $version; this app reads version 1 (update the app)")
        val created = date(root["created"], "created")
        val from = date(root["from"], "from")
        val to = date(root["to"], "to")
        if (from.isAfter(to)) throw Bad("pack-index.json's dates are the wrong way round")
        val list = root["parts"] as? List<*> ?: throw Bad("pack-index.json lists no parts")
        if (list.isEmpty()) throw Bad("pack-index.json lists no parts")
        if (list.size > MAX_PARTS) throw Bad("pack-index.json lists more than $MAX_PARTS parts")
        val parts = ArrayList<Part>()
        val names = HashSet<String>()
        for (e in list) {
            val o = e as? Map<*, *> ?: throw Bad("pack-index.json has a bad part")
            val name = o["name"] as? String ?: throw Bad("pack-index.json has a part without a name")
            if (!partNameOk(name)) throw Bad("pack-index.json has a part with a bad name")
            if (!names.add(name)) throw Bad("pack-index.json lists $name twice")
            val size = long(o["size"]) ?: throw Bad("pack-index.json has no size for $name")
            if (size <= 0 || size > MAX_PART_BYTES) throw Bad("$name's size is outside the limit (${MAX_PART_BYTES / (1024 * 1024)} MB)")
            val sha = (o["sha256"] as? String)?.lowercase()?.takeIf { SHA.matches(it) } ?: throw Bad("pack-index.json has no sha256 for $name")
            parts += Part(name, size, sha)
        }
        val total = long(root["totalBytes"]) ?: throw Bad("pack-index.json has no totalBytes")
        val sum = parts.sumOf { it.size }
        if (total != sum) throw Bad("pack-index.json's totalBytes does not add up")
        if (total > MAX_TOTAL_BYTES) throw Bad("the pack is larger than ${MAX_TOTAL_BYTES / (1L shl 30)} GB")
        return Index(version.toInt(), created, parts.sortedBy { it.name }, total, from, to)
    }

    /**
     * Where to resume part [size] bytes long with [have] bytes already cached: [have] when it is a usable prefix (0 to
     * size, size meaning "complete, check it"), else 0 (start again - more than the part cannot be it).
     */
    fun resumeFrom(have: Long, size: Long): Long = if (have in 0..size) have else 0L

    /** The Range header asking for the rest from [have], or null for the whole part. */
    fun rangeHeader(have: Long): String? = if (have > 0) "bytes=$have-" else null

    /** A 206 reply's Content-Range must continue exactly at [have] and end at the part's last byte ([size] in all). */
    fun contentRangeOk(header: String?, have: Long, size: Long): Boolean {
        val m = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$").matchEntire(header?.trim() ?: return false) ?: return false
        val a = m.groupValues[1].toLongOrNull() ?: return false
        val b = m.groupValues[2].toLongOrNull() ?: return false
        val t = m.groupValues[3].let { if (it == "*") size else it.toLongOrNull() ?: return false }
        return a == have && b == size - 1 && t == size
    }

    /**
     * Free space the run needs: what is left to download of [index] ([cached] bytes of it already in the cache), the
     * unpacking (staged beside the store before the merge - gzip files do not shrink in a zip, so about the parts' size,
     * with a tenth more for headroom) and [SPARE_BYTES].
     */
    fun spaceNeeded(index: Index, cached: Long): Long =
        (index.totalBytes - cached.coerceIn(0, index.totalBytes)) + index.totalBytes + index.totalBytes / 10 + SPARE_BYTES

    /** The wait before retry [attempt] (0-based): 2 s, 4 s, ... at most a minute, with up to a quarter more by [jitter]. */
    fun backoffMs(attempt: Int, jitter: Double = 0.0): Long {
        val base = (2_000L shl attempt.coerceIn(0, 5)).coerceAtMost(60_000L)
        return base + (base * jitter.coerceIn(0.0, 1.0) / 4).toLong()
    }

    /** The sha256 of [input] in lowercase hex, streamed (closed here). */
    fun sha256(input: InputStream): String = input.use { s ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) { val r = s.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(f: File): String = sha256(f.inputStream())

    /** Is [f] the part [p]: its size and its sha256 as listed? */
    fun partOk(f: File, p: Part): Boolean = f.isFile && f.length() == p.size && runCatching { sha256(f) == p.sha256 }.getOrDefault(false)

    /** Files in the cache folder that are not parts of [index] (an older pack's, or anything else): to delete. */
    fun strays(names: Collection<String>, index: Index): List<String> {
        val keep = index.parts.map { it.name }.toSet()
        return names.filter { it !in keep }
    }
}
