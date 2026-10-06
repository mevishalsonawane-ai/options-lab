package com.optionslab.ira.dhan

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * A Dhan data pack, imported from zip parts Boss picks on the phone (IraAlgo-dhan-pack-01.zip, -02.zip, ...): each part
 * holds a root folder `dhan/` in the store's own layout ([Files]) and, in any part, `pack-manifest.json`:
 *
 *   {"format": "IraAlgo-dhan-pack", "version": 1,
 *    "files": [{"path": "dhan/idx/NIFTY/day/2000-01-01.csv.gz", "size": 12345, "sha256": "<64 hex>"}, ...]}
 *
 * Safe by construction:
 *  - every part is STREAMED (one entry at a time, 64 KB buffers; nothing is held whole);
 *  - every entry name must be one of the store's own file paths under `dhan/` ([storePath]) - no "..", no absolute or
 *    drive path, no backslash, nothing outside `dhan/` but the manifest, only .csv.gz / .json.gz (and the two plain .csv
 *    lists) - so a zip-slip name is refused before a byte is written;
 *  - bytes actually inflated are counted (the declared sizes are not trusted): per entry and in total ([Limits]), so a
 *    zip bomb stops at the cap;
 *  - a symlink entry cannot become a link: entries are only ever written as regular files, and a link's content (a path)
 *    fails the gzip / text check; the destination's real path must also lie inside the store;
 *  - with a manifest, every file must be listed in it with its size and sha256, or the import is refused.
 *
 * Two phases: every part is first unpacked into a staging folder beside the store ([staging]) and checked whole; only
 * when ALL of it passes is it merged into the store, file by file, each by rename (a file is complete or absent, as the
 * downloader writes them). Anything refused leaves the store untouched.
 *
 * The merge follows the store's own rules ([Plan], [Files]): an imported chunk is a file on its grid path, so a chunk
 * whose window has ended counts as done and the downloader skips it; a window that reaches today is refetched as always.
 * An existing file is never replaced by an older one ([decide]). Market prices only; nothing here reaches the network or
 * the token. Pure JVM (java.io / java.util.zip), tested on the JVM.
 */
class PackImport(val files: Files, val limits: Limits = Limits()) {

    /** The caps. Totals count bytes as they are inflated, never the sizes the zip declares. */
    data class Limits(
        val maxTotalBytes: Long = 6L * 1024 * 1024 * 1024,
        val maxEntryBytes: Long = 256L * 1024 * 1024,
        /** The two plain lists (master.csv, expiries/<U>.csv) are small text. */
        val maxTextBytes: Long = 8L * 1024 * 1024,
        val maxManifestBytes: Long = 32L * 1024 * 1024,
        val maxEntries: Int = 500_000,
        /** Inflating a stored .csv.gz to compare it with the one on the phone: at most this much, lines at most [maxLine]. */
        val maxInflatedBytes: Long = 1024L * 1024 * 1024,
        val maxLine: Int = 4096,
    )

    /** The import was refused: the reason is safe to show (no secret, a short path at most). */
    class Refused(reason: String) : IOException(reason)

    /** Boss stopped it. Nothing was merged after the stop point; the staging folder is removed. */
    class Cancelled : IOException("import stopped")

    data class Progress(val part: Int, val parts: Int, val files: Int, val bytes: Long, val stage: String)

    /** One listed file of the manifest. [path] is relative to the store root (the "dhan/" dropped). */
    data class Listed(val path: String, val size: Long, val sha256: String)

    /** What happened, for the page. */
    data class Report(val added: Int, val replaced: Int, val kept: Int, val merged: Int, val missing: Int, val bytes: Long, val checked: Boolean) {
        val files: Int get() = added + replaced + kept + merged
        fun say(): String = buildString {
            append("$files file(s) imported (${"%.1f".format(java.util.Locale.ENGLISH, bytes / 1e6)} MB): ")
            append("$added new")
            if (replaced > 0) append(", $replaced newer than the phone's")
            if (kept > 0) append(", $kept already on the phone (kept)")
            if (merged > 0) append(", $merged merged")
            append(if (checked) "; every checksum matched" else "; no manifest, checksums not checked")
            if (missing > 0) append(". $missing listed file(s) were not in the parts chosen - pick the other parts too")
        }
    }

    /** What a staged file is, as the merge sees it. */
    enum class Action { ADD, REPLACE, KEEP, UNION }

    /** A candle/option file's measure for "newer": its last minute's time, then its rows. */
    data class Stat(val lastT: Long?, val rows: Long)

    companion object {
        const val ROOT = "dhan/"
        const val MANIFEST = "pack-manifest.json"
        const val FORMAT = "IraAlgo-dhan-pack"

        private val SYM = "[A-Z0-9_-]{1,40}"
        private val DATE = "\\d{4}-\\d{2}-\\d{2}"
        private val LAYOUT = listOf(
            Regex("master\\.csv"),
            Regex("expiries/$SYM\\.csv"),
            Regex("chain/$SYM/($DATE)\\.json\\.gz"),
            Regex("(idx|fut|eq)/$SYM/(day|min)/($DATE)\\.csv\\.gz"),
            Regex("opt/$SYM/[A-Z]{1,10}/($DATE)/(CE|PE)[+-]\\d{1,2}\\.csv\\.gz"),
        )

        /**
         * The store path (relative to the store root) an entry [name] is written to, or a [Refused] naming why not.
         * Only the store's own file paths pass: "dhan/" + one of the layouts in [Files], with a real calendar date.
         */
        fun storePath(name: String): String {
            if (name.isEmpty() || name.length > 200) throw Refused("an entry name is empty or too long")
            if (name.any { it == '\u0000' || it < ' ' || it == '\\' || it == ':' }) throw Refused("an entry name has a forbidden character: ${shown(name)}")
            if (name.startsWith("/") || name.startsWith("~")) throw Refused("an absolute path in the pack: ${shown(name)}")
            val segs = name.split('/')
            if (segs.any { it == ".." || it == "." }) throw Refused("a path that climbs out (\"..\"): ${shown(name)}")
            if (segs.any { it.isEmpty() }) throw Refused("an empty path segment: ${shown(name)}")
            if (!name.startsWith(ROOT)) throw Refused("a file outside dhan/: ${shown(name)}")
            val rel = name.removePrefix(ROOT)
            val m = LAYOUT.firstNotNullOfOrNull { it.matchEntire(rel) }
                ?: throw Refused(if (allowedExt(rel)) "a file not in the store's layout: ${shown(name)}" else "a file type the store does not keep: ${shown(name)}")
            // Every date in the path must be a real day.
            for (g in m.groupValues.drop(1)) if (g.length == 10 && g[4] == '-') runCatching { LocalDate.parse(g) }.getOrElse { throw Refused("a bad date in ${shown(name)}") }
            return rel
        }

        /** A directory entry: "dhan/" or a folder under it with plain segments (written nowhere; skipped). */
        fun dirOk(name: String): Boolean {
            if (!name.endsWith("/")) return false
            val body = name.removeSuffix("/")
            if (body + "/" == ROOT) return true
            if (!name.startsWith(ROOT) || name.contains('\\') || name.contains(':')) return false
            return body.removePrefix(ROOT).split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_+.-]{1,40}")) }
        }

        fun allowedExt(path: String): Boolean = path.endsWith(".csv.gz") || path.endsWith(".json.gz") || path.endsWith(".csv") || path.endsWith(".json")

        private fun shown(name: String): String = name.take(80).map { if (it < ' ' || it == '\u007f') '?' else it }.joinToString("")

        /**
         * The manifest's listed files, keyed by store path. Refused when it is not the pack's format, a path is not a
         * store path, or a size / checksum is malformed.
         */
        fun parseManifest(text: String): Map<String, Listed> {
            val root = runCatching { com.optionslab.engine.strategy.Json.parse(text) }.getOrNull() as? Map<*, *>
                ?: throw Refused("pack-manifest.json is not readable")
            (root["format"] as? String)?.let { if (it != FORMAT) throw Refused("pack-manifest.json is not an IraAlgo Dhan pack") }
            val list = root["files"] as? List<*> ?: throw Refused("pack-manifest.json lists no files")
            val out = LinkedHashMap<String, Listed>()
            for (e in list) {
                val o = e as? Map<*, *> ?: throw Refused("pack-manifest.json has a bad entry")
                val p = o["path"] as? String ?: throw Refused("pack-manifest.json has an entry without a path")
                val rel = storePath(if (p.startsWith(ROOT)) p else ROOT + p)
                val size = (o["size"] as? Long)?.takeIf { it >= 0 } ?: throw Refused("pack-manifest.json has no size for ${shown(p)}")
                val sha = (o["sha256"] as? String)?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    ?: throw Refused("pack-manifest.json has no sha256 for ${shown(p)}")
                if (out.put(rel, Listed(rel, size, sha)) != null) throw Refused("pack-manifest.json lists ${shown(p)} twice")
            }
            return out
        }

        /** The check of one staged file against the manifest: null when it matches, else the reason. */
        fun checkListed(rel: String, size: Long, sha256: String, manifest: Map<String, Listed>): String? {
            val l = manifest[rel] ?: return "dhan/$rel is not in pack-manifest.json"
            if (l.sha256 != sha256) return "dhan/$rel does not match its checksum in pack-manifest.json"
            if (l.size != size) return "dhan/$rel has $size bytes, the manifest says ${l.size}"
            return null
        }

        /**
         * The merge rule for one file ([rel] its store path; [existing] the phone's file's measure, null when absent or
         * unreadable; [incoming] the pack's). Never replaces a newer file with an older one:
         *  - absent on the phone: ADD;
         *  - the expiry lists: UNION (a listed expiry stays a fact once it has passed, as [Files.addExpiries]);
         *  - the scrip master and a day's option chain: KEEP the phone's (the downloader refreshes them itself);
         *  - candles and expired options: REPLACE only when the pack's file reaches a later minute (or, to the same minute,
         *    holds more rows) - a chunk whose window had not ended when the phone fetched it; otherwise KEEP.
         */
        fun decide(rel: String, existingPresent: Boolean, existing: Stat?, incoming: Stat?): Action {
            if (!existingPresent) return Action.ADD
            if (rel.startsWith("expiries/")) return Action.UNION
            if (rel == "master.csv" || rel.startsWith("chain/")) return Action.KEEP
            if (incoming == null) return Action.KEEP
            if (existing == null) return Action.REPLACE      // the phone's file is unreadable: the pack's checked one wins
            val a = existing.lastT ?: Long.MIN_VALUE
            val b = incoming.lastT ?: Long.MIN_VALUE
            return if (b > a || (b == a && incoming.rows > existing.rows)) Action.REPLACE else Action.KEEP
        }

        /** The expiry dates of two lists, each once, oldest first (lines that are not dates dropped). */
        fun unionExpiries(a: String, b: String): String =
            (a.lineSequence() + b.lineSequence()).mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
                .distinct().sorted().joinToString("\n", postfix = "\n")

        fun headerFor(rel: String): String? = when {
            rel.startsWith("opt/") -> Files.OPTION_HEADER
            rel.endsWith(".csv.gz") -> Files.CANDLE_HEADER
            else -> null
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    /** The staging folder: beside the store (same disk, so the merge is a rename), app-private like it. */
    val staging: File = File(files.root.parentFile ?: files.root, files.root.name + ".import")

    private class Staged(val rel: String, val size: Long, val sha256: String)

    private val staged = LinkedHashMap<String, Staged>()
    private var manifest: Map<String, Listed>? = null
    private var manifestSha: String? = null
    private var total = 0L
    private var entries = 0

    /** Start afresh: whatever a stopped import left in the staging folder goes. */
    fun begin() {
        staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) throw IOException("could not prepare the import folder")
        staged.clear(); manifest = null; manifestSha = null; total = 0; entries = 0
    }

    /** Remove the staging folder (after [finish], a refusal or a stop). */
    fun abort() { staging.deleteRecursively() }

    /**
     * Unpack one part ([input], streamed; closed here) into the staging folder, checking every entry. [part] / [parts]
     * count from 1 for the progress. Throws [Refused] (nothing merged) or [Cancelled] when [cancelled] turns true.
     */
    fun readPart(input: InputStream, part: Int, parts: Int, progress: (Progress) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val buf = ByteArray(1 shl 16)
        ZipInputStream(BufferedInputStream(input, 1 shl 16)).use { zip ->
            var any = false
            while (true) {
                if (cancelled()) throw Cancelled()
                val e: ZipEntry = try { zip.nextEntry } catch (x: java.util.zip.ZipException) { throw Refused("part $part is not a readable zip (${x.message?.take(60)})") } ?: break
                any = true
                if (++entries > limits.maxEntries) throw Refused("the pack has more than ${limits.maxEntries} entries")
                val name = e.name
                if (e.isDirectory) {
                    if (!dirOk(name)) throw Refused("a folder outside dhan/'s layout: ${shown(name)}")
                    continue
                }
                if (name == MANIFEST) { readManifest(zip, buf, part); continue }
                val rel = storePath(name)
                if (staged.containsKey(rel)) throw Refused("dhan/$rel is in the pack twice")
                val text = !rel.endsWith(".gz")
                val cap = if (text) limits.maxTextBytes else limits.maxEntryBytes
                if (e.size > cap) throw Refused("dhan/$rel is larger than the per-file limit")
                val dest = File(staging, rel)
                inside(staging, dest)
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                val md = MessageDigest.getInstance("SHA-256")
                var n = 0L
                var h0 = -1; var h1 = -1
                try {
                    java.io.FileOutputStream(tmp).use { fo ->
                        while (true) {
                            val r = try { zip.read(buf) } catch (x: java.util.zip.ZipException) { throw Refused("dhan/$rel is damaged in part $part") }
                            if (r < 0) break
                            n += r; total += r
                            if (n > cap) throw Refused("dhan/$rel is larger than the per-file limit")
                            if (total > limits.maxTotalBytes) throw Refused("the pack unpacks to more than ${limits.maxTotalBytes / (1L shl 30)} GB")
                            for (k in 0 until minOf(r, 2)) { val v = buf[k].toInt() and 0xff; if (h0 < 0) h0 = v else if (h1 < 0) h1 = v }
                            if (text) for (i in 0 until r) { val c = buf[i].toInt() and 0xff; if (c < 9 || (c in 14..31) || c == 11 || c == 12 || c >= 0x7f) throw Refused("dhan/$rel is not plain text") }
                            md.update(buf, 0, r)
                            fo.write(buf, 0, r)
                            if (cancelled()) throw Cancelled()
                        }
                        fo.fd.sync()
                    }
                    if (!text && !(h0 == 0x1f && h1 == 0x8b)) throw Refused("dhan/$rel is not gzip data")
                    val sha = hex(md.digest())
                    manifest?.let { mf -> checkListed(rel, n, sha, mf)?.let { throw Refused(it) } }
                    if (!tmp.renameTo(dest)) throw IOException("could not stage dhan/$rel")
                    staged[rel] = Staged(rel, n, sha)
                } finally { tmp.delete() }
                progress(Progress(part, parts, staged.size, total, "Part $part of $parts: ${rel.substringBefore('/')}"))
            }
            if (!any) throw Refused("part $part is empty or not a zip")
        }
    }

    private fun readManifest(zip: ZipInputStream, buf: ByteArray, part: Int) {
        val out = java.io.ByteArrayOutputStream()
        val md = MessageDigest.getInstance("SHA-256")
        while (true) {
            val r = zip.read(buf); if (r < 0) break
            if (out.size() + r > limits.maxManifestBytes) throw Refused("pack-manifest.json is too large")
            total += r
            if (total > limits.maxTotalBytes) throw Refused("the pack unpacks to more than ${limits.maxTotalBytes / (1L shl 30)} GB")
            out.write(buf, 0, r); md.update(buf, 0, r)
        }
        val sha = hex(md.digest())
        if (manifestSha != null) {
            if (manifestSha != sha) throw Refused("part $part has a different pack-manifest.json (parts of two packs?)")
            return
        }
        val mf = parseManifest(out.toString(Charsets.UTF_8.name()))
        // Files staged before the manifest arrived are checked against it now.
        for (s in staged.values) checkListed(s.rel, s.size, s.sha256, mf)?.let { throw Refused(it) }
        manifest = mf; manifestSha = sha
    }

    /** The destination's real path must lie inside [root] (a link planted in the folder cannot redirect a write). */
    private fun inside(root: File, f: File) {
        val r = root.canonicalPath + File.separator
        val p = f.canonicalPath
        if (!p.startsWith(r)) throw Refused("a path that leaves the store")
    }

    /** First line of a gzip CSV, at most [Limits.maxLine] characters; null when unreadable. */
    private fun header(f: File): String? = runCatching {
        GZIPInputStream(f.inputStream(), 1 shl 12).use { z ->
            val sb = StringBuilder()
            while (sb.length <= limits.maxLine) { val c = z.read(); if (c < 0 || c == '\n'.code) break; sb.append(c.toChar()) }
            sb.toString().trimEnd('\r')
        }
    }.getOrNull()

    /** The last minute and the rows of a gzip CSV, streamed and bounded; null when unreadable or over the bounds. */
    fun stat(f: File): Stat? = runCatching {
        GZIPInputStream(f.inputStream(), 1 shl 16).use { z ->
            val b = ByteArray(1 shl 16)
            val line = StringBuilder()
            var inflated = 0L; var rows = -1L; var lastT: Long? = null
            fun end() {
                if (line.isNotBlank()) { rows++; if (rows > 0) line.substring(0, line.indexOf(',').let { if (it < 0) line.length else it }).toLongOrNull()?.let { lastT = it } }
                line.setLength(0)
            }
            while (true) {
                val r = z.read(b); if (r < 0) break
                inflated += r
                if (inflated > limits.maxInflatedBytes) return@runCatching null
                for (i in 0 until r) {
                    val c = b[i].toInt().toChar()
                    if (c == '\n') end() else { if (line.length >= limits.maxLine) return@runCatching null; line.append(c) }
                }
            }
            end()
            Stat(lastT, maxOf(0, rows))
        }
    }.getOrNull()

    /**
     * Check the staged pack whole, then merge it into the store. Throws [Refused] (store untouched) or [Cancelled] (the
     * files merged so far stay, each whole). The staging folder is removed either way.
     */
    fun finish(progress: (Progress) -> Unit = {}, cancelled: () -> Boolean = { false }): Report {
        try {
            if (staged.isEmpty()) throw Refused(if (manifest != null) "the parts chosen hold no dhan/ files" else "the files chosen hold no Dhan data pack")
            val mf = manifest
            if (mf != null) for (s in staged.values) checkListed(s.rel, s.size, s.sha256, mf)?.let { throw Refused(it) }
            // Every candle/option file must open with its header (a damaged or foreign gzip is refused before any merge).
            for (s in staged.values) {
                if (cancelled()) throw Cancelled()
                val h = headerFor(s.rel) ?: continue
                if (header(File(staging, s.rel)) != h) throw Refused("dhan/${s.rel} is not a ${if (h == Files.OPTION_HEADER) "options" else "candles"} file")
            }
            files.root.mkdirs()
            var added = 0; var replaced = 0; var kept = 0; var merged = 0; var bytes = 0L; var i = 0
            for (s in staged.values) {
                if (cancelled()) throw Cancelled()
                val src = File(staging, s.rel)
                val dest = files.file(s.rel)
                inside(files.root, dest)
                val present = dest.isFile
                val gz = headerFor(s.rel) != null
                val action = decide(s.rel, present, if (present && gz) stat(dest) else null, if (present && gz) stat(src) else null)
                when (action) {
                    Action.ADD, Action.REPLACE -> {
                        dest.parentFile?.mkdirs()
                        if (!src.renameTo(dest)) { dest.delete(); if (!src.renameTo(dest)) throw IOException("could not store dhan/${s.rel}") }
                        if (action == Action.ADD) added++ else replaced++
                        bytes += s.size
                    }
                    Action.UNION -> {
                        files.writeText(s.rel, unionExpiries(dest.readText(), src.readText()), gzip = false)
                        merged++; bytes += s.size
                    }
                    Action.KEEP -> kept++
                }
                if (++i % 50 == 0 || i == staged.size) progress(Progress(0, 0, i, bytes, "Storing $i of ${staged.size} files"))
            }
            val missing = mf?.keys?.count { it !in staged } ?: 0
            return Report(added, replaced, kept, merged, missing, bytes, mf != null)
        } finally {
            abort()
        }
    }
}
