package com.jupiterlabs.ghostdrive.entropy

import kotlin.math.*

/**
 * NIST SP 800-90B batch estimators + continuous health tests ported from EntropicDroid.
 *
 * Assessed min-entropy = min(MCV, Markov, t-Tuple), the conservative
 * non-IID track of SP 800-90B section 6.3. Continuous tests (RCT, APT)
 * follow section 4.4 with alpha = 2^-20.
 */
object Sp80090b {

    const val MIN_WINDOW = 512
    const val MAX_ASSESS_BYTES = 32768
    private const val LOG2E = 1.4426950408889634
    private const val Z99 = 2.5758293035489004 // 99% two-sided z, per 90B

    data class Scores(
        val shannon: Double,        // bits / byte
        val mcvMinH: Double,        // bits / byte
        val markovMinH: Double,     // bits / byte (binary Markov x 8)
        val tTupleMinH: Double,     // bits / byte
        val assessedMinH: Double,   // min of the three, floored at 0
        val governingEstimator: String, // which estimator produced assessedMinH
        val chiSquare: Double,      // 255 df statistic
        val chiSquareOk: Boolean    // < 310.457 (alpha = 0.01)
    )

    fun assess(data: ByteArray): Scores {
        if (data.isEmpty()) {
            return Scores(0.0, 0.0, 0.0, 0.0, 0.0, "Empty", 0.0, false)
        }
        val sampled = if (data.size <= MAX_ASSESS_BYTES) data else data.copyOfRange(0, MAX_ASSESS_BYTES)
        val shannon = shannonBitsPerByte(sampled)
        val mcv = mcvMinH(sampled)
        val markov = markovMinH(sampled)
        val ttuple = tTupleMinH(sampled)
        val assessed = max(0.0, min(mcv, min(markov, ttuple)))
        val governing = when (assessed) {
            mcv -> "MCV"
            markov -> "Markov"
            else -> "t-Tuple"
        }
        val chi2 = chiSquare(sampled)
        return Scores(
            shannon = shannon,
            mcvMinH = mcv,
            markovMinH = markov,
            tTupleMinH = ttuple,
            assessedMinH = assessed,
            governingEstimator = governing,
            chiSquare = chi2,
            chiSquareOk = chi2 < 310.457
        )
    }

    fun shannonBitsPerByte(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        val counts = IntArray(256)
        for (b in data) counts[b.toInt() and 0xFF]++
        val n = data.size.toDouble()
        var h = 0.0
        for (c in counts) {
            if (c > 0) {
                val p = c / n
                h -= p * ln(p) * LOG2E
            }
        }
        return h
    }

    /** SP 800-90B 6.3.1 Most Common Value estimate, byte alphabet. */
    fun mcvMinH(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        val counts = IntArray(256)
        var maxCount = 0
        for (b in data) {
            val i = b.toInt() and 0xFF
            counts[i]++
            if (counts[i] > maxCount) maxCount = counts[i]
        }
        val n = data.size.toDouble()
        val pHat = maxCount / n
        val pU = min(1.0, pHat + Z99 * sqrt(pHat * (1.0 - pHat) / n))
        return -ln(pU) * LOG2E
    }

    /** SP 800-90B 6.3.3 Markov estimate on the bit expansion (MSB first). */
    fun markovMinH(data: ByteArray): Double {
        if (data.size < 2) return 0.0
        val nBits = data.size * 8
        var c1 = 0L
        var c00 = 0L; var c01 = 0L; var c10 = 0L; var c11 = 0L
        var prev = -1
        for (byte in data) {
            val v = byte.toInt() and 0xFF
            for (shift in 7 downTo 0) {
                val bit = (v ushr shift) and 1
                if (bit == 1) c1++
                if (prev >= 0) {
                    when {
                        prev == 0 && bit == 0 -> c00++
                        prev == 0 && bit == 1 -> c01++
                        prev == 1 && bit == 0 -> c10++
                        else -> c11++
                    }
                }
                prev = bit
            }
        }
        val n = nBits.toDouble()
        val c0 = n - c1
        val eps = 1e-12
        val p0 = max(c0 / n, eps)
        val p1 = max(c1 / n, eps)
        val from0 = (c00 + c01).toDouble()
        val from1 = (c10 + c11).toDouble()
        val p00 = if (from0 > 0) max(c00 / from0, eps) else 0.5
        val p01 = if (from0 > 0) max(c01 / from0, eps) else 0.5
        val p10 = if (from1 > 0) max(c10 / from1, eps) else 0.5
        val p11 = if (from1 > 0) max(c11 / from1, eps) else 0.5

        val l00 = ln(p00) * LOG2E; val l01 = ln(p01) * LOG2E
        val l10 = ln(p10) * LOG2E; val l11 = ln(p11) * LOG2E
        var v0 = ln(p0) * LOG2E
        var v1 = ln(p1) * LOG2E
        repeat(127) {
            val n0 = max(v0 + l00, v1 + l10)
            val n1 = max(v0 + l01, v1 + l11)
            v0 = n0; v1 = n1
        }
        val logPMax = max(v0, v1)
        val hPerBit = min(1.0, max(0.0, -logPMax / 128.0))
        return 8.0 * hPerBit
    }

    /** SP 800-90B 6.3.5 t-Tuple estimate, byte alphabet. */
    fun tTupleMinH(data: ByteArray): Double {
        if (data.size < 35) return 0.0
        val l = data.size
        var pMax = 0.0
        var t = 1
        while (t <= 8) {
            val counts = HashMap<Long, Int>(l)
            var key = 0L
            val mask = if (t == 8) -1L else (1L shl (t * 8)) - 1L
            var maxCount = 0
            for (i in 0 until l) {
                key = ((key shl 8) or (data[i].toLong() and 0xFF)) and mask
                if (i >= t - 1) {
                    val c = (counts[key] ?: 0) + 1
                    counts[key] = c
                    if (c > maxCount) maxCount = c
                }
            }
            if (maxCount < 35) break
            val pT = maxCount.toDouble() / (l - t + 1).toDouble()
            val p = pT.pow(1.0 / t)
            if (p > pMax) pMax = p
            t++
        }
        if (pMax <= 0.0) return mcvMinH(data)
        val pU = min(1.0, pMax + Z99 * sqrt(pMax * (1.0 - pMax) / (l - 1)))
        return -ln(pU) * LOG2E
    }

    fun chiSquare(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        val counts = IntArray(256)
        for (b in data) counts[b.toInt() and 0xFF]++
        val expected = data.size / 256.0
        if (expected <= 0.0) return 0.0
        var chi = 0.0
        for (c in counts) {
            val d = c - expected
            chi += (d * d) / expected
        }
        return chi
    }

    // ── Continuous health tests (SP 800-90B 4.4) ────────────────────

    /** Repetition Count Test with alpha = 2^-20. */
    class Rct(hBitsPerSample: Double) {
        val cutoff: Int = 1 + ceil(20.0 / max(0.1, min(8.0, hBitsPerSample))).toInt()
        private var last: Int = -1
        private var count: Int = 0
        var failures: Long = 0; private set

        fun feed(sample: Int): Boolean {
            if (sample == last) {
                count++
                if (count >= cutoff) {
                    failures++
                    count = 1
                    return false
                }
            } else {
                last = sample
                count = 1
            }
            return true
        }
    }

    /** Adaptive Proportion Test, window 512, exact binomial cutoff. */
    class Apt(hBitsPerSample: Double) {
        companion object { const val WINDOW = 512 }
        val cutoff: Int = aptCutoff(WINDOW, 2.0.pow(-max(0.3, min(8.0, hBitsPerSample))))
        private var reference: Int = -1
        private var index: Int = 0
        private var matches: Int = 0
        var failures: Long = 0; private set

        fun feed(sample: Int): Boolean {
            if (index == 0) {
                reference = sample
                matches = 1
                index = 1
                return true
            }
            if (sample == reference) matches++
            index++
            var ok = true
            if (matches >= cutoff) {
                failures++
                ok = false
                index = 0
            } else if (index >= WINDOW) {
                index = 0
            }
            return ok
        }
    }

    fun aptCutoff(w: Int, p: Double): Int {
        val alpha = 2.0.pow(-20.0)
        val lnP = ln(max(p, 1e-300))
        val lnQ = ln(max(1.0 - p, 1e-300))
        val pmf = DoubleArray(w + 1)
        for (k in 0..w) {
            val lg = logChoose(w, k) + k * lnP + (w - k) * lnQ
            pmf[k] = if (lg < -745.0) 0.0 else exp(lg)
        }
        var tail = 0.0
        var cutoff = w + 1
        for (k in w downTo 0) {
            tail += pmf[k]
            if (tail > alpha) {
                cutoff = k + 1
                break
            }
        }
        return min(cutoff, w)
    }

    private fun logChoose(n: Int, k: Int): Double =
        lgamma(n + 1.0) - lgamma(k + 1.0) - lgamma(n - k + 1.0)

    private fun lgamma(x: Double): Double {
        if (x < 0.5) return ln(PI / sin(PI * x)) - lgamma(1.0 - x)
        val g = doubleArrayOf(
            676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7
        )
        val xx = x - 1.0
        var a = 0.99999999999980993
        val t = xx + 7.5
        for (i in g.indices) a += g[i] / (xx + i + 1.0)
        return 0.5 * ln(2.0 * PI) + (xx + 0.5) * ln(t) - t + ln(a)
    }
}
