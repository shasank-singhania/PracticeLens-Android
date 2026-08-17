package app.practicelens.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FirebaseVariantStructureTest {
    @Test fun `demo debug application id avoids the stale demo signing identity`() {
        val build = repoFile("app/build.gradle.kts").readText()
        assertTrue(build.contains("applicationId = \"app.practicelens.android\""))
        assertTrue(build.contains("applicationIdSuffix = \".autopractice\""))
        assertFalse(build.contains("applicationIdSuffix = \".demo\""))
        assertTrue(build.contains("applicationIdSuffix = \".debug\""))
    }

    @Test fun `google services plugin is conditional on app config file`() {
        val build = repoFile("app/build.gradle.kts").readText()
        val rootBuild = repoFile("build.gradle.kts").readText()
        assertTrue(rootBuild.contains("alias(libs.plugins.google.services) apply false"))
        assertTrue(build.contains("file(\"google-services.json\").exists()"))
        assertTrue(build.contains("apply(plugin = \"com.google.gms.google-services\")"))
    }

    @Test fun `demo google services tasks are disabled without disabling production tasks`() {
        val build = repoFile("app/build.gradle.kts").readText()
        assertTrue(build.contains("it.name.startsWith(\"processDemo\") && it.name.endsWith(\"GoogleServices\")"))
        assertTrue(build.contains("enabled = false"))
        assertFalse(build.contains("processProductionDebugGoogleServices") && build.contains("enabled = false"))
        assertFalse(build.contains("processProductionReleaseGoogleServices") && build.contains("enabled = false"))
    }

    @Test fun `missing Firebase options do not install App Check during startup`() {
        val initializer = repoFile("app/src/production/kotlin/app/practicelens/android/PracticeLensFirebaseInitializer.kt").readText()
        assertTrue(initializer.contains("FirebaseApp.initializeApp(application)"))
        assertTrue(initializer.contains("if (firebaseApp != null)"))
        assertTrue(initializer.contains("FirebaseAppCheck.getInstance(firebaseApp)"))
        assertFalse(initializer.contains("FirebaseAppCheck.getInstance().installAppCheckProviderFactory"))
    }

    @Test fun `production debug uses debug App Check provider without reflection fallback`() {
        val debug = repoFile("app/src/productionDebug/kotlin/app/practicelens/android/PracticeLensAppCheckInstaller.kt").readText()
        val production = repoFile("app/src/production/kotlin/app/practicelens/android/PracticeLensFirebaseInitializer.kt").readText()
        assertTrue(debug.contains("DebugAppCheckProviderFactory"))
        assertFalse(production.contains("Class.forName"))
        assertFalse(production.contains("PlayIntegrityAppCheckProviderFactory.getInstance()"))
    }

    @Test fun `production release uses Play Integrity and not debug provider`() {
        val release = repoFile("app/src/productionRelease/kotlin/app/practicelens/android/PracticeLensAppCheckInstaller.kt").readText()
        assertTrue(release.contains("PlayIntegrityAppCheckProviderFactory"))
        assertFalse(release.contains("DebugAppCheckProviderFactory"))
    }

    @Test fun `demo initializer remains offline no op`() {
        val demo = repoFile("app/src/demo/kotlin/app/practicelens/android/PracticeLensFirebaseInitializer.kt").readText()
        assertTrue(demo.contains("fun initialize(application: Application) = Unit"))
        assertFalse(demo.contains("FirebaseApp"))
        assertFalse(demo.contains("FirebaseAppCheck"))
    }

    private fun repoFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("Missing user.dir")).canonicalFile
        while (dir.name != "PracticeLens-Android" && dir.parentFile != null) dir = dir.parentFile!!.canonicalFile
        return File(dir, path)
    }
}
