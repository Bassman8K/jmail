package com.jmail.backend.mail

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Message bodies are attacker-authored input, so this is a security test as much as a
 * formatting one. Each case below is a real technique for getting script execution or a
 * tracking callback past a naive sanitiser.
 */
class HtmlSanitizerTest {

    private val sanitizer = HtmlSanitizer()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<script>alert('xss')</script>",
            "<img src=x onerror=alert('xss')>",
            "<svg/onload=alert('xss')>",
            "<iframe src='https://evil.example'></iframe>",
            "<object data='https://evil.example'></object>",
            "<embed src='https://evil.example'>",
            "<style>body{background:url('https://evil.example/track')}</style>",
            "<a href=\"javascript:alert('xss')\">click</a>",
            "<div onclick=\"alert('xss')\">click</div>",
            "<meta http-equiv='refresh' content='0;url=https://evil.example'>",
            "<form action='https://evil.example'><input name='p'></form>",
        ],
    )
    fun `strips everything executable or capable of phoning home`(hostile: String) {
        val cleaned = sanitizer.sanitize(hostile).orEmpty().lowercase()

        assertThat(cleaned).doesNotContain("script")
        assertThat(cleaned).doesNotContain("onerror")
        assertThat(cleaned).doesNotContain("onload")
        assertThat(cleaned).doesNotContain("onclick")
        assertThat(cleaned).doesNotContain("javascript:")
        assertThat(cleaned).doesNotContain("<iframe")
        assertThat(cleaned).doesNotContain("<object")
        assertThat(cleaned).doesNotContain("<embed")
        assertThat(cleaned).doesNotContain("<style")
        assertThat(cleaned).doesNotContain("<meta")
        assertThat(cleaned).doesNotContain("<form")
    }

    @Test
    fun `keeps the formatting that makes a message readable`() {
        val html = """
            <p>Hello <strong>Ada</strong>,</p>
            <p>Here is a <a href="https://example.com">link</a> and a list:</p>
            <ul><li>One</li><li>Two</li></ul>
            <table><tr><td>Cell</td></tr></table>
        """.trimIndent()

        val cleaned = sanitizer.sanitize(html).orEmpty()

        assertThat(cleaned).contains("<strong>Ada</strong>")
        assertThat(cleaned).contains("href=\"https://example.com\"")
        assertThat(cleaned).contains("<li>One</li>")
        assertThat(cleaned).contains("<td>Cell</td>")
    }

    @Test
    fun `links open safely in a new context`() {
        val cleaned = sanitizer.sanitize("<a href='https://example.com'>x</a>").orEmpty()

        assertThat(cleaned).contains("target=\"_blank\"")
        // Without noopener the destination can navigate the opener window (tabnabbing).
        assertThat(cleaned).contains("noopener")
        assertThat(cleaned).contains("noreferrer")
    }

    @Test
    fun `blocks remote images by default and keeps the source for later`() {
        val cleaned = sanitizer.sanitize("<img src='https://tracker.example/pixel.gif'>").orEmpty()

        // The leading space matters: `data-jmail-src="…"` also ends in `src="…"`, so a bare
        // substring check would pass even if the real src attribute were still present.
        assertThat(cleaned).doesNotContain(" src=\"")
        assertThat(cleaned).contains("data-jmail-src=\"https://tracker.example/pixel.gif\"")
        assertThat(cleaned).contains("jmail-blocked-image")
    }

    @Test
    fun `loads remote images when the user explicitly asks`() {
        val cleaned = sanitizer
            .sanitize("<img src='https://example.com/photo.png'>", allowRemoteImages = true)
            .orEmpty()

        assertThat(cleaned).contains("src=\"https://example.com/photo.png\"")
        assertThat(cleaned).doesNotContain("data-jmail-src")
    }

    @Test
    fun `leaves inline cid images alone, since they carry no network request`() {
        val cleaned = sanitizer.sanitize("<img src='cid:logo@example'>").orEmpty()

        assertThat(cleaned).contains("cid:logo@example")
    }

    @Test
    fun `containsRemoteImages reports what the banner should say`() {
        assertThat(sanitizer.containsRemoteImages("<img src='https://a.example/x.png'>")).isTrue()
        assertThat(sanitizer.containsRemoteImages("<img data-jmail-src='https://a.example/x.png'>")).isTrue()
        assertThat(sanitizer.containsRemoteImages("<img src='cid:inline'>")).isFalse()
        assertThat(sanitizer.containsRemoteImages("<p>no images</p>")).isFalse()
        assertThat(sanitizer.containsRemoteImages(null)).isFalse()
    }

    @Test
    fun `converts html to readable plain text`() {
        val text = sanitizer.toPlainText(
            "<p>First paragraph</p><p>Second paragraph</p><div>Third</div>",
        )

        assertThat(text).contains("First paragraph")
        assertThat(text).contains("Second paragraph")
        assertThat(text).contains("Third")
        assertThat(text).doesNotContain("<p>")
    }

    @Test
    fun `collapses runs of blank lines so the snippet reads as prose`() {
        val text = sanitizer.toPlainText("<p>One</p><p></p><p></p><p></p><p>Two</p>")

        assertThat(text).doesNotContain("\n\n\n")
    }

    @Test
    fun `snippet truncates on a word boundary with an ellipsis`() {
        val snippet = sanitizer.snippet("a".repeat(500), maxLength = 50)

        assertThat(snippet.length).isEqualTo(50)
        assertThat(snippet.endsWith("…")).isTrue()
    }

    @Test
    fun `snippet collapses whitespace`() {
        assertThat(sanitizer.snippet("  hello \n\n  world  ")).isEqualTo("hello world")
    }

    @Test
    fun `null and blank input round trip to nothing`() {
        assertThat(sanitizer.sanitize(null)).isNull()
        assertThat(sanitizer.sanitize("   ")).isNull()
        assertThat(sanitizer.toPlainText(null)).isEmpty()
        assertThat(sanitizer.snippet(null)).isEmpty()
    }
}
