package app.practicelens.android.camera

import app.practicelens.android.core.fingerprint
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class FrameMetrics(
    val timestampMs: Long,
    val luminanceSample: ByteArray,
    val sharpness: Double,
    val exposure: Double,
    val ocrText: String? = null,
    val candidate: CroppedFrameCandidate? = null,
) {
    override fun equals(other: Any?): Boolean = other is FrameMetrics && timestampMs == other.timestampMs
    override fun hashCode(): Int = timestampMs.hashCode()
}

data class CroppedFrameCandidate(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val luminance: ByteArray,
)

data class StableFrameDecision(
    val accepted: Boolean,
    val reason: String,
    val stableCount: Int,
    val sharpest: FrameMetrics?,
)

class StableFrameDetector(
    private val minStableDurationMs: Long = 800,
    private val minSharpness: Double = 22.0,
    private val minExposure: Double = 35.0,
    private val maxExposure: Double = 220.0,
    private val maxMotion: Double = 18.0,
    private val cooldownMs: Long = 1200,
    private val maxStableWindowMs: Long = minStableDurationMs + 2200,
    @Suppress("unused") private val minReadableCharacters: Int = 12,
    private val minTextSimilarity: Double = 0.72,
) {
    private val window = ArrayDeque<FrameMetrics>()
    private var lastAcceptedAt = Long.MIN_VALUE / 2
    private var lastFingerprint: String? = null
    private var lastTimestampMs = Long.MIN_VALUE

    fun observe(frame: FrameMetrics): StableFrameDecision {
        if (frame.timestampMs - lastAcceptedAt < cooldownMs) {
            return StableFrameDecision(false, "cooldown", window.size, null)
        }
        if (frame.timestampMs <= lastTimestampMs) {
            window.clear()
            return StableFrameDecision(false, "timestamp", 0, null)
        }
        lastTimestampMs = frame.timestampMs
        if (frame.sharpness < minSharpness) return rejectAndReset("blur")
        if (frame.exposure !in minExposure..maxExposure) return rejectAndReset("exposure")

        val previous = window.lastOrNull()
        if (previous != null && averageMotion(previous, frame) > maxMotion) {
            window.clear()
            window.addLast(frame)
            return StableFrameDecision(false, "motion", window.size, null)
        }
        window.addLast(frame)
        while (window.isNotEmpty() && frame.timestampMs - window.first().timestampMs > maxStableWindowMs) {
            window.removeFirst()
        }
        val texts = window.mapNotNull { metrics ->
            metrics.ocrText
                ?.let(::normalizeOcrText)
                ?.takeIf { text -> text.count { character -> character.isLetterOrDigit() } >= minReadableCharacters }
        }
        if (texts.size >= 2 && ocrSimilarity(texts[texts.lastIndex - 1], texts.last()) < minTextSimilarity) {
            window.clear()
            window.addLast(frame)
            return StableFrameDecision(false, "ocr-inconsistent", window.size, null)
        }
        if (window.size < 3) return StableFrameDecision(false, "warming", window.size, null)

        val duration = frame.timestampMs - window.first().timestampMs
        if (duration < minStableDurationMs) return StableFrameDecision(false, "duration", window.size, null)
        val textFingerprint = texts.lastOrNull()?.let(::fingerprint)
        if (textFingerprint != null && textFingerprint == lastFingerprint) {
            window.clear()
            return StableFrameDecision(false, "duplicate-question", window.size, null)
        }
        val sharpest = window.maxBy { it.sharpness }
        lastAcceptedAt = frame.timestampMs
        lastFingerprint = textFingerprint
        window.clear()
        return StableFrameDecision(true, "accepted", 0, sharpest)
    }

    fun reset() {
        window.clear()
        lastTimestampMs = Long.MIN_VALUE
    }

    fun resetPreviousQuestion() {
        lastFingerprint = null
    }

    private fun rejectAndReset(reason: String): StableFrameDecision {
        window.clear()
        return StableFrameDecision(false, reason, 0, null)
    }

    private fun averageMotion(a: FrameMetrics, b: FrameMetrics): Double {
        val count = minOf(a.luminanceSample.size, b.luminanceSample.size)
        if (count <= 0) return 0.0
        val side = sqrt(count.toDouble()).toInt()
        if (side * side != count || side < 3) return directMotion(a.luminanceSample, b.luminanceSample, count)

        var best = Double.MAX_VALUE
        for (offsetY in -1..1) {
            for (offsetX in -1..1) {
                var total = 0L
                var compared = 0
                for (y in 0 until side) {
                    val otherY = y + offsetY
                    if (otherY !in 0 until side) continue
                    for (x in 0 until side) {
                        val otherX = x + offsetX
                        if (otherX !in 0 until side) continue
                        total += abs(
                            (a.luminanceSample[y * side + x].toInt() and 0xff) -
                                (b.luminanceSample[otherY * side + otherX].toInt() and 0xff),
                        )
                        compared++
                    }
                }
                if (compared > 0) best = minOf(best, total.toDouble() / compared)
            }
        }
        return best
    }

    private fun directMotion(a: ByteArray, b: ByteArray, count: Int): Double {
        var total = 0L
        repeat(max(1, count)) { total += abs((a[it].toInt() and 0xff) - (b[it].toInt() and 0xff)) }
        return total.toDouble() / count
    }

    private fun normalizeOcrText(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun ocrSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val counts = HashMap<String, Int>()
        for (index in 0 until a.length - 1) {
            val pair = a.substring(index, index + 2)
            counts[pair] = (counts[pair] ?: 0) + 1
        }
        var matches = 0
        for (index in 0 until b.length - 1) {
            val pair = b.substring(index, index + 2)
            val available = counts[pair] ?: 0
            if (available > 0) {
                matches++
                if (available == 1) counts.remove(pair) else counts[pair] = available - 1
            }
        }
        return (2.0 * matches) / ((a.length - 1) + (b.length - 1))
    }
}
