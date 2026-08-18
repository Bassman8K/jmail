package com.jmail.backend.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Component

/**
 * Cleans message HTML before it is stored or rendered.
 *
 * Email bodies are the most hostile input a mail client handles: they are attacker-authored
 * by definition. Everything executable or capable of phoning home is removed — scripts,
 * styles, iframes, objects, event handlers and `javascript:` URLs — leaving the formatting
 * that makes a message readable.
 *
 * Remote images are stripped by default and their source kept in `data-jmail-src`, so the
 * client can offer "show images" without the mere act of opening a message confirming to a
 * sender that the address is live.
 */
@Component
class HtmlSanitizer {

    private val safelist: Safelist = Safelist.relaxed()
        .addTags("hr", "span", "div", "font", "center", "table", "thead", "tbody", "tfoot")
        .addAttributes("a", "href", "title", "target")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addAttributes("td", "colspan", "rowspan", "align", "valign")
        .addAttributes("th", "colspan", "rowspan", "align", "valign")
        .addAttributes("table", "border", "cellpadding", "cellspacing", "align", "width")
        .addAttributes(":all", "dir", "lang")
        // Only these schemes may appear in a link; anything else (javascript:, data:, vbscript:)
        // is dropped along with the attribute.
        .addProtocols("a", "href", "http", "https", "mailto", "tel")
        .addProtocols("img", "src", "http", "https", "cid")
        .preserveRelativeLinks(false)

    /**
     * @param allowRemoteImages when false, `<img src="http…">` is rewritten so nothing loads
     *   until the user asks for it.
     */
    fun sanitize(html: String?, allowRemoteImages: Boolean = false): String? {
        if (html.isNullOrBlank()) return null

        val cleaned = Jsoup.clean(html, "", safelist, Document.OutputSettings().prettyPrint(false))
        val document = Jsoup.parseBodyFragment(cleaned)

        // Links open outside the reading pane and must not hand the opener window to the
        // destination — noopener is what stops tabnabbing.
        document.select("a[href]").forEach { anchor ->
            anchor.attr("target", "_blank")
            anchor.attr("rel", "noopener noreferrer nofollow")
        }

        if (!allowRemoteImages) {
            document.select("img[src]").forEach { image ->
                val source = image.attr("src")
                if (source.startsWith("http://", ignoreCase = true) ||
                    source.startsWith("https://", ignoreCase = true)
                ) {
                    image.attr("data-jmail-src", source)
                    image.removeAttr("src")
                    image.addClass("jmail-blocked-image")
                }
            }
        }

        return document.body().html()
    }

    /** True when the body contains at least one remote image, which drives the UI banner. */
    fun containsRemoteImages(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        return Jsoup.parseBodyFragment(html)
            .select("img")
            .any { image ->
                val source = image.attr("src").ifBlank { image.attr("data-jmail-src") }
                source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)
            }
    }

    /**
     * Plain-text fallback for messages that only carry HTML, used for the list snippet and
     * the search index. Block-level elements become line breaks so the result reads as prose
     * rather than one long run-on line.
     */
    fun toPlainText(html: String?): String {
        if (html.isNullOrBlank()) return ""

        val document = Jsoup.parseBodyFragment(html)
        document.select("br").before("\\n")
        document.select("p, div, tr, li, h1, h2, h3, h4, h5, h6").before("\\n")

        return Jsoup.clean(
            document.html(),
            "",
            Safelist.none(),
            Document.OutputSettings().prettyPrint(false),
        )
            .replace("\\n", "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .replace(Regex("[ \t]{2,}"), " ")
            .trim()
    }

    /** The one-line preview shown in the message list. */
    fun snippet(text: String?, maxLength: Int = 200): String {
        if (text.isNullOrBlank()) return ""
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength - 1).trimEnd() + "…"
    }
}
