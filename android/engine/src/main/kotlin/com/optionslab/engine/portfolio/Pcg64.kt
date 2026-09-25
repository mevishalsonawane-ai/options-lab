package com.optionslab.engine.portfolio

/**
 * numpy's `default_rng(seed)`: a SeedSequence feeding PCG64 (XSL-RR 128/64),
 * with `Generator.integers` drawn by Lemire's bounded method exactly as numpy
 * draws them. The Monte Carlo panel uses a fixed seed so a portfolio always
 * shows the same risk figures; reproducing the generator bit for bit is what
 * lets the port show the same figures as IraAlgo.
 */
internal class Pcg64(seed: Long) {
    private var hi = 0L
    private var lo = 0L
    private var incHi = 0L
    private var incLo = 0L
    private var hasUint32 = false
    private var uinteger = 0

    init {
        val words = seedSequenceState(seed, 8)
        val v = LongArray(4) { (words[2 * it + 1].toLong() shl 32) or (words[2 * it].toLong() and MASK32) }
        // pcg64_set_seed: state and increment as (high, low) pairs.
        val seqHi = v[2]; val seqLo = v[3]
        incHi = (seqHi shl 1) or (seqLo ushr 63)
        incLo = (seqLo shl 1) or 1L
        hi = 0; lo = 0
        step()
        val (h, l) = add128(hi, lo, v[0], v[1])
        hi = h; lo = l
        step()
    }

    private fun step() {
        // state = state * MULT + inc (mod 2^128)
        val newLo = lo * MUL_LO
        val newHi = unsignedMultiplyHigh(lo, MUL_LO) + lo * MUL_HI + hi * MUL_LO
        val (h, l) = add128(newHi, newLo, incHi, incLo)
        hi = h; lo = l
    }

    fun nextLong(): Long {
        step()
        val x = hi xor lo
        val rot = (hi ushr 58).toInt()
        return java.lang.Long.rotateRight(x, rot)
    }

    fun nextInt(): Int {
        if (hasUint32) { hasUint32 = false; return uinteger }
        val next = nextLong()
        hasUint32 = true
        uinteger = (next ushr 32).toInt()
        return next.toInt()
    }

    /** `Generator.integers(low, high, size)` for the default int64 dtype, high exclusive. */
    fun integers(low: Long, high: Long, size: Int): LongArray {
        val rng = high - 1 - low
        val out = LongArray(size)
        if (rng == 0L) { out.fill(low); return out }
        if (java.lang.Long.compareUnsigned(rng, MASK32) <= 0) {
            for (i in 0 until size) {
                out[i] = low + if (rng == MASK32) (nextInt().toLong() and MASK32) else boundedLemire32(rng)
            }
        } else {
            for (i in 0 until size) out[i] = low + boundedLemire64(rng)
        }
        return out
    }

    private fun boundedLemire32(rng: Long): Long {
        val rngExcl = rng + 1 // fits in 32 bits unsigned
        var m = (nextInt().toLong() and MASK32) * rngExcl
        var leftover = m and MASK32
        if (leftover < rngExcl) {
            val threshold = ((MASK32 - rng) and MASK32) % rngExcl
            while (leftover < threshold) {
                m = (nextInt().toLong() and MASK32) * rngExcl
                leftover = m and MASK32
            }
        }
        return m ushr 32
    }

    private fun boundedLemire64(rng: Long): Long {
        val rngExcl = rng + 1
        var x = nextLong()
        var mLo = x * rngExcl
        var mHi = unsignedMultiplyHigh(x, rngExcl)
        if (java.lang.Long.compareUnsigned(mLo, rngExcl) < 0) {
            val threshold = java.lang.Long.remainderUnsigned(-1L - rng, rngExcl)
            while (java.lang.Long.compareUnsigned(mLo, threshold) < 0) {
                x = nextLong()
                mLo = x * rngExcl
                mHi = unsignedMultiplyHigh(x, rngExcl)
            }
        }
        return mHi
    }

    companion object {
        private const val MASK32 = 0xFFFFFFFFL
        private const val MUL_HI = 2549297995355413924L
        private const val MUL_LO = 4865540595714422341L

        /** High 64 bits of the unsigned 128-bit product (Math.multiplyHigh needs Android API 31). */
        private fun unsignedMultiplyHigh(a: Long, b: Long): Long {
            val aLo = a and MASK32; val aHi = a ushr 32
            val bLo = b and MASK32; val bHi = b ushr 32
            val loLo = aLo * bLo
            val hiLo = aHi * bLo + (loLo ushr 32)
            val loHi = aLo * bHi + (hiLo and MASK32)
            return aHi * bHi + (hiLo ushr 32) + (loHi ushr 32)
        }

        private fun add128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Pair<Long, Long> {
            val l = aLo + bLo
            val carry = if (java.lang.Long.compareUnsigned(l, aLo) < 0) 1L else 0L
            return Pair(aHi + bHi + carry, l)
        }

        // numpy.random.SeedSequence, pool of four 32-bit words.
        private const val INIT_A = 0x43b0d7e5
        private const val MULT_A = 0x931e8875.toInt()
        private const val INIT_B = 0x8b51f9dd.toInt()
        private const val MULT_B = 0x58f38ded
        private const val MIX_MULT_L = 0xca01f9dd.toInt()
        private const val MIX_MULT_R = 0x4973f715

        /** The uint32 words `SeedSequence(seed).generate_state(n, uint32)` yields. */
        fun seedSequenceState(seed: Long, nWords: Int): IntArray {
            require(seed >= 0) { "seed must be non-negative" }
            val entropy = ArrayList<Int>()
            var s = seed
            do { entropy.add((s and MASK32).toInt()); s = s ushr 32 } while (s != 0L)
            val pool = IntArray(4)
            var hashConst = INIT_A
            fun hashmix(value: Int): Int {
                var v = value xor hashConst
                hashConst *= MULT_A
                v *= hashConst
                v = v xor (v ushr 16)
                return v
            }
            fun mix(x: Int, y: Int): Int {
                var r = MIX_MULT_L * x - MIX_MULT_R * y
                r = r xor (r ushr 16)
                return r
            }
            for (i in pool.indices) pool[i] = hashmix(if (i < entropy.size) entropy[i] else 0)
            for (src in pool.indices) for (dst in pool.indices) {
                if (src != dst) pool[dst] = mix(pool[dst], hashmix(pool[src]))
            }
            for (src in pool.size until entropy.size) for (dst in pool.indices) {
                pool[dst] = mix(pool[dst], hashmix(entropy[src]))
            }
            val out = IntArray(nWords)
            var hb = INIT_B
            for (i in 0 until nWords) {
                var d = pool[i % pool.size]
                d = d xor hb
                hb *= MULT_B
                d *= hb
                d = d xor (d ushr 16)
                out[i] = d
            }
            return out
        }
    }
}
