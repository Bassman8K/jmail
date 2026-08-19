import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

buildscript {
    // The Android Gradle Plugin sits on the build classpath unconditionally, because a build
    // script that references its types has to compile even on a machine with no Android SDK.
    // Whether it is *applied* is still conditional (see :shared and :composeApp), so a
    // contributor without an SDK builds desktop and web exactly as before.
    // NOTE: keep this version in sync with `agp` in gradle/libs.versions.toml.
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.kotlinSpring) apply false
    alias(libs.plugins.kotlinJpa) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.springDependencyManagement) apply false
    alias(libs.plugins.kover)
}

val coverageMinimum = (property("jmail.coverage.min") as String).toInt()

/**
 * Branch coverage is held to a lower bar than line coverage, deliberately.
 *
 * A large share of the branches Kotlin emits are not decisions anyone wrote: null-safety
 * checks behind `?.` and `?:`, the generated `equals`/`hashCode`/`copy` of every data class,
 * and default-argument dispatch. Many of those halves are unreachable in practice, so
 * chasing a high branch percentage rewards writing tests for the compiler rather than for
 * the product. Line coverage is the number that reflects whether the code was exercised;
 * this bound exists to catch regressions, not to be maximised.
 */
val branchCoverageMinimum = (property("jmail.coverage.branch.min") as String).toInt()

/**
 * Aggregated coverage across every module. `./gradlew koverHtmlReport` produces a single
 * browsable report at build/reports/kover/html/index.html; `koverVerify` fails the build
 * when line coverage of testable code drops below `jmail.coverage.min`.
 */
dependencies {
    kover(project(":backend"))
    kover(project(":shared"))
    kover(project(":composeApp"))
}

kover {
    reports {
        total {
            filters {
                excludes {
                    // Excluded because they contain no logic to assert on, not because they
                    // are hard to test:
                    //   * framework bootstrap and configuration (wiring, not behaviour)
                    //   * DTOs and generated resource accessors (declarations)
                    //   * Compose screens, which are layout — their logic lives in the
                    //     shared stores and in the pure helpers below, all of which ARE
                    //     measured (MessageBody's parser, WindowSizeClass, the avatar's
                    //     colour derivation, the row's accessibility description).
                    classes(
                        "com.jmail.backend.JMailApplicationKt",
                        "com.jmail.backend.config.*",
                        "com.jmail.backend.*.dto.*",

                        // The shared wire models, for the same reason as the backend DTOs
                        // above: they are `@Serializable` data classes with no behaviour.
                        // Measuring them means measuring compiler-generated equals, hashCode,
                        // copy and write$Self — 781 of the 1,263 uncovered branches in this
                        // report came from there, against 41 uncovered lines, which made the
                        // branch figure describe the Kotlin compiler rather than JMail. The
                        // one member with real logic, MessageSummary.isLowConfidence, is
                        // asserted directly by ModelsTest, which still runs.
                        "com.jmail.shared.model.*",
                        "com.jmail.shared.generated.*",
                        "jmail.composeapp.generated.resources.*",

                        // Compose entry points and whole-screen composables.
                        "com.jmail.app.MainKt",
                        "com.jmail.app.AppKt*",
                        "com.jmail.app.MainActivity*",
                        "com.jmail.app.JMailApplication*",
                        "com.jmail.app.MainViewControllerKt*",
                        "com.jmail.app.ui.theme.ThemeKt*",
                        "com.jmail.app.ui.signin.*",
                        "com.jmail.app.ui.settings.*",
                        "com.jmail.app.ui.compose.*",
                        "com.jmail.app.ui.mailbox.MailboxScreenKt*",
                        "com.jmail.app.ui.mailbox.SidebarKt*",
                        "com.jmail.app.ui.mailbox.MessageListKt*",
                        "com.jmail.app.ui.reader.ReaderPaneKt*",
                        "com.jmail.app.ui.components.StateViewsKt*",
                        "com.jmail.app.ui.components.ModifiersKt*",
                    )
                    annotatedBy("androidx.compose.runtime.Composable")
                    annotatedBy("com.jmail.shared.util.ExcludeFromCoverage")
                }
            }
            verify {
                rule("Line coverage of business logic") {
                    bound {
                        minValue = coverageMinimum
                        coverageUnits = CoverageUnit.LINE
                        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    }
                }
                rule("Branch coverage of business logic") {
                    bound {
                        minValue = branchCoverageMinimum
                        coverageUnits = CoverageUnit.BRANCH
                        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    }
                }
            }
        }
    }
}

/**
 * Deletes the "Foo 2.kt" / "Foo 2.class" copies that file-sync services (iCloud Drive on
 * macOS, Dropbox, OneDrive) leave in build directories when they resolve a conflict.
 *
 * Gradle compiles and runs whatever it finds, so a stray duplicate shows up as a
 * duplicate-declaration error or as a bogus "could not execute test class" failure that has
 * nothing to do with the change being built. Sweeping them before every compile keeps that
 * failure mode away from contributors who keep their checkout in a synced folder.
 */
val cleanSyncDuplicates by tasks.registering {
    group = "build"
    description = "Removes file-sync duplicate files from build directories."

    doLast {
        val removed = rootDir.walkTopDown()
            .onEnter { directory -> directory.name != ".git" }
            .filter { file -> file.isFile && file.path.contains("/build/") }
            .filter { file -> Regex(""".* \d+\.[A-Za-z0-9]+$""").matches(file.name) }
            .toList()

        removed.forEach { it.delete() }
        if (removed.isNotEmpty()) {
            logger.lifecycle("Removed ${removed.size} file-sync duplicate(s) from build directories")
        }
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        dependsOn(rootProject.tasks.named("cleanSyncDuplicates"))
        compilerOptions {
            freeCompilerArgs.addAll("-Xexpect-actual-classes")
            allWarningsAsErrors.set(providers.gradleProperty("jmail.strict").isPresent)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }
        // Deterministic tests: never inherit the developer's local timezone/locale.
        systemProperty("user.timezone", "UTC")
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }
}

/** Convenience entry point used by `./run.sh test`. */
tasks.register("verifyAll") {
    group = "verification"
    description = "Runs every check in the repository plus the aggregated coverage gate."
    dependsOn(
        ":backend:test",
        ":shared:allTests",
        ":composeApp:desktopTest",
        "koverXmlReport",
        "koverHtmlReport",
        "koverVerify",
    )
}
