package app.practicelens.android

import app.practicelens.android.camera.CloseableFrame
import app.practicelens.android.camera.FrameMetrics
import app.practicelens.android.camera.FrameMetricsCalculator
import app.practicelens.android.camera.FrameTextRecognizer
import app.practicelens.android.camera.StableFrameDetector
import app.practicelens.android.camera.StableOcrFrameAnalyzer
import app.practicelens.android.ocr.OcrObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerLifecycleTest {
    @Test fun `OCR success closes frames and accepts once`() {
        val recognizer = FakeRecognizer()
        var accepts = 0
        val analyzer = analyzer(recognizer) { accepts++ }
        val frames = listOf(FakeFrame(0), FakeFrame(400), FakeFrame(900))

        frames.forEach(analyzer::analyze)

        assertEquals(1, accepts)
        frames.forEach { assertEquals(1, it.closeCount) }
        analyzer.analyze(FakeFrame(1300).also { assertEquals(0, it.closeCount) })
    }

    @Test fun `metric failure closes frame`() {
        val frame = FakeFrame(0)
        val recognizer = FakeRecognizer()
        val analyzer = StableOcrFrameAnalyzer(
            metricsCalculator = FrameMetricsCalculator<FakeFrame> { error("boom") },
            recognizer = recognizer,
            detector = StableFrameDetector(),
            onAccepted = { _, _ -> },
        )

        analyzer.analyze(frame)

        assertEquals(1, frame.closeCount)
        assertEquals(0, recognizer.calls)
    }

    @Test fun `OCR failure and cancellation close frames`() {
        val recognizer = FakeRecognizer(result = Result.failure(IllegalStateException("no text")))
        val frame = FakeFrame(0)

        analyzer(recognizer).analyze(frame)

        assertEquals(1, frame.closeCount)
    }

    @Test fun `busy recognizer drops and closes new frame`() {
        val recognizer = FakeRecognizer(pending = true)
        val analyzer = analyzer(recognizer)
        val first = FakeFrame(0)
        val second = FakeFrame(200)

        analyzer.analyze(first)
        analyzer.analyze(second)

        assertEquals(0, first.closeCount)
        assertEquals(1, second.closeCount)
        recognizer.complete(Result.success(OcrObservation("Q\nA. a\nB. b")))
        assertEquals(1, first.closeCount)
    }

    @Test fun `disposal prevents acceptance callbacks`() {
        val recognizer = FakeRecognizer(pending = true)
        var accepted = false
        val analyzer = analyzer(recognizer) { accepted = true }
        val frame = FakeFrame(0)

        analyzer.analyze(frame)
        analyzer.dispose()
        recognizer.complete(Result.success(OcrObservation("Q\nA. a\nB. b")))

        assertFalse(accepted)
        assertEquals(1, frame.closeCount)
        assertTrue(recognizer.closed)
    }

    private fun analyzer(
        recognizer: FakeRecognizer,
        onAccepted: () -> Unit = {},
    ) = StableOcrFrameAnalyzer(
        metricsCalculator = FrameMetricsCalculator<FakeFrame> {
            FrameMetrics(it.timestampMs, ByteArray(32) { 100 }, 40.0, 120.0)
        },
        recognizer = recognizer,
        detector = StableFrameDetector(minStableDurationMs = 750, cooldownMs = 0),
        onAccepted = { _, _ -> onAccepted() },
    )

    private class FakeFrame(override val timestampMs: Long) : CloseableFrame {
        var closeCount = 0
        override fun close() {
            closeCount++
        }
    }

    private class FakeRecognizer(
        private val result: Result<OcrObservation> = Result.success(OcrObservation("Q\nA. a\nB. b")),
        private val pending: Boolean = false,
    ) : FrameTextRecognizer<FakeFrame> {
        private val callbacks = ArrayDeque<(Result<OcrObservation>) -> Unit>()
        var calls = 0
        var closed = false

        override fun recognize(frame: FakeFrame, onComplete: (Result<OcrObservation>) -> Unit) {
            calls++
            if (pending) callbacks.addLast(onComplete) else onComplete(result)
        }

        fun complete(value: Result<OcrObservation>) {
            callbacks.removeFirst().invoke(value)
        }

        override fun close() {
            closed = true
        }
    }
}
