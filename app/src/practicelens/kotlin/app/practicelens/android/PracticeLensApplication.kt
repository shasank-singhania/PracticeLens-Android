package app.practicelens.android

import android.app.Application
import app.practicelens.android.camera.AndroidQuestionMediaProcessor

class PracticeLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidQuestionMediaProcessor(this).cleanOrphanedCacheFiles()
        PracticeLensFirebaseInitializer.initialize(this)
    }
}
