import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

val androidEnabled = project.extra["jmail.androidEnabled"] as Boolean
val iosEnabled = project.extra["jmail.iosEnabled"] as Boolean

if (androidEnabled) {
    apply(plugin = "com.android.library")

    // Configured here rather than in a script applied with `apply(from = …)`: such a script
    // is compiled against its own classpath, not the one it is applied into, so the Android
    // Gradle Plugin's types are not visible inside it.
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
}

kotlin {
    jvmToolchain(17)

    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false } // headless browser is not assumed to exist on dev machines
        }
    }

    if (androidEnabled) {
        androidTarget {
            compilations.all {
                compileTaskProvider.configure {
                    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
            instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }

    if (iosEnabled) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.auth)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
                implementation(libs.kotlinx.browser)
            }
        }

        if (androidEnabled) {
            val androidMain by getting {
                dependencies {
                    implementation(libs.ktor.client.okhttp)
                    implementation(libs.androidx.security.crypto)
                    implementation(libs.androidx.core.ktx)
                }
            }
        }

        if (iosEnabled) {
            val iosMain by getting {
                dependencies {
                    implementation(libs.ktor.client.darwin)
                }
            }
        }
    }
}

// Kover instruments the JVM ("desktop") target automatically; its report feeds the
// aggregated total report configured in the root build.
