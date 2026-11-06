import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// --- The model on the phone (llama.cpp) ---
// llama.cpp comes from the llama-cpp-python source package on PyPI: a fixed version with a known SHA-256 (the same
// sources the core test builds on a computer). Downloaded once into build/, never committed. Only the CPU part is
// unpacked: no GPU backends, models, docs, tools or tests.
val llamaVersion = "0.3.36"
val llamaArchiveUrl = "https://files.pythonhosted.org/packages/ec/e9/e7de2b0463ea3ffbf0ede6cb21b58c1258a8f6521aae45ca773a59fe7cf3/llama_cpp_python-$llamaVersion.tar.gz"
val llamaArchiveSha256 = "832db0699007f1be95a7e41ef12e88926b02ba836461e36a36372db2760c1a2e"
val llamaDir: File = rootProject.layout.buildDirectory.dir("llama.cpp-$llamaVersion").get().asFile
val llamaSkip = listOf("models", "docs", "media", "examples", "tools", "tests", "gguf-py", "scripts", "pocs", "benches", "app",
    "skills", "ci", "requirements", "grammars", ".github", ".devops")
val llamaGpu = listOf("cuda", "vulkan", "sycl", "cann", "hip", "musa", "opencl", "openvino", "metal", "webgpu", "zdnn", "zendnn",
    "virtgpu", "hexagon", "et")

val fetchLlamaCpp by tasks.registering {
    description = "Downloads and checks the llama.cpp sources for the on-phone model."
    val marker = File(llamaDir, ".complete")
    outputs.file(marker)
    doLast {
        if (marker.isFile && marker.readText() == llamaArchiveSha256) return@doLast
        val archive = File(temporaryDir, "llama_cpp_python-$llamaVersion.tar.gz")
        fun sha(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!archive.isFile || sha(archive) != llamaArchiveSha256) {
            logger.lifecycle("Downloading llama.cpp sources (llama-cpp-python $llamaVersion)")
            uri(llamaArchiveUrl).toURL().openStream().use { i -> archive.outputStream().use { o -> i.copyTo(o) } }
        }
        check(sha(archive) == llamaArchiveSha256) { "llama.cpp sources: SHA-256 mismatch" }
        llamaDir.deleteRecursively()
        copy {
            from(tarTree(resources.gzip(archive)))
            include("*/vendor/llama.cpp/**")
            llamaSkip.forEach { exclude("*/vendor/llama.cpp/$it/**") }
            llamaGpu.forEach { exclude("*/vendor/llama.cpp/ggml/src/ggml-$it/**") }
            eachFile { path = path.substringAfter("/vendor/llama.cpp/") }
            includeEmptyDirs = false
            into(llamaDir)
        }
        marker.writeText(llamaArchiveSha256)
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchLlamaCpp)
}

android {
    namespace = "ru.iskra.app"
    compileSdk = 36

    defaultConfig {
        // Release: ru.iskra.app. Debug: ru.iskra.app.debug (suffix below), so both can be installed side by side.
        applicationId = "ru.iskra.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "0.12.8"
        // Shown on the diagnostics screen: which CI run and commit this APK came from.
        buildConfigField("String", "CI_BUILD", "\"${System.getenv("GITHUB_RUN_NUMBER") ?: "local"}\"")
        buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: ""}\"")
        // Redmi Note 14 and nearly all current phones are arm64; armeabi-v7a kept for old devices.
        // Dropping x86 keeps the speech engine from adding ~20 MB. The CI emulator check adds x86/x86_64
        // with -Piskra.abis=... (never in the published APKs).
        val abis = (project.findProperty("iskra.abis") as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "armeabi-v7a")
        ndk { abiFilters += abis }
        // The on-phone model is built for 64-bit ARM phones (and x86_64 for the emulator check); elsewhere the app
        // simply doesn't offer it. Always optimized: a debug build of llama.cpp would be far too slow to talk to.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DLLAMA_SRC=${llamaDir.absolutePath}", "-DCMAKE_BUILD_TYPE=Release")
                abiFilters += abis.filter { it == "arm64-v8a" || it == "x86_64" }
            }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
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
