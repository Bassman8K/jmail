package com.jmail.backend.mail

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface FolderRepository : JpaRepository<Folder, UUID> {

    fun findAllByAccountIdOrderByPositionAscNameAsc(accountId: UUID): List<Folder>

    fun findAllByAccountIdIn(accountIds: Collection<UUID>): List<Folder>

    fun findByAccountIdAndRemoteId(accountId: UUID, remoteId: String): Folder?

    fun findFirstByAccountIdAndType(accountId: UUID, type: FolderType): Folder?

    fun deleteAllByAccountId(accountId: UUID)
}

/** Per-category totals for the sidebar, computed in one grouped query. */
interface CategoryCountProjection {
    val categoryId: UUID?
    val total: Long
    val unread: Long
}

/** Per-folder totals, used to keep the folder tree's badges honest after bulk actions. */
interface FolderCountProjection {
    val folderId: UUID
    val total: Long
    val unread: Long
}

@Repository
interface MessageRepository : JpaRepository<Message, UUID>, JpaSpecificationExecutor<Message> {

    fun findByIdAndAccountIdIn(id: UUID, accountIds: Collection<UUID>): Message?

    fun findByAccountIdAndRemoteId(accountId: UUID, remoteId: String): Message?

    fun findAllByAccountIdAndRemoteIdIn(accountId: UUID, remoteIds: Collection<String>): List<Message>

    /** Every message in a conversation, oldest first — the order a thread is read in. */
    fun findAllByAccountIdInAndThreadIdOrderByReceivedAtAsc(
        accountIds: Collection<UUID>,
        threadId: String,
    ): List<Message>

    fun findAllByIdInAndAccountIdIn(ids: Collection<UUID>, accountIds: Collection<UUID>): List<Message>

    fun deleteAllByAccountId(accountId: UUID)

    fun countByAccountId(accountId: UUID): Long

    @Query(
        """
        SELECT message.categoryId AS categoryId,
               COUNT(message)     AS total,
               SUM(CASE WHEN message.isRead = false THEN 1L ELSE 0L END) AS unread
        FROM Message message
        WHERE message.accountId IN :accountIds
          AND message.isTrashed = false
          AND message.isSpam = false
          AND message.isDraft = false
        GROUP BY message.categoryId
        """,
    )
    fun countsByCategory(@Param("accountIds") accountIds: Collection<UUID>): List<CategoryCountProjection>

    @Query(
        """
        SELECT message.folderId AS folderId,
               COUNT(message)   AS total,
               SUM(CASE WHEN message.isRead = false THEN 1L ELSE 0L END) AS unread
        FROM Message message
        WHERE message.accountId IN :accountIds
        GROUP BY message.folderId
        """,
    )
    fun countsByFolder(@Param("accountIds") accountIds: Collection<UUID>): List<FolderCountProjection>

    /**
     * Ranked full-text search over the generated `search_vector` column, with a trigram
     * fallback so partial tokens (order numbers, half-typed addresses) still match.
     *
     * Native because `websearch_to_tsquery` and `ts_rank` have no JPQL equivalent; the
     * parameters are bound, never interpolated.
     */
    @Query(
        value = """
            SELECT * FROM messages message
            WHERE message.account_id IN (:accountIds)
              AND message.is_trashed = false
              AND (
                    message.search_vector @@ websearch_to_tsquery('english', :query)
                 OR message.subject ILIKE CONCAT('%', :query, '%')
                 OR message.from_address ILIKE CONCAT('%', :query, '%')
                 OR message.from_name ILIKE CONCAT('%', :query, '%')
              )
            ORDER BY ts_rank(message.search_vector, websearch_to_tsquery('english', :query)) DESC,
                     message.received_at DESC
        """,
        countQuery = """
            SELECT COUNT(*) FROM messages message
            WHERE message.account_id IN (:accountIds)
              AND message.is_trashed = false
              AND (
                    message.search_vector @@ websearch_to_tsquery('english', :query)
                 OR message.subject ILIKE CONCAT('%', :query, '%')
                 OR message.from_address ILIKE CONCAT('%', :query, '%')
                 OR message.from_name ILIKE CONCAT('%', :query, '%')
              )
        """,
        nativeQuery = true,
    )
    fun search(
        @Param("accountIds") accountIds: Collection<UUID>,
        @Param("query") query: String,
        pageable: Pageable,
    ): Page<Message>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE Message message
        SET message.isRead = :read, message.updatedAt = :now
        WHERE message.id IN :ids AND message.accountId IN :accountIds
        """,
    )
    fun updateReadState(
        @Param("ids") ids: Collection<UUID>,
        @Param("accountIds") accountIds: Collection<UUID>,
        @Param("read") read: Boolean,
        @Param("now") now: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE Message message
        SET message.categoryId = :categoryId,
            message.categoryPinned = true,
            message.categoryConfidence = 1.0,
            message.updatedAt = :now
        WHERE message.id IN :ids AND message.accountId IN :accountIds
        """,
    )
    fun assignCategory(
        @Param("ids") ids: Collection<UUID>,
        @Param("accountIds") accountIds: Collection<UUID>,
        @Param("categoryId") categoryId: UUID?,
        @Param("now") now: Instant,
    ): Int

    /** Clears a deleted category from the messages that referenced it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Message message SET message.categoryId = NULL WHERE message.categoryId = :categoryId")
    fun detachCategory(@Param("categoryId") categoryId: UUID): Int
}

@Repository
interface AttachmentRepository : JpaRepository<Attachment, UUID> {

    fun findAllByMessageId(messageId: UUID): List<Attachment>

    fun findAllByMessageIdIn(messageIds: Collection<UUID>): List<Attachment>

    fun findByIdAndMessageId(id: UUID, messageId: UUID): Attachment?

    fun deleteAllByMessageId(messageId: UUID)
}

@Repository
interface SyncRunRepository : JpaRepository<SyncRun, UUID> {

    fun findFirstByAccountIdOrderByStartedAtDesc(accountId: UUID): SyncRun?

    fun findAllByAccountIdOrderByStartedAtDesc(accountId: UUID, pageable: Pageable): List<SyncRun>
}
