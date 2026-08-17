package app.practicelens.android

import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.LockedAttempt
import app.practicelens.android.core.PracticeOption
import app.practicelens.android.core.PracticeQuestion
import app.practicelens.android.evaluation.EvaluationGateway
import app.practicelens.android.evaluation.EvaluationGatewayRequest
import app.practicelens.android.evaluation.EvaluationResponseValidator
import app.practicelens.android.evaluation.GatewayGeminiEvaluator
import app.practicelens.android.interpretation.InterpretationStatus
import app.practicelens.android.interpretation.ModelResponseException
import app.practicelens.android.interpretation.PracticeLensError
import app.practicelens.android.interpretation.QuestionInterpretationValidator
import app.practicelens.android.interpretation.mapModelGatewayFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpreterAndEvaluatorContractTest {
    @Test fun `interpreter accepts ready ordered unicode options`() {
        val parsed = QuestionInterpretationValidator.parseJson(
            """
            {
              "status":"READY",
              "questionText":"Solve α + β = γ",
              "options":[
                {"position":0,"displayLabel":"A","text":"α = γ - β"},
                {"position":1,"displayLabel":"B","text":"α = γ + β"}
              ],
              "confidence":0.81,
              "warnings":["wrapped option preserved"]
            }
            """.trimIndent(),
        )

        assertEquals(InterpretationStatus.READY, parsed.status)
        assertEquals("α = γ - β", parsed.options.first().text)
    }

    @Test fun `interpreter rejects invalid positions and malformed json`() {
        assertInvalid {
            QuestionInterpretationValidator.parseJson(
                """{"status":"READY","questionText":"Q","options":[{"position":1,"displayLabel":"A","text":"One"},{"position":2,"displayLabel":"B","text":"Two"}],"confidence":0.5}""",
            )
        }
        assertInvalid { QuestionInterpretationValidator.parseJson("{") }
    }

    @Test fun `evaluator validates supplied option ids and uncertainty`() {
        val parsed = EvaluationResponseValidator.parseJson(
            """{"correctOptionId":"option-1","explanation":"Because the confirmed option matches the relation.","confidence":0.73,"uncertain":false}""",
            setOf("option-0", "option-1"),
        )

        assertEquals("option-1", parsed.correctOptionId)
    }

    @Test fun `evaluator rejects unknown option and invalid confidence`() {
        assertInvalid {
            EvaluationResponseValidator.parseJson(
                """{"correctOptionId":"option-x","explanation":"No","confidence":0.5,"uncertain":false}""",
                setOf("option-0"),
            )
        }
        assertInvalid {
            EvaluationResponseValidator.parseJson(
                """{"correctOptionId":"option-0","explanation":"No","confidence":2.0,"uncertain":false}""",
                setOf("option-0"),
            )
        }
    }

    @Test fun `gateway evaluator retries one transient failure and supplies image`() = runTest {
        val gateway = RetryingGateway()
        val evaluator = GatewayGeminiEvaluator(gateway, timeoutMs = 5_000)
        val question = PracticeQuestion(
            prompt = "Q",
            options = listOf(PracticeOption("option-0", "One"), PracticeOption("option-1", "Two")),
            media = CapturedQuestionMedia("file:///tmp/crop.jpg", "image/jpeg", 10, 10, 0, "sha", 1),
            ocrText = "Q\nA. One\nB. Two",
        )

        val result = evaluator.evaluate(question, LockedAttempt(question.id, "option-0", 1, 10))

        assertEquals("option-1", result.correctOptionId)
        assertEquals(2, gateway.calls)
        assertEquals("sha", gateway.lastRequest?.imageSha256)
    }

    @Test fun `gateway evaluator propagates coroutine cancellation unchanged`() = runTest {
        val cancellation = CancellationException("caller cancelled")
        val evaluator = GatewayGeminiEvaluator(ThrowingGateway(cancellation), timeoutMs = 5_000)

        val thrown = captureThrowable {
            evaluator.evaluate(questionWithMedia(), LockedAttempt("q1", "option-0", 1, 10))
        }

        assertTrue(thrown is CancellationException)
        assertEquals(cancellation.message, thrown.message)
    }

    @Test fun `gateway evaluator propagates timeout cancellation unchanged`() = runTest {
        val evaluator = GatewayGeminiEvaluator(
            object : EvaluationGateway {
                override suspend fun evaluate(request: EvaluationGatewayRequest): String =
                    withTimeout(1) { awaitCancellation() }
            },
            timeoutMs = 5_000,
        )

        val thrown = captureThrowable {
            evaluator.evaluate(questionWithMedia(), LockedAttempt("q1", "option-0", 1, 10))
        }

        assertTrue(thrown is TimeoutCancellationException)
    }

    @Test fun `firebase like failures still map to typed model errors`() {
        val mapped = mapModelGatewayFailure(RuntimeException("RESOURCE_EXHAUSTED quota exceeded"), "Firebase AI evaluation")

        assertEquals(PracticeLensError.QUOTA_EXCEEDED, mapped.error)
    }

    @Test fun `gateway failure mapper rethrows coroutine cancellation unchanged`() {
        val cancellation = CancellationException("caller cancelled")

        val thrown = try {
            mapModelGatewayFailure(cancellation, "Firebase AI evaluation")
            error("Expected cancellation")
        } catch (t: Throwable) {
            t
        }

        assertSame(cancellation, thrown)
    }

    private class RetryingGateway : EvaluationGateway {
        var calls = 0
        var lastRequest: EvaluationGatewayRequest? = null

        override suspend fun evaluate(request: EvaluationGatewayRequest): String {
            calls++
            lastRequest = request
            if (calls == 1) throw ModelResponseException(PracticeLensError.TEMPORARY_SERVICE_FAILURE, "Temporary")
            return """{"correctOptionId":"option-1","explanation":"Validated JSON only.","confidence":0.6,"uncertain":false}"""
        }
    }

    private class ThrowingGateway(private val throwable: Throwable) : EvaluationGateway {
        override suspend fun evaluate(request: EvaluationGatewayRequest): String {
            throw throwable
        }
    }

    private fun questionWithMedia() = PracticeQuestion(
        id = "q1",
        prompt = "Q",
        options = listOf(PracticeOption("option-0", "One"), PracticeOption("option-1", "Two")),
        media = CapturedQuestionMedia("file:///tmp/crop.jpg", "image/jpeg", 10, 10, 0, "sha", 1),
    )

    private suspend fun captureThrowable(block: suspend () -> Unit): Throwable =
        try {
            block()
            error("Expected failure")
        } catch (t: Throwable) {
            t
        }

    private fun assertInvalid(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is ModelResponseException)
        assertEquals(PracticeLensError.INVALID_MODEL_RESPONSE, (error as ModelResponseException).error)
    }
}
