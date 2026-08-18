import java.util.Properties

rootProject.name = "JMail"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(":shared")
include(":composeApp")
include(":backend")

// ---------------------------------------------------------------------------
// Toolchain auto-detection.
//
// JMail always builds: backend (JVM), shared (JVM + Wasm), desktop app, web app.
// Android and iOS targets are additionally enabled when the required SDKs are
// present, so that `./run.sh` succeeds on a machine without them instead of
// failing on a missing Android SDK or Xcode installation.
//
// Override explicitly with -Pjmail.android.enabled=true|false (same for ios).
// ---------------------------------------------------------------------------

fun localProperty(key: String): String? {
    val file = rootDir.resolve("local.properties")
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use(::load) }.getProperty(key)
}

fun flag(name: String, autoDetect: () -> Boolean): Boolean =
    when (val explicit = settings.providers.gradleProperty(name).orNull?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        null, "", "auto" -> autoDetect()
        else -> error("Property $name must be one of: true, false, auto (was '$explicit')")
    }

val androidSdkDir: String? = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: localProperty("sdk.dir")

val androidEnabled = flag("jmail.android.enabled") {
    androidSdkDir != null && File(androidSdkDir).isDirectory
}

val iosEnabled = flag("jmail.ios.enabled") {
    System.getProperty("os.name").contains("Mac") &&
        File("/Applications/Xcode.app").exists()
}

gradle.beforeProject {
    extra["jmail.androidEnabled"] = androidEnabled
    extra["jmail.iosEnabled"] = iosEnabled
}

gradle.projectsEvaluated {
    logger.lifecycle(
        "JMail targets -> jvm/backend: on, desktop: on, web(wasm): on, " +
            "android: ${if (androidEnabled) "on" else "off (no Android SDK)"}, " +
            "ios: ${if (iosEnabled) "on" else "off (no Xcode)"}"
    )
}
