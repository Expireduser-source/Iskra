import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.iskra.app"
    compileSdk = 35

    defaultConfig {
        // Release: ru.iskra.app. Debug: ru.iskra.app.debug (suffix below), so both can be installed side by side.
        applicationId = "ru.iskra.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.9.1"
        // Shown on the diagnostics screen: which CI run and commit this APK came from.
        buildConfigField("String", "CI_BUILD", "\"${System.getenv("GITHUB_RUN_NUMBER") ?: "local"}\"")
        buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: ""}\"")
        // Redmi Note 14 and nearly all current phones are arm64; armeabi-v7a kept for old devices.
        // Dropping x86 keeps the speech engine from adding ~20 MB.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Fixed debug key committed to the repo so every CI build can be installed over the
        // previous one without losing data. Debug-only; not a secret.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release signing from the environment only (CI secrets on a v* tag, or a local shell).
        // The key is never in the repo. Without it the release APK stays unsigned — never debug-signed.
        create("release") {
            val path = System.getenv("ISKRA_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("ISKRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ISKRA_KEY_ALIAS")
                keyPassword = System.getenv("ISKRA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("ISKRA_KEYSTORE") != null) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin") }
    }
    lint {
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint-results-debug.txt")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    // Offline speech recognition for always-on listening (Apache-2.0). Brings JNA (AAR) with it.
    implementation(libs.vosk.android)

    testImplementation(libs.junit)
}
