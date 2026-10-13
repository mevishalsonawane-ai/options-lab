package com.optionslab.ira

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sealed day file that goes to GitHub (TODO G1, 13 Oct): what the phone uploads each evening, encrypted on the phone
 * with a key stretched from Boss's backup passphrase, so Boss can open it on a PC too (research/data/decrypt_live.py) and
 * GitHub only ever holds noise.
 *
 * Format "IRLIVE01" (big-endian numbers):
 *
 *     header  "IRLIVE01" (8) · kdf (1: 1 = PBKDF2-HMAC-SHA256) · rounds (4) · salt (16) · nonce prefix (8) · chunk size (4)
 *     body    chunk 0, chunk 1, ... each AES-256-GCM: ciphertext + 16-byte tag
 *               nonce = nonce prefix (8) + chunk number (4)
 *               additional data = the whole header + chunk number (4) + last flag (1: 1 on the last chunk only)
 *             every chunk but the last holds exactly "chunk size" plain bytes; the last holds 0 up to "chunk size".
 *
 * The key is PBKDF2-HMAC-SHA256(passphrase as UTF-8, salt, rounds, 32 bytes). Chunks keep memory small on the phone (a day's
 * tick file is 60-100 MB) and the last flag means a cut-off file is refused rather than read short. Any change to any byte
 * is refused too. Pure: no Android, no file, no setting.
 */
object LiveSeal {
    val MAGIC = "IRLIVE01".toByteArray(Charsets.US_ASCII)
    const val KDF_PBKDF2_SHA256: Int = 1
    /** OWASP's figure for PBKDF2-SHA256, as the phone backup uses ([com.optionslab.app.data.Backup]). */
    const val ROUNDS = 600_000
    /** The plain bytes in each chunk (1 MiB). */
    const val CHUNK = 1 shl 20
    const val HEADER_SIZE = 8 + 1 + 4 + 16 + 8 + 4
    const val TAG = 16
    /** What a file may ask for (a crafted header cannot make a reader spin for ever, nor allocate gigabytes). */
    const val MAX_ROUNDS = 10_000_000
    const val MAX_CHUNK = 16 shl 20

    class Refused(why: String) : java.io.IOException(why)

    /** The key's recipe kept with it: what goes into each header. */
    class Key(val salt: ByteArray, val rounds: Int, internal val bytes: ByteArray) {
        init { require(salt.size == 16 && bytes.size == 32 && rounds in 1..MAX_ROUNDS) }
        fun wipe() = bytes.fill(0)
        /** For the settings vault only: "rounds:salt-hex:key-hex". */
        fun encode(): String = "$rounds:${hex(salt)}:${hex(bytes)}"

        companion object {
            fun decode(s: String?): Key? = runCatching {
                val (r, salt, k) = s!!.split(":")
                Key(unhex(salt), r.toInt(), unhex(k))
            }.getOrNull()
        }
    }

    /** PBKDF2-HMAC-SHA256 of [passphrase] (the JVM and Android both read its characters as UTF-8). Slow on purpose. */
    fun derive(passphrase: CharArray, salt: ByteArray, rounds: Int = ROUNDS): Key {
        val spec = PBEKeySpec(passphrase, salt, rounds, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Key(salt.copyOf(), rounds, raw)
        } finally { spec.clearPassword() }
    }

    /** A fresh random salt, then [derive]. */
    fun newKey(passphrase: CharArray, rounds: Int = ROUNDS, random: SecureRandom = SecureRandom()): Key =
        derive(passphrase, ByteArray(16).also(random::nextBytes), rounds)

    private fun header(key: Key, prefix: ByteArray, chunk: Int): ByteArray = ByteBuffer.allocate(HEADER_SIZE)
        .put(MAGIC).put(KDF_PBKDF2_SHA256.toByte()).putInt(key.rounds).put(key.salt).put(prefix).putInt(chunk).array()

    private fun nonce(prefix: ByteArray, i: Int): ByteArray = ByteBuffer.allocate(12).put(prefix).putInt(i).array()

    private fun aad(header: ByteArray, i: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(i).put(if (last) 1 else 0).array()

    /**
     * An output stream that seals everything written to it into [out] (closed with it). [prefix] and [chunk] are for the
     * tests only; the app always takes a random prefix and [CHUNK].
     */
    fun sealing(out: OutputStream, key: Key, prefix: ByteArray = ByteArray(8).also(SecureRandom()::nextBytes), chunk: Int = CHUNK): OutputStream {
        require(prefix.size == 8 && chunk in 1..MAX_CHUNK)
        return Sealer(out, SecretKeySpec(key.bytes, "AES"), header(key, prefix, chunk), prefix, chunk)
    }

    private class Sealer(private val out: OutputStream, private val k: SecretKeySpec, private val header: ByteArray,
                         private val prefix: ByteArray, chunk: Int) : OutputStream() {
        private val buf = ByteArray(chunk)
        private var n = 0
        private var i = 0
        private var closed = false

        init { out.write(header) }

        private fun emit(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG * 8, nonce(prefix, i)))
            c.updateAAD(aad(header, i, last))
            out.write(c.doFinal(buf, 0, n))
            if (i == Int.MAX_VALUE) throw Refused("too many chunks")
            i++; n = 0
        }

        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var o = off; var left = len
            while (left > 0) {
                // A full chunk goes out only once more bytes arrive: the last chunk is known for sure only at close.
                if (n == buf.size) emit(last = false)
                val take = minOf(left, buf.size - n)
                System.arraycopy(b, o, buf, n, take)
                n += take; o += take; left -= take
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try { emit(last = true); out.flush() } finally { buf.fill(0); out.close() }
        }
    }

    /** Everything in [plain], sealed (small inputs: the tests and the backup's own size). */
    fun seal(plain: ByteArray, key: Key, prefix: ByteArray = ByteArray(8).also(SecureRandom()::nextBytes), chunk: Int = CHUNK): ByteArray {
        val b = ByteArrayOutputStream()
        sealing(b, key, prefix, chunk).use { it.write(plain) }
        return b.toByteArray()
    }

    /** The header's recipe: rounds and salt, to derive the key with the passphrase. */
    class Head(val rounds: Int, val salt: ByteArray, val prefix: ByteArray, val chunk: Int, val raw: ByteArray)

    fun head(input: InputStream): Head {
        val raw = ByteArray(HEADER_SIZE)
        try { DataInputStream(input).readFully(raw) } catch (e: EOFException) { throw Refused("This is not an IraAlgo live file (too short).") }
        if (!raw.copyOfRange(0, 8).contentEquals(MAGIC)) throw Refused("This is not an IraAlgo live file.")
        val b = ByteBuffer.wrap(raw, 8, HEADER_SIZE - 8)
        if (b.get().toInt() != KDF_PBKDF2_SHA256) throw Refused("Unknown key recipe in this file.")
        val rounds = b.int
        val salt = ByteArray(16).also { b.get(it) }
        val prefix = ByteArray(8).also { b.get(it) }
        val chunk = b.int
        if (rounds !in 1..MAX_ROUNDS || chunk !in 1..MAX_CHUNK) throw Refused("This file's header is out of range.")
        return Head(rounds, salt, prefix, chunk, raw)
    }

    /** Opens [input] with [passphrase] into [out]; returns the plain bytes written. Refuses a wrong passphrase, any change, a cut. */
    fun open(input: InputStream, passphrase: CharArray, out: OutputStream): Long {
        val h = head(input)
        val key = derive(passphrase, h.salt, h.rounds)
        try { return openWith(input, h, key, out) } finally { key.wipe() }
    }

    /** As [open], with the key already derived (the tests; a PC opening many files of one key). */
    fun openWith(input: InputStream, h: Head, key: Key, out: OutputStream): Long {
        val k = SecretKeySpec(key.bytes, "AES")
        val block = h.chunk + TAG
        var cur = readUpTo(input, block)
        var i = 0
        var total = 0L
        while (true) {
            if (cur.size < TAG) throw Refused("The file is cut short.")
            val next = if (cur.size == block) readUpTo(input, block) else ByteArray(0)
            val last = next.isEmpty()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG * 8, nonce(h.prefix, i)))
            c.updateAAD(aad(h.raw, i, last))
            val plain = try { c.doFinal(cur) } catch (e: javax.crypto.AEADBadTagException) {
                throw Refused(if (i == 0) "Wrong passphrase, or the file was changed." else "The file was changed or cut short.")
            }
            out.write(plain); total += plain.size
            if (last) return total
            cur = next; i++
        }
    }

    fun open(sealed: ByteArray, passphrase: CharArray): ByteArray =
        ByteArrayOutputStream().also { open(sealed.inputStream(), passphrase, it) }.toByteArray()

    private fun readUpTo(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) { val r = input.read(b, got, n - got); if (r < 0) break; got += r }
        return if (got == n) b else b.copyOf(got)
    }

    internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    internal fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
