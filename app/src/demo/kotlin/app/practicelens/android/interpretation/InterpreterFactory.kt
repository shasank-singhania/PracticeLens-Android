package app.practicelens.android.interpretation

object InterpreterFactory {
    fun create(selectedBackend: () -> AnswerBackend): QuestionImageInterpreter = DemoQuestionImageInterpreter()
}
