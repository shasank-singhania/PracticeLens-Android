package app.practicelens.android

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

object PracticeLensAppCheckInstaller {
    fun providerFactory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
}
