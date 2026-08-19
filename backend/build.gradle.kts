plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.kotlinJpa)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.kover)
}

group = "com.jmail"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.cache)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(libs.angus.mail) // IMAP/SMTP for Exchange on-prem and generic accounts
    implementation(libs.jsoup) // HTML sanitisation of untrusted message bodies
    implementation(libs.bucket4j.core) // per-principal rate limiting
    implementation(libs.springdoc.openapi)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(module = "mockito-core") // MockK is used throughout instead
    }
    testImplementation(libs.spring.security.test)
    // kotlin.test resolves to its JUnit 5 variant automatically, since tests run on the
    // JUnit Platform. Lets assertions read the same way here as in the multiplatform module.
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.assertk)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.h2) // fast slice tests that do not need a container
}

// JPA entities are `open`ed by the kotlin-jpa plugin; also open @Component classes so that
// Spring's CGLIB proxies (transactions, caching) work without hand-written `open` modifiers.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test>().configureEach {
    systemProperty("spring.profiles.active", "test")

    // Testcontainers looks for /var/run/docker.sock, which Docker Desktop on macOS does not
    // create for the user by default — its socket lives under ~/.docker/run. Point the test
    // JVM at whatever the active Docker context actually uses, so integration tests run
    // without every contributor having to export DOCKER_HOST by hand.
    if (System.getenv("DOCKER_HOST") == null) {
        val userSocket = File(System.getProperty("user.home"), ".docker/run/docker.sock")
        val colimaSocket = File(System.getProperty("user.home"), ".colima/default/docker.sock")

        val socket = listOf(userSocket, colimaSocket, File("/var/run/docker.sock"))
            .firstOrNull { it.exists() }

        if (socket != null) {
            environment("DOCKER_HOST", "unix://${socket.absolutePath}")
            environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        }
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("jmail-backend.jar")
}

springBoot {
    buildInfo() // exposes version + build time on /actuator/info
}
