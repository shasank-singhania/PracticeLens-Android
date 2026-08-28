import com.android.build.api.variant.BuildConfigField
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kover)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

tasks.matching {
    it.name.startsWith("processDemo") && it.name.endsWith("GoogleServices")
}.configureEach {
    enabled = false
}

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use(::load)
}
fun secret(name: String): String =
    providers.environmentVariable(name).orNull ?: localProps.getProperty(name) ?: ""
fun prop(name: String, fallback: String = ""): String =
    localProps.getProperty(name) ?: providers.environmentVariable(name).orNull ?: fallback

android {
    namespace = "app.practicelens.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.practicelens.android"
        minSdk = 26
        targetSdk = 35
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_MODEL_ID", "\"${prop("PRACTICELENS_GEMINI_MODEL_ID", "gemini-3.6-flash")}\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("demo") {
            dimension = "distribution"
            applicationIdSuffix = ".autopractice"
            versionNameSuffix = "-demo"
            buildConfigField("Boolean", "DEMO_EVALUATOR", "true")
            buildConfigField("Boolean", "DRIVE_ENABLED", "false")
            buildConfigField("Boolean", "ANSWER_BACKEND_GEMINI_ENABLED", "false")
            buildConfigField("Boolean", "ANSWER_BACKEND_SIMULATED_ENABLED", "true")
            buildConfigField("String", "DEFAULT_ANSWER_BACKEND", "\"SIMULATED\"")
        }
        create("production") {
            dimension = "distribution"
            buildConfigField("Boolean", "DEMO_EVALUATOR", "false")
            buildConfigField("Boolean", "DRIVE_ENABLED", "true")
            buildConfigField("Boolean", "ANSWER_BACKEND_GEMINI_ENABLED", "true")
            buildConfigField("Boolean", "ANSWER_BACKEND_SIMULATED_ENABLED", "false")
            buildConfigField("String", "DEFAULT_ANSWER_BACKEND", "\"GEMINI\"")
        }
    }

    signingConfigs {
        create("productionRelease") {
            val storeFilePath = prop("PRACTICELENS_KEYSTORE_FILE")
            if (storeFilePath.isNotBlank()) storeFile = rootProject.file(storeFilePath)
            keyAlias = secret("PRACTICELENS_KEY_ALIAS")
            storePassword = secret("PRACTICELENS_STORE_PASSWORD")
            keyPassword = secret("PRACTICELENS_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("productionRelease")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/practicelens/kotlin")
            res.srcDirs("src/main/res")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
        getByName("test") {
            java.srcDirs("src/practicelensTest/kotlin")
        }
        getByName("demo") {
            java.srcDirs("src/demo/kotlin")
        }
        getByName("production") {
            java.srcDirs("src/production/kotlin")
        }
        maybeCreate("productionDebug").apply {
            java.srcDirs("src/productionDebug/kotlin")
        }
        maybeCreate("productionRelease").apply {
            java.srcDirs("src/productionRelease/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.all {
            it.maxParallelForks = 1
            it.maxHeapSize = "256m"
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

androidComponents {
    onVariants(selector().withFlavor("distribution" to "production").withBuildType("debug")) { variant ->
        variant.buildConfigFields.put(
            "ANSWER_BACKEND_SIMULATED_ENABLED",
            BuildConfigField("Boolean", "true", "Allow offline simulation only in productionDebug."),
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.activity.ktx)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.test.manifest)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.gson)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    add("productionImplementation", libs.work.runtime)
    add("productionImplementation", libs.okhttp)
    add("productionImplementation", platform(libs.firebase.bom))
    add("productionImplementation", libs.firebase.ai)
    add("productionImplementation", libs.play.services.auth)
    add("productionDebugImplementation", libs.firebase.appcheck.debug)
    add("productionReleaseImplementation", libs.firebase.appcheck.playintegrity)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
