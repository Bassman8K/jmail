import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.kover)
}

val androidEnabled = project.extra["jmail.androidEnabled"] as Boolean
val iosEnabled = project.extra["jmail.iosEnabled"] as Boolean

val appVersion = "1.0.0"

if (androidEnabled) {
    apply(plugin = "com.android.application")

    // Configured here rather than in a script applied with `apply(from = …)`: such a script is
    // compiled against its own classpath, not the one it is applied into, so the Android Gradle
    // Plugin's types are not visible inside it.
    //
    // Produces:
    //   ./gradlew :composeApp:assembleRelease   -> APK  (sideload / CI artifact)
    //   ./gradlew :composeApp:bundleRelease     -> AAB  (Play Store upload)
    extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
        namespace = "com.jmail.app"
        compileSdk = 35

        defaultConfig {
            applicationId = "com.jmail.app"
            minSdk = 26
            targetSdk = 35
            versionCode = 1
            versionName = "1.0.0"

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
        // The config is only *created* when a keystore is actually there -- an empty one is
        // rejected at packaging time with "missing required property storeFile", so a public
        // build with no signing secrets has to produce an unsigned APK instead.
        val releaseKeystore = System.getenv("JMAIL_ANDROID_KEYSTORE")
            ?.let(::File)
            ?.takeIf { it.exists() }

        if (releaseKeystore != null) {
            signingConfigs {
                create("release") {
                    storeFile = releaseKeystore
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
                if (releaseKeystore != null) {
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

        testOptions {
            unitTests.all { test ->
                // Compose UI tests need a real Android runtime -- a device, an emulator, or
                // Robolectric. Against the stubbed android.jar that unit tests compile with,
                // `android.os.Build.FINGERPRINT` is null and every `runComposeUiTest` dies
                // there before reaching an assertion. The very same sources do run, for real,
                // on the JVM through `:composeApp:desktopTest`, so nothing goes uncovered.
                test.exclude("**/*UiTest*")
            }
        }
    }
}

kotlin {
    jvmToolchain(17)

    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "jmail"
        browser {
            commonWebpackConfig {
                outputFileName = "jmail.js"
            }
            testTask { enabled = false }
        }
        binaries.executable()
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
        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "JMailApp"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            // Drives real composables in tests: the same Compose testing API as Jetpack
            // Compose's createComposeRule, available on every target.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            // Lets UI tests drive screens through a real store with a stubbed HTTP layer,
            // so the assertions cover the wiring as well as the rendering.
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }

        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        if (androidEnabled) {
            val androidMain by getting {
                dependencies {
                    implementation(libs.androidx.activity.compose)
                    implementation(libs.androidx.core.ktx)
                    implementation(compose.preview)

                    // Jetpack Compose artifacts from Google, aligned by the BOM. The UI
                    // source is unchanged — androidx.compose.* resolves to these on Android.
                    implementation(project.dependencies.platform(libs.androidx.compose.bom))
                    implementation(libs.androidx.compose.ui.tooling.preview)
                }
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.jmail.app.MainKt"

        jvmArgs += listOf(
            "-Dapple.awt.application.appearance=system",
            "-Dsun.java2d.metal=true",
        )

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg, // macOS installer
                TargetFormat.Pkg, // macOS package
                TargetFormat.Msi, // Windows installer
                TargetFormat.Exe, // Windows portable installer
                TargetFormat.Deb, // Debian/Ubuntu
                TargetFormat.Rpm, // Fedora/RHEL
            )
            packageName = "JMail"
            packageVersion = appVersion
            description = "JMail — a fast, unified mail client for every account you own."
            copyright = "© 2026 JMail. All rights reserved."
            vendor = "JMail"
            licenseFile.set(rootProject.file("LICENSE"))

            // Where the installers are written. Overridable because macOS refuses to sign a
            // bundle carrying `com.apple.FinderInfo`, and some sync/backup agents re-attach
            // that attribute to anything under the project directory faster than it can be
            // stripped. Pointing this outside the watched tree sidesteps the fight:
            //   ./gradlew :composeApp:packageDmg -Pjmail.dist.dir=/tmp/jmail-dist
            providers.gradleProperty("jmail.dist.dir").orNull?.let { configured ->
                outputBaseDir.set(file(configured))
            }

            val iconsDir = layout.buildDirectory.dir("generated/icons").get().asFile

            macOS {
                bundleID = "com.jmail.app"
                dockName = "JMail"
                iconFile.set(iconsDir.resolve("JMail.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                          <dict>
                            <key>CFBundleURLName</key>
                            <string>com.jmail.app.auth</string>
                            <key>CFBundleURLSchemes</key>
                            <array><string>jmail</string></array>
                          </dict>
                        </array>
                        <key>NSHighResolutionCapable</key>
                        <true/>
                    """.trimIndent()
                }
            }
            windows {
                menuGroup = "JMail"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                iconFile.set(iconsDir.resolve("JMail.ico"))
                // Stable UUID: required so upgrades replace rather than duplicate the install.
                upgradeUuid = "9F2B6C1E-4A73-4E5A-9B0D-6C3E2F1A8D44"
            }
            linux {
                packageName = "jmail"
                menuGroup = "Network;Email"
                appCategory = "Network"
                iconFile.set(iconsDir.resolve("JMail-512.png"))
                debMaintainer = "support@jmail.app"
            }
        }

        buildTypes.release.proguard {
            // Compose Desktop's default rules already keep the runtime; obfuscation adds
            // no value for a locally installed desktop app and complicates crash reports.
            isEnabled.set(false)
        }
    }
}

/**
 * Renders the JMail app icon at every size the installers need, then writes the
 * platform container formats (.icns for macOS, .ico for Windows, .png for Linux).
 * Doing this in-build keeps binary blobs out of source control and guarantees that
 * `./run.sh package` works on a clean checkout with no image tooling installed.
 */
val generateAppIcons by tasks.registering {
    group = "distribution"
    description = "Generates JMail.icns, JMail.ico and PNG icons for the native installers."

    val outputDir = layout.buildDirectory.dir("generated/icons")
    outputs.dir(outputDir)

    doLast {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        val sizes = listOf(16, 32, 64, 128, 256, 512, 1024)
        val pngBySize = sizes.associateWith { size -> JMailIconRenderer.renderPng(size) }

        pngBySize.forEach { (size, bytes) -> dir.resolve("JMail-$size.png").writeBytes(bytes) }
        dir.resolve("JMail.icns").writeBytes(
            JMailIconRenderer.icns(listOf(128, 256, 512, 1024).associateWith { pngBySize.getValue(it) }),
        )
        dir.resolve("JMail.ico").writeBytes(
            JMailIconRenderer.ico(listOf(16, 32, 64, 128, 256).associateWith { pngBySize.getValue(it) }),
        )
        logger.lifecycle("Generated app icons in ${dir.absolutePath}")
    }
}

/**
 * Writes the iOS app icon into the asset catalog before Xcode compiles it.
 *
 * Same reasoning as `generateAppIcons`: the artwork is code, so a clean checkout builds
 * without any image tooling and no binary blob is committed. The appiconset is gitignored.
 * A single 1024x1024 universal image is all Xcode 13 and newer need.
 */
val generateIosAppIcon by tasks.registering {
    group = "distribution"
    description = "Writes AppIcon.appiconset into the iOS asset catalog."

    val appIconSet = rootProject.layout.projectDirectory
        .dir("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
    outputs.dir(appIconSet)

    doLast {
        val dir = appIconSet.asFile.apply { mkdirs() }
        dir.resolve("JMail-1024.png").writeBytes(JMailIconRenderer.renderIosAppIconPng(1024))
        dir.resolve("Contents.json").writeText(
            """
            {
              "images" : [
                {
                  "filename" : "JMail-1024.png",
                  "idiom" : "universal",
                  "platform" : "ios",
                  "size" : "1024x1024"
                }
              ],
              "info" : {
                "author" : "gradle",
                "version" : 1
              }
            }
            """.trimIndent() + "\n",
        )
        logger.lifecycle("Wrote the iOS app icon to ${dir.absolutePath}")
    }
}

tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") }
    .configureEach {
        dependsOn(generateAppIcons)

        // macOS refuses to sign a bundle carrying extended attributes, and file-sync
        // services (iCloud Drive, Dropbox) attach `com.apple.FinderInfo` to anything inside
        // a synced folder — including build output. jpackage then fails deep inside
        // codesign with an error that says nothing about the real cause. Stripping the
        // attributes immediately beforehand keeps packaging working from a synced checkout.
        if (System.getProperty("os.name").contains("Mac")) {
            doFirst {
                val binaries = layout.buildDirectory.dir("compose/binaries").get().asFile
                if (binaries.exists()) {
                    providers.exec {
                        commandLine("xattr", "-cr", binaries.absolutePath)
                        isIgnoreExitValue = true
                    }.result.get()
                }
            }
        }
    }

// Kover instruments the JVM ("desktop") target automatically; its report feeds the
// aggregated total report configured in the root build.
