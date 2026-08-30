package app.practicelens.android

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: BasicPracticeViewModel by viewModels {
        BasicPracticeViewModelFactory(FirebaseGeminiGateway(), MlKitLocalOcrProcessor(this))
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.start()
        } else {
            viewModel.onCaptureFailed("Camera permission was denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        setContent {
            BasicPracticeApp(
                viewModel = viewModel,
                cameraGranted = { hasCameraPermission() },
                requestCamera = { requestCamera.launch(Manifest.permission.CAMERA) },
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onBackgrounded()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun BasicPracticeApp(
    viewModel: BasicPracticeViewModel,
    cameraGranted: () -> Boolean,
    requestCamera: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when (state.state) {
                BasicState.IDLE -> StartScreen(
                    onStart = {
                        if (cameraGranted()) viewModel.start() else requestCamera()
                    },
                )
                BasicState.CAMERA_STARTING,
                BasicState.CAPTURING,
                BasicState.SENDING,
                BasicState.SHOWING_RESPONSE -> CameraSessionScreen(state, viewModel)
                BasicState.ERROR -> ErrorScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun StartScreen(onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PracticeLens", style = MaterialTheme.typography.headlineMedium)
        Text("Basic smoke loop: capture one image, ask Gemini once, show the raw response, then repeat.")
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Start")
        }
    }
}

@Composable
private fun CameraSessionScreen(state: BasicUiState, viewModel: BasicPracticeViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { BasicCameraController(context) }
    var showRawGeminiOutput by remember(state.presentationResult?.rawGeminiText) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also {
                    controller.bind(it, lifecycleOwner, viewModel::onCameraReady, viewModel::onCaptureFailed)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("State: ${state.state.name}", style = MaterialTheme.typography.titleMedium)
                when (state.state) {
                    BasicState.SENDING -> Text("Waiting for Gemini response")
                    BasicState.SHOWING_RESPONSE -> {
                        Text("Next capture in ${state.countdownSeconds} seconds")
                        state.presentationResult?.notice?.let { Text(it) }
                        AnswersSection(state.presentationResult)
                        if (!state.presentationResult?.rawGeminiText.isNullOrEmpty()) {
                            OutlinedButton(
                                onClick = { showRawGeminiOutput = !showRawGeminiOutput },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                Text(if (showRawGeminiOutput) "Hide Gemini output" else "View Gemini output")
                            }
                        }
                        if (showRawGeminiOutput) RawGeminiOutput(state.presentationResult?.rawGeminiText.orEmpty())
                    }
                    else -> Unit
                }
                Button(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Stop")
                }
            }
        }
    }
    LaunchedEffect(state.captureRequestId) {
        if (state.captureRequestId > 0) {
            val accepted = controller.capture(viewModel::onImageCaptured, viewModel::onCaptureFailed)
            if (!accepted) viewModel.onCaptureFailed("Camera capture was not ready.", imageCaptureBound = controller.isBound)
        }
    }
    DisposableEffect(Unit) {
        onDispose { controller.dispose() }
    }
}

@Composable
private fun AnswersSection(result: PresentationResult?) {
    val answers = result?.answers.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Answers", style = MaterialTheme.typography.titleSmall)
        if (answers.isEmpty()) {
            Text("Gemini output is available.")
        } else {
            answers.forEachIndexed { index, answer ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Question ${answer.questionOrdinal ?: index + 1}", style = MaterialTheme.typography.labelLarge)
                    answer.optionPosition?.let { Text("Selected option: position $it") }
                    answer.marker?.let { Text("Visible marker: $it") }
                    answer.geminiOptionText?.let { Text("Gemini answer: $it") }
                    if (answer.optionPosition == null && answer.marker == null && answer.geminiOptionText == null) {
                        Text(answer.rawGeminiLine)
                    }
                    answer.ocrSuggestion?.let {
                        Text("${answer.ocrLabel.displayText()}: $it")
                    }
                }
            }
        }
    }
}

@Composable
private fun RawGeminiOutput(rawText: String) {
    SelectionContainer {
        Text(
            rawText,
            modifier = Modifier
                .height(260.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
    }
}

private fun OcrSuggestionLabel?.displayText(): String =
    when (this) {
        OcrSuggestionLabel.APPROXIMATE_MATCH -> "Approximate OCR match"
        OcrSuggestionLabel.APPROXIMATE_OPTION_TEXT -> "Approximate option text"
        OcrSuggestionLabel.POSSIBLE_MATCH -> "Possible OCR match"
        null -> "Possible OCR match"
    }

@Composable
private fun ErrorScreen(state: BasicUiState, viewModel: BasicPracticeViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gemini request failed.", style = MaterialTheme.typography.titleLarge)
        Text(state.errorCategory?.name ?: GeminiFailureCategory.UNKNOWN.name)
        Text(state.errorMessage.orEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::startAgain, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Start again")
            }
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Stop")
            }
        }
    }
}

private class BasicPracticeViewModelFactory(
    private val gateway: GeminiGateway,
    private val ocrProcessor: LocalOcrProcessor,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BasicPracticeViewModel(gateway, ocrProcessor) as T
}
