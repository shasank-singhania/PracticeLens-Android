package app.practicelens.android

import app.practicelens.android.camera.FrameMetrics
import app.practicelens.android.camera.StableFrameDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableFrameDetectorTest {
    @Test fun `blur and exposure are rejected`() {
        val detector = StableFrameDetector()
        assertFalse(detector.observe(frame(0, sharpness = 1.0)).accepted)
        assertFalse(detector.observe(frame(1, exposure = 250.0)).accepted)
    }

    @Test fun `stable readable content accepts once and deduplicates`() {
        val detector = StableFrameDetector(minStableDurationMs = 800, cooldownMs = 0)
        assertFalse(detector.observe(frame(0, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(400, text = "Q\nA. a\nB. b")).accepted)
        assertTrue(detector.observe(frame(900, text = "Q\nA. a\nB. b")).accepted)
        assertFalse(detector.observe(frame(1800, text = "Q\nA. a\nB. b")).accepted)
    }

    private fun frame(
        t: Long,
        sharpness: Double = 40.0,
        exposure: Double = 120.0,
        text: String? = null,
    ) = FrameMetrics(t, ByteArray(32) { 100 }, sharpness, exposure, text)
}
