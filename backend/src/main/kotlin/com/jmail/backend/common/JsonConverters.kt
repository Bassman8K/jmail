package com.jmail.backend.common

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Recipient lists and labels are stored as JSON in a `text` column.
 *
 * They are read and written as a unit with their message and are never queried by element,
 * so a child table would add a join to the hottest query in the product for no benefit.
 * `text` rather than `jsonb` keeps the JDBC binding a plain string — no driver-specific
 * type handling, and it round-trips identically on every database used in tests.
 */
private val converterJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Converter
class EmailAddressListConverter : AttributeConverter<List<EmailAddress>, String> {

    override fun convertToDatabaseColumn(attribute: List<EmailAddress>?): String =
        converterJson.encodeToString(ListSerializer(EmailAddress.serializer()), attribute ?: emptyList())

    override fun convertToEntityAttribute(dbData: String?): List<EmailAddress> {
        if (dbData.isNullOrBlank()) return emptyList()
        // Corrupt JSON must not take down the whole message list; an empty recipient
        // list degrades the row gracefully and is logged by the caller if it matters.
        return runCatching {
            converterJson.decodeFromString(ListSerializer(EmailAddress.serializer()), dbData)
        }.getOrDefault(emptyList())
    }
}

@Converter
class StringListConverter : AttributeConverter<List<String>, String> {

    override fun convertToDatabaseColumn(attribute: List<String>?): String =
        converterJson.encodeToString(ListSerializer(String.serializer()), attribute ?: emptyList())

    override fun convertToEntityAttribute(dbData: String?): List<String> {
        if (dbData.isNullOrBlank()) return emptyList()
        return runCatching {
            converterJson.decodeFromString(ListSerializer(String.serializer()), dbData)
        }.getOrDefault(emptyList())
    }
}
