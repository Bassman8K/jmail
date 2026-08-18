package com.jmail.backend.mail

import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

/** The filters the message list supports, as sent by the client. */
data class MessageFilter(
    val accountIds: Collection<UUID>,
    val accountId: UUID? = null,
    val folderId: UUID? = null,
    val folderType: FolderType? = null,
    val categoryId: UUID? = null,
    val unreadOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val withAttachmentsOnly: Boolean = false,
    val includeTrashed: Boolean = false,
    val includeSpam: Boolean = false,
    val includeDrafts: Boolean = true,
    val from: String? = null,
)

/**
 * Builds the message-list query.
 *
 * Criteria API rather than a JPQL string with `(:param IS NULL OR …)` guards: those defeat
 * the query planner's ability to use the partial indexes on `is_read` and `is_starred`, and
 * they force PostgreSQL to infer types for null-valued parameters, which it cannot always do.
 */
object MessageSpecifications {

    fun matching(filter: MessageFilter, folderIdsByType: Collection<UUID> = emptyList()): Specification<Message> =
        Specification { root, _, builder ->
            val predicates = mutableListOf<Predicate>()

            // Ownership: always scoped to the caller's own accounts. An empty collection
            // must match nothing rather than everything.
            predicates += if (filter.accountIds.isEmpty()) {
                builder.disjunction()
            } else {
                root.get<UUID>("accountId").`in`(filter.accountIds)
            }

            filter.accountId?.let { predicates += builder.equal(root.get<UUID>("accountId"), it) }
            filter.folderId?.let { predicates += builder.equal(root.get<UUID>("folderId"), it) }

            if (filter.folderType != null && folderIdsByType.isNotEmpty()) {
                predicates += root.get<UUID>("folderId").`in`(folderIdsByType)
            }

            filter.categoryId?.let { predicates += builder.equal(root.get<UUID>("categoryId"), it) }

            if (filter.unreadOnly) predicates += builder.isFalse(root.get("isRead"))
            if (filter.starredOnly) predicates += builder.isTrue(root.get("isStarred"))
            if (filter.withAttachmentsOnly) predicates += builder.isTrue(root.get("hasAttachments"))

            // Trash, spam and drafts are excluded from every list unless explicitly asked for;
            // seeing deleted mail in the inbox is never what someone means.
            if (!filter.includeTrashed) predicates += builder.isFalse(root.get("isTrashed"))
            if (!filter.includeSpam) predicates += builder.isFalse(root.get("isSpam"))
            if (!filter.includeDrafts) predicates += builder.isFalse(root.get("isDraft"))

            filter.from?.takeIf { it.isNotBlank() }?.let { sender ->
                val pattern = "%${sender.lowercase()}%"
                predicates += builder.or(
                    builder.like(builder.lower(root.get("fromAddress")), pattern),
                    builder.like(builder.lower(root.get("fromName")), pattern),
                )
            }

            builder.and(*predicates.toTypedArray())
        }
}
