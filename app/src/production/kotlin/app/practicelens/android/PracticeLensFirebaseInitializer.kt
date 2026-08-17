package app.practicelens.android

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

object PracticeLensFirebaseInitializer {
    fun initialize(application: Application) {
        val firebaseApp = runCatching { FirebaseApp.initializeApp(application) }.getOrNull()
        if (firebaseApp != null) {
            FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(PracticeLensAppCheckInstaller.providerFactory())
        }
    }
}
