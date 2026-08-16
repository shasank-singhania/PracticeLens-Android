package app.practicelens.android.evaluation

import app.practicelens.android.BuildConfig
import app.practicelens.android.core.EvaluationResult
import app.practicelens.android.core.LockedAttempt
import app.practicelens.android.core.PracticeQuestion

class FirebaseGeminiEvaluator : GeminiEvaluator {
    override suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult {
        error(
            "Firebase AI Logic evaluator is wired for production but requires Firebase configuration, " +
                "App Check enforcement, and model ${BuildConfig.GEMINI_MODEL_ID} before enabling."
        )
    }
}

object EvaluatorFactory {
    fun create(): GeminiEvaluator = FirebaseGeminiEvaluator()
}
