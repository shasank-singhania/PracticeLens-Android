package app.practicelens.android.camera

import app.practicelens.android.core.fingerprint
import kotlin.math.abs
import kotlin.math.max

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
    private val maxMotion: Double = 10.0,
    private val cooldownMs: Long = 1200,
    private val maxStableWindowMs: Long = minStableDurationMs + 500,
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
        if (window.size < 3) return StableFrameDecision(false, "warming", window.size, null)

        val texts = window.mapNotNull { it.ocrText?.trim()?.takeIf(String::isNotBlank) }
        if (texts.size >= 2 && texts.distinctBy(::fingerprint).size > 1) {
            window.clear()
            return StableFrameDecision(false, "ocr-inconsistent", window.size, null)
        }

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
        val count = max(1, minOf(a.luminanceSample.size, b.luminanceSample.size))
        var total = 0L
        repeat(count) { total += abs((a.luminanceSample[it].toInt() and 0xff) - (b.luminanceSample[it].toInt() and 0xff)) }
        return total.toDouble() / count
    }
}
