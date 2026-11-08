plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.jupiterlabs.ghostdrive"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jupiterlabs.ghostdrive"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2-test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/**/OSGI-INF/MANIFEST.MF") }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.mapsforge:mapsforge-map-android:0.24.1")
    implementation("org.mapsforge:mapsforge-map-reader:0.24.1")
    implementation("org.mapsforge:mapsforge-themes:0.24.1")
    // On-device AI inference via Android AICore (Gemini Nano on S25 Ultra Hexagon NPU & Pixel 11 Pro XL Tensor TPU)
    implementation("com.google.mlkit:genai-prompt:1.0.0-alpha1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
