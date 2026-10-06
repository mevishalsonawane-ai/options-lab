package com.optionslab.ira.neuro

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The graph on disk, under one folder in the app's private storage (files/neuro): graph.bin.gz (the [NeuroGraph], what
 * Jarvis reads) and state.bin.gz (the builder's [NeuroState], to read only what is new next time). Both are versioned
 * gzip binary, written whole to a temporary file and renamed into place. The graph file is capped at [MAX_GRAPH_BYTES]:
 * the weakest learned edges are pruned until it fits. Market-derived figures only; nothing personal. Pure JVM.
 */
class NeuroStore(val root: File) {
    companion object {
        const val GRAPH = "graph.bin.gz"
        const val STATE = "state.bin.gz"
        const val MAX_GRAPH_BYTES = 400_000
    }

    private fun f(name: String) = File(root, name)

    private fun writeWhole(name: String, bytes: ByteArray) {
        root.mkdirs()
        val target = f(name)
        val tmp = File(root, "$name.part")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("could not store $name") } }
    }

    private fun gz(write: (DataOutputStream) -> Unit): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(bo))).use { write(it) }
        return bo.toByteArray()
    }

    private fun <T> readGz(name: String, read: (DataInputStream) -> T): T? {
        val file = f(name)
        if (!file.isFile) return null
        return runCatching { DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()))).use(read) }.getOrNull()
    }

    /** The graph, or null when none is built (or it is of another version: rebuilt on the next run). */
    fun graph(): NeuroGraph? = readGz(GRAPH) { NeuroGraph.read(it) }

    fun state(): NeuroState? = readGz(STATE) { NeuroState.read(it) }

    /** Write [g], pruned until the file fits [MAX_GRAPH_BYTES]; returns the graph as written. */
    fun writeGraph(g: NeuroGraph, maxBytes: Int = MAX_GRAPH_BYTES): NeuroGraph {
        var cur = g
        var bytes = gz { cur.write(it) }
        while (bytes.size > maxBytes && cur.edges.any { it.learned }) {
            val next = cur.pruned((cur.edges.size * 3) / 4)
            if (next.edges.size == cur.edges.size) break
            cur = next
            bytes = gz { cur.write(it) }
        }
        writeWhole(GRAPH, bytes)
        return cur
    }

    fun writeState(s: NeuroState) = writeWhole(STATE, gz { s.write(it) })

    /** When the graph was last written, or null. */
    fun builtAt(): Long? = f(GRAPH).takeIf { it.isFile }?.lastModified()

    fun bytes(): Long = root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Forget everything learned (the next build starts from the store again). */
    fun clear(): Boolean = !root.exists() || root.deleteRecursively()

    /**
     * One build: [NeuroBuilder.update] from the kept state (or from scratch when [full], or when [NeuroBuilder.needsFull]
     * says so and [allowFull]), the state saved at each checkpoint, then the graph learned and written. Returns it, or the
     * old graph when the run was stopped before the end.
     */
    fun build(files: com.optionslab.ira.dhan.Files, today: LocalDate, now: Long, full: Boolean = false, allowFull: Boolean = true,
              progress: (String, Float) -> Unit = { _, _ -> }, active: () -> Boolean = { true }): NeuroGraph? {
        val old = state()
        val fullNow = full || old == null || (allowFull && NeuroBuilder.needsFull(files, old) != null)
        val s = NeuroBuilder.update(files, if (fullNow) null else old, today, fullNow, progress, active) { runCatching { writeState(it) } }
        writeState(s)
        if (!active()) return graph()
        progress("Learning the relations", 0.97f)
        return writeGraph(NeuroLearn.graph(s, now))
    }
}
