package com.myanmartts.studio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Kotlin port of edge-tts (HarryXiaoCN fork, sec-ms-gec-v2 branch) `drm.py`.
 *
 * Generates the Sec-MS-GEC token required by Microsoft Edge's online
 * text-to-speech service, plus the random MUID cookie.
 *
 * Arithmetic notes (mirrors the Python exactly):
 * - Every 32-bit value uses [UInt]; Kotlin's wrapping arithmetic is exactly
 *   the Python code's `& 0xFFFFFFFF` masking (low 32 bits of a sum/xor/shift
 *   only ever depend on the low 32 bits of the inputs).
 * - The `e * 0x100000001` rotation trick needs 64 bits, done with [ULong].
 * - The few accumulators Python does not mask on every step (swap_acc, acc)
 *   use [ULong]; the mask is applied exactly where the Python applies it.
 *
 * Correctness is pinned by [DrmTest], whose vectors were generated with the
 * Python fork itself.
 */
object Drm {

    const val WIN_EPOCH = 11644473600L
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private val SHA256_INITIAL = uintArrayOf(
        0x6A09E667u, 0xBB67AE85u, 0x3C6EF372u, 0xA54FF53Au,
        0x510E527Fu, 0x9B05688Cu, 0x1F83D9ABu, 0x5BE0CD19u,
    )
    private val SHA256_STATE = uintArrayOf(
        0xFD9FBEA2u, 0x91243CE7u, 0xBE499094u, 0xCD2E1E0Au,
        0x6C006F49u, 0x04BD35ABu, 0xFEB0A7E4u, 0x03EFFBD2u,
    )
    private val SHA256_SCHEDULE = uintArrayOf(
        0xEB24BF9Eu, 0x1558691Fu, 0xA0C09EE2u, 0x0CFA32BAu,
        0xA622EE8Cu, 0xD1DD9491u, 0xC0DD25A0u, 0xF9E70AB6u,
        0xF0C31573u, 0x7058CE4Eu, 0x7188E606u, 0x00E5AB33u,
        0xD258B060u, 0x72766564u,
    )
    private val SHA256_ROUND_CONSTANTS = uintArrayOf(
        0x428A2F98u, 0x71374491u, 0xB5C0FBCFu, 0xE9B5DBA5u,
        0x3956C25Bu, 0x59F111F1u, 0x923F82A4u, 0xAB1C5ED5u,
        0xD807AA98u, 0x12835B01u, 0x243185BEu, 0x550C7DC3u,
        0x72BE5D74u, 0x80DEB1FEu, 0x9BDC06A7u, 0xC19BF174u,
        0xE49B69C1u, 0xEFBE4786u, 0x0FC19DC6u, 0x240CA1CCu,
        0x2DE92C6Fu, 0x4A7484AAu, 0x5CB0A9DCu, 0x76F988DAu,
        0x983E5152u, 0xA831C66Du, 0xB00327C8u, 0xBF597FC7u,
        0xC6E00BF3u, 0xD5A79147u, 0x06CA6351u, 0x14292967u,
        0x27B70A85u, 0x2E1B2138u, 0x4D2C6DFCu, 0x53380D13u,
        0x650A7354u, 0x766A0ABBu, 0x81C2C92Eu, 0x92722C85u,
        0xA2BFE8A1u, 0xA81A664Bu, 0xC24B8B70u, 0xC76C51A3u,
        0xD192E819u, 0xD6990624u, 0xF40E3585u, 0x106AA070u,
        0x19A4C116u, 0x1E376C08u, 0x2748774Cu, 0x34B0BCB5u,
        0x391C0CB3u, 0x4ED8AA4Au, 0x5B9CCA4Fu, 0x682E6FF3u,
        0x748F82EEu, 0x78A5636Fu, 0x84C87814u, 0x8CC70208u,
        0x90BEFFFAu, 0xA4506CEBu, 0xBEF9A3F7u, 0xC67178F2u,
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7u
    private val SHA256_BLOCK_CONSTANTS: UIntArray by lazy {
        val list = SHA256_ROUND_CONSTANTS.slice(0 until 14).toMutableList()
        list.add(SHA256_ADJUSTMENT)
        list.addAll(SHA256_ROUND_CONSTANTS.slice(15 until 64))
        list.toUIntArray()
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80u
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0u

    private val CS64_COEFFICIENTS = listOf(
        0xE82E91B1u to 0xB32700D0u,
        0x44DE24C6u to 0x88DFF51Du,
        0x3BEB65BCu to 0x29514038u,
        0xC89C5933u to 0xB328CBDFu,
        0x8D1EC211u to 0xD405B4D5u,
        0x314EF5D5u to 0x26E55F3Cu,
        0xE92491B1u to 0x607801C6u,
        0xF0A5B9D5u to 0x82B2C1FBu,
        0x06064CF3u to 0xFA51A3DAu,
        0xDA79FA60u to 0x207CC464u,
        0xBED20560u to 0x3C3C74F3u,
        0x89F9C9F8u to 0xD2D14B61u,
        0x35FA38C6u to 0xBB04A2C1u,
        0xCB49E3C6u to 0xC5626167u,
    )
    private const val CS64_FACTOR = 0x82893933u
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1Au
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000u

    private const val MASK64 = 0xFFFFFFFFuL

    // -------------------------------------------------------------

    private fun rol16(value: UInt): UInt = (value shl 16) or (value shr 16)

    private fun wordsLe4Even(data: ByteArray): List<UInt> {
        val pad = (4 - data.size % 4) % 4
        val buf = ByteBuffer.allocate(data.size + pad).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(data)
        repeat(pad) { buf.put(0) }
        buf.flip()
        val words = mutableListOf<UInt>()
        while (buf.remaining() >= 4) words.add(buf.int.toUInt())
        if (words.size % 2 == 1) words.add(0u)
        return words
    }

    private fun sigma0(value: UInt): UInt =
        (value shr 7) xor (value shl 25) xor (value shr 18) xor (value shl 14) xor (value shr 3)

    private fun sigma1(value: UInt): UInt =
        (value shr 17) xor (value shl 15) xor (value shr 19) xor (value shl 13) xor (value shr 10)

    private fun sha256Rounds(state: UIntArray, words: List<UInt>, firstRound: Int = 0): UIntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        for (i in firstRound until 64) {
            // Multiplying a 32-bit word by 2^32 + 1 repeats it in 64 bits, so a
            // right shift then carries ROTR's low 32 bits with no left shift.
            val rotE = e.toULong() * 0x100000001uL
            val rotA = a.toULong() * 0x100000001uL
            val s1e = ((rotE shr 6) xor (rotE shr 11) xor (rotE shr 25)).toUInt()
            val s0a = ((rotA shr 2) xor (rotA shr 13) xor (rotA shr 22)).toUInt()
            val t1 = h + s1e + (g xor (e and (f xor g))) + k[i] + words[i]
            val t2 = s0a + ((a and b) or (c and (a or b)))
            val oa = a; val ob = b; val oc = c; val od = d
            val oe = e; val of = f; val og = g
            a = t1 + t2; b = oa; c = ob; d = oc
            e = od + t1; f = oe; g = of; h = og
        }
        return uintArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSha256Schedule(words: MutableList<UInt>, firstWord: Int = 16) {
        for (i in firstWord until 64) {
            val x = words[i - 2]; val y = words[i - 15]
            val s1 = (x shr 17) xor (x shl 15) xor (x shr 19) xor (x shl 13) xor (x shr 10)
            val s0 = (y shr 7) xor (y shl 25) xor (y shr 18) xor (y shl 14) xor (y shr 3)
            words.add(s1 + words[i - 7] + s0 + words[i - 16])
        }
    }

    private fun be24(d: ByteArray, o: Int): UInt =
        ((d[o].toUInt() and 0xFFu) shl 16) or
            ((d[o + 1].toUInt() and 0xFFu) shl 8) or
            (d[o + 2].toUInt() and 0xFFu)

    private fun be32(d: ByteArray, o: Int): UInt =
        ((d[o].toUInt() and 0xFFu) shl 24) or
            ((d[o + 1].toUInt() and 0xFFu) shl 16) or
            ((d[o + 2].toUInt() and 0xFFu) shl 8) or
            (d[o + 3].toUInt() and 0xFFu)

    private fun be64(v: ULong): ByteArray {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(v.toLong())
        return bb.array()
    }

    private fun sha256Init(data: ByteArray): UIntArray {
        val words = MutableList(14) { 0u }
        words.add(be24(data, 0))
        words.add(be32(data, 3))
        for (i in 16..29) {
            var v = SHA256_SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) (sigma1(words[i - 2]) xor SHA256_SIGMA1_ADJUSTMENT) else sigma1(words[i - 2])
            if (i - 7 >= 14) v += words[i - 7]
            if (i - 15 >= 14) v += if (i == 29) (sigma0(words[i - 15]) xor SHA256_SIGMA0_ADJUSTMENT) else sigma0(words[i - 15])
            words.add(v)
        }
        words.add(
            sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
        )
        expandSha256Schedule(words, 31)
        val working = sha256Rounds(SHA256_STATE.copyOf(), words, 14)
        return UIntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val padLen = Math.floorMod(55 - length, 64)
        val padded = data.sliceArray(7 until data.size) +
            byteArrayOf(0x80.toByte()) +
            ByteArray(padLen) +
            be64((length * 8).toULong())
        var state = sha256Init(data.sliceArray(0 until 7))
        var off = 0
        while (off < padded.size) {
            val words = MutableList(16) { j -> be32(padded, off + j * 4) }
            expandSha256Schedule(words)
            val working = sha256Rounds(state, words)
            state = UIntArray(8) { state[it] + working[it] }
            off += 64
        }
        val out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        state.forEach { out.putInt(it.toInt()) }
        return out.array()
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<UInt, UInt> {
        val bb = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = bb.int.toUInt() or 1u
        val keyB = bb.int.toUInt() or 1u
        val f0 = keyA * CS64_FACTOR
        val f1 = keyB * CS64_FACTOR
        val terms = ArrayList<UInt>(32)
        val offsets = ArrayList<UInt>(32)
        CS64_COEFFICIENTS.forEachIndexed { i, (coeff, off) ->
            terms.add(coeff * if (i % 2 == 0) f0 else f1)
            offsets.add(off)
        }
        wordsLe4Even(byteArrayOf(0) + data).forEachIndexed { i, word ->
            val key = if (i % 2 == 0) keyA else keyB
            var term = word * key
            var off = rol16(word) * (if (i % 2 == 0) 0xEF0569FBu else 0xC31713DBu)
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * f0
                off += CS64_ADJUSTMENT1
            }
            terms.add(term)
            offsets.add(off)
        }
        var swapAcc = 0uL
        var swapLast = 0u
        var state = 0u
        var acc = 0uL
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16(offsets[i] + x)
            var q = t * 0x76F50F34u
            swapAcc += (rol16(q) * 0xC3EFEA97u + q).toULong()
            t = rol16(offsets[i + 1] + y)
            q = t * 0x1881CE3Cu
            swapLast = rol16(q) * 0x35BD1EC9u + q
            swapAcc = (swapAcc + swapLast.toULong()) and MASK64

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111u
            state = rol16(state) * 0x87085B9Fu
            state = rol16(state) * 0x12CEB96Du
            state = rol16(state) * 0x257E1D83u
            acc += state.toULong()
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5u
            t = rol16(t) * 0xD38396FFu
            state = rol16(rol16(t) * 0x7C932B89u) * 0xBFA49F69u
            acc = (acc + state.toULong()) and MASK64
            i += 2
        }
        return Pair(
            (swapLast.toULong() xor state.toULong()).toUInt(),
            (swapAcc xor acc).toUInt(),
        )
    }

    private fun UInt.toLeBytes(): ByteArray {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(this.toInt())
        return bb.array()
    }

    private fun tokenHash(data: ByteArray): Pair<String, String> {
        val digest = sha256Digest(data)
        val (a, b) = cs64Digest(data, digest)
        val cs64 = (a.toLeBytes() + b.toLeBytes()).joinToString("") { "%02X".format(it) }
        val sha = digest.joinToString("") { "%02X".format(it) }
        return Pair(cs64, sha)
    }

    /**
     * Generates a v2 Sec-MS-GEC token, e.g.
     * `2CD23A2CA0B2595A-F3C846C3...`.
     *
     * Pass a fixed [unixTimestamp] only for tests; otherwise the current time
     * is used (rounded down to 5-minute Windows ticks, like the Python fork).
     */
    fun generateSecMsGec(unixTimestamp: Double = System.currentTimeMillis() / 1000.0): String {
        var ticks = unixTimestamp + WIN_EPOCH
        ticks -= ticks % 300
        ticks *= 1e7
        val strToHash = "${ticks.toLong()}$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val (cs64, sha256) = tokenHash(strToHash)
        return "$cs64-$sha256"
    }

    /** Random 32-char uppercase hex MUID for the Cookie header. */
    fun generateMuid(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
