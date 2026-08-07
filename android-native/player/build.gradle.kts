plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.mdblisthub.tv.player"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    buildFeatures { compose = true }
}

dependencies {
    api(projects.core.model)
    // The only module that sees mpv. Everything upstream talks to the
    // engine through `PlaybackController`, so swapping the backend never
    // reaches the screens.
    //
    // No Maven coordinate exists for this — Stremio's own mpv wrapper
    // (github.com/jarnedemeulemeester/libmpv-android, the same one their
    // GitHub org actively forks and builds from) only ships a GitHub
    // Release AAR, not a published artifact. Vendored here rather than
    // resolved because JitPack cannot complete this repo's NDK cross-compile
    // build within its time limit — every version there shows `"Error"`.
    api(files("libs/libmpv-release.aar"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
}
