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
        // New application id: does not collide with the earlier ru.iskra.companion prototype.
        applicationId = "ru.iskra.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0-mvp"
    }

    signingConfigs {
        // Optional release signing from environment (CI secrets or local shell). Never commit keys.
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
            signingConfig = if (System.getenv("ISKRA_KEYSTORE") != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
    buildFeatures { compose = true }
    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin") }
    }
    lint {
        abortOnError = false
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

    testImplementation(libs.junit)
}
