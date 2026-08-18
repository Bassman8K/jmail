/**
 * Android configuration for :shared, applied only when an Android SDK is detected
 * (see settings.gradle.kts). Kept in a separate script so that machines without the
 * SDK never need the Android Gradle Plugin on the build classpath.
 */
apply(plugin = "com.android.library")

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "com.jmail.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
