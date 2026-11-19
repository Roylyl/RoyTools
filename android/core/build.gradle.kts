plugins {
    kotlin("android")
    id("com.android.library")
    id("kotlinx-serialization")
}

// Bundle the arm64 core, including per-network proxy usage counters.
// scripts/build-native.ps1 rebuilds both native libraries from the included sources.
dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.core)
    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)
    testImplementation("junit:junit:4.13.2")
}
