package app.practicelens.android.camera

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.practicelens.android.ocr.OcrObservation
import app.practicelens.android.ocr.OcrParser
import app.practicelens.android.ocr.OcrTextBlock
import app.practicelens.android.ocr.OcrTextElement
import app.practicelens.android.ocr.OcrTextLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraScannerController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onAccepted: (OcrObservation) -> Unit,
    private val onError: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizer = MlKitImageCaptureOcrEngine()
    private val parser = OcrParser()
    private val stateMachine = ScannerStateMachine(clock = { SystemClock.elapsedRealtime() })
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var analyzer: StableOcrFrameAnalyzer<ImageProxyFrame>? = null
    private var camera: androidx.camera.core.Camera? = null
    private val stopped = AtomicBoolean(false)
    private val captureRunning = AtomicBoolean(false)
    private val resolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .build()

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
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val capture = ImageCapture.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val rotation = previewView.display?.rotation ?: 0
                imageAnalysis.targetRotation = rotation
                capture.targetRotation = rotation

                val frameAnalyzer = StableOcrFrameAnalyzer(
                    metricsCalculator = ImageProxyMetricsCalculator(),
                    detector = StableFrameDetector(),
                    onStable = {
                        reportStatus("Stable frame found. Focusing before capture.")
                        captureQuestion(manual = false)
                    },
                    onRejected = { rejection -> reportRejection(rejection) },
                )
                analyzer = frameAnalyzer
                analysis = imageAnalysis
                imageCapture = capture
                imageAnalysis.setAnalyzer(executor) { image ->
                    frameAnalyzer.analyze(ImageProxyFrame(image))
                }
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                    capture,
                )
                configureTouchGestures()
                logCameraInfo("bound")
                scheduleTimeout()
            } catch (t: Throwable) {
                Log.e(TAG, "Camera scanner failed to start", t)
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
        imageCapture = null
        analyzer = null
        camera = null
    }

    fun captureQuestion(manual: Boolean = true): Boolean {
        if (stopped.get() || !captureRunning.compareAndSet(false, true)) return false
        if (!stateMachine.beginCapture(manual)) {
            captureRunning.set(false)
            return false
        }
        analysis?.clearAnalyzer()
        ContextCompat.getMainExecutor(context).execute {
            reportStatus(if (manual) "Capturing question..." else "Auto-capturing question...")
            focusThenCapture(manual)
        }
        return true
    }

    fun dispose() {
        stop()
        recognizer.close()
        executor.shutdownNow()
    }

    private fun focusThenCapture(manual: Boolean) {
        val focusCompleted = AtomicBoolean(false)
        val cameraControl = camera?.cameraControl
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()
        if (cameraControl == null) {
            reportFocus("focus unavailable; capturing with current focus", manual)
            takePicture(manual, "Focus unavailable; review OCR carefully.")
            return
        }
        cameraControl.startFocusAndMetering(action).addListener({
            if (focusCompleted.compareAndSet(false, true)) {
                reportFocus("center autofocus completed", manual)
                takePicture(manual, null)
            }
        }, executor)
        previewView.postDelayed({
            if (focusCompleted.compareAndSet(false, true)) {
                reportFocus("autofocus timed out; capturing with current focus", manual)
                takePicture(manual, "Autofocus timed out; review OCR carefully.")
            }
        }, 1_200)
    }

    private fun takePicture(manual: Boolean, warning: String?) {
        val capture = imageCapture
        if (capture == null) {
            captureRunning.set(false)
            stateMachine.fail()
            reportError("Camera capture is unavailable.")
            return
        }
        stateMachine.capturing()
        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val startMs = SystemClock.elapsedRealtime()
                    stateMachine.recognizing()
                    Log.i(
                        TAG,
                        "capture resolution=${image.width}x${image.height}, rotation=${image.imageInfo.rotationDegrees}, manual=$manual",
                    )
                    recognizer.recognize(CapturedImageFrame(image)) { result ->
                        try {
                            val observation = result.getOrElse {
                                stateMachine.fail()
                                reportError("OCR failed. Retake with the full question in view.")
                                return@recognize
                            }
                            val elapsed = SystemClock.elapsedRealtime() - startMs
                            val draft = parser.parse(observation)
                            Log.i(
                                TAG,
                                "ocr durationMs=$elapsed blocks=${observation.blocks.size} lines=${observation.lines.size} " +
                                    "elements=${observation.lines.sumOf { it.elements.size }} options=${draft.options.size} " +
                                    "confidence=${"%.2f".format(draft.confidence)} reason=${if (draft.valid) "parsed" else draft.message.take(80)} " +
                                    "preview=${observation.fullText.replace('\n', ' ').take(160)}",
                            )
                            stateMachine.reviewReady()
                            ContextCompat.getMainExecutor(context).execute {
                                stop()
                                onAccepted(observation)
                            }
                        } finally {
                            image.close()
                            captureRunning.set(false)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Image capture failed", exception)
                    captureRunning.set(false)
                    stateMachine.fail()
                    reportError("Capture failed. Hold steady and try again.")
                }
            },
        )
    }

    private fun configureTouchGestures() {
        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                    val nextZoom = (zoomState.zoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    camera?.cameraControl?.setZoomRatio(nextZoom)
                    reportStatus("Zoom ${"%.1f".format(nextZoom)}x")
                    return true
                }
            },
        )
        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                tapToFocus(event.x, event.y)
            }
            true
        }
    }

    private fun tapToFocus(x: Float, y: Float) {
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)?.addListener({
            reportFocus("tap focus completed", manual = true)
        }, executor)
        reportStatus("Focusing...")
    }

    private fun scheduleTimeout() {
        ContextCompat.getMainExecutor(context).execute {
            previewView.postDelayed({
                if (!stopped.get() && !captureRunning.get() && stateMachine.tick() == ScannerPhase.NEEDS_MANUAL_CAPTURE) {
                    analysis?.clearAnalyzer()
                    reportStatus("Auto-capture timed out. Use Capture question when the full question is framed.")
                }
            }, 9_000)
        }
    }

    private fun reportRejection(rejection: String) {
        Log.d(TAG, "Frame rejected: $rejection")
        val status = when (rejection.substringBefore('|')) {
            "blur" -> "Move closer and let the camera focus."
            "exposure" -> "Improve the lighting and avoid glare."
            "motion" -> "Hold the phone steady."
            "metrics" -> "Reposition the page inside the guide."
            "duration", "warming" -> "Hold steady for auto-capture, or tap Capture question."
            else -> "Frame the complete question and options."
        }
        reportStatus(status)
    }

    private fun reportStatus(status: String) {
        ContextCompat.getMainExecutor(context).execute { onStatus(status) }
    }

    private fun reportError(message: String) {
        ContextCompat.getMainExecutor(context).execute { onError(message) }
    }

    private fun reportFocus(message: String, manual: Boolean) {
        Log.i(TAG, "focus result=$message, manual=$manual")
        reportStatus(message)
    }

    private fun logCameraInfo(stage: String) {
        Log.i(TAG, "camera $stage analysis requested=highest_available capture=MAXIMIZE_QUALITY")
    }

    private companion object {
        const val TAG = "PracticeLensScanner"
    }
}

class CapturedImageFrame(val imageProxy: ImageProxy)

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

class MlKitImageCaptureOcrEngine : OcrEngine<CapturedImageFrame> {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    override fun recognize(frame: CapturedImageFrame, onComplete: (Result<OcrObservation>) -> Unit) {
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
                val blocks = text.textBlocks.map { block ->
                    val blockBox = block.boundingBox ?: Rect()
                    val lines = block.lines.map { line ->
                        val box = line.boundingBox ?: Rect()
                        OcrTextLine(
                            text = line.text,
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                            elements = line.elements.map { element ->
                                val elementBox = element.boundingBox ?: Rect()
                                OcrTextElement(
                                    text = element.text,
                                    left = elementBox.left,
                                    top = elementBox.top,
                                    right = elementBox.right,
                                    bottom = elementBox.bottom,
                                    confidence = element.confidence,
                                )
                            },
                            confidence = line.confidence,
                            recognizedLanguage = line.recognizedLanguage,
                        )
                    }
                    OcrTextBlock(
                        text = block.text,
                        left = blockBox.left,
                        top = blockBox.top,
                        right = blockBox.right,
                        bottom = blockBox.bottom,
                        lines = lines,
                        recognizedLanguage = block.recognizedLanguage,
                    )
                }
                onComplete(
                    Result.success(
                        OcrObservation(
                            fullText = text.text,
                            lines = blocks.flatMap { it.lines },
                            blocks = blocks,
                            rotationDegrees = frame.imageProxy.imageInfo.rotationDegrees,
                            width = frame.imageProxy.width,
                            height = frame.imageProxy.height,
                        ),
                    ),
                )
            }
    }

    override fun close() {
        recognizer.close()
    }
}
