package app.practicelens.android

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

object PracticeLensAppCheckInstaller {
    fun providerFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
}
