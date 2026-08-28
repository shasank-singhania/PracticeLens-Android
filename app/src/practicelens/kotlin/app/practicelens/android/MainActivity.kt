package app.practicelens.android

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.practicelens.android.camera.AndroidCropOcrProcessor
import app.practicelens.android.camera.AndroidQuestionMediaProcessor
import app.practicelens.android.camera.CameraScannerController
import app.practicelens.android.camera.CropReviewGeometry
import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.EvaluationState
import app.practicelens.android.core.QuestionMediaJanitor
import app.practicelens.android.interpretation.AnswerBackend
import app.practicelens.android.interpretation.ModelRequestState
import app.practicelens.android.ocr.OcrDraft
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

internal object AutomaticPracticeSettingsLayoutContract {
    const val title = "PracticeLens"
    const val modeLabel = "Mode"
    const val resultDurationLabel = "Result display duration"
    const val answerBackendLabel = "Answer backend"
    const val orientationLabel = "Capture orientation"
    const val explanationLabel = "Include explanation"
    const val automaticallyContinueLabel = "Automatically continue"
    const val requestLimitLabel = "Session request ceiling"
    const val startAutomaticLabel = "Start automatic practice"
    const val openCameraLabel = "Open camera"
    const val usesSingleVerticalScroll = true
    const val hasVisibleOverflowScrollThumb = true
    const val respectsSafeDrawingInsets = true
    const val estimatedContentHeightDp = 720

    val requiredChoices = listOf(
        modeLabel,
        answerBackendLabel,
        resultDurationLabel,
        orientationLabel,
        explanationLabel,
        automaticallyContinueLabel,
        requestLimitLabel,
    )

    fun allSettingsReachable(viewportHeightDp: Int): Boolean =
        viewportHeightDp > 0 &&
            usesSingleVerticalScroll &&
            hasVisibleOverflowScrollThumb &&
            respectsSafeDrawingInsets

    fun contentCanOverflow(viewportHeightDp: Int): Boolean =
        estimatedContentHeightDp > viewportHeightDp
}

internal enum class PracticeLensRootRoute {
    DISCLOSURE,
    CAMERA_PERMISSION,
    AUTOMATIC_PRACTICE,
    MANUAL_AI_RESULT,
    CROP_REVIEW,
    QUESTION_REVIEW,
    CAMERA_SCANNER,
    ATTEMPT,
    WAITING,
}

internal object PracticeLensRootRouter {
    fun route(state: PracticeLensUiState): PracticeLensRootRoute =
        when {
            !state.disclosureAccepted -> PracticeLensRootRoute.DISCLOSURE
            !state.cameraPermissionGranted && state.cameraPermissionPermanentlyDenied -> PracticeLensRootRoute.CAMERA_PERMISSION
            state.practiceMode == PracticeMode.AUTOMATIC_AI_PRACTICE &&
                state.automaticState != AutomaticPracticeState.CONFIGURING -> PracticeLensRootRoute.AUTOMATIC_PRACTICE
            state.practiceMode == PracticeMode.MANUAL_CAPTURE &&
                state.automaticState in setOf(
                    AutomaticPracticeState.ANALYZING,
                    AutomaticPracticeState.SHOWING_RESULT,
                    AutomaticPracticeState.RECOVERABLE_ERROR,
                ) -> PracticeLensRootRoute.MANUAL_AI_RESULT
            state.capturedImage != null && state.croppedImage == null &&
                state.practiceMode == PracticeMode.MANUAL_CROP_REVIEW -> PracticeLensRootRoute.CROP_REVIEW
            state.ocrReview != null -> PracticeLensRootRoute.QUESTION_REVIEW
            state.scanning -> PracticeLensRootRoute.CAMERA_SCANNER
            state.attempt != null -> PracticeLensRootRoute.ATTEMPT
            else -> PracticeLensRootRoute.WAITING
        }
}

class MainActivity : ComponentActivity() {
    private val disclosureStore: SharedPreferencesDisclosureAcceptanceStore by lazy {
        SharedPreferencesDisclosureAcceptanceStore(
            getSharedPreferences("practice_lens_preferences", MODE_PRIVATE),
        )
    }
    private val viewModel: PracticeLensViewModel by viewModels {
        PracticeLensViewModelFactory(disclosureStore)
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permanentlyDenied = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        viewModel.setCameraPermission(granted, permanentlyDenied)
        if (granted) viewModel.startSelectedPractice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        viewModel.setCameraPermission(granted)
        setContent {
            PracticeLensApp(
                viewModel = viewModel,
                onRequestCamera = { requestCamera.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null),
                        ),
                    )
                },
            )
        }
    }
}

@Composable
fun PracticeLensApp(
    viewModel: PracticeLensViewModel,
    onRequestCamera: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(context) {
        val processor = AndroidQuestionMediaProcessor(context)
        QuestionMediaJanitor.install(processor::delete)
        onDispose { QuestionMediaJanitor.clear() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onBackgrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val reviewDraft = state.ocrReview
            val capturedImage = state.capturedImage
            when (PracticeLensRootRouter.route(state)) {
                PracticeLensRootRoute.DISCLOSURE -> DisclosureScreen(viewModel::acceptDisclosure)
                PracticeLensRootRoute.CAMERA_PERMISSION -> CameraPermissionScreen(
                    permanentlyDenied = state.cameraPermissionPermanentlyDenied,
                    onRetry = onRequestCamera,
                    onOpenSettings = onOpenSettings,
                )
                PracticeLensRootRoute.AUTOMATIC_PRACTICE -> AutomaticPracticeScreen(state, viewModel)
                PracticeLensRootRoute.MANUAL_AI_RESULT -> ManualAiResultScreen(state, viewModel)
                PracticeLensRootRoute.CROP_REVIEW -> CropReviewScreen(capturedImage!!, viewModel)
                PracticeLensRootRoute.QUESTION_REVIEW -> QuestionReviewScreen(state, reviewDraft!!, viewModel)
                PracticeLensRootRoute.CAMERA_SCANNER -> CameraScanner(
                    automatic = false,
                    orientation = state.captureOrientation,
                    onReady = {},
                    onStableFrame = { false },
                    onSceneFingerprint = {},
                    captureRequestId = 0,
                    analysisResetId = 0,
                    captureQualityWarning = null,
                    onCaptured = { media, _ ->
                        if (state.practiceMode == PracticeMode.MANUAL_CAPTURE) {
                            viewModel.analyzeManualFullImage(media)
                        } else {
                            viewModel.openCropReview(media)
                        }
                    },
                    onError = viewModel::scannerFailed,
                )
                PracticeLensRootRoute.ATTEMPT -> AttemptScreen(state, viewModel)
                PracticeLensRootRoute.WAITING -> WaitingScreen(
                    state = state,
                    scannerError = state.scannerError,
                    onMode = viewModel::setPracticeMode,
                    onAnswerBackend = viewModel::setAnswerBackend,
                    onResultDuration = viewModel::setResultDisplaySeconds,
                    onOrientation = viewModel::setCaptureOrientation,
                    onIncludeExplanation = viewModel::setIncludeExplanation,
                    onAutomaticallyContinue = viewModel::setAutomaticallyContinue,
                    onRequestCeiling = viewModel::setAutomaticRequestCeiling,
                    onScan = {
                        if (!state.cameraPermissionGranted) {
                            onRequestCamera()
                        } else {
                            when (state.practiceMode) {
                                PracticeMode.AUTOMATIC_AI_PRACTICE -> viewModel.startAutomaticPractice()
                                PracticeMode.MANUAL_CAPTURE,
                                PracticeMode.MANUAL_CROP_REVIEW -> viewModel.resumeScanning()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DisclosureScreen(onAccept: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PracticeLens", style = MaterialTheme.typography.headlineMedium)
        Text("PracticeLens scans only foreground practice material that you point the rear camera at. It does not read other apps, capture the screen, record audio, use overlays, inspect the clipboard, or use accessibility capture.")
        Text("Camera access starts only after you accept this preparation-only scanner disclosure.")
        Button(onClick = onAccept, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Accept and continue") }
    }
}

@Composable
private fun CameraPermissionScreen(
    permanentlyDenied: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Camera access is required", style = MaterialTheme.typography.headlineSmall)
        Text("The scanner needs the foreground rear camera to read practice questions. No audio or background camera work is used.")
        if (permanentlyDenied) {
            Text("Camera permission is denied in system settings.")
            Button(onClick = onOpenSettings, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Open settings") }
        } else {
            Button(onClick = onRetry, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Allow camera") }
        }
    }
}

@Composable
private fun WaitingScreen(
    state: PracticeLensUiState,
    scannerError: String?,
    onMode: (PracticeMode) -> Unit,
    onAnswerBackend: (AnswerBackend) -> Unit,
    onResultDuration: (Int) -> Unit,
    onOrientation: (CaptureOrientation) -> Unit,
    onIncludeExplanation: (Boolean) -> Unit,
    onAutomaticallyContinue: (Boolean) -> Unit,
    onRequestCeiling: (Int) -> Unit,
    onScan: () -> Unit,
) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(AutomaticPracticeSettingsLayoutContract.title, style = MaterialTheme.typography.headlineMedium)
            Text(AutomaticPracticeSettingsLayoutContract.modeLabel)
            PracticeMode.values().forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.practiceMode == mode, onClick = { onMode(mode) })
                    Text(
                        when (mode) {
                            PracticeMode.AUTOMATIC_AI_PRACTICE -> "Automatic AI Practice"
                            PracticeMode.MANUAL_CAPTURE -> "Manual capture"
                            PracticeMode.MANUAL_CROP_REVIEW -> "Manual crop and review"
                        },
                    )
                }
            }
            Text(AutomaticPracticeSettingsLayoutContract.answerBackendLabel)
            state.answerBackendPolicy.allowedBackends().forEach { backend ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.answerBackend == backend, onClick = { onAnswerBackend(backend) })
                    Text(backend.displayText())
                }
            }
            Text(AutomaticPracticeSettingsLayoutContract.resultDurationLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 8, 10).forEach { seconds ->
                    OutlinedButton(onClick = { onResultDuration(seconds) }, enabled = state.resultDisplaySeconds != seconds) {
                        Text("${seconds}s")
                    }
                }
            }
            Text(AutomaticPracticeSettingsLayoutContract.orientationLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureOrientation.values().forEach { orientation ->
                    OutlinedButton(onClick = { onOrientation(orientation) }, enabled = state.captureOrientation != orientation) {
                        Text(orientation.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.includeExplanation, onCheckedChange = onIncludeExplanation)
                Text(AutomaticPracticeSettingsLayoutContract.explanationLabel)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.automaticallyContinue, onCheckedChange = onAutomaticallyContinue)
                Text(AutomaticPracticeSettingsLayoutContract.automaticallyContinueLabel)
            }
            Text(AutomaticPracticeSettingsLayoutContract.requestLimitLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 30, 60).forEach { limit ->
                    OutlinedButton(onClick = { onRequestCeiling(limit) }, enabled = state.automaticRequestCeiling != limit) {
                        Text(limit.toString())
                    }
                }
            }
            scannerError?.let { Text(it) }
            state.automaticStatus.takeIf { it != "Ready" && state.automaticState == AutomaticPracticeState.CONFIGURING }?.let { Text(it) }
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp)) {
                Text(
                    if (state.practiceMode == PracticeMode.AUTOMATIC_AI_PRACTICE) {
                        AutomaticPracticeSettingsLayoutContract.startAutomaticLabel
                    } else {
                        AutomaticPracticeSettingsLayoutContract.openCameraLabel
                    },
                )
            }
        }
        if (scroll.maxValue > 0) {
            VerticalScrollThumb(
                scrollValue = scroll.value,
                maxScrollValue = scroll.maxValue,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(6.dp)
                    .padding(vertical = 8.dp, horizontal = 1.dp)
                    .semantics { contentDescription = "Settings scroll indicator" },
            )
        }
    }
}

@Composable
private fun VerticalScrollThumb(scrollValue: Int, maxScrollValue: Int, modifier: Modifier = Modifier) {
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    Box(
        modifier.drawBehind {
            if (maxScrollValue <= 0) return@drawBehind
            val thumbHeight = (size.height * size.height / (size.height + maxScrollValue.toFloat()))
                .coerceAtLeast(48.dp.toPx())
                .coerceAtMost(size.height)
            val top = (size.height - thumbHeight) * (scrollValue.toFloat() / maxScrollValue.toFloat())
            drawRoundRect(
                color = thumbColor,
                topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f, size.width / 2f),
            )
        },
    )
}

@Composable
private fun CameraScanner(
    automatic: Boolean,
    orientation: CaptureOrientation,
    onReady: () -> Unit,
    onStableFrame: () -> Boolean,
    onSceneFingerprint: (String) -> Unit,
    captureRequestId: Long,
    analysisResetId: Long,
    captureQualityWarning: String?,
    onCaptured: (CapturedQuestionMedia, Long) -> Unit,
    onError: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { AtomicReference<CameraScannerController?>(null) }
    val activeAutomaticRequestId = remember { AtomicLong(0) }
    var scannerStatus by remember { mutableStateOf("Frame the question, then capture.") }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    val scannerController = CameraScannerController(
                        context = ctx,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        onCaptured = { media -> onCaptured(media, activeAutomaticRequestId.get()) },
                        onError = onError,
                        onStatus = { scannerStatus = it },
                        onReady = { if (automatic) onReady() },
                        onStableFrame = { automatic && onStableFrame() },
                        onSceneFingerprint = { if (automatic) onSceneFingerprint(it) },
                        orientation = orientation,
                        autoCaptureEnabled = automatic,
                    )
                    controller.set(scannerController)
                    previewView.setTag(scannerController)
                    scannerController.start()
                }
            },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Rear camera preview" },
            onRelease = { view ->
                val releasedController = view.getTag() as? CameraScannerController
                releasedController?.dispose()
                controller.compareAndSet(releasedController, null)
            },
        )
        LaunchedEffect(orientation) {
            controller.get()?.updateTargetRotation(orientation)
        }
        LaunchedEffect(analysisResetId) {
            if (automatic && analysisResetId > 0) controller.get()?.rearmAutomaticAnalysis()
        }
        LaunchedEffect(captureRequestId) {
            if (automatic && captureRequestId > 0) {
                activeAutomaticRequestId.set(captureRequestId)
                if (controller.get()?.captureQuestion(manual = false, requestedWarning = captureQualityWarning) != true) {
                    scannerStatus = "Capture is already running."
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.72f)
                .border(2.dp, Color.White)
                .semantics { contentDescription = "Question aiming guide" },
        )
        if (!automatic) Card(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Align one multiple-choice question inside the guide.")
                Text("Tap the preview to focus. Pinch to zoom.")
                Text(scannerStatus)
                Button(
                    onClick = {
                        if (controller.get()?.captureQuestion(manual = true) != true) {
                            scannerStatus = "Capture is already running."
                        }
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("Capture question") }
            }
        }
    }
}

@Composable
private fun AutomaticPracticeScreen(state: PracticeLensUiState, viewModel: PracticeLensViewModel) {
    Box(Modifier.fillMaxSize()) {
        CameraScanner(
            automatic = true,
            orientation = state.captureOrientation,
            onReady = viewModel::automaticCameraReady,
            onStableFrame = viewModel::automaticStableFrameAccepted,
            onSceneFingerprint = viewModel::automaticSceneObserved,
            captureRequestId = state.automaticCaptureRequestId,
            analysisResetId = state.automaticAnalysisResetId,
            captureQualityWarning = state.automaticCaptureQualityWarning,
            onCaptured = { media, requestId -> viewModel.onAutomaticImageCaptured(media, requestId) },
            onError = viewModel::automaticCaptureFailed,
        )
        Card(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.automaticStatus, style = MaterialTheme.typography.titleMedium)
                AutomaticDiagnostics(state)
                state.automaticResult?.let { AutomaticResultCard(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.automaticState == AutomaticPracticeState.PAUSED) {
                        Button(onClick = viewModel::resumeAutomaticPractice, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Resume") }
                    } else {
                        OutlinedButton(onClick = viewModel::pauseAutomaticPractice, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Pause") }
                    }
                    Button(onClick = viewModel::stopAutomaticPractice, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Stop") }
                    if (state.automaticState == AutomaticPracticeState.RECOVERABLE_ERROR) {
                        Button(onClick = viewModel::retryAutomaticFailure, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Retry") }
                    }
                }
                Text("Requests ${state.automaticRequestCount}/${state.automaticRequestCeiling}")
            }
        }
    }
}

class SharedPreferencesDisclosureAcceptanceStore(
    private val preferences: SharedPreferences,
) : DisclosureAcceptanceStore {
    override fun isAccepted(): Boolean = preferences.getBoolean(KEY, false)
    override fun setAccepted(accepted: Boolean) {
        preferences.edit().putBoolean(KEY, accepted).apply()
    }
    override fun reset() {
        preferences.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "disclosure_accepted"
    }
}

private class PracticeLensViewModelFactory(
    private val disclosureStore: DisclosureAcceptanceStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PracticeLensViewModel(disclosureStore = disclosureStore) as T
}

@Composable
private fun ManualAiResultScreen(state: PracticeLensUiState, viewModel: PracticeLensViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.automaticStatus, style = MaterialTheme.typography.titleLarge)
        AutomaticDiagnostics(state)
        state.automaticResult?.let { AutomaticResultCard(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.automaticState == AutomaticPracticeState.RECOVERABLE_ERROR) {
                Button(onClick = viewModel::retake, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Retry") }
            }
            Button(onClick = viewModel::retake, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Capture another") }
        }
    }
}

@Composable
private fun AutomaticResultCard(result: app.practicelens.android.interpretation.AutomaticAnswer) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (result.simulated) Text("Simulated result")
        result.questionSummary?.let { Text(it) }
        if (result.status == app.practicelens.android.interpretation.AutomaticAnswerStatus.ANSWERED) {
            Text("Answer: ${listOfNotNull(result.answerLabel, result.answerText).joinToString(" ")}")
        } else {
            Text(result.status.name)
        }
        result.explanation?.let { Text(it) }
        Text("Confidence ${"%.0f".format(result.confidence * 100)}%")
    }
}

@Composable
private fun AutomaticDiagnostics(state: PracticeLensUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Backend: ${state.answerBackend.displayText()}")
        Text("Model: ${state.answerBackendPolicy.modelId}")
        Text("Request: ${state.modelRequestState.displayText()}")
        state.automaticError?.let { Text("Error: $it") }
    }
}

private fun AnswerBackend.displayText(): String =
    when (this) {
        AnswerBackend.GEMINI -> "Gemini"
        AnswerBackend.SIMULATED -> "Offline simulation"
    }

private fun ModelRequestState.displayText(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun CropReviewScreen(media: CapturedQuestionMedia, viewModel: PracticeLensViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val processor = remember { AndroidCropOcrProcessor(context) }
    var crop by remember(media.sha256) { mutableStateOf(CropReviewGeometry.initialGuide()) }
    var rotation by remember(media.sha256) { mutableStateOf(0) }
    val bitmap = remember(media.uri) { BitmapFactory.decodeFile(Uri.parse(media.uri).toFile().absolutePath)?.asImageBitmap() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Confirm crop", style = MaterialTheme.typography.titleLarge)
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Captured question image",
                modifier = Modifier.fillMaxWidth().height(320.dp).border(1.dp, Color.Gray),
                contentScale = ContentScale.Fit,
            )
        }
        Text("Crop ${"%.0f".format((crop.right - crop.left) * 100)}% x ${"%.0f".format((crop.bottom - crop.top) * 100)}%, rotation $rotation degrees.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { crop = CropReviewGeometry.move(crop, -0.03f, 0f) }) { Text("Left") }
            OutlinedButton(onClick = { crop = CropReviewGeometry.move(crop, 0.03f, 0f) }) { Text("Right") }
            OutlinedButton(onClick = { crop = CropReviewGeometry.move(crop, 0f, -0.03f) }) { Text("Up") }
            OutlinedButton(onClick = { crop = CropReviewGeometry.move(crop, 0f, 0.03f) }) { Text("Down") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { crop = CropReviewGeometry.resize(crop, left = -0.03f, top = -0.03f, right = 0.03f, bottom = 0.03f) }) { Text("Larger") }
            OutlinedButton(onClick = { crop = CropReviewGeometry.resize(crop, left = 0.03f, top = 0.03f, right = -0.03f, bottom = -0.03f) }) { Text("Smaller") }
            OutlinedButton(onClick = { rotation = (rotation + 270) % 360 }) { Text("Rotate left") }
            OutlinedButton(onClick = { rotation = (rotation + 90) % 360 }) { Text("Rotate right") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { crop = CropReviewGeometry.initialGuide(); rotation = 0 }) { Text("Reset crop") }
            OutlinedButton(onClick = { crop = CropReviewGeometry.fullImage() }) { Text("Use full image") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                processor.delete(media)
                viewModel.retake()
            }) { Text("Retake") }
            Button(
                onClick = {
                    viewModel.startCropOcr(media, crop, rotation, processor)
                },
                enabled = !state.cropOcrLoading,
            ) { Text(if (state.cropOcrLoading) "Processing..." else "Confirm crop") }
        }
        media.qualityWarnings.forEach { Text(it) }
    }
    DisposableEffect(Unit) { onDispose { processor.close() } }
}

@Composable
private fun QuestionReviewScreen(state: PracticeLensUiState, draft: OcrDraft, viewModel: PracticeLensViewModel) {
    val scroll = rememberScrollState()
    var showRaw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review question", style = MaterialTheme.typography.titleLarge)
        state.croppedImage?.let { media ->
            val bitmap = remember(media.uri) { BitmapFactory.decodeFile(Uri.parse(media.uri).toFile().absolutePath)?.asImageBitmap() }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Confirmed crop",
                    modifier = Modifier.fillMaxWidth().height(180.dp).border(1.dp, Color.Gray),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Text("Detected options: ${draft.options.size}; confidence: ${"%.0f".format(draft.confidence * 100)}%")
        if (!draft.valid) Text(draft.message)
        draft.warnings.forEach { Text(it) }
        if (state.interpretationLoading) Text("Analyzing confirmed crop with AI...")
        state.interpretationMessage?.let { Text(it) }
        OutlinedTextField(
            value = draft.question,
            onValueChange = viewModel::editOcrQuestion,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Question") },
            isError = draft.question.isBlank(),
        )
        draft.options.forEachIndexed { index, option ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = option.label,
                        onValueChange = { viewModel.editOcrOptionLabel(index, it) },
                        modifier = Modifier.weight(0.25f),
                        label = { Text("Label") },
                        isError = option.labelError != null,
                    )
                    OutlinedTextField(
                        value = option.text,
                        onValueChange = { viewModel.editOcrOptionText(index, it) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Option") },
                        isError = option.textError != null,
                    )
                }
                option.labelError?.let { Text(it) }
                option.textError?.let { Text(it) }
                OutlinedButton(
                    onClick = { viewModel.removeOcrOption(index) },
                    enabled = draft.options.size > 2,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("Remove option") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.mergeOcrOptionWithPrevious(index) },
                        enabled = index > 0,
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) { Text("Merge with previous") }
                    OutlinedButton(
                        onClick = { viewModel.splitOcrOption(index) },
                        enabled = draft.options.size < 8 && option.text.lines().size > 1,
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) { Text("Split option") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::addOcrOption,
                enabled = draft.options.size < 8,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Add option") }
            OutlinedButton(onClick = viewModel::rejectOcr, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Reject OCR") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::retake, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Retake") }
            OutlinedButton(
                onClick = viewModel::analyzeImageWithAi,
                enabled = state.croppedImage != null && !state.interpretationLoading,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Analyze image with AI") }
            Button(
                onClick = viewModel::confirmOcrDraft,
                enabled = draft.valid,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Confirm question") }
        }
        OutlinedButton(onClick = { showRaw = !showRaw }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
            Text(if (showRaw) "Hide raw OCR" else "Show raw OCR")
        }
        if (showRaw) {
            Text(draft.rawText.take(1600))
        }
    }
}

@Composable
private fun AttemptScreen(state: PracticeLensUiState, viewModel: PracticeLensViewModel) {
    val attempt = state.attempt ?: return
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress) viewModel.stopAutoNext()
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Answer", style = MaterialTheme.typography.titleLarge)
        Text(attempt.question.prompt)
        attempt.question.options.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = attempt.finalSelectedOptionId == option.id,
                    onClick = { viewModel.select(option.id) },
                    enabled = attempt.evaluationState != EvaluationState.IN_FLIGHT && attempt.evaluationState != EvaluationState.COMPLETE,
                )
                Text("${option.id}. ${option.text}")
            }
        }
        when (attempt.evaluationState) {
            EvaluationState.GRACE_PERIOD -> Text("Evaluating after grace period unless you change the answer.")
            EvaluationState.IN_FLIGHT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("Evaluating confirmed answer...")
            }
            EvaluationState.COMPLETE -> {
                val result = attempt.result
                Text("Correct option: ${result?.correctOptionId}")
                Text(result?.explanation.orEmpty())
                result?.warning?.let { Text(it) }
                Text("AI-generated feedback can be wrong. Verify important material with your course resources.")
                LinearProgressIndicator(progress = { state.autoNextProgress }, modifier = Modifier.fillMaxWidth())
                Text("Auto-next in ${state.autoNextRemainingSeconds}s")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::stopAutoNext, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Stop auto-next") }
                    Button(onClick = viewModel::nextNow, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Next now") }
                }
                Button(onClick = viewModel::practiceAgainLater, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Practice again later") }
            }
            EvaluationState.FAILED -> {
                state.evaluationError?.let { Text("Evaluation error: $it") }
                state.evaluationMessage?.let { Text(it) }
                Button(onClick = viewModel::retryEvaluation, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Retry evaluation") }
            }
            else -> Text("Select an answer when ready.")
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::retake, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Retake camera image") }
    }
}
