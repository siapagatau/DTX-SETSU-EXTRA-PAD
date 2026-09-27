plugins {
    // AGP 9.1.0 - versi minimum yang resmi mendukung compileSdk/targetSdk 36
    // (Android 16). AGP 8.x mentok di compileSdk 35.
    id("com.android.application") version "9.1.0" apply false
    // Kotlin 2.2.10 - syarat minimum Kotlin Gradle Plugin untuk AGP 9.x.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // Sejak Kotlin 2.0+, Compose compiler dipisah jadi plugin sendiri
    // (bukan lagi composeOptions.kotlinCompilerExtensionVersion di app module).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
