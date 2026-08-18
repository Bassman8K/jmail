package com.jmail.backend.category

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A category is the primary way JMail organises a mailbox: every message lands in exactly
 * one, either from the rules below or because the user moved it there by hand.
 *
 * System categories have a null [userId] and are shared by everyone; a user's own
 * categories live alongside them and are ordered together by [position].
 */
@Entity
@Table(name = "categories")
class Category(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    /** Null for the built-in categories shared by all users. */
    @Column(name = "user_id")
    var userId: UUID? = null,

    /** Stable identifier used by the client for icons and deep links, e.g. `promotions`. */
    @Column(name = "key", nullable = false, length = 64)
    var key: String = "",

    @Column(name = "name", nullable = false, length = 100)
    var name: String = "",

    @Column(name = "description", length = 300)
    var description: String? = null,

    @Column(name = "color", nullable = false, length = 9)
    var color: String = "#4F46E5",

    @Column(name = "icon", nullable = false, length = 64)
    var icon: String = "inbox",

    @Column(name = "position", nullable = false)
    var position: Int = 0,

    @Column(name = "is_system", nullable = false)
    var isSystem: Boolean = false,

    @Column(name = "is_enabled", nullable = false)
    var isEnabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }

    companion object {
        /** The category every message falls back to when no rule scores above zero. */
        const val PRIMARY_KEY = "primary"
    }
}

/** Which part of a message a rule inspects. */
enum class RuleField {
    SENDER,
    SENDER_DOMAIN,
    SUBJECT,
    BODY,
    LIST_ID,
    HEADER,
    RECIPIENT,
}

enum class RuleOperation {
    CONTAINS,
    EQUALS,
    STARTS_WITH,
    ENDS_WITH,
    REGEX,
}

@Entity
@Table(name = "category_rules")
class CategoryRule(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "category_id", nullable = false)
    var categoryId: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, length = 32)
    var field: RuleField = RuleField.SUBJECT,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 32)
    var operation: RuleOperation = RuleOperation.CONTAINS,

    @Column(name = "value", nullable = false, length = 500)
    var value: String = "",

    /**
     * Score contributed when this rule matches (1–100). Specific signals such as a bank's
     * domain deserve a higher weight than a generic keyword so they win ties.
     */
    @Column(name = "weight", nullable = false)
    var weight: Int = 10,

    @Column(name = "is_enabled", nullable = false)
    var isEnabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
