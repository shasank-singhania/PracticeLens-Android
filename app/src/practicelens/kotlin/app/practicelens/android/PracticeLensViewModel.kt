package app.practicelens.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.practicelens.android.camera.CropOcrProcessor
import app.practicelens.android.camera.CropOcrResult
import app.practicelens.android.camera.NormalizedCropRect
import app.practicelens.android.core.EvaluationState
import app.practicelens.android.core.MonotonicClock
import app.practicelens.android.core.PracticeAttemptState
import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.QuestionMediaJanitor
import app.practicelens.android.core.PracticeReducer
import app.practicelens.android.core.SystemMonotonicClock
import app.practicelens.android.evaluation.GeminiEvaluator
import app.practicelens.android.evaluation.EvaluatorFactory
import app.practicelens.android.interpretation.InterpreterFactory
import app.practicelens.android.interpretation.InterpretationStatus
import app.practicelens.android.interpretation.ModelResponseException
import app.practicelens.android.interpretation.PracticeLensError
import app.practicelens.android.interpretation.QuestionImageInterpreter
import app.practicelens.android.ocr.OcrDraft
import app.practicelens.android.ocr.OcrObservation
import app.practicelens.android.ocr.OcrOptionDraft
import app.practicelens.android.ocr.OcrParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PracticeLensUiState(
    val disclosureAccepted: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val cameraPermissionPermanentlyDenied: Boolean = false,
    val scanning: Boolean = false,
    val scannerError: String? = null,
    val capturedImage: CapturedQuestionMedia? = null,
    val croppedImage: CapturedQuestionMedia? = null,
    val cropOcrLoading: Boolean = false,
    val ocrReview: OcrDraft? = null,
    val interpretationLoading: Boolean = false,
    val interpretationError: PracticeLensError? = null,
    val interpretationMessage: String? = null,
    val evaluationError: PracticeLensError? = null,
    val evaluationMessage: String? = null,
    val attempt: PracticeAttemptState? = null,
    val feedbackMode: String = "Feedback after each answer",
    val autoNextSeconds: Int = 5,
    val autoNextRemainingSeconds: Int = 0,
    val autoNextProgress: Float = 0f,
)

class PracticeLensViewModel(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val interpreter: QuestionImageInterpreter = InterpreterFactory.create(),
    private val evaluator: GeminiEvaluator = EvaluatorFactory.create(),
) : ViewModel() {
    private val reducer = PracticeReducer(clock)
    private val parser = OcrParser()
    private var graceJob: Job? = null
    private var autoNextJob: Job? = null
    private var interpretationJob: Job? = null
    private var evaluationJob: Job? = null
    private var cropOcrJob: Job? = null
    private var captureGeneration: Long = 0
    private val interpretedFingerprints = mutableSetOf<String>()
    private val _uiState = MutableStateFlow(PracticeLensUiState())
    val uiState: StateFlow<PracticeLensUiState> = _uiState

    fun acceptDisclosure() {
        _uiState.update { it.copy(disclosureAccepted = true) }
    }

    fun setCameraPermission(granted: Boolean, permanentlyDenied: Boolean = false) {
        _uiState.update {
            it.copy(
                cameraPermissionGranted = granted,
                cameraPermissionPermanentlyDenied = !granted && permanentlyDenied,
                scanning = granted && it.disclosureAccepted && it.attempt == null && it.ocrReview == null,
            )
        }
    }

    fun resumeScanning() {
        graceJob?.cancel()
        autoNextJob?.cancel()
        interpretationJob?.cancel()
        evaluationJob?.cancel()
        cancelActiveCropOcr()
        captureGeneration++
        retireActiveMedia()
        _uiState.update {
            it.copy(
                scanning = it.cameraPermissionGranted && it.disclosureAccepted,
                scannerError = null,
                capturedImage = null,
                croppedImage = null,
                cropOcrLoading = false,
                ocrReview = null,
                interpretationLoading = false,
                interpretationError = null,
                interpretationMessage = null,
                evaluationError = null,
                evaluationMessage = null,
                attempt = null,
                autoNextRemainingSeconds = 0,
                autoNextProgress = 0f,
            )
        }
    }

    fun acceptOcrText(text: String) {
        openOcrReview(OcrObservation(text))
    }

    fun openCropReview(media: CapturedQuestionMedia) {
        interpretationJob?.cancel()
        cancelActiveCropOcr()
        retireActiveMedia()
        captureGeneration++
        _uiState.update {
            it.copy(
                scanning = false,
                scannerError = null,
                capturedImage = media,
                croppedImage = null,
                cropOcrLoading = false,
                ocrReview = null,
                interpretationLoading = false,
                interpretationError = null,
                interpretationMessage = null,
                attempt = null,
            )
        }
    }

    fun startCropOcr(
        source: CapturedQuestionMedia,
        crop: NormalizedCropRect,
        rotationDegrees: Int,
        processor: CropOcrProcessor,
    ) {
        val requestGeneration = captureGeneration
        val sourceFingerprint = source.sha256
        cropOcrJob?.cancel()
        _uiState.update { state ->
            if (!state.isActiveCropReview(sourceFingerprint, requestGeneration)) return@update state
            state.copy(cropOcrLoading = true, scannerError = null)
        }
        cropOcrJob = viewModelScope.launch {
            var result: CropOcrResult? = null
            try {
                result = processor.process(source, crop, rotationDegrees)
                val applied = applyCropResult(sourceFingerprint, requestGeneration, result)
                if (!applied) processor.delete(result.croppedMedia)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { state ->
                    if (!state.isActiveCropReview(sourceFingerprint, requestGeneration)) return@update state
                    state.copy(cropOcrLoading = false, scannerError = e.message ?: "Crop processing failed.")
                }
            }
        }
    }

    fun openOcrReview(observation: OcrObservation) {
        val draft = parser.parse(observation)
        _uiState.update {
            it.copy(
                scanning = false,
                scannerError = null,
                ocrReview = draft,
                attempt = null,
            )
        }
    }

    fun scannerFailed(message: String) {
        _uiState.update { it.copy(scanning = false, scannerError = message) }
    }

    fun analyzeImageWithAi() {
        val state = _uiState.value
        val image = state.croppedImage ?: return
        if (state.interpretationLoading || image.sha256 in interpretedFingerprints) return
        val optionalOcr = state.ocrReview?.rawText
        interpretationJob?.cancel()
        _uiState.update { it.copy(interpretationLoading = true, interpretationError = null, interpretationMessage = null) }
        interpretationJob = viewModelScope.launch {
            try {
                val interpretation = interpreter.interpret(image, optionalOcr)
                _uiState.update { current ->
                    if (current.croppedImage?.sha256 != image.sha256) return@update current
                    interpretedFingerprints += image.sha256
                    when (interpretation.status) {
                        InterpretationStatus.READY -> current.copy(
                            ocrReview = interpretation.toOcrDraft(optionalOcr.orEmpty()),
                            interpretationLoading = false,
                            interpretationError = null,
                            interpretationMessage = interpretation.warnings.joinToString("\n").ifBlank { null },
                        )
                        InterpretationStatus.RETAKE_REQUIRED,
                        InterpretationStatus.NOT_MCQ -> current.copy(
                            interpretationLoading = false,
                            interpretationError = PracticeLensError.INVALID_MODEL_RESPONSE,
                            interpretationMessage = interpretation.retakeReason ?: "No single readable MCQ was found in the confirmed crop.",
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.update { current ->
                    if (current.croppedImage?.sha256 != image.sha256) return@update current
                    current.copy(interpretationLoading = false, interpretationError = PracticeLensError.TIMEOUT, interpretationMessage = e.message)
                }
            } catch (e: CancellationException) {
                _uiState.update { current ->
                    if (current.croppedImage?.sha256 != image.sha256) return@update current
                    current.copy(interpretationLoading = false, interpretationError = PracticeLensError.CANCELLED, interpretationMessage = "Image analysis was cancelled.")
                }
                throw e
            } catch (e: ModelResponseException) {
                _uiState.update { current ->
                    if (current.croppedImage?.sha256 != image.sha256) return@update current
                    current.copy(interpretationLoading = false, interpretationError = e.error, interpretationMessage = e.message)
                }
            } catch (e: Exception) {
                _uiState.update { current ->
                    if (current.croppedImage?.sha256 != image.sha256) return@update current
                    current.copy(interpretationLoading = false, interpretationError = PracticeLensError.UNKNOWN, interpretationMessage = e.message ?: "Image analysis failed.")
                }
            }
        }
    }

    fun editOcrQuestion(question: String) {
        updateOcrDraft { parser.validate(question, it.options, it.rawText) }
    }

    fun editOcrOptionLabel(index: Int, label: String) {
        updateOcrDraft {
            val options = it.options.toMutableList()
            if (index !in options.indices) return@updateOcrDraft it
            options[index] = options[index].copy(label = label)
            parser.validate(it.question, options, it.rawText)
        }
    }

    fun editOcrOptionText(index: Int, text: String) {
        updateOcrDraft {
            val options = it.options.toMutableList()
            if (index !in options.indices) return@updateOcrDraft it
            options[index] = options[index].copy(text = text)
            parser.validate(it.question, options, it.rawText)
        }
    }

    fun addOcrOption() {
        updateOcrDraft {
            if (it.options.size >= 8) return@updateOcrDraft it
            val used = it.options.map { option -> option.label }.toSet()
            val nextLabel = ('A'..'H').map(Char::toString).firstOrNull { label -> label !in used } ?: ""
            parser.validate(it.question, it.options + OcrOptionDraft(nextLabel, ""), it.rawText)
        }
    }

    fun removeOcrOption(index: Int) {
        updateOcrDraft {
            if (it.options.size <= 2 || index !in it.options.indices) return@updateOcrDraft it
            parser.validate(it.question, it.options.filterIndexed { i, _ -> i != index }, it.rawText)
        }
    }

    fun mergeOcrOptionWithPrevious(index: Int) {
        updateOcrDraft {
            if (index <= 0 || index !in it.options.indices) return@updateOcrDraft it
            val options = it.options.toMutableList()
            val previous = options[index - 1]
            val current = options.removeAt(index)
            options[index - 1] = previous.copy(text = listOf(previous.text, current.text).filter(String::isNotBlank).joinToString("\n"))
            parser.validate(it.question, options, it.rawText)
        }
    }

    fun splitOcrOption(index: Int) {
        updateOcrDraft {
            if (it.options.size >= 8 || index !in it.options.indices) return@updateOcrDraft it
            val option = it.options[index]
            val parts = option.text.lines().map(String::trim).filter(String::isNotBlank)
            if (parts.size < 2) return@updateOcrDraft it
            val used = it.options.map { existing -> existing.label }.toSet()
            val nextLabel = ('A'..'H').map(Char::toString).firstOrNull { label -> label !in used } ?: return@updateOcrDraft it
            val options = it.options.toMutableList()
            options[index] = option.copy(text = parts.first())
            options.add(index + 1, OcrOptionDraft(nextLabel, parts.drop(1).joinToString("\n")))
            parser.validate(it.question, options, it.rawText)
        }
    }

    fun rejectOcr() {
        _uiState.update { it.copy(scanning = false, ocrReview = null) }
    }

    fun confirmOcrDraft() {
        val draft = _uiState.value.ocrReview ?: return
        if (!draft.valid) return
        val media = _uiState.value.croppedImage
        _uiState.update {
            it.copy(
                scanning = false,
                ocrReview = null,
                attempt = PracticeAttemptState(
                    question = draft.toPracticeQuestion(media),
                    createdAtMs = clock.nowMs(),
                ),
            )
        }
    }

    private fun updateOcrDraft(transform: (OcrDraft) -> OcrDraft) {
        _uiState.update { state ->
            val draft = state.ocrReview ?: return@update state
            state.copy(ocrReview = transform(draft))
        }
    }

    fun editPrompt(prompt: String) {
        _uiState.update { state ->
            val attempt = state.attempt ?: return@update state
            state.copy(attempt = attempt.copy(question = attempt.question.copy(prompt = prompt)))
        }
    }

    fun select(optionId: String) {
        graceJob?.cancel()
        autoNextJob?.cancel()
        var snapshot: PracticeAttemptState? = null
        _uiState.update { state ->
            val attempt = state.attempt ?: return@update state
            val next = reducer.selectOption(attempt, optionId)
            snapshot = next
            state.copy(attempt = next)
        }
        val selected = snapshot ?: return
        graceJob = viewModelScope.launch {
            delay(700)
            lockAndEvaluate(selected.question.id, selected.selectionVersion, optionId)
        }
    }

    private fun lockAndEvaluate(questionId: String, selectionVersion: Long, optionId: String) {
        var locked: PracticeAttemptState? = null
        _uiState.update { state ->
            val attempt = state.attempt ?: return@update state
            val next = reducer.tryLockForEvaluation(attempt, questionId, selectionVersion, optionId)
            locked = next.takeIf { it.evaluationState == EvaluationState.IN_FLIGHT }
            state.copy(attempt = next)
        }
        val inFlight = locked ?: return
        val imageFingerprint = inFlight.question.media?.sha256
        evaluationJob?.cancel()
        evaluationJob = viewModelScope.launch {
            try {
                val evaluation = evaluator.evaluate(inFlight.question, inFlight.lockedAttempt!!)
                var completedCurrentAttempt = false
                _uiState.update { state ->
                    val attempt = state.attempt ?: return@update state
                    if (!attempt.matchesEvaluationRequest(questionId, selectionVersion, imageFingerprint)) return@update state
                    completedCurrentAttempt = true
                    state.copy(
                        attempt = reducer.complete(attempt, evaluation),
                        evaluationError = null,
                        evaluationMessage = null,
                    )
                }
                if (completedCurrentAttempt) startAutoNext()
            } catch (e: TimeoutCancellationException) {
                failMatchingEvaluation(questionId, selectionVersion, imageFingerprint, PracticeLensError.TIMEOUT, e.message)
            } catch (e: CancellationException) {
                cancelMatchingEvaluation(questionId, selectionVersion, imageFingerprint)
                throw e
            } catch (e: ModelResponseException) {
                failMatchingEvaluation(questionId, selectionVersion, imageFingerprint, e.error, e.message)
            } catch (e: Exception) {
                failMatchingEvaluation(questionId, selectionVersion, imageFingerprint, PracticeLensError.UNKNOWN, e.message)
            }
        }
    }

    private fun PracticeAttemptState.matchesEvaluationRequest(
        questionId: String,
        selectionVersion: Long,
        imageFingerprint: String?,
    ): Boolean =
        question.id == questionId &&
            this.selectionVersion == selectionVersion &&
            question.media?.sha256 == imageFingerprint

    private fun failMatchingEvaluation(
        questionId: String,
        selectionVersion: Long,
        imageFingerprint: String?,
        error: PracticeLensError,
        message: String?,
    ) {
        _uiState.update { state ->
            val attempt = state.attempt ?: return@update state
            if (!attempt.matchesEvaluationRequest(questionId, selectionVersion, imageFingerprint)) return@update state
            state.copy(
                attempt = reducer.fail(attempt),
                evaluationError = error,
                evaluationMessage = message,
            )
        }
    }

    private fun cancelMatchingEvaluation(
        questionId: String,
        selectionVersion: Long,
        imageFingerprint: String?,
    ) {
        _uiState.update { state ->
            val attempt = state.attempt ?: return@update state
            if (!attempt.matchesEvaluationRequest(questionId, selectionVersion, imageFingerprint)) return@update state
            state.copy(
                attempt = reducer.cancelEvaluation(attempt),
                evaluationError = PracticeLensError.CANCELLED,
                evaluationMessage = "Evaluation was cancelled.",
            )
        }
    }

    private fun startAutoNext() {
        val seconds = _uiState.value.autoNextSeconds
        if (seconds <= 0) return
        autoNextJob?.cancel()
        autoNextJob = viewModelScope.launch {
            for (remaining in seconds downTo 0) {
                _uiState.update {
                    it.copy(
                        autoNextRemainingSeconds = remaining,
                        autoNextProgress = 1f - (remaining.toFloat() / seconds.toFloat()),
                    )
                }
                delay(1000)
            }
            nextNow()
        }
    }

    fun stopAutoNext() {
        autoNextJob?.cancel()
        _uiState.update { it.copy(autoNextRemainingSeconds = 0, autoNextProgress = 0f) }
    }

    fun nextNow() = resumeScanning()

    fun practiceAgainLater() {
        graceJob?.cancel()
        stopAutoNext()
        retireActiveMedia()
        _uiState.update { it.copy(scanning = false) }
    }

    fun retryEvaluation() {
        val attempt = _uiState.value.attempt ?: return
        val selected = attempt.finalSelectedOptionId ?: return
        _uiState.update { it.copy(attempt = reducer.retry(attempt)) }
        select(selected)
    }

    fun retake() = resumeScanning()

    fun onBackgrounded() {
        graceJob?.cancel()
        interpretationJob?.cancel()
        evaluationJob?.cancel()
        cancelActiveCropOcr()
        stopAutoNext()
        _uiState.update { it.copy(scanning = false, cropOcrLoading = false) }
    }

    override fun onCleared() {
        cancelActiveCropOcr()
        retireActiveMedia()
        super.onCleared()
    }

    private fun cancelActiveCropOcr() {
        cropOcrJob?.cancel()
        cropOcrJob = null
    }

    private fun PracticeLensUiState.isActiveCropReview(sourceFingerprint: String, requestGeneration: Long): Boolean =
        capturedImage?.sha256 == sourceFingerprint &&
            captureGeneration == requestGeneration &&
            croppedImage == null &&
            ocrReview == null &&
            attempt == null &&
            !scanning

    private fun applyCropResult(
        sourceFingerprint: String,
        requestGeneration: Long,
        result: CropOcrResult,
    ): Boolean {
        var applied = false
        val draft = result.observation?.let(parser::parse) ?: parser.parse("")
        _uiState.update { state ->
            if (!state.isActiveCropReview(sourceFingerprint, requestGeneration)) return@update state
            applied = true
            state.copy(
                scanning = false,
                scannerError = null,
                croppedImage = result.croppedMedia,
                cropOcrLoading = false,
                ocrReview = draft,
                interpretationLoading = false,
                interpretationError = null,
                interpretationMessage = null,
                attempt = null,
            )
        }
        return applied
    }

    private fun retireActiveMedia() {
        val state = _uiState.value
        QuestionMediaJanitor.delete(state.capturedImage)
        QuestionMediaJanitor.delete(state.croppedImage)
        QuestionMediaJanitor.delete(state.attempt?.question?.media)
    }
}
