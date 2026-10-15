package com.optionslab.ira

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveSealTest {
    // Few rounds keep the tests quick; the phone uses LiveSeal.ROUNDS. The rounds travel in the header either way.
    private val pass = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }
    private val key = LiveSeal.derive(pass, salt, 1_000)
    private val prefix = ByteArray(8) { (0xA0 + it).toByte() }

    private fun open(sealed: ByteArray, p: CharArray = pass) = LiveSeal.open(sealed, p)

    @Test fun roundTripAcrossChunkEdges() {
        // Empty, under one chunk, exactly one, one and a bit, exactly three: the last chunk's flag is right in every case.
        for (n in listOf(0, 1, 63, 64, 65, 192, 1000)) {
            val plain = Random(n).nextBytes(n)
            val sealed = LiveSeal.seal(plain, key, prefix, chunk = 64)
            assertContentEquals(plain, open(sealed), "length $n")
            val chunks = maxOf(1, (n + 63) / 64)
            assertEquals(LiveSeal.HEADER_SIZE + n + chunks * LiveSeal.TAG, sealed.size, "length $n")
        }
    }

    @Test fun theDefaultChunkStreamsABigFile() {
        val plain = Random(7).nextBytes(LiveSeal.CHUNK * 2 + 123)
        val out = ByteArrayOutputStream()
        LiveSeal.sealing(out, key).use { s -> plain.asList().chunked(70_000).forEach { s.write(it.toByteArray()) } }
        assertContentEquals(plain, open(out.toByteArray()))
    }

    @Test fun aWrongPassphraseIsRefused() {
        val sealed = LiveSeal.seal("hello".toByteArray(), key, prefix)
        val e = assertFailsWith<LiveSeal.Refused> { open(sealed, "wrong passphrase!".toCharArray()) }
        assertTrue(e.message!!.contains("Wrong passphrase"))
    }

    @Test fun anyChangedByteIsRefused() {
        val sealed = LiveSeal.seal(Random(1).nextBytes(150), key, prefix, chunk = 64)
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<java.io.IOException>("byte $i") { open(bad) }
        }
    }

    @Test fun aCutFileIsRefused() {
        val sealed = LiveSeal.seal(Random(2).nextBytes(200), key, prefix, chunk = 64)
        // Cut at a chunk boundary: the chunk before it was not sealed as the last, so it is refused, never read short.
        val cut = sealed.copyOf(LiveSeal.HEADER_SIZE + 2 * (64 + LiveSeal.TAG))
        assertFailsWith<LiveSeal.Refused> { open(cut) }
        assertFailsWith<LiveSeal.Refused> { open(sealed.copyOf(sealed.size - 1)) }
        assertFailsWith<LiveSeal.Refused> { open(sealed.copyOf(10)) }
    }

    @Test fun chunksCannotBeSwapped() {
        val plain = Random(3).nextBytes(128)
        val sealed = LiveSeal.seal(plain, key, prefix, chunk = 64)
        val a = LiveSeal.HEADER_SIZE; val len = 64 + LiveSeal.TAG
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, a + len, swapped, a, len)
        System.arraycopy(sealed, a, swapped, a + len, len)
        assertFailsWith<LiveSeal.Refused> { open(swapped) }
    }

    @Test fun theHeaderCarriesTheRecipe() {
        val sealed = LiveSeal.seal(ByteArray(3), key, prefix)
        val h = LiveSeal.head(sealed.inputStream())
        assertEquals(1_000, h.rounds)
        assertContentEquals(salt, h.salt)
        assertContentEquals(prefix, h.prefix)
        assertEquals(LiveSeal.CHUNK, h.chunk)
        assertFailsWith<LiveSeal.Refused> { LiveSeal.head("IRABK3....................................................".toByteArray().inputStream()) }
    }

    @Test fun theKeyIsKeptAndReadBack() {
        val k = assertNotNull(LiveSeal.Key.decode(key.encode()))
        assertEquals(key.rounds, k.rounds)
        assertContentEquals(LiveSeal.seal("x".toByteArray(), key, prefix), LiveSeal.seal("x".toByteArray(), k, prefix))
        assertEquals(null, LiveSeal.Key.decode("rubbish"))
        assertEquals(null, LiveSeal.Key.decode(null))
    }

    /**
     * The fixed vector research/data/tests/test_decrypt_live.py opens with Python (the PC side reads exactly what the
     * phone writes): passphrase "Boss's pässphrase 1" (UTF-8, with a non-ASCII letter), salt 00..0f, 1,000 rounds,
     * prefix a0..a7, chunk 16, plain "IraAlgo live day, sealed on the phone." (38 bytes: three chunks).
     */
    @Test fun theVectorThePcTestOpens() {
        val k = LiveSeal.derive("Boss's pässphrase 1".toCharArray(), salt, 1_000)
        val sealed = LiveSeal.seal("IraAlgo live day, sealed on the phone.".toByteArray(), k, prefix, chunk = 16)
        assertEquals(VECTOR, LiveSeal.hex(sealed))
    }

    companion object {
        const val VECTOR =
            "49524c495645303101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6a700000010560d6c4d7a69d6119e87b0312b24d9305a6df450ff941dd1bff61533a1a6fbb45e0e3fbe229764333ceee9549cbe5bed754c96f76b807648c934b316e2e7e8a39d11a5fd0c56dffac48318df95c4acc0f552131bf879"
    }
}
