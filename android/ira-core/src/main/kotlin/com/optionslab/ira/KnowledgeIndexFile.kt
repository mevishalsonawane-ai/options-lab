package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * The knowledge index built once, loaded at once (memory audit, 14 Oct 2026, item 3; Boss's "build once, load instantly").
 *
 * Before, every start unzipped the 5.6 MB pack, parsed its JSON into a tree (about 40-50 MB at the peak) and tokenised every
 * fact again to build the [Knowledge.Index]. Now CI builds the index at APK build time ([main], run by the app's Gradle
 * build) into one binary asset next to the pack, and the phone reads it straight into the index's own arrays: no unzip,
 * no JSON tree, no tokenising. The facts' repeated words (kinds, sources, sections, dates, tags) are shared, not copied
 * per fact.
 *
 * Research knowledge only (the pack is public and in the repo); Boss's own findings never go in here.
 *
 * The file names the pack it was built from (its size and CRC-32): the app checks that against the pack it ships, and a
 * file from another pack, another version or a damaged one is not used - the app then builds the index from the pack, as
 * before ([read] gives null). Pure JVM; the caller does the I/O off the main thread.
 */
object KnowledgeIndexFile {
    const val ASSET = "knowledge/index.bin"
    private const val MAGIC = 0x49524B58   // "IRKX"
    /** Bumped whenever the layout below or the words' rules ([Knowledge.tokens]) change. */
    const val VERSION = 1

    /** The size and CRC-32 of [packBytes]: what an index file must name to be used with that pack. */
    fun packId(packBytes: ByteArray): Pair<Int, Long> = packBytes.size to CRC32().also { it.update(packBytes) }.value

    /** The index file for the pack [packBytes] (gzip or plain JSON), written to [out]. Throws on a malformed pack. */
    fun build(packBytes: ByteArray, out: OutputStream) {
        val root = Json.parse(Knowledge.text(packBytes)) as? Map<*, *> ?: throw IllegalArgumentException("pack: not an object")
        val pack = Knowledge.parse(root)
        val shared = (root["shared"] as? Map<*, *>)?.let { Json.write(it) }
        write(Knowledge.Index(pack), shared, packId(packBytes), out)
    }

    internal fun write(ix: Knowledge.Index, shared: String?, pack: Pair<Int, Long>, out: OutputStream) {
        val o = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
        o.writeInt(MAGIC); o.writeInt(VERSION); o.writeInt(Knowledge.SCHEMA)
        o.writeInt(pack.first); o.writeLong(pack.second)
        str(o, ix.pack.version); str(o, ix.pack.builtFrom)
        o.writeBoolean(shared != null); if (shared != null) str(o, shared)
        o.writeInt(ix.pack.facts.size)
        for (f in ix.pack.facts) {
            str(o, f.id); str(o, f.kind); str(o, f.title); str(o, f.text)
            o.writeInt(f.tags.size); f.tags.forEach { str(o, it) }
            o.writeInt(f.inst.size); f.inst.forEach { str(o, it) }
            str(o, f.src); str(o, f.sec); str(o, f.date); str(o, f.verdict); str(o, f.conf)
            o.writeDouble(f.weight); o.writeBoolean(f.personal)
        }
        // The postings in a fixed (sorted) order, so the same pack always gives the same file.
        val words = ix.postings.keys.sorted()
        // A posting is (fact << 16 | count): four bytes while there are fewer than 65,536 facts (the file's half), else eight.
        val narrow = ix.pack.facts.size < 65_536
        o.writeBoolean(narrow)
        o.writeInt(words.size)
        for (w in words) {
            str(o, w); val p = ix.postings.getValue(w); o.writeInt(p.size)
            for (v in p) if (narrow) o.writeInt(v.toInt()) else o.writeLong(v)
        }
        for (n in ix.length) o.writeInt(n)
        for (t in ix.titles) { val ws = t.sorted(); o.writeInt(ws.size); ws.forEach { str(o, it) } }
        o.writeDouble(ix.avg)
        o.writeInt(MAGIC)
        o.flush()
    }

    /**
     * The index from an index file, when it was built for the pack [pack] ([packId]; null: any pack - the tests), by this
     * version, and reads whole; else null (the caller builds from the pack). Never throws.
     */
    fun read(input: InputStream, pack: Pair<Int, Long>?): Knowledge.Index? = runCatching {
        val i = DataInputStream(BufferedInputStream(input, 1 shl 16))
        if (i.readInt() != MAGIC || i.readInt() != VERSION || i.readInt() != Knowledge.SCHEMA) return null
        val id = i.readInt() to i.readLong()
        if (pack != null && id != pack) return null
        // Repeated words read once and shared (kinds, sources, sections, dates, tags, the postings' words).
        val pool = HashMap<String, String>()
        fun s(): String { val x = str(i); return pool.getOrPut(x) { x } }
        val version = s(); val builtFrom = s()
        val shared = if (i.readBoolean()) str(i) else null
        val n = i.readInt()
        require(n in 0..10_000_000)
        val facts = ArrayList<Knowledge.Fact>(n)
        repeat(n) {
            val fid = str(i); val kind = s(); val title = s(); val text = str(i)
            val tags = List(i.readInt()) { s() }; val inst = List(i.readInt()) { s() }
            facts += Knowledge.Fact(fid, kind, title, text, tags, inst, s(), s(), s(), s(), s(), i.readDouble(), i.readBoolean())
        }
        val narrow = i.readBoolean()
        val wn = i.readInt()
        require(wn in 0..10_000_000)
        val postings = HashMap<String, LongArray>(wn * 4 / 3 + 1)
        repeat(wn) { val w = s(); postings[w] = LongArray(i.readInt()) { if (narrow) i.readInt().toLong() and 0xFFFF_FFFFL else i.readLong() } }
        val length = IntArray(n) { i.readInt() }
        val titles = List(n) { HashSet<String>().apply { repeat(i.readInt()) { add(s()) } } }
        val avg = i.readDouble()
        if (i.readInt() != MAGIC) return null
        val research = shared?.let { t -> runCatching { (Json.parse(t) as? Map<*, *>)?.let { SharedKnowledge.research(it) } }.getOrNull() }
        Knowledge.Index(Knowledge.Pack(Knowledge.SCHEMA, version, builtFrom, facts, research), postings, length, titles, avg)
    }.getOrNull()

    /**
     * Only the pack's shared section and version from an index file (the head of the file; the facts and postings are not
     * read): what the shared knowledge needs, without the index in memory. Null as [read] (another pack, version, damage).
     */
    fun readShared(input: InputStream, pack: Pair<Int, Long>?): Pair<SharedKnowledge.Research?, String>? = runCatching {
        val i = DataInputStream(BufferedInputStream(input, 1 shl 14))
        if (i.readInt() != MAGIC || i.readInt() != VERSION || i.readInt() != Knowledge.SCHEMA) return null
        val id = i.readInt() to i.readLong()
        if (pack != null && id != pack) return null
        val version = str(i); str(i)
        val shared = if (i.readBoolean()) str(i) else null
        shared?.let { t -> runCatching { (Json.parse(t) as? Map<*, *>)?.let { SharedKnowledge.research(it) } }.getOrNull() } to version
    }.getOrNull()

    private fun str(o: DataOutputStream, s: String) { val b = s.toByteArray(Charsets.UTF_8); o.writeInt(b.size); o.write(b) }
    private fun str(i: DataInputStream, max: Int = 64 shl 20): String {
        val n = i.readInt()
        require(n in 0..max)
        val b = ByteArray(n); i.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    /**
     * The build's tool: `main(pack, out)` writes the index file for the pack at `pack` to `out` (the app's Gradle build runs it
     * for every APK; the file is not kept in git).
     */
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "usage: KnowledgeIndexFile <pack.bin> <index.bin>" }
        val bytes = File(args[0]).readBytes()
        val out = File(args[1])
        out.parentFile?.mkdirs()
        out.outputStream().use { build(bytes, it) }
        println("knowledge index: ${out.length()} bytes for a ${bytes.size}-byte pack")
    }
}
