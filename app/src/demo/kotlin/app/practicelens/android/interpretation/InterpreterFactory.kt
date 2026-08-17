package app.practicelens.android.interpretation

object InterpreterFactory {
    fun create(): QuestionImageInterpreter = DemoQuestionImageInterpreter()
}
