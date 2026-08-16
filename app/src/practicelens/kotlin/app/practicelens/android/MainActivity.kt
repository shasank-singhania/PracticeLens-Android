package app.practicelens.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.practicelens.android.core.EvaluationState
import app.practicelens.android.core.PracticeOption
import app.practicelens.android.core.PracticeQuestion
import app.practicelens.android.ocr.OcrParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

class MainActivity : ComponentActivity() {
    private val viewModel: PracticeLensViewModel by viewModels()
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setCameraPermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        viewModel.setCameraPermission(granted)
        if (!granted) requestCamera.launch(Manifest.permission.CAMERA)
        setContent { PracticeLensApp(viewModel) }
    }
}

@Composable
fun PracticeLensApp(viewModel: PracticeLensViewModel) {
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
            when {
                !state.cameraPermissionGranted -> PermissionScreen()
                state.scanning -> CameraScanner(onTextAccepted = viewModel::acceptOcrText)
                state.attempt != null -> AttemptScreen(state, viewModel)
                else -> WaitingScreen(onScan = viewModel::resumeScanning)
            }
        }
    }
}

@Composable
private fun PermissionScreen() {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Camera access is required", style = MaterialTheme.typography.headlineSmall)
        Text("PracticeLens uses only the foreground rear camera for authorized practice material. It cannot read other apps, capture the device screen, or draw over other apps.")
    }
}

@Composable
private fun WaitingScreen(onScan: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PracticeLens", style = MaterialTheme.typography.headlineMedium)
        Text("PracticeLens is intended for self-study and authorized practice. It cannot read other apps, capture the device screen, control another device, or request an answer before you attempt the question.")
        Text("Waiting for a new question...")
        Button(onClick = onScan) { Text("Open camera") }
    }
}

@Composable
private fun CameraScanner(onTextAccepted: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(Dispatchers.Default.asExecutor(), DemoAnalyzer(onTextAccepted))
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Rear camera preview" },
        )
        Card(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Align one multiple-choice question inside the guide.")
                Text("Camera analysis stops as soon as a stable readable frame is accepted.")
            }
        }
    }
}

private class DemoAnalyzer(private val onTextAccepted: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var closed = false
    override fun analyze(image: ImageProxy) {
        try {
            if (!closed) {
                closed = true
                onTextAccepted("Which planet is known as the Red Planet?\nA. Venus\nB. Mars\nC. Jupiter\nD. Saturn")
            }
        } finally {
            image.close()
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
        Text("Review OCR", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = attempt.question.prompt,
            onValueChange = viewModel::editPrompt,
            enabled = attempt.evaluationState == EvaluationState.NOT_STARTED || attempt.evaluationState == EvaluationState.GRACE_PERIOD,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Question") },
        )
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
            EvaluationState.IN_FLIGHT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("Evaluating confirmed answer...")
            }
            EvaluationState.COMPLETE -> {
                val result = attempt.result
                Text("Correct option: ${result?.correctOptionId}")
                Text(result?.explanation.orEmpty())
                Text("AI-generated feedback can be wrong. Verify important material with your course resources.")
                LinearProgressIndicator(progress = { state.autoNextProgress }, modifier = Modifier.fillMaxWidth())
                Text("Auto-next in ${state.autoNextRemainingSeconds}s")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::stopAutoNext) { Text("Stop auto-next") }
                    Button(onClick = viewModel::nextNow) { Text("Next now") }
                }
                Button(onClick = viewModel::practiceAgainLater) { Text("Practice again later") }
            }
            EvaluationState.FAILED -> Button(onClick = viewModel::retryEvaluation) { Text("Retry evaluation") }
            else -> Text("Select an answer when ready.")
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::retake) { Text("Retake camera image") }
    }
}
