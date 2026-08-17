package app.practicelens.android.core

object QuestionMediaJanitor {
    @Volatile private var deleter: (CapturedQuestionMedia) -> Unit = {}

    fun install(deleter: (CapturedQuestionMedia) -> Unit) {
        this.deleter = deleter
    }

    fun clear() {
        deleter = {}
    }

    fun delete(media: CapturedQuestionMedia?) {
        media ?: return
        runCatching { deleter(media) }
    }
}
