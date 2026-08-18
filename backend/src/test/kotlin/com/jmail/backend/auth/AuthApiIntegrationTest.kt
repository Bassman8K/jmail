package com.jmail.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.jmail.backend.AbstractIntegrationTest
import org.junit.jupiter.api.Test
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

    @Test
    fun `health and docs stay reachable without a token`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }
    }
}
