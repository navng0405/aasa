import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val gemmaBaseUrl = providers.gradleProperty("aasaGemmaBaseUrl")
    .orElse(providers.environmentVariable("AASA_GEMMA_BASE_URL"))
    .orElse("http://127.0.0.1:8000/")
    .map { rawUrl ->
        if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
    }
val enableGemmaBridge = providers.gradleProperty("aasaEnableGemmaBridge")
    .orElse(providers.environmentVariable("AASA_ENABLE_GEMMA_BRIDGE"))
    .orElse("false")
    .map { it.equals("true", ignoreCase = true) }

android {
    namespace = "com.aasa.eldercare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aasa.eldercare"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "AASA_GEMMA_BASE_URL", "\"${gemmaBaseUrl.get()}\"")
        buildConfigField("boolean", "AASA_ENABLE_GEMMA_BRIDGE", enableGemmaBridge.get().toString())
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")

    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Phase 9: on-device Gemma 4 via LiteRT-LM.
    // The model file (gemma-4-E2B-it.litertlm) is NOT bundled — it is side-loaded
    // to /sdcard/Android/data/com.aasa.eldercare/files/models/ via `adb push`.
    // See AASA_PROJECT_OVERVIEW.md §21.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    // Phase 10: Android Health Connect — wearable-ready (Fitbit Air, Pixel Watch, etc.).
    // Read-only access to sleep / heart rate / steps on-device. No cloud APIs.
    // See AASA_PROJECT_OVERVIEW.md §22.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
