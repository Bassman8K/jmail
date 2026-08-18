package com.jmail.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.theme.JMailTheme

/**
 * Renders a message body.
 *
 * JMail displays the plain-text form of every message rather than executing its HTML. That
 * is a deliberate trade: rendering arbitrary sender-authored HTML inside the app means
 * embedding a browser engine on every platform and accepting the tracking, layout hijacking
 * and exploit surface that comes with it. The backend derives clean text from the HTML
 * (see `HtmlSanitizer.toPlainText`) so nothing is lost but presentation, and links are
 * detected and made tappable here.
 *
 * Quoted history is styled as a rail rather than hidden, so a reply's context stays readable
 * without pretending the quoting does not exist.
 */
@Composable
fun MessageBody(
    bodyText: String?,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {},
) {
    val text = bodyText?.takeIf { it.isNotBlank() }

    if (text == null) {
        Text(
            text = "This message has no readable content.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val blocks = remember(text) { splitIntoBlocks(text) }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is BodyBlock.Quote -> QuotedBlock(block.lines, onLinkClick)
                is BodyBlock.Paragraph -> ParagraphBlock(block.text, onLinkClick)
            }
            Spacer(Modifier.height(JMailTheme.spacing.medium))
        }
    }
}

@Composable
private fun ParagraphBlock(text: String, onLinkClick: (String) -> Unit) {
    val annotated = remember(text) { annotateLinks(text) }

    ClickableBodyText(annotated = annotated, onLinkClick = onLinkClick)
}

@Composable
private fun QuotedBlock(lines: List<String>, onLinkClick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        // The rail is the whole affordance: it says "this is history" without a control to
        // press or a chunk of text that vanishes.
        Box(
            Modifier
                .width(3.dp)
                .height((lines.size * 22).dp.coerceAtMost(400.dp))
                .background(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.extraSmall,
                ),
        )
        Spacer(Modifier.width(JMailTheme.spacing.medium))

        Column(Modifier.weight(1f)) {
            lines.forEach { line ->
                Text(
                    text = line.removePrefix(">").trimStart(),
                    style = MaterialTheme.typography.bodySmall,
                    color = JMailTheme.semantic.quotedText,
                )
            }
        }
    }
}

@Composable
private fun ClickableBodyText(annotated: AnnotatedString, onLinkClick: (String) -> Unit) {
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = LINK_TAG, start = offset, end = offset)
                .firstOrNull()
                ?.let { annotation -> onLinkClick(annotation.item) }
        },
    )
}

/** A body split into prose and quoted history. */
internal sealed interface BodyBlock {
    data class Paragraph(val text: String) : BodyBlock
    data class Quote(val lines: List<String>) : BodyBlock
}

/**
 * Groups consecutive quoted lines together so the rail is drawn once per quoted section
 * rather than once per line.
 */
internal fun splitIntoBlocks(body: String): List<BodyBlock> {
    val blocks = mutableListOf<BodyBlock>()
    val paragraph = StringBuilder()
    val quote = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += BodyBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    fun flushQuote() {
        if (quote.isNotEmpty()) blocks += BodyBlock.Quote(quote.toList())
        quote.clear()
    }

    body.lines().forEach { line ->
        when {
            line.trimStart().startsWith(">") -> {
                flushParagraph()
                quote += line
            }

            line.isBlank() -> {
                flushQuote()
                flushParagraph()
            }

            else -> {
                flushQuote()
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }

    flushQuote()
    flushParagraph()
    return blocks
}

private const val LINK_TAG = "url"

/**
 * Finds bare URLs and email addresses and marks them up.
 *
 * Deliberately conservative: it matches http(s) and mailto-able addresses only, and trailing
 * punctuation is excluded so "see https://example.com." does not produce a broken link.
 */
internal fun annotateLinks(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""(https?://[^\s<>"]+|[\w.+-]+@[\w-]+\.[\w.-]+)""")
    var lastIndex = 0

    pattern.findAll(text).forEach { match ->
        append(text.substring(lastIndex, match.range.first))

        val raw = match.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
        val target = if (raw.contains("@") && !raw.startsWith("http")) "mailto:$raw" else raw

        pushStringAnnotation(tag = LINK_TAG, annotation = target)
        withStyle(
            SpanStyle(
                color = Color(0xFF4F46E5),
                textDecoration = TextDecoration.Underline,
            ),
        ) {
            append(raw)
        }
        pop()

        // Anything trimmed off the end is punctuation and belongs to the sentence.
        append(match.value.removePrefix(raw))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) append(text.substring(lastIndex))
}
