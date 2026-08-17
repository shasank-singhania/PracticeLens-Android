package app.practicelens.android

import app.practicelens.android.camera.FrameMetrics
import app.practicelens.android.camera.StableFrameDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableFrameDetectorTest {
    private val readableQuestion = "Which answer is correct?\nA. First answer\nB. Second answer"

    @Test fun `moving frames never trigger`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, maxMotion = 3.0, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, sample = ByteArray(32) { 10 }, text = readableQuestion)).accepted)
        assertFalse(detector.observe(frame(260, sample = ByteArray(32) { 80 }, text = readableQuestion)).accepted)
        assertFalse(detector.observe(frame(900, sample = ByteArray(32) { 82 }, text = readableQuestion)).accepted)
    }

    @Test fun `blurry frames never trigger`() {
        val detector = StableFrameDetector()
        assertFalse(detector.observe(frame(0, sharpness = 1.0)).accepted)
        assertFalse(detector.observe(frame(900, sharpness = 1.0)).accepted)
    }

    @Test fun `underexposure and overexposure are rejected`() {
        val detector = StableFrameDetector()
        assertFalse(detector.observe(frame(0, exposure = 10.0)).accepted)
        assertFalse(detector.observe(frame(900, exposure = 250.0)).accepted)
    }

    @Test fun `stable readable content accepts exactly once and deduplicates`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, text = readableQuestion)).accepted)
        assertFalse(detector.observe(frame(333, text = readableQuestion)).accepted)
        assertTrue(detector.observe(frame(817, text = readableQuestion)).accepted)
        assertFalse(detector.observe(frame(1800, text = readableQuestion)).accepted)
    }

    @Test fun `intermittent bad frame resets stability`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, text = readableQuestion)).accepted)
        assertFalse(detector.observe(frame(300, sharpness = 1.0, text = readableQuestion)).accepted)
        assertFalse(detector.observe(frame(800, text = readableQuestion)).accepted)
        assertFalse(detector.observe(frame(1100, text = readableQuestion)).accepted)
        assertTrue(detector.observe(frame(1600, text = readableQuestion)).accepted)
    }

    @Test fun `cooldown prevents immediate second acceptance`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 1200)
        acceptQuestion(detector, readableQuestion, 0)
        assertFalse(detector.observe(frame(1000, text = "Which answer is different?\nA. Third answer\nB. Fourth answer")).accepted)
    }

    @Test fun `materially changed question can be accepted after duplicate`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        acceptQuestion(detector, readableQuestion, 0)
        assertFalse(acceptQuestion(detector, readableQuestion, 1200))
        assertTrue(acceptQuestion(detector, "Which answer is different?\nA. Third answer\nB. Fourth answer", 2400))
    }

    @Test fun `OCR inconsistency is rejected`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, text = "What is the capital of France?\nA. Paris\nB. Rome")).accepted)
        assertFalse(detector.observe(frame(300, text = "Different calculus problem\nA. derivative\nB. integral")).accepted)
        assertFalse(detector.observe(frame(900, text = "Different calculus problem\nA. derivative\nB. integral")).accepted)
    }

    @Test fun `minor OCR punctuation and whitespace drift remains stable`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, text = "Which answer is correct?\nA. First option\nB. Second option")).accepted)
        assertFalse(detector.observe(frame(350, text = "Which answer is correct \n(A) First option\nB) Second option")).accepted)
        assertTrue(detector.observe(frame(850, text = "Which answer is correct?\nA First option\nB. Second option")).accepted)
    }

    @Test fun `slow OCR frames remain inside stability window`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        val text = "Question with slower OCR\nA. First answer\nB. Second answer"
        assertFalse(detector.observe(frame(0, text = text)).accepted)
        assertFalse(detector.observe(frame(800, text = text)).accepted)
        assertTrue(detector.observe(frame(1600, text = text)).accepted)
    }

    @Test fun `stable frames without OCR can trigger still capture`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, text = "")).accepted)
        assertFalse(detector.observe(frame(400, text = "")).accepted)
        assertTrue(detector.observe(frame(900, text = "")).accepted)
    }

    @Test fun `one sample camera translation remains stable`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, maxMotion = 3.0, cooldownMs = 0)
        val original = ByteArray(64) { index -> ((index % 8) * 20).toByte() }
        val shifted = ByteArray(64) { index ->
            val x = index % 8
            val y = index / 8
            if (x == 0) 0 else original[y * 8 + x - 1]
        }
        val text = "Translated camera question\nA. One\nB. Two"
        assertFalse(detector.observe(frame(0, text = text, sample = original)).accepted)
        assertFalse(detector.observe(frame(350, text = text, sample = shifted)).accepted)
        assertTrue(detector.observe(frame(850, text = text, sample = original)).accepted)
    }

    @Test fun `sharpest candidate selected`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, sharpness = 30.0, text = readableQuestion)).accepted)
        assertFalse(detector.observe(frame(400, sharpness = 60.0, text = readableQuestion)).accepted)
        val decision = detector.observe(frame(900, sharpness = 40.0, text = readableQuestion))
        assertTrue(decision.accepted)
        assertEquals(60.0, decision.sharpest?.sharpness ?: 0.0, 0.01)
    }

    private fun frame(
        t: Long,
        sharpness: Double = 40.0,
        exposure: Double = 120.0,
        text: String? = null,
        sample: ByteArray = ByteArray(32) { 100 },
    ) = FrameMetrics(t, sample, sharpness, exposure, text)

    private fun acceptQuestion(detector: StableFrameDetector, text: String, start: Long): Boolean {
        detector.observe(frame(start, text = text))
        detector.observe(frame(start + 350, text = text))
        return detector.observe(frame(start + 800, text = text)).accepted
    }
}
