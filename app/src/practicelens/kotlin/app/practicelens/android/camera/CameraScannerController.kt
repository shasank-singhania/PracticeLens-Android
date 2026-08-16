package app.practicelens.android.camera

import android.content.Context
import android.graphics.Rect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.practicelens.android.ocr.OcrObservation
import app.practicelens.android.ocr.OcrTextLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraScannerController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onAccepted: (OcrObservation) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizer = MlKitFrameTextRecognizer()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analyzer: StableOcrFrameAnalyzer<ImageProxyFrame>? = null
    private val stopped = AtomicBoolean(false)

    fun start() {
        stopped.set(false)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.targetRotation = previewView.display?.rotation ?: 0

                val frameAnalyzer = StableOcrFrameAnalyzer(
                    metricsCalculator = ImageProxyMetricsCalculator(),
                    recognizer = recognizer,
                    detector = StableFrameDetector(),
                    onAccepted = { observation, _ ->
                        stop()
                        ContextCompat.getMainExecutor(context).execute { onAccepted(observation) }
                    },
                    onRejected = { },
                )
                analyzer = frameAnalyzer
                analysis = imageAnalysis
                imageAnalysis.setAnalyzer(executor) { image ->
                    frameAnalyzer.analyze(ImageProxyFrame(image))
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (t: Throwable) {
                stop()
                ContextCompat.getMainExecutor(context).execute { onError("Camera scanner failed to start.") }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        analysis?.clearAnalyzer()
        analyzer?.dispose()
        provider?.unbindAll()
        analysis = null
        analyzer = null
    }

    fun dispose() {
        stop()
        recognizer.close()
        executor.shutdownNow()
    }
}

class ImageProxyFrame(private val proxy: ImageProxy) : CloseableFrame {
    private val closed = AtomicBoolean(false)
    override val timestampMs: Long = proxy.imageInfo.timestamp / 1_000_000L
    val imageProxy: ImageProxy get() = proxy

    override fun close() {
        if (closed.compareAndSet(false, true)) proxy.close()
    }
}

class ImageProxyMetricsCalculator(
    private val luminanceCalculator: LuminanceFrameMetricsCalculator = LuminanceFrameMetricsCalculator(),
) : FrameMetricsCalculator<ImageProxyFrame> {
    override fun calculate(frame: ImageProxyFrame): FrameMetrics {
        val proxy = frame.imageProxy
        val y = proxy.planes.firstOrNull() ?: error("Missing Y plane")
        val buffer = y.buffer.duplicate()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return luminanceCalculator.calculate(
            RawLuminanceFrame(
                width = proxy.width,
                height = proxy.height,
                rotationDegrees = proxy.imageInfo.rotationDegrees,
                timestampMs = frame.timestampMs,
                yPlane = bytes,
                rowStride = y.rowStride,
                pixelStride = y.pixelStride,
            ),
        )
    }
}

class MlKitFrameTextRecognizer : FrameTextRecognizer<ImageProxyFrame> {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @ExperimentalGetImage
    override fun recognize(frame: ImageProxyFrame, onComplete: (Result<OcrObservation>) -> Unit) {
        val mediaImage = frame.imageProxy.image
        if (mediaImage == null) {
            onComplete(Result.failure(IllegalStateException("Missing media image")))
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, frame.imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    onComplete(Result.failure(task.exception ?: IllegalStateException("OCR was canceled")))
                    return@addOnCompleteListener
                }
                val text = task.result
                val lines = text.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        val box = line.boundingBox ?: Rect()
                        OcrTextLine(line.text, box.left, box.top, box.right, box.bottom)
                    }
                }
                onComplete(Result.success(OcrObservation(text.text, lines)))
            }
    }

    override fun close() {
        recognizer.close()
    }
}
