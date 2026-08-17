package app.practicelens.android

import android.app.Application

class PracticeLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PracticeLensFirebaseInitializer.initialize(this)
    }
}
