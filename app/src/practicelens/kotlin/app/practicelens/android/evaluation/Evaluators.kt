package app.practicelens.android.evaluation

import app.practicelens.android.core.EvaluationResult
import app.practicelens.android.core.LockedAttempt
import app.practicelens.android.core.PracticeQuestion
import kotlinx.coroutines.delay

interface GeminiEvaluator {
    suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult
}

class FakePracticeEvaluator : GeminiEvaluator {
    override suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult {
        delay(150)
        val correct = question.options.getOrNull(1)?.id ?: question.options.first().id
        return EvaluationResult(
            correctOptionId = correct,
            explanation = "Fake demo test data: compare the chosen option against the key idea in the prompt before committing.",
            confidence = 0.72,
            uncertain = false,
            warning = "Fake evaluator; no network request was made.",
        )
    }
}
