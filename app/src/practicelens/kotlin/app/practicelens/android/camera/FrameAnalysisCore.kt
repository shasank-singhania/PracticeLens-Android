package app.practicelens.android.camera

import app.practicelens.android.ocr.OcrObservation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class CropRegion(val left: Int, val top: Int, val width: Int, val height: Int)

data class RawLuminanceFrame(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampMs: Long,
    val yPlane: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
)

interface CloseableFrame {
    val timestampMs: Long
    fun close()
}

fun interface FrameMetricsCalculator<F : CloseableFrame> {
    fun calculate(frame: F): FrameMetrics
}

interface FrameTextRecognizer<F : CloseableFrame> {
    fun recognize(frame: F, onComplete: (Result<OcrObservation>) -> Unit)
    fun close()
}

class LuminanceFrameMetricsCalculator(
    private val cropFractionWidth: Float = 0.88f,
    private val cropFractionHeight: Float = 0.58f,
    private val sampleSize: Int = 32,
) {
    fun cropFor(width: Int, height: Int): CropRegion {
        val cropWidth = (width * cropFractionWidth).roundToInt().coerceIn(1, width)
        val cropHeight = (height * cropFractionHeight).roundToInt().coerceIn(1, height)
        return CropRegion(
            left = ((width - cropWidth) / 2).coerceAtLeast(0),
            top = ((height - cropHeight) / 2).coerceAtLeast(0),
            width = cropWidth,
            height = cropHeight,
        )
    }

    fun calculate(frame: RawLuminanceFrame): FrameMetrics {
        val crop = cropFor(frame.width, frame.height)
        val cropped = copyCrop(frame, crop)
        val sample = downsample(cropped, crop.width, crop.height)
        return FrameMetrics(
            timestampMs = frame.timestampMs,
            luminanceSample = sample,
            sharpness = laplacianVariance(cropped, crop.width, crop.height),
            exposure = sample.map { it.toInt() and 0xff }.average(),
            candidate = CroppedFrameCandidate(crop.width, crop.height, frame.rotationDegrees, cropped),
        )
    }

    fun copyCrop(frame: RawLuminanceFrame, crop: CropRegion): ByteArray {
        val out = ByteArray(crop.width * crop.height)
        for (y in 0 until crop.height) {
            val sourceY = crop.top + y
            for (x in 0 until crop.width) {
                val sourceX = crop.left + x
                val sourceIndex = sourceY * frame.rowStride + sourceX * frame.pixelStride
                out[y * crop.width + x] = frame.yPlane[sourceIndex]
            }
        }
        return out
    }

    fun downsample(luminance: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(sampleSize * sampleSize)
        for (y in 0 until sampleSize) {
            val sourceY = ((y + 0.5f) * height / sampleSize).toInt().coerceIn(0, height - 1)
            for (x in 0 until sampleSize) {
                val sourceX = ((x + 0.5f) * width / sampleSize).toInt().coerceIn(0, width - 1)
                out[y * sampleSize + x] = luminance[sourceY * width + sourceX]
            }
        }
        return out
    }

    fun laplacianVariance(luminance: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        val values = ArrayList<Int>((width - 2) * (height - 2))
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = luminance[y * width + x].toInt() and 0xff
                val laplacian =
                    (luminance[(y - 1) * width + x].toInt() and 0xff) +
                        (luminance[(y + 1) * width + x].toInt() and 0xff) +
                        (luminance[y * width + x - 1].toInt() and 0xff) +
                        (luminance[y * width + x + 1].toInt() and 0xff) -
                        (4 * center)
                values += laplacian
            }
        }
        val mean = values.average()
        return values.sumOf { value ->
            val delta = value - mean
            delta * delta
        } / values.size.toDouble()
    }
}

class StableOcrFrameAnalyzer<F : CloseableFrame>(
    private val metricsCalculator: FrameMetricsCalculator<F>,
    private val recognizer: FrameTextRecognizer<F>,
    private val detector: StableFrameDetector,
    private val onAccepted: (OcrObservation, CroppedFrameCandidate?) -> Unit,
    private val onRejected: (String) -> Unit = {},
) {
    private val busy = AtomicBoolean(false)
    private val accepted = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    fun analyze(frame: F) {
        if (disposed.get() || accepted.get()) {
            frame.close()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            frame.close()
            onRejected("busy")
            return
        }
        val metrics = try {
            metricsCalculator.calculate(frame)
        } catch (t: Throwable) {
            busy.set(false)
            frame.close()
            onRejected("metrics")
            return
        }

        recognizer.recognize(frame) { result ->
            try {
                if (disposed.get() || accepted.get()) return@recognize
                val observation = result.getOrElse {
                    onRejected("ocr")
                    return@recognize
                }
                val decision = detector.observe(metrics.copy(ocrText = observation.fullText))
                if (decision.accepted && accepted.compareAndSet(false, true)) {
                    onAccepted(observation, decision.sharpest?.candidate ?: metrics.candidate)
                } else {
                    onRejected(decision.reason)
                }
            } finally {
                busy.set(false)
                frame.close()
            }
        }
    }

    fun dispose() {
        disposed.set(true)
        detector.reset()
        recognizer.close()
    }
}
