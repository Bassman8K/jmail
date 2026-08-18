/**
 * Android application configuration for :composeApp, applied only when an Android SDK is
 * detected (see settings.gradle.kts).
 *
 * Produces:
 *   ./gradlew :composeApp:assembleRelease   -> APK  (sideload / CI artifact)
 *   ./gradlew :composeApp:bundleRelease     -> AAB  (Play Store upload)
 */
apply(plugin = "com.android.application")

extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
    namespace = "com.jmail.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jmail.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the app looks for the backend. Overridden per build so a debug install can
        // point at a laptop on the LAN (10.0.2.2 is the host as seen from the emulator).
        buildConfigField(
            "String",
            "JMAIL_API_URL",
            "\"${System.getenv("JMAIL_API_URL") ?: "http://10.0.2.2:8090"}\"",
        )
    }

    // A checked-in debug keystore is deliberately avoided; release builds are signed from
    // environment variables so CI can publish without secrets living in the repository.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("JMAIL_ANDROID_KEYSTORE")
            if (storePath != null && File(storePath).exists()) {
                storeFile = File(storePath)
                storePassword = System.getenv("JMAIL_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JMAIL_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("JMAIL_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro"),
            )
            if (System.getenv("JMAIL_ANDROID_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Instrumented Jetpack Compose tests run on a device or emulator:
    //   ./gradlew :composeApp:connectedAndroidTest
    // The same UI test sources also run on the JVM through :composeApp:desktopTest, so a
    // contributor without an emulator still exercises every screen.
    dependencies {
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.compose.ui.test.junit4)
        androidTestImplementation(libs.androidx.test.junit)
        androidTestImplementation(libs.androidx.espresso.core)
        debugImplementation(libs.androidx.compose.ui.test.manifest)
        // The Compose layout inspector and @Preview rendering, debug builds only.
        debugImplementation(libs.androidx.compose.ui.tooling)
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += "GradleDependency"
    }
}
