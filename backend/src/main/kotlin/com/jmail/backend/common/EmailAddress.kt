package com.jmail.backend.common

import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.Serializable

/** A single mailbox: the address plus the display name it was presented with. */
@Serializable
@Schema(description = "An email address with its optional display name")
data class EmailAddress(
    @get:Schema(example = "ada@example.com")
    val address: String,
    @get:Schema(example = "Ada Lovelace")
    val name: String? = null,
) {
    /** "Ada Lovelace <ada@example.com>", or just the address when no name is known. */
    fun format(): String = if (name.isNullOrBlank()) address else "$name <$address>"
}

/**
 * Parsing and normalisation for addresses arriving from providers, which vary wildly in
 * how they quote and encode headers.
 */
object EmailAddresses {

    // Deliberately permissive: this validates shape, not deliverability. Anything stricter
    // rejects addresses that real servers accept (plus-tags, long TLDs, unicode domains).
    private val ADDRESS_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    private val ANGLE_BRACKET_PATTERN = Regex("^\\s*(.*?)\\s*<([^>]+)>\\s*$")

    /**
     * Lowercases the address for storage and comparison.
     *
     * The local part is technically case-sensitive per RFC 5321, but no provider JMail
     * supports treats it that way, and storing a canonical form is what makes the unique
     * indexes and account de-duplication correct.
     */
    fun canonical(address: String): String = address.trim().lowercase()

    fun isValid(address: String): Boolean = ADDRESS_PATTERN.matches(address.trim())

    /** The part after the `@`, lowercased; empty when the address is malformed. */
    fun domainOf(address: String): String = address.substringAfterLast('@', "").trim().lowercase()

    /**
     * Parses one header value such as `"Ada Lovelace" <ada@example.com>` or `ada@example.com`.
     * Returns null when no address can be recovered.
     */
    fun parse(raw: String): EmailAddress? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        val match = ANGLE_BRACKET_PATTERN.find(value)
        return if (match != null) {
            val name = match.groupValues[1].trim().trim('"').takeIf { it.isNotEmpty() }
            val address = canonical(match.groupValues[2])
            if (isValid(address)) EmailAddress(address, name) else null
        } else {
            val address = canonical(value)
            if (isValid(address)) EmailAddress(address) else null
        }
    }

    /** Parses a comma-separated header (To, Cc, Bcc), skipping anything unparseable. */
    fun parseList(raw: String?): List<EmailAddress> {
        if (raw.isNullOrBlank()) return emptyList()
        return splitTopLevel(raw).mapNotNull(::parse)
    }

    /**
     * Splits on commas that are not inside quotes or angle brackets, so display names
     * containing commas ("Lovelace, Ada" <ada@example.com>) survive intact.
     */
    private fun splitTopLevel(raw: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var inAngles = false

        for (character in raw) {
            when {
                character == '"' -> {
                    inQuotes = !inQuotes
                    current.append(character)
                }
                character == '<' && !inQuotes -> {
                    inAngles = true
                    current.append(character)
                }
                character == '>' && !inQuotes -> {
                    inAngles = false
                    current.append(character)
                }
                character == ',' && !inQuotes && !inAngles -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }
}
