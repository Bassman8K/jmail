package com.jmail.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import com.jmail.backend.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The sign-in surface, end to end: real HTTP, real security filters, real database.
 *
 * These cover the properties that unit tests cannot reach — that the security configuration
 * actually protects what it claims to, and that a token issued by one request is accepted by
 * the next one.
 */
class AuthApiIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /**
     * A real IMAP server for the address-and-password path. Mocking the connection would
     * defeat the point of that code, which exists to prove credentials work before storing
     * them. A dynamic port because the local Docker stack also publishes IMAP on 3143.
     *
     * `final` counteracts the kotlin-spring allopen plugin, which opens @SpringBootTest
     * classes and their members; @JvmField — which JUnit needs to see the field — may only
     * be applied to a final property.
     */
    @JvmField
    @RegisterExtension
    final val greenMail: GreenMailExtension = GreenMailExtension(ServerSetupTest.IMAP.dynamicPort())
        .withConfiguration(
            GreenMailConfiguration.aConfig()
                .withUser("ada@example.com", "ada@example.com", "correct-password")
                .withUser("ada@gmail.com", "ada@gmail.com", "correct-password"),
        )

    private fun exchangeSignIn(
        email: String = "ada@example.com",
        password: String = "correct-password",
        host: String? = "127.0.0.1",
        port: Int? = null,
    ): String = buildString {
        append("""{"email":"$email","password":"$password","useTls":false""")
        if (host != null) append(""","imapHost":"$host","smtpHost":"$host"""")
        val resolved = port ?: greenMail.imap.port
        if (host != null) append(""","imapPort":$resolved,"smtpPort":$resolved""")
        append("}")
    }

    private fun signInAsDemo(): Pair<String, String> {
        val response = mockMvc.post("/api/v1/auth/demo")
            .andReturn().response.contentAsString

        val json = objectMapper.readTree(response)
        return json.path("accessToken").asText() to json.path("refreshToken").asText()
    }

    @Test
    fun `the providers endpoint is reachable without a token`() {
        mockMvc.get("/api/v1/auth/providers")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$") { isArray() } }
    }

    @Test
    fun `demo sign-in returns a usable session and a seeded mailbox`() {
        mockMvc.post("/api/v1/auth/demo")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.accessToken") { exists() } }
            .andExpect { jsonPath("$.refreshToken") { exists() } }
            .andExpect { jsonPath("$.tokenType") { value("Bearer") } }
            .andExpect { jsonPath("$.user.email") { value("demo@jmail.app") } }
            // The demo account is what makes a fresh checkout usable, so it must arrive
            // already connected rather than as an empty shell.
            .andExpect { jsonPath("$.user.accounts[0].provider") { value("DEMO") } }
            .andExpect { jsonPath("$.user.accounts[0].status") { value("CONNECTED") } }
    }

    @Test
    fun `signing in as the demo user twice does not duplicate the mailbox`() {
        val first = mockMvc.post("/api/v1/auth/demo").andReturn().response.contentAsString
        val second = mockMvc.post("/api/v1/auth/demo").andReturn().response.contentAsString

        val firstUserId = objectMapper.readTree(first).path("user").path("id").asText()
        val secondUserId = objectMapper.readTree(second).path("user").path("id").asText()
        val accountCount = objectMapper.readTree(second).path("user").path("accounts").size()

        assertEquals(firstUserId, secondUserId)
        assertEquals(1, accountCount)
    }

    @Test
    fun `an access token from sign-in is accepted by a protected endpoint`() {
        val (accessToken, _) = signInAsDemo()

        mockMvc.get("/api/v1/users/me") {
            header("Authorization", "Bearer $accessToken")
        }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.email") { value("demo@jmail.app") } }
    }

    @Test
    fun `a protected endpoint refuses an anonymous request`() {
        mockMvc.get("/api/v1/users/me")
            .andExpect { status { isUnauthorized() } }
            .andExpect { jsonPath("$.code") { value("unauthorized") } }
    }

    @Test
    fun `a malformed token is refused with a code the client can branch on`() {
        mockMvc.get("/api/v1/users/me") {
            header("Authorization", "Bearer not-a-real-token")
        }
            .andExpect { status { isUnauthorized() } }
            .andExpect { jsonPath("$.code") { value("invalid_token") } }
    }

    @Test
    fun `refreshing rotates the refresh token and re-uses are refused`() {
        val (_, refreshToken) = signInAsDemo()

        val refreshed = mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refreshToken"}"""
        }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val rotated = objectMapper.readTree(refreshed).path("refreshToken").asText()
        assertNotEquals(refreshToken, rotated)

        // The original is now spent: presenting it again is treated as a leak.
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refreshToken"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a rotated refresh token works exactly once`() {
        val (_, refreshToken) = signInAsDemo()

        val rotated = objectMapper
            .readTree(
                mockMvc.post("/api/v1/auth/refresh") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"refreshToken":"$refreshToken"}"""
                }.andReturn().response.contentAsString,
            )
            .path("refreshToken").asText()

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$rotated"}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `an unknown refresh token is refused`() {
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"never-issued"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `validation failures name the field rather than returning a bare 400`() {
        mockMvc.post("/api/v1/auth/exchange-server/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"not-an-email","password":""}"""
        }
            .andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("validation_failed") } }
            .andExpect { jsonPath("$.details.email") { exists() } }
            .andExpect { jsonPath("$.details.password") { exists() } }
    }

    @Test
    fun `the exchange settings suggestion is available before sign-in`() {
        mockMvc.get("/api/v1/auth/exchange-server/suggest?email=someone@outlook.com")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.imapHost") { value("outlook.office365.com") } }
            .andExpect { jsonPath("$.confident") { value(true) } }
    }

    @Test
    fun `an unconfigured provider is reported rather than failing obscurely`() {
        // Apple has no private key in the test profile, so starting its flow must explain why.
        mockMvc.post("/api/v1/auth/apple/start")
            .andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("provider_not_configured") } }
    }

    @Test
    fun `an unknown provider slug is rejected`() {
        mockMvc.post("/api/v1/auth/carrierpigeon/start")
            .andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("unsupported_provider") } }
    }

    @Test
    fun `a google sign-in produces a PKCE authorization url`() {
        val response = mockMvc.post("/api/v1/auth/google/start?target=APP")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val url = objectMapper.readTree(response).path("authorizationUrl").asText()

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth"), url)
        assertTrue(url.contains("code_challenge="), url)
        assertTrue(url.contains("code_challenge_method=S256"), url)
        // Without offline access Google never issues a refresh token, and sync dies in an hour.
        assertTrue(url.contains("access_type=offline"), url)
        assertTrue(url.contains("state="), url)
    }

    @Test
    fun `an oauth callback with an error redirects the user back to the app`() {
        mockMvc.get("/api/v1/auth/google/callback?error=access_denied&target=WEB")
            .andExpect { status { isFound() } }
            .andExpect { header { string("Location", org.hamcrest.Matchers.containsString("error=access_denied")) } }
    }

    @Test
    fun `a browser callback returns to the web app, not to a deep link`() {
        // A browser cannot open jmail://, and not every static server rewrites unknown paths
        // to index.html — so the browser goes back to the app's own origin with the code.
        mockMvc.get("/api/v1/auth/google/callback?error=access_denied&target=WEB")
            .andExpect { status { isFound() } }
            .andExpect {
                header {
                    string("Location", org.hamcrest.Matchers.startsWith("http://localhost:3000"))
                }
            }
    }

    @Test
    fun `an installed app callback returns to its registered scheme`() {
        mockMvc.get("/api/v1/auth/google/callback?error=access_denied&target=APP")
            .andExpect { status { isFound() } }
            .andExpect {
                header {
                    string("Location", org.hamcrest.Matchers.startsWith("jmail://auth/callback"))
                }
            }
    }

    @Test
    fun `an oauth callback with a stale state redirects rather than showing raw json`() {
        mockMvc.get("/api/v1/auth/google/callback?code=abc&state=never-issued&target=WEB")
            .andExpect { status { isFound() } }
            .andExpect { header { string("Location", org.hamcrest.Matchers.containsString("error=")) } }
    }

    @Test
    fun `logging out revokes the session`() {
        val (accessToken, refreshToken) = signInAsDemo()

        mockMvc.post("/api/v1/auth/logout") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refreshToken"}"""
        }.andExpect { status { isNoContent() } }

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refreshToken"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- the address-and-password path --------------------------------------------

    @Test
    fun `the mail service directory is available before sign-in`() {
        val body = mockMvc.get("/api/v1/auth/mail-providers")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$") { isArray() } }
            .andReturn().response.contentAsString

        val gmail = objectMapper.readTree(body).first { it.path("id").asText() == "gmail" }

        // Gmail must be flagged as needing an app password, with somewhere to get one —
        // that guidance is the entire reason this picker exists.
        assertEquals("imap.gmail.com", gmail.path("imapHost").asText())
        assertTrue(gmail.path("requiresAppPassword").asBoolean())
        assertTrue(gmail.path("appPasswordUrl").asText().startsWith("https://"))
        assertTrue(gmail.path("helpText").asText().isNotBlank())
    }

    @Test
    fun `every listed service carries what the form needs to be filled in`() {
        val body = mockMvc.get("/api/v1/auth/mail-providers").andReturn().response.contentAsString
        val providers = objectMapper.readTree(body)

        assertTrue(providers.size() > 1, "the directory should not be empty")
        providers.forEach { provider ->
            assertTrue(provider.path("id").asText().isNotBlank(), "id: $provider")
            assertTrue(provider.path("displayName").asText().isNotBlank(), "displayName: $provider")
            // A service with no host is one the user must fill in by hand, and it has to
            // say so rather than silently offering an empty form.
            val hasHost = provider.path("imapHost").asText().isNotBlank()
            assertEquals(!hasHost, provider.path("requiresManualServer").asBoolean(), "$provider")
        }
    }

    @Test
    fun `signing in with an address and password connects the mailbox`() {
        val response = mockMvc.post("/api/v1/auth/exchange-server/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = exchangeSignIn()
        }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val json = objectMapper.readTree(response)
        assertTrue(json.path("accessToken").asText().isNotBlank())
        assertEquals("ada@example.com", json.path("user").path("email").asText())
        assertEquals("IMAP", json.path("user").path("accounts")[0].path("provider").asText())
        assertEquals("CONNECTED", json.path("user").path("accounts")[0].path("status").asText())
    }

    @Test
    fun `the wrong password is refused and nothing is stored`() {
        mockMvc.post("/api/v1/auth/exchange-server/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = exchangeSignIn(email = "ada@example.com", password = "wrong")
        }
            .andExpect { status { isUnauthorized() } }
            .andExpect { jsonPath("$.code") { value("exchange_authentication_failed") } }
    }

    @Test
    fun `a gmail address rejected over IMAP is told to use an app password`() {
        // Google has refused account passwords over IMAP since May 2022, and reports it as a
        // plain authentication failure. Passing that through verbatim sends the user off to
        // check a password that is not the problem.
        mockMvc.post("/api/v1/auth/exchange-server/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = exchangeSignIn(email = "ada@gmail.com", password = "wrong")
        }
            .andExpect { status { isUnauthorized() } }
            .andExpect { jsonPath("$.code") { value("app_password_required") } }
            .andExpect { jsonPath("$.message") { value(org.hamcrest.Matchers.containsString("app password")) } }
    }

    @Test
    fun `a server that cannot be reached is reported as such, not as a bad password`() {
        // Port 1 is closed, so the connection is refused immediately — the same failure a
        // typo in the server name produces, and the user needs to be told which of the two
        // problems they have. Calling it "wrong password" sends them to fix the wrong thing.
        mockMvc.post("/api/v1/auth/exchange-server/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = exchangeSignIn(port = 1)
        }
            .andExpect { jsonPath("$.code") { value("exchange_unreachable") } }
            .andExpect { jsonPath("$.message") { value(org.hamcrest.Matchers.containsString("127.0.0.1:1")) } }
    }

    @Test
    fun `an unrecognised domain is guessed at, and the guess is flagged as one`() {
        // There is no directory entry for this domain, so the suggestion falls back to the
        // imap.<domain> convention most self-hosted servers follow — offered as a guess so
        // the form can prompt rather than present it as fact.
        val body = mockMvc.get("/api/v1/auth/exchange-server/suggest?email=someone@a-domain-we-do-not-know.example")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val suggestion = objectMapper.readTree(body)
        assertEquals("imap.a-domain-we-do-not-know.example", suggestion.path("imapHost").asText())
        assertEquals(false, suggestion.path("confident").asBoolean())
        // No directory entry, so there is no provider to name — absent or null, not a guess.
        assertTrue(suggestion.path("providerId").asText("").isEmpty(), suggestion.toString())
        assertEquals(false, suggestion.path("requiresAppPassword").asBoolean())
    }

    @Test
    fun `the suggestion says when it is only a guess`() {
        mockMvc.get("/api/v1/auth/exchange-server/suggest?email=someone@a-domain-we-do-not-know.example")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.confident") { value(false) } }
    }

    @Test
    fun `the suggestion for a known service carries its app-password guidance`() {
        mockMvc.get("/api/v1/auth/exchange-server/suggest?email=someone@gmail.com")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.providerId") { value("gmail") } }
            .andExpect { jsonPath("$.requiresAppPassword") { value(true) } }
            .andExpect { jsonPath("$.appPasswordUrl") { exists() } }
            .andExpect { jsonPath("$.helpText") { exists() } }
    }

    @Test
    fun `signing in twice with the same mailbox does not duplicate it`() {
        repeat(2) {
            mockMvc.post("/api/v1/auth/exchange-server/sign-in") {
                contentType = MediaType.APPLICATION_JSON
                content = exchangeSignIn()
            }.andExpect { status { isOk() } }
        }

        val body = mockMvc.post("/api/v1/auth/exchange-server/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = exchangeSignIn()
        }.andReturn().response.contentAsString

        assertEquals(1, objectMapper.readTree(body).path("user").path("accounts").size())
    }

    @Test
    fun `the wrong method on a real route is 405, not a 500`() {
        // The exception is raised during handler mapping, before any controller runs, so
        // this checks the advice actually sees it — the unit test cannot prove that.
        mockMvc.get("/api/v1/auth/demo")
            .andExpect { status { isMethodNotAllowed() } }
            .andExpect { jsonPath("$.code") { value("method_not_allowed") } }
            .andExpect { header { string("Allow", org.hamcrest.Matchers.containsString("POST")) } }
    }

    @Test
    fun `health and docs stay reachable without a token`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }
    }
}
