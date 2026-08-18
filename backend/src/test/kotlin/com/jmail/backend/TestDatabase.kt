package com.jmail.backend

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager

/**
 * Resolves a PostgreSQL instance for integration tests.
 *
 * Three sources are tried in order, because no single one works everywhere:
 *
 *  1. `JMAIL_TEST_DB_URL` — an explicitly provided database, which CI or a developer can
 *     point anywhere.
 *  2. The local `docker compose` stack — already running for anyone developing on JMail, so
 *     tests cost nothing extra and start instantly.
 *  3. Testcontainers — a throwaway container, which is what CI uses when no stack is up.
 *
 * If none is reachable the integration tests are skipped with an explanation rather than
 * failing: a contributor without Docker should still be able to run the unit suite.
 *
 * Whichever source wins, tests get their *own* database (`jmail_test`) that is dropped and
 * recreated at the start of the run, so a suite never sees another run's leftovers and can
 * never touch development data.
 */
object TestDatabase {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One database per Gradle test worker.
     *
     * The suite runs in several forks, and they would otherwise take turns dropping and
     * recreating the same database underneath each other — producing "relation does not
     * exist" failures that only appear in a full run.
     */
    val testDatabaseName: String = buildString {
        append("jmail_test")
        System.getProperty("org.gradle.test.worker")?.let { worker -> append("_").append(worker) }
    }

    data class Connection(val jdbcUrl: String, val username: String, val password: String)

    /** Null when no PostgreSQL is available; callers turn that into a skipped test. */
    val connection: Connection? by lazy { resolve() }

    val skipReason: String
        get() = "No PostgreSQL available for integration tests. Start the stack with " +
            "`docker compose -f docker/compose.yml up -d postgres`, or set JMAIL_TEST_DB_URL."

    private fun resolve(): Connection? =
        fromEnvironment() ?: fromLocalStack() ?: fromTestcontainers()

    private fun fromEnvironment(): Connection? {
        val url = System.getenv("JMAIL_TEST_DB_URL") ?: return null
        val username = System.getenv("JMAIL_TEST_DB_USER") ?: "jmail"
        val password = System.getenv("JMAIL_TEST_DB_PASSWORD") ?: "jmail_local_dev"

        log.info("Integration tests using the database from JMAIL_TEST_DB_URL")
        return Connection(url, username, password).takeIf { canConnect(it) }
    }

    /**
     * The Postgres from docker/compose.yml. A dedicated database is created on it so the
     * suite is isolated from whatever the developer has been doing in the app.
     */
    private fun fromLocalStack(): Connection? {
        val host = System.getenv("POSTGRES_HOST") ?: "localhost"
        val port = System.getenv("POSTGRES_PORT") ?: "5432"
        val username = System.getenv("POSTGRES_USER") ?: "jmail"
        val password = System.getenv("POSTGRES_PASSWORD") ?: "jmail_local_dev"

        val adminUrl = "jdbc:postgresql://$host:$port/postgres"
        val testUrl = "jdbc:postgresql://$host:$port/$testDatabaseName"

        return runCatching {
            DriverManager.getConnection(adminUrl, username, password).use { admin ->
                recreateDatabase(admin)
            }
            installExtensions(testUrl, username, password)

            log.info("Integration tests using the local docker compose PostgreSQL at {}:{}", host, port)
            Connection(testUrl, username, password)
        }.onFailure {
            log.debug("Local compose PostgreSQL is not available: {}", it.message)
        }.getOrNull()
    }

    /**
     * A throwaway container. Reflection keeps Testcontainers off the critical path: when the
     * Docker API it expects is unavailable — as with some Docker Desktop builds — this simply
     * returns null instead of exploding in a static initialiser.
     */
    private fun fromTestcontainers(): Connection? = runCatching {
        val containerClass = Class.forName("org.testcontainers.containers.PostgreSQLContainer")
        val container = containerClass.getConstructor(String::class.java)
            .newInstance("postgres:17-alpine")

        containerClass.getMethod("withDatabaseName", String::class.java)
            .invoke(container, testDatabaseName)
        containerClass.getMethod("withInitScript", String::class.java)
            .invoke(container, "db/init-extensions.sql")
        containerClass.getMethod("start").invoke(container)

        val jdbcUrl = containerClass.getMethod("getJdbcUrl").invoke(container) as String
        val username = containerClass.getMethod("getUsername").invoke(container) as String
        val password = containerClass.getMethod("getPassword").invoke(container) as String

        log.info("Integration tests using a Testcontainers PostgreSQL at {}", jdbcUrl)
        Connection(jdbcUrl, username, password)
    }.onFailure {
        log.debug("Testcontainers could not start PostgreSQL: {}", it.message)
    }.getOrNull()

    /**
     * Drops and recreates the test database. Doing this once per JVM rather than per class
     * keeps the suite fast while still guaranteeing every run starts from an empty schema.
     */
    private fun recreateDatabase(admin: java.sql.Connection) {
        admin.createStatement().use { statement ->
            // Existing sessions would block the DROP; they are terminated first.
            statement.execute(
                """
                SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                WHERE datname = '$testDatabaseName' AND pid <> pg_backend_pid()
                """.trimIndent(),
            )
            statement.execute("DROP DATABASE IF EXISTS $testDatabaseName")
            statement.execute("CREATE DATABASE $testDatabaseName")
        }
    }

    /** pg_trgm and uuid-ossp need superuser, so they cannot live in a Flyway migration. */
    private fun installExtensions(jdbcUrl: String, username: String, password: String) {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
                statement.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"")
            }
        }
    }

    private fun canConnect(connection: Connection): Boolean = runCatching {
        DriverManager.getConnection(connection.jdbcUrl, connection.username, connection.password).close()
        true
    }.getOrDefault(false)
}
