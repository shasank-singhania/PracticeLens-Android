package app.practicelens.android

import android.app.Application

class PracticeLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Demo builds are fully offline. Production Firebase/App Check initialization will
        // be added with the production Firebase configuration work.
    }
}
