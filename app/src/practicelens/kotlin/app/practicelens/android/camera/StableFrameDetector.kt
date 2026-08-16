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
) {
    override fun equals(other: Any?): Boolean = other is FrameMetrics && timestampMs == other.timestampMs
    override fun hashCode(): Int = timestampMs.hashCode()
}

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
) {
    private val window = ArrayDeque<FrameMetrics>()
    private var lastAcceptedAt = Long.MIN_VALUE / 2
    private var lastFingerprint: String? = null

    fun observe(frame: FrameMetrics): StableFrameDecision {
        if (frame.timestampMs - lastAcceptedAt < cooldownMs) {
            return StableFrameDecision(false, "cooldown", window.size, null)
        }
        if (frame.sharpness < minSharpness) return StableFrameDecision(false, "blur", window.size, null)
        if (frame.exposure !in minExposure..maxExposure) return StableFrameDecision(false, "exposure", window.size, null)

        window.addLast(frame)
        while (window.isNotEmpty() && frame.timestampMs - window.first().timestampMs > minStableDurationMs) {
            window.removeFirst()
        }
        if (window.size < 3) return StableFrameDecision(false, "warming", window.size, null)
        val motion = averageMotion(window.elementAt(window.size - 2), frame)
        if (motion > maxMotion) return StableFrameDecision(false, "motion", window.size, null)

        val texts = window.mapNotNull { it.ocrText?.trim()?.takeIf(String::isNotBlank) }
        if (texts.size >= 2 && texts.distinctBy(::fingerprint).size > 1) {
            return StableFrameDecision(false, "ocr-inconsistent", window.size, null)
        }

        val duration = frame.timestampMs - window.first().timestampMs
        if (duration < minStableDurationMs) return StableFrameDecision(false, "duration", window.size, null)

        val textFingerprint = texts.lastOrNull()?.let(::fingerprint)
        if (textFingerprint != null && textFingerprint == lastFingerprint) {
            return StableFrameDecision(false, "duplicate-question", window.size, null)
        }
        val sharpest = window.maxBy { it.sharpness }
        lastAcceptedAt = frame.timestampMs
        lastFingerprint = textFingerprint
        window.clear()
        return StableFrameDecision(true, "accepted", 0, sharpest)
    }

    fun resetPreviousQuestion() {
        lastFingerprint = null
    }

    private fun averageMotion(a: FrameMetrics, b: FrameMetrics): Double {
        val count = max(1, minOf(a.luminanceSample.size, b.luminanceSample.size))
        var total = 0L
        repeat(count) { total += abs((a.luminanceSample[it].toInt() and 0xff) - (b.luminanceSample[it].toInt() and 0xff)) }
        return total.toDouble() / count
    }
}
