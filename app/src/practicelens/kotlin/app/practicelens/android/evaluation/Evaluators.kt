package app.practicelens.android.evaluation

import app.practicelens.android.BuildConfig
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
            explanation = "Demo feedback: compare the chosen option against the key idea in the prompt before committing.",
            confidence = 0.72,
            uncertain = false,
            warning = "Fake evaluator; no network request was made.",
        )
    }
}

class FirebaseGeminiEvaluator : GeminiEvaluator {
    override suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult {
        error(
            "Firebase AI Logic evaluator is wired for production but requires Firebase configuration, " +
                "App Check enforcement, and model ${BuildConfig.GEMINI_MODEL_ID} before enabling."
        )
    }
}

object EvaluatorFactory {
    fun create(): GeminiEvaluator =
        if (BuildConfig.DEMO_EVALUATOR) FakePracticeEvaluator() else FirebaseGeminiEvaluator()
}
