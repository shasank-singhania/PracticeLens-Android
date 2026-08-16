package app.practicelens.android

import app.practicelens.android.camera.FrameMetrics
import app.practicelens.android.camera.StableFrameDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableFrameDetectorTest {
    @Test fun `moving frames never trigger`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, maxMotion = 3.0, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, sample = ByteArray(32) { 10 }, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(260, sample = ByteArray(32) { 80 }, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(900, sample = ByteArray(32) { 82 }, text = "Q\nA. a\nB. b")).accepted)
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
        assertFalse(detector.observe(frame(0, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(333, text = "Q\nA. a\nB. b")).accepted)
        assertTrue(detector.observe(frame(817, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(1800, text = "Q\nA. a\nB. b")).accepted)
    }

    @Test fun `intermittent bad frame resets stability`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(300, sharpness = 1.0, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(800, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(1100, text = "Q\nA. a\nB. b")).accepted)
        assertTrue(detector.observe(frame(1600, text = "Q\nA. a\nB. b")).accepted)
    }

    @Test fun `cooldown prevents immediate second acceptance`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 1200)
        acceptQuestion(detector, "Q1\nA. a\nB. b", 0)
        assertFalse(detector.observe(frame(1000, text = "Q2\nA. a\nB. b")).accepted)
    }

    @Test fun `materially changed question can be accepted after duplicate`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        acceptQuestion(detector, "Q1\nA. a\nB. b", 0)
        assertFalse(acceptQuestion(detector, "Q1\nA. a\nB. b", 1200))
        assertTrue(acceptQuestion(detector, "Q2\nA. c\nB. d", 2400))
    }

    @Test fun `OCR inconsistency is rejected`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, text = "Q1\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(300, text = "Q2\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(900, text = "Q2\nA. a\nB. b")).accepted)
    }

    @Test fun `sharpest candidate selected`() {
        val detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, sharpness = 30.0, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(400, sharpness = 60.0, text = "Q\nA. a\nB. b")).accepted)
        val decision = detector.observe(frame(900, sharpness = 40.0, text = "Q\nA. a\nB. b"))
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
