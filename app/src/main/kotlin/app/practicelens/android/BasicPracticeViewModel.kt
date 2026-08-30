package app.practicelens.android

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BasicState {
    IDLE,
    CAMERA_STARTING,
    CAPTURING,
    SENDING,
    SHOWING_RESPONSE,
    ERROR,
}

data class BasicUiState(
    val state: BasicState = BasicState.IDLE,
    val captureRequestId: Long = 0,
    val responseText: String? = null,
    val presentationResult: PresentationResult? = null,
    val errorCategory: GeminiFailureCategory? = null,
    val errorMessage: String? = null,
    val countdownSeconds: Int = 0,
    val running: Boolean = false,
    val cameraReady: Boolean = false,
    val cameraStartCount: Int = 0,
)

class BasicPracticeViewModel(
    private val gateway: GeminiGateway,
    private val ocrProcessor: LocalOcrProcessor = NoOpLocalOcrProcessor(),
    private val deleteFile: (File) -> Boolean = { it.delete() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(BasicUiState())
    val uiState: StateFlow<BasicUiState> = _uiState

    private var loopJob: Job? = null
    private var requestJob: Job? = null
    private var captureInFlight = false
    private var requestInFlight = false
    private var cameraReady = false
    private var generation = 0L

    fun start() {
        stopJobs()
        generation++
        captureInFlight = false
        requestInFlight = false
        cameraReady = false
        _uiState.value = BasicUiState(state = BasicState.CAMERA_STARTING, running = true, cameraStartCount = 1)
        log("state=CAMERA_STARTING")
    }

    fun startAgain() = start()

    fun stop() {
        stopJobs()
        generation++
        captureInFlight = false
        requestInFlight = false
        cameraReady = false
        _uiState.value = BasicUiState(state = BasicState.IDLE)
        log("loop-stopped state=IDLE")
    }

    fun onBackgrounded() {
        stopJobs()
        generation++
        captureInFlight = false
        requestInFlight = false
        cameraReady = false
        _uiState.value = BasicUiState(state = BasicState.IDLE)
        log("loop-backgrounded state=IDLE")
    }

    fun onCameraReady() {
        cameraReady = true
        _uiState.update { it.copy(cameraReady = true) }
        if (_uiState.value.state == BasicState.CAMERA_STARTING && _uiState.value.running && !captureInFlight && !requestInFlight) {
            scheduleWarmupCapture()
        }
    }

    fun onImageCaptured(imageFile: File) {
        if (!_uiState.value.running || requestInFlight) {
            deleteFile(imageFile)
            return
        }
        val requestGeneration = generation
        captureInFlight = false
        requestInFlight = true
        _uiState.update { it.copy(state = BasicState.SENDING, responseText = null, presentationResult = null, countdownSeconds = 0) }
        log("request-started state=SENDING")
        requestJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val ocrDeferred = async { runCatching { ocrProcessor.recognize(imageFile) } }
            try {
                val response = gateway.answerImage(imageFile)
                val rawGeminiText = response.rawText
                deleteFile(imageFile)
                requestInFlight = false
                if (requestGeneration != generation || !_uiState.value.running) return@launch
                if (rawGeminiText.isBlank()) {
                    val isMaxTokens = response.finishReason == "MAX_TOKENS"
                    _uiState.update {
                        it.copy(
                            state = BasicState.ERROR,
                            running = false,
                            errorCategory = if (isMaxTokens) GeminiFailureCategory.UNKNOWN else GeminiFailureCategory.UNKNOWN,
                            errorMessage = if (isMaxTokens) {
                                "Gemini reached its model output limit before returning displayable text."
                            } else {
                                "Gemini returned an empty response."
                            },
                        )
                    }
                    logRequestCompleted(response, 0, 0)
                    return@launch
                }
                val extractedAnswers = AnswerExtractor.extract(rawGeminiText)
                val result = PresentationResult(
                    answers = extractedAnswers,
                    rawGeminiText = rawGeminiText,
                    finishReason = response.finishReason,
                    tokenMetadata = response.tokenMetadata,
                    notice = if (response.finishReason == "MAX_TOKENS") {
                        "Gemini reached its model output limit; this response may be incomplete."
                    } else {
                        null
                    },
                )
                _uiState.update {
                    it.copy(
                        state = BasicState.SHOWING_RESPONSE,
                        responseText = rawGeminiText,
                        presentationResult = result,
                        errorCategory = null,
                        errorMessage = null,
                        countdownSeconds = 5,
                    )
                }
                val ocrResult = ocrDeferred.await().getOrDefault(OcrResult())
                if (requestGeneration != generation || !_uiState.value.running || _uiState.value.state != BasicState.SHOWING_RESPONSE) return@launch
                val enrichedAnswers = OcrAnswerMatcher.enrich(extractedAnswers, ocrResult)
                val ocrMatchCount = enrichedAnswers.count { it.ocrSuggestion != null }
                _uiState.update {
                    it.copy(
                        presentationResult = result.copy(answers = enrichedAnswers),
                    )
                }
                logRequestCompleted(response, enrichedAnswers.size, ocrMatchCount)
                scheduleNextCapture()
            } catch (e: CancellationException) {
                ocrDeferred.cancel()
                deleteFile(imageFile)
                requestInFlight = false
                log("request-failed state=IDLE category=CANCELLED")
                throw e
            } catch (e: GeminiRequestException) {
                ocrDeferred.cancel()
                deleteFile(imageFile)
                requestInFlight = false
                if (requestGeneration != generation) return@launch
                val visibleMessage = if (e.category == GeminiFailureCategory.QUOTA) {
                    "Gemini free-tier rate or usage quota was reached. Automatic capture has paused. Wait and tap Start to continue."
                } else {
                    e.message
                }
                _uiState.update {
                    it.copy(
                        state = BasicState.ERROR,
                        running = false,
                        errorCategory = e.category,
                        errorMessage = visibleMessage,
                    )
                }
                log("request-failed state=ERROR category=${e.category} durationMs=${System.currentTimeMillis() - startedAt}")
            } catch (e: Exception) {
                ocrDeferred.cancel()
                deleteFile(imageFile)
                requestInFlight = false
                if (requestGeneration != generation) return@launch
                _uiState.update {
                    it.copy(
                        state = BasicState.ERROR,
                        running = false,
                        errorCategory = GeminiFailureCategory.UNKNOWN,
                        errorMessage = e.message ?: "Unknown Firebase error.",
                    )
                }
                log("request-failed state=ERROR category=UNKNOWN durationMs=${System.currentTimeMillis() - startedAt}")
            }
        }
    }

    fun onCaptureFailed(message: String, errorCode: Int? = null, exceptionClass: String? = null, imageCaptureBound: Boolean? = null) {
        captureInFlight = false
        val visibleMessage = buildString {
            append(message)
            errorCode?.let { append("\nImageCapture error code: ").append(it) }
            exceptionClass?.let { append("\nException: ").append(it) }
            imageCaptureBound?.let { append("\nImageCapture bound: ").append(it) }
        }
        _uiState.update {
            it.copy(
                state = BasicState.ERROR,
                running = false,
                errorCategory = GeminiFailureCategory.UNKNOWN,
                errorMessage = visibleMessage,
            )
        }
        log("capture-failed state=ERROR code=${errorCode ?: -1} exception=${exceptionClass.orEmpty()} bound=${imageCaptureBound ?: false}")
    }

    private fun scheduleWarmupCapture() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch {
            delay(1_000)
            if (!_uiState.value.running || !cameraReady || captureInFlight || requestInFlight) return@launch
            captureInFlight = true
            _uiState.update { it.copy(state = BasicState.CAPTURING, captureRequestId = it.captureRequestId + 1) }
            log("capture-started state=CAPTURING")
        }
    }

    private fun scheduleNextCapture() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch {
            for (seconds in 5 downTo 1) {
                if (!_uiState.value.running) return@launch
                _uiState.update { it.copy(countdownSeconds = seconds) }
                delay(1_000)
            }
            if (!_uiState.value.running) return@launch
            scheduleWarmupCapture()
        }
    }

    private fun stopJobs() {
        loopJob?.cancel()
        requestJob?.cancel()
        loopJob = null
        requestJob = null
    }

    private fun log(message: String) {
        runCatching { Log.d("PracticeLensBasic", message) }
    }

    private fun logRequestCompleted(response: GeminiResponse, answersExtracted: Int, ocrMatchCount: Int) {
        val tokens = response.tokenMetadata
        log(
            "request-completed finishReason=${response.finishReason ?: "UNKNOWN"} " +
                "promptTokens=${tokens.promptTokens ?: -1} " +
                "outputTokens=${tokens.outputTokens ?: -1} " +
                "thinkingTokens=${tokens.thinkingTokens ?: -1} " +
                "totalTokens=${tokens.totalTokens ?: -1} " +
                "answersExtracted=$answersExtracted " +
                "ocrMatchCount=$ocrMatchCount",
        )
    }

    override fun onCleared() {
        stopJobs()
        super.onCleared()
    }
}
