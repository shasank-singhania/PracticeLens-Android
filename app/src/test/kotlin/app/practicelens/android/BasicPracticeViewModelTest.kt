package app.practicelens.android

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BasicPracticeViewModelTest {
    @Test fun `one MCQ produces one answer with option marker and text`() = viewModelRunTest {
        val vm = completedVm("Q1: position 2 | marker B | Photosynthesis")

        val answer = vm.uiState.value.presentationResult!!.answers.single()
        assertEquals(1, answer.questionOrdinal)
        assertEquals(2, answer.optionPosition)
        assertEquals("B", answer.marker)
        assertEquals("Photosynthesis", answer.geminiOptionText)
    }

    @Test fun `two MCQs produce ordered answers`() = viewModelRunTest {
        val raw = "Q1: position 2 | marker B | Photosynthesis\nQ2: position 3 | marker ③ | 42"
        val vm = completedVm(raw)

        assertEquals(listOf(1, 2), vm.uiState.value.presentationResult!!.answers.map { it.questionOrdinal })
        assertEquals(listOf("Photosynthesis", "42"), vm.uiState.value.presentationResult!!.answers.map { it.geminiOptionText })
    }

    @Test fun `lettered and numeric markers work`() {
        val answers = AnswerExtractor.extract("Q1: position 1 | marker A | Alpha\nQ2: position 4 | marker 4 | Delta")

        assertEquals("A", answers[0].marker)
        assertEquals("4", answers[1].marker)
    }

    @Test fun `Roman numeral markers work`() {
        val answer = AnswerExtractor.extract("Q1: position 4 | marker IV | Blue").single()

        assertEquals("IV", answer.marker)
        assertEquals("Blue", answer.geminiOptionText)
    }

    @Test fun `circled Unicode markers work`() {
        val answer = AnswerExtractor.extract("Q1: position 2 | marker ② | 42").single()

        assertEquals("②", answer.marker)
        assertEquals("42", answer.geminiOptionText)
    }

    @Test fun `word markers such as True and False work`() {
        val answer = AnswerExtractor.extract("Q1: position 1 | marker True | The statement is correct").single()

        assertEquals("True", answer.marker)
    }

    @Test fun `symbol markers work when recognized`() {
        val answer = AnswerExtractor.extract("Q1: position 1 | marker ✓ | Correct answer").single()

        assertEquals("✓", answer.marker)
    }

    @Test fun `no-marker options keep position and text`() {
        val answer = AnswerExtractor.extract("Q1: position 1 | marker NONE | None of the above").single()

        assertEquals(1, answer.optionPosition)
        assertNull(answer.marker)
        assertEquals("None of the above", answer.geminiOptionText)
    }

    @Test fun `OCR can recover option text when Gemini returns only a marker`() = viewModelRunTest {
        val ocr = FakeOcr(OcrResult(candidates = listOf(candidate("B Photosynthesis", marker = "B", confidence = 0.95f))))
        val vm = completedVm("Q1: marker B", ocr = ocr)

        val answer = vm.uiState.value.presentationResult!!.answers.single()
        assertEquals("B", answer.marker)
        assertEquals("B Photosynthesis", answer.ocrSuggestion)
        assertEquals(OcrSuggestionLabel.APPROXIMATE_OPTION_TEXT, answer.ocrLabel)
    }

    @Test fun `OCR mismatch never rejects Gemini`() = viewModelRunTest {
        val ocr = FakeOcr(OcrResult(candidates = listOf(candidate("C Respiration", marker = "C"))))
        val vm = completedVm("Q1: position 2 | marker B | Photosynthesis", ocr = ocr)

        val result = vm.uiState.value.presentationResult!!
        assertEquals(BasicState.SHOWING_RESPONSE, vm.uiState.value.state)
        assertEquals("B", result.answers.single().marker)
        assertEquals("Photosynthesis", result.answers.single().geminiOptionText)
        assertEquals("Q1: position 2 | marker B | Photosynthesis", result.rawGeminiText)
    }

    @Test fun `empty OCR still displays Gemini`() = viewModelRunTest {
        val vm = completedVm("Q1: position 2 | marker B | Photosynthesis", ocr = FakeOcr(OcrResult()))

        assertEquals(BasicState.SHOWING_RESPONSE, vm.uiState.value.state)
        assertEquals("Photosynthesis", vm.uiState.value.presentationResult!!.answers.single().geminiOptionText)
    }

    @Test fun `OCR exception still displays Gemini`() = viewModelRunTest {
        val vm = completedVm("Q1: position 2 | marker B | Photosynthesis", ocr = FakeOcr(failure = IllegalStateException("ocr failed")))

        assertEquals(BasicState.SHOWING_RESPONSE, vm.uiState.value.state)
        assertEquals("Photosynthesis", vm.uiState.value.presentationResult!!.answers.single().geminiOptionText)
    }

    @Test fun `weak approximate matching still preserves Gemini as primary`() = viewModelRunTest {
        val ocr = FakeOcr(OcrResult(candidates = listOf(candidate("unrelated text", marker = "Z"))))
        val vm = completedVm("Q1: position 2 | marker B | Photosynthesis", ocr = ocr)

        val answer = vm.uiState.value.presentationResult!!.answers.single()
        assertEquals("B", answer.marker)
        assertEquals("Photosynthesis", answer.geminiOptionText)
    }

    @Test fun `conflicting OCR cannot change Gemini selected option`() = viewModelRunTest {
        val ocr = FakeOcr(OcrResult(candidates = listOf(candidate("C Photosynthesis", marker = "C", confidence = 0.99f))))
        val vm = completedVm("Q1: position 2 | marker B | Photosynthesis", ocr = ocr)

        val answer = vm.uiState.value.presentationResult!!.answers.single()
        assertEquals(2, answer.optionPosition)
        assertEquals("B", answer.marker)
    }

    @Test fun `malformed Gemini formatting still displays raw output`() = viewModelRunTest {
        val raw = "I think the answer is probably B because it names photosynthesis."
        val vm = completedVm(raw)

        assertEquals(raw, vm.uiState.value.presentationResult!!.rawGeminiText)
        assertEquals(raw, vm.uiState.value.presentationResult!!.answers.single().rawGeminiLine)
    }

    @Test fun `exact raw Gemini output is never modified`() = viewModelRunTest {
        val raw = " Q1: position 2 | marker B | Photosynthesis  \n\nQ2: odd *markdown*"
        val vm = completedVm(raw)

        assertEquals(raw, vm.uiState.value.presentationResult!!.rawGeminiText)
    }

    @Test fun `global assignment does not reuse one OCR option for every answer`() = viewModelRunTest {
        val raw = "Q1: marker A\nQ2: marker B"
        val ocr = FakeOcr(OcrResult(candidates = listOf(candidate("A Alpha", marker = "A", confidence = 0.9f))))
        val vm = completedVm(raw, ocr = ocr)

        val answers = vm.uiState.value.presentationResult!!.answers
        assertEquals("A Alpha", answers[0].ocrSuggestion)
        assertNull(answers[1].ocrSuggestion)
    }

    @Test fun `MAX TOKENS with text remains visible`() = viewModelRunTest {
        val vm = completedVm("Q1: position 2 | marker B | Partial", finishReason = "MAX_TOKENS")

        val result = vm.uiState.value.presentationResult!!
        assertEquals(BasicState.SHOWING_RESPONSE, vm.uiState.value.state)
        assertEquals("Q1: position 2 | marker B | Partial", result.rawGeminiText)
        assertEquals("Gemini reached its model output limit; this response may be incomplete.", result.notice)
    }

    @Test fun `MAX TOKENS without text shows visible message`() = viewModelRunTest {
        val vm = completedVm("", finishReason = "MAX_TOKENS")

        assertEquals(BasicState.ERROR, vm.uiState.value.state)
        assertEquals("Gemini reached its model output limit before returning displayable text.", vm.uiState.value.errorMessage)
    }

    @Test fun `429 pauses without retry`() = viewModelRunTest {
        val gateway = FakeGateway(failure = GeminiRequestException(GeminiFailureCategory.QUOTA, "HTTP 429"))
        val vm = vm(gateway)

        runOneCapture(vm)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, gateway.calls)
        assertEquals(BasicState.ERROR, vm.uiState.value.state)
        assertFalse(vm.uiState.value.running)
        assertEquals(
            "Gemini free-tier rate or usage quota was reached. Automatic capture has paused. Wait and tap Start to continue.",
            vm.uiState.value.errorMessage,
        )
    }

    @Test fun `exactly one Gemini call and one OCR operation occur per image`() = viewModelRunTest {
        val gateway = FakeGateway("Q1: marker B")
        val ocr = FakeOcr(OcrResult(candidates = listOf(candidate("B Beta", marker = "B"))))
        val vm = vm(gateway, ocr)

        runOneCapture(vm)
        runCurrent()

        assertEquals(1, gateway.calls)
        assertEquals(1, ocr.calls)
    }

    @Test fun `rotation and recomposition style reads create no duplicate calls`() = viewModelRunTest {
        val gateway = FakeGateway("Q1: marker B")
        val ocr = FakeOcr()
        val vm = vm(gateway, ocr)

        runOneCapture(vm)
        repeat(20) { vm.uiState.value }
        runCurrent()

        assertEquals(1, gateway.calls)
        assertEquals(1, ocr.calls)
    }

    @Test fun `the CameraX second-cycle behavior remains fixed`() = viewModelRunTest {
        val vm = vm(FakeGateway("Q1: position 2 | marker B | Photosynthesis"))

        runOneCapture(vm)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(BasicState.SHOWING_RESPONSE, vm.uiState.value.state)
        assertEquals(1, vm.uiState.value.cameraStartCount)
        assertEquals(1, vm.uiState.value.captureRequestId)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(BasicState.CAPTURING, vm.uiState.value.state)
        assertEquals(1, vm.uiState.value.cameraStartCount)
        assertEquals(2, vm.uiState.value.captureRequestId)
    }

    @Test fun `stop during countdown prevents capture`() = viewModelRunTest {
        val vm = completedVm("Q1: marker B")

        vm.stop()
        advanceTimeBy(8_000)
        runCurrent()

        assertEquals(BasicState.IDLE, vm.uiState.value.state)
        assertEquals(0, vm.uiState.value.captureRequestId)
    }

    @Test fun `backgrounding cancels countdown and prevents capture`() = viewModelRunTest {
        val vm = completedVm("Q1: marker B")

        vm.onBackgrounded()
        advanceTimeBy(8_000)
        runCurrent()

        assertEquals(BasicState.IDLE, vm.uiState.value.state)
        assertEquals(0, vm.uiState.value.captureRequestId)
    }

    @Test fun `temporary files are deleted after success failure and cancellation`() = viewModelRunTest {
        val deleted = mutableListOf<String>()
        val successFile = tempFile("success")
        vm(FakeGateway("Q1: marker B"), deleted = deleted).also {
            runOneCapture(it, successFile)
            runCurrent()
        }
        val failureFile = tempFile("failure")
        vm(FakeGateway(failure = GeminiRequestException(GeminiFailureCategory.NETWORK, "offline")), deleted = deleted).also {
            runOneCapture(it, failureFile)
            runCurrent()
        }
        val cancellationGateway = BlockingGateway()
        val cancelFile = tempFile("cancel")
        vm(cancellationGateway, deleted = deleted).also {
            runOneCapture(it, cancelFile)
            runCurrent()
            it.stop()
            runCurrent()
        }

        assertTrue(deleted.contains(successFile.absolutePath))
        assertTrue(deleted.contains(failureFile.absolutePath))
        assertTrue(deleted.contains(cancelFile.absolutePath))
    }

    private suspend fun TestScope.completedVm(
        raw: String,
        finishReason: String = "STOP",
        ocr: LocalOcrProcessor = FakeOcr(),
    ): BasicPracticeViewModel {
        val vm = vm(FakeGateway(raw, finishReason), ocr)
        runOneCapture(vm)
        runCurrent()
        return vm
    }

    private suspend fun TestScope.runOneCapture(vm: BasicPracticeViewModel, imageFile: File = tempFile()) {
        vm.start()
        vm.onCameraReady()
        advanceTimeBy(1_000)
        runCurrent()
        vm.onImageCaptured(imageFile)
    }

    private fun vm(
        gateway: GeminiGateway = FakeGateway("Q1: marker B"),
        ocr: LocalOcrProcessor = FakeOcr(),
        deleted: MutableList<String> = mutableListOf(),
    ) = BasicPracticeViewModel(gateway, ocr) {
        deleted += it.absolutePath
        true
    }

    private fun candidate(text: String, marker: String? = null, confidence: Float = 0.8f, order: Int = 0) =
        OcrCandidate(text = text, marker = marker, confidence = confidence, readingOrder = order)

    private fun tempFile(name: String = "image"): File =
        File(System.getProperty("java.io.tmpdir"), "practice-lens-$name-${System.nanoTime()}.jpg")

    private class FakeGateway(
        private val response: String = "answer",
        private val finishReason: String = "STOP",
        private val failure: GeminiRequestException? = null,
    ) : GeminiGateway {
        var calls = 0
        override suspend fun answerImage(imageFile: File): GeminiResponse {
            calls++
            failure?.let { throw it }
            return GeminiResponse(
                rawText = response,
                finishReason = finishReason,
                tokenMetadata = GeminiTokenMetadata(promptTokens = 11, outputTokens = 7, thinkingTokens = 0, totalTokens = 18),
            )
        }
    }

    private class FakeOcr(
        private val result: OcrResult = OcrResult(),
        private val failure: Throwable? = null,
    ) : LocalOcrProcessor {
        var calls = 0
        override suspend fun recognize(imageFile: File): OcrResult {
            calls++
            failure?.let { throw it }
            return result
        }
    }

    private class BlockingGateway : GeminiGateway {
        val started = CompletableDeferred<Unit>()
        override suspend fun answerImage(imageFile: File): GeminiResponse {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private fun viewModelRunTest(block: suspend TestScope.() -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher, testBody = block)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
