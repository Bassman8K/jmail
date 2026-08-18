package com.jmail.backend

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc

/**
 * Base class for tests that exercise the real stack: a genuine PostgreSQL instance, the real
 * Flyway migrations, and the full Spring context.
 *
 * The database comes from [TestDatabase], which prefers whatever PostgreSQL is already
 * available. Spring's context cache keeps the application up between classes, so the
 * database is prepared once for the entire suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
abstract class AbstractIntegrationTest {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    companion object {

        @JvmStatic
        @BeforeAll
        fun requireDatabase() {
            assumeTrue(TestDatabase.connection != null, TestDatabase.skipReason)
        }

        /**
         * Registered even when no database was resolved: Spring evaluates these lazily, and
         * the assumption above stops the class before the context is ever built.
         */
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            val connection = TestDatabase.connection ?: return

            registry.add("spring.datasource.url") { connection.jdbcUrl }
            registry.add("spring.datasource.username") { connection.username }
            registry.add("spring.datasource.password") { connection.password }
        }
    }
}
