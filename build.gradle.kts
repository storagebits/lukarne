plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied (AGP 9 has built-in Kotlin); declared only to pin the Kotlin Gradle Plugin version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
