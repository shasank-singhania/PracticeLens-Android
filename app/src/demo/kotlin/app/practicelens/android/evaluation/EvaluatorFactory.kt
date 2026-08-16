package app.practicelens.android.evaluation

object EvaluatorFactory {
    fun create(): GeminiEvaluator = FakePracticeEvaluator()
}
