package app.practicelens.android

import android.app.Application
import com.google.firebase.FirebaseApp

class PracticeLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEMO_EVALUATOR) {
            FirebaseApp.initializeApp(this)
        }
    }
}
