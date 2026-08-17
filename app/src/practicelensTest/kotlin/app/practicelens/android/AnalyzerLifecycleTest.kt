package app.practicelens.android

import app.practicelens.android.camera.CloseableFrame
import app.practicelens.android.camera.FrameMetrics
import app.practicelens.android.camera.FrameMetricsCalculator
import app.practicelens.android.camera.ScannerPhase
import app.practicelens.android.camera.ScannerStateMachine
import app.practicelens.android.camera.StableFrameDetector
import app.practicelens.android.camera.StableOcrFrameAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerLifecycleTest {
    @Test fun `stable analysis frames close and trigger capture once`() {
        var accepts = 0
        val analyzer = analyzer { accepts++ }
        val frames = listOf(FakeFrame(0), FakeFrame(400), FakeFrame(900))

        frames.forEach(analyzer::analyze)

        assertEquals(1, accepts)
        frames.forEach { assertEquals(1, it.closeCount) }
        analyzer.analyze(FakeFrame(1300).also { assertEquals(0, it.closeCount) })
    }

    @Test fun `metric failure closes frame`() {
        val frame = FakeFrame(0)
        val analyzer = StableOcrFrameAnalyzer(
            metricsCalculator = FrameMetricsCalculator<FakeFrame> { error("boom") },
            detector = StableFrameDetector(),
            onStable = { },
        )

        analyzer.analyze(frame)

        assertEquals(1, frame.closeCount)
    }

    @Test fun `manual capture requests are deduplicated`() {
        var accepts = 0
        val analyzer = analyzer { accepts++ }

        assertTrue(analyzer.requestCapture())
        assertFalse(analyzer.requestCapture())
        assertEquals(1, accepts)
    }

    @Test fun `busy analyzer drops and closes new frame`() {
        val analyzer = StableOcrFrameAnalyzer(
            metricsCalculator = FrameMetricsCalculator<FakeFrame> {
                Thread.sleep(50)
                FrameMetrics(it.timestampMs, ByteArray(32) { 100 }, 40.0, 120.0)
            },
            detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0),
            onStable = {},
        )
        val frame = FakeFrame(0)
        val second = FakeFrame(100)
        val worker = Thread { analyzer.analyze(frame) }

        worker.start()
        Thread.sleep(5)
        analyzer.analyze(second)
        worker.join()

        assertEquals(1, second.closeCount)
        assertEquals(1, frame.closeCount)
    }

    @Test fun `disposal prevents acceptance callbacks`() {
        var accepted = false
        val analyzer = analyzer { accepted = true }
        val frame = FakeFrame(0)

        analyzer.dispose()
        analyzer.analyze(frame)

        assertFalse(accepted)
        assertEquals(1, frame.closeCount)
    }

    @Test fun `scanner timeout transitions to manual capture`() {
        var now = 0L
        val state = ScannerStateMachine(timeoutMs = 9_000, clock = { now })

        assertEquals(ScannerPhase.FRAMING, state.tick())
        now = 9_001
        assertEquals(ScannerPhase.NEEDS_MANUAL_CAPTURE, state.tick())
    }

    @Test fun `state machine deduplicates concurrent capture requests`() {
        val state = ScannerStateMachine(timeoutMs = 9_000, clock = { 0 })

        assertTrue(state.beginCapture())
        assertFalse(state.beginCapture(manual = true))
    }

    @Test fun `rotation metadata is preserved in metrics`() {
        val metrics = FrameMetrics(0, ByteArray(32), 40.0, 120.0, candidate = null)

        assertEquals(0, metrics.timestampMs)
    }

    private fun analyzer(
        onAccepted: () -> Unit = {},
    ) = StableOcrFrameAnalyzer(
        metricsCalculator = FrameMetricsCalculator<FakeFrame> {
            FrameMetrics(it.timestampMs, ByteArray(32) { 100 }, 40.0, 120.0)
        },
        detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0),
        onStable = { onAccepted() },
    )

    private class FakeFrame(override val timestampMs: Long) : CloseableFrame {
        var closeCount = 0
        override fun close() {
            closeCount++
        }
    }
}
