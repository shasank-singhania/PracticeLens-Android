package app.practicelens.android

import app.practicelens.android.camera.LuminanceFrameMetricsCalculator
import app.practicelens.android.camera.RawLuminanceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMetricsCalculatorTest {
    @Test fun `crop region is centered and bounded`() {
        val calculator = LuminanceFrameMetricsCalculator(cropFractionWidth = 0.5f, cropFractionHeight = 0.5f)
        val crop = calculator.cropFor(width = 100, height = 80)

        assertEquals(25, crop.left)
        assertEquals(20, crop.top)
        assertEquals(50, crop.width)
        assertEquals(40, crop.height)
    }

    @Test fun `Y plane extraction respects row and pixel stride`() {
        val calculator = LuminanceFrameMetricsCalculator(cropFractionWidth = 1f, cropFractionHeight = 1f)
        val frame = RawLuminanceFrame(
            width = 2,
            height = 2,
            rotationDegrees = 90,
            timestampMs = 1,
            yPlane = byteArrayOf(1, 99, 2, 99, 3, 99, 4, 99),
            rowStride = 4,
            pixelStride = 2,
        )

        val metrics = calculator.calculate(frame)

        assertEquals(listOf(1, 2, 3, 4), metrics.candidate?.luminance?.map { it.toInt() })
        assertEquals(90, metrics.candidate?.rotationDegrees)
    }

    @Test fun `exposure and sharpness are calculated from luminance`() {
        val calculator = LuminanceFrameMetricsCalculator(cropFractionWidth = 1f, cropFractionHeight = 1f)
        val flat = RawLuminanceFrame(4, 4, 0, 1, ByteArray(16) { 100 }, 4, 1)
        val edge = RawLuminanceFrame(4, 4, 0, 2, ByteArray(16) { (if (it % 2 == 0) 20 else 230).toByte() }, 4, 1)

        assertEquals(100.0, calculator.calculate(flat).exposure, 0.01)
        assertTrue(calculator.calculate(edge).sharpness > calculator.calculate(flat).sharpness)
    }
}
