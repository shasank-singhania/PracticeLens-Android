package app.practicelens.android

import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.MonotonicClock
import app.practicelens.android.interpretation.AnswerBackend
import app.practicelens.android.interpretation.AutomaticAnswer
import app.practicelens.android.interpretation.AutomaticAnswerStatus
import app.practicelens.android.interpretation.InterpretationStatus
import app.practicelens.android.interpretation.InterpretedOption
import app.practicelens.android.interpretation.ModelResponseException
import app.practicelens.android.interpretation.PracticeLensError
import app.practicelens.android.interpretation.QuestionImageGateway
import app.practicelens.android.interpretation.QuestionImageInterpreter
import app.practicelens.android.interpretation.QuestionInterpretation
import app.practicelens.android.interpretation.RoutingQuestionImageInterpreter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnswerBackendRoutingTest {
    @Test fun `real mode routes to Gemini gateway and sends image contract`() = runTest {
        val gateway = FakeGateway(successJson())
        val interpreter = RoutingQuestionImageInterpreter(gateway, FakeSimulatedInterpreter(), { AnswerBackend.GEMINI })

        val result = interpreter.answerFromImage(media(), includeExplanation = true, optionalOcrText = "diagnostic")

        assertEquals(1, gateway.answerCalls)
        assertEquals("file:///tmp/prepared.jpg", gateway.lastImage?.uri)
        assertEquals("image/jpeg", gateway.lastImage?.mimeType)
        assertEquals("diagnostic", gateway.lastOcr)
        assertEquals(AutomaticAnswerStatus.ANSWERED, result.status)
    }

    @Test fun `simulated mode never calls Gemini`() = runTest {
        val gateway = FakeGateway(successJson())
        val interpreter = RoutingQuestionImageInterpreter(gateway, FakeSimulatedInterpreter(), { AnswerBackend.SIMULATED })

        val result = interpreter.answerFromImage(media(), includeExplanation = true)

        assertEquals(0, gateway.answerCalls)
        assertTrue(result.simulated)
    }

    @Test fun `real failure does not silently fall back to simulation`() = runTest {
        val gateway = FakeGateway(throwable = ModelResponseException(PracticeLensError.APP_CHECK_REJECTED, "App Check rejected."))
        val interpreter = RoutingQuestionImageInterpreter(gateway, FakeSimulatedInterpreter(), { AnswerBackend.GEMINI })

        val thrown = captureThrowable { interpreter.answerFromImage(media(), includeExplanation = true) }

        assertTrue(thrown is ModelResponseException)
        assertEquals(1, gateway.answerCalls)
    }

    @Test fun `malformed json unknown option and unreadable no mcq are rejected`() = runTest {
        assertInvalid("{")
        assertInvalid(
            """{"status":"ANSWERED","questionText":"Q","options":[{"position":0,"displayLabel":"A","text":"One"},{"position":1,"displayLabel":"B","text":"Two"}],"selectedOptionIndex":9,"answerText":"Missing","explanation":"Because.","confidence":0.7,"imageReadable":true,"answerable":true}""",
        )
        assertInvalid("""{"status":"UNREADABLE","confidence":0.2,"imageReadable":false,"answerable":false}""")
        assertInvalid("""{"status":"NO_SINGLE_MCQ","confidence":0.2,"imageReadable":true,"answerable":false}""")
    }

    @Test fun `timeout configuration failure and cancellation surface distinctly`() = runTest {
        val timeoutInterpreter = RoutingQuestionImageInterpreter(
            FakeGateway(never = true),
            FakeSimulatedInterpreter(),
            { AnswerBackend.GEMINI },
            timeoutMs = 1,
        )
        assertTrue(captureThrowable { timeoutInterpreter.answerFromImage(media(), true) } is TimeoutCancellationException)

        val missingConfig = RoutingQuestionImageInterpreter(null, FakeSimulatedInterpreter(), { AnswerBackend.GEMINI })
        val configError = captureThrowable { missingConfig.answerFromImage(media(), true) }
        assertTrue(configError is ModelResponseException)
        assertEquals(PracticeLensError.FIREBASE_NOT_CONFIGURED, (configError as ModelResponseException).error)

        val cancellation = CancellationException("cancelled")
        val cancelled = RoutingQuestionImageInterpreter(FakeGateway(throwable = cancellation), FakeSimulatedInterpreter(), { AnswerBackend.GEMINI })
        val cancelledError = captureThrowable { cancelled.answerFromImage(media(), true) }
        assertTrue(cancelledError is CancellationException)
        assertEquals(cancellation.message, cancelledError.message)
    }

    @Test fun `view model duplicate prevention and stale suppression keep one visible current result`() = viewModelRunTest {
        val interpreter = SlowInterpreter()
        val vm = PracticeLensViewModel(TestClock, interpreter).also {
            it.acceptDisclosure()
            it.setCameraPermission(true)
            it.setResultDisplaySeconds(3)
        }
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        val request = vm.uiState.value.automaticCaptureRequestId

        vm.onAutomaticImageCaptured(media("one", "0000000000000000"), request)
        vm.onAutomaticImageCaptured(media("duplicate", "ffffffffffffffff"), request)
        runCurrent()
        vm.stopAutomaticPractice()
        interpreter.completeNext(answer("Stale"))
        runCurrent()

        assertEquals(1, interpreter.answerCalls)
        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertEquals(null, vm.uiState.value.automaticResult)
    }

    private suspend fun assertInvalid(json: String) {
        val interpreter = RoutingQuestionImageInterpreter(FakeGateway(json), FakeSimulatedInterpreter(), { AnswerBackend.GEMINI })
        val thrown = captureThrowable { interpreter.answerFromImage(media(), includeExplanation = true) }
        assertTrue(thrown is ModelResponseException)
        assertEquals(PracticeLensError.INVALID_MODEL_RESPONSE, (thrown as ModelResponseException).error)
    }

    private class FakeGateway(
        private val json: String = successJson(),
        private val throwable: Throwable? = null,
        private val never: Boolean = false,
    ) : QuestionImageGateway {
        override val backend = AnswerBackend.GEMINI
        override val modelId = "test-model"
        var answerCalls = 0
        var lastImage: CapturedQuestionMedia? = null
        var lastOcr: String? = null

        override suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): String = json

        override suspend fun answer(
            image: CapturedQuestionMedia,
            includeExplanation: Boolean,
            optionalOcrText: String?,
        ): String {
            answerCalls++
            lastImage = image
            lastOcr = optionalOcrText
            throwable?.let { throw it }
            if (never) awaitCancellation()
            return json
        }
    }

    private class FakeSimulatedInterpreter : QuestionImageInterpreter {
        override suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation =
            QuestionInterpretation(InterpretationStatus.RETAKE_REQUIRED, "", emptyList(), 0.0)

        override suspend fun answerFromImage(
            image: CapturedQuestionMedia,
            includeExplanation: Boolean,
            optionalOcrText: String?,
        ): AutomaticAnswer =
            answer("Simulated", simulated = true)
    }

    private class SlowInterpreter : QuestionImageInterpreter {
        private val pending = mutableListOf<kotlinx.coroutines.CompletableDeferred<AutomaticAnswer>>()
        var answerCalls = 0

        override suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation =
            QuestionInterpretation(InterpretationStatus.RETAKE_REQUIRED, "", emptyList(), 0.0)

        override suspend fun answerFromImage(
            image: CapturedQuestionMedia,
            includeExplanation: Boolean,
            optionalOcrText: String?,
        ): AutomaticAnswer {
            answerCalls++
            val deferred = kotlinx.coroutines.CompletableDeferred<AutomaticAnswer>()
            pending += deferred
            return deferred.await()
        }

        fun completeNext(answer: AutomaticAnswer) {
            pending.removeAt(0).complete(answer)
        }
    }

    private object TestClock : MonotonicClock {
        override fun nowMs(): Long = 10_000
    }

    private suspend fun captureThrowable(block: suspend () -> Unit): Throwable =
        try {
            block()
            error("Expected failure")
        } catch (t: Throwable) {
            t
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

    private companion object {
        fun successJson(): String =
            """{"status":"ANSWERED","questionText":"Q","options":[{"position":0,"displayLabel":"A","text":"One"},{"position":1,"displayLabel":"B","text":"Two"}],"selectedOptionIndex":1,"answerLabel":"B","answerText":"Two","explanation":"Because two is best.","confidence":0.8,"imageReadable":true,"answerable":true}"""

        fun answer(label: String, simulated: Boolean = false) = AutomaticAnswer(
            status = AutomaticAnswerStatus.ANSWERED,
            questionText = "Q",
            options = listOf(InterpretedOption(0, "A", "One"), InterpretedOption(1, "B", "Two")),
            selectedOptionIndex = 1,
            answerLabel = "B",
            answerText = label,
            explanation = "Because.",
            confidence = 0.8,
            imageReadable = true,
            answerable = true,
            simulated = simulated,
        )

        fun media(sha: String = "sha", dhash: String = "0000000000000000") = CapturedQuestionMedia(
            uri = "file:///tmp/prepared.jpg",
            mimeType = "image/jpeg",
            width = 1200,
            height = 900,
            appliedRotationDegrees = 0,
            sha256 = sha,
            capturedAtMs = 1,
            qualityWarnings = listOf("dhash:$dhash"),
        )
    }
}
