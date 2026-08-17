package app.practicelens.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.practicelens.android.camera.CameraScannerController
import app.practicelens.android.core.EvaluationState
import app.practicelens.android.ocr.OcrDraft
import app.practicelens.android.ocr.OcrObservation
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    private val viewModel: PracticeLensViewModel by viewModels()
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permanentlyDenied = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        viewModel.setCameraPermission(granted, permanentlyDenied)
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
            when {
                !state.disclosureAccepted -> DisclosureScreen {
                    viewModel.acceptDisclosure()
                    if (state.cameraPermissionGranted) viewModel.resumeScanning() else onRequestCamera()
                }
                !state.cameraPermissionGranted -> CameraPermissionScreen(
                    permanentlyDenied = state.cameraPermissionPermanentlyDenied,
                    onRetry = onRequestCamera,
                    onOpenSettings = onOpenSettings,
                )
                reviewDraft != null -> OcrReviewScreen(reviewDraft, viewModel)
                state.scanning -> CameraScanner(
                    onAccepted = viewModel::openOcrReview,
                    onError = viewModel::scannerFailed,
                )
                state.attempt != null -> AttemptScreen(state, viewModel)
                else -> WaitingScreen(
                    scannerError = state.scannerError,
                    onScan = viewModel::resumeScanning,
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
private fun WaitingScreen(scannerError: String?, onScan: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PracticeLens", style = MaterialTheme.typography.headlineMedium)
        Text("Waiting for a new question...")
        scannerError?.let { Text(it) }
        Button(onClick = onScan, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Open camera") }
    }
}

@Composable
private fun CameraScanner(
    onAccepted: (OcrObservation) -> Unit,
    onError: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { AtomicReference<CameraScannerController?>(null) }
    var scannerStatus by remember { mutableStateOf("Reading question — hold steady.") }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    val scannerController = CameraScannerController(
                        context = ctx,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        onAccepted = onAccepted,
                        onError = onError,
                        onStatus = { scannerStatus = it },
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
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.72f)
                .border(2.dp, Color.White)
                .semantics { contentDescription = "Question aiming guide" },
        )
        Card(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
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
private fun OcrReviewScreen(draft: OcrDraft, viewModel: PracticeLensViewModel) {
    val scroll = rememberScrollState()
    var showRaw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review OCR", style = MaterialTheme.typography.titleLarge)
        Text("Detected options: ${draft.options.size}; confidence: ${"%.0f".format(draft.confidence * 100)}%")
        if (!draft.valid) Text(draft.message)
        draft.warnings.forEach { Text(it) }
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
            EvaluationState.FAILED -> Button(onClick = viewModel::retryEvaluation, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Retry evaluation") }
            else -> Text("Select an answer when ready.")
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::retake, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Retake camera image") }
    }
}
