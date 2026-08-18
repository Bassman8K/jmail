package com.jmail.shared.repository

import com.jmail.shared.model.AssignCategoryRequest
import com.jmail.shared.model.BulkActionResult
import com.jmail.shared.model.Category
import com.jmail.shared.model.ComposeRequest
import com.jmail.shared.model.CreateCategoryRequest
import com.jmail.shared.model.MailFolder
import com.jmail.shared.model.MailThread
import com.jmail.shared.model.MailboxCounts
import com.jmail.shared.model.MessageActionRequest
import com.jmail.shared.model.MessageDetail
import com.jmail.shared.model.MessageSummary
import com.jmail.shared.model.Page
import com.jmail.shared.model.SyncResult
import com.jmail.shared.model.UpdateCategoryRequest
import com.jmail.shared.network.ApiResult
import com.jmail.shared.network.JMailApiClient

/** What the message list is currently showing. */
data class MailboxQuery(
    val accountId: String? = null,
    val folderId: String? = null,
    val folderType: String? = "INBOX",
    val categoryId: String? = null,
    val unreadOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val withAttachmentsOnly: Boolean = false,
    val searchQuery: String? = null,
) {
    val isSearch: Boolean get() = !searchQuery.isNullOrBlank()

    /** True when nothing but the default scope is applied, which drives the "clear filters" chip. */
    val hasFilters: Boolean
        get() = unreadOnly || starredOnly || withAttachmentsOnly || categoryId != null
}

/**
 * Reads and writes mail through the API.
 *
 * Intentionally stateless: it holds no cache of its own. The stores above it own what is on
 * screen, which keeps there from being two competing ideas of the truth — the bug that makes
 * mail clients show a message as unread in one place and read in another.
 */
class MailRepository(private val apiClient: JMailApiClient) {

    suspend fun messages(query: MailboxQuery, page: Int, pageSize: Int = DEFAULT_PAGE_SIZE): ApiResult<Page<MessageSummary>> =
        if (query.isSearch) {
            apiClient.search(query.searchQuery!!, page, pageSize)
        } else {
            apiClient.messages(
                accountId = query.accountId,
                folderId = query.folderId,
                folderType = query.folderType,
                categoryId = query.categoryId,
                unreadOnly = query.unreadOnly,
                starredOnly = query.starredOnly,
                withAttachmentsOnly = query.withAttachmentsOnly,
                page = page,
                size = pageSize,
            )
        }

    suspend fun message(messageId: String, loadRemoteImages: Boolean = false): ApiResult<MessageDetail> =
        apiClient.message(messageId, loadRemoteImages)

    suspend fun thread(threadId: String): ApiResult<MailThread> = apiClient.thread(threadId)

    suspend fun counts(): ApiResult<MailboxCounts> = apiClient.counts()

    suspend fun folders(): ApiResult<List<MailFolder>> = apiClient.folders()

    suspend fun categories(): ApiResult<List<Category>> = apiClient.categories()

    suspend fun createCategory(request: CreateCategoryRequest): ApiResult<Category> =
        apiClient.createCategory(request)

    suspend fun updateCategory(categoryId: String, request: UpdateCategoryRequest): ApiResult<Category> =
        apiClient.updateCategory(categoryId, request)

    suspend fun deleteCategory(categoryId: String): ApiResult<Unit> = apiClient.deleteCategory(categoryId)

    suspend fun markRead(messageIds: List<String>, read: Boolean): ApiResult<BulkActionResult> =
        apiClient.applyAction(MessageActionRequest(messageIds = messageIds, isRead = read))

    suspend fun star(messageIds: List<String>, starred: Boolean): ApiResult<BulkActionResult> =
        apiClient.applyAction(MessageActionRequest(messageIds = messageIds, isStarred = starred))

    suspend fun archive(messageIds: List<String>): ApiResult<BulkActionResult> =
        apiClient.applyAction(MessageActionRequest(messageIds = messageIds, isArchived = true))

    suspend fun trash(messageIds: List<String>): ApiResult<BulkActionResult> =
        apiClient.applyAction(MessageActionRequest(messageIds = messageIds, isTrashed = true))

    suspend fun markSpam(messageIds: List<String>): ApiResult<BulkActionResult> =
        apiClient.applyAction(MessageActionRequest(messageIds = messageIds, isSpam = true))

    suspend fun assignCategory(messageIds: List<String>, categoryId: String?): ApiResult<BulkActionResult> =
        apiClient.assignCategory(AssignCategoryRequest(messageIds, categoryId))

    suspend fun send(request: ComposeRequest): ApiResult<MessageDetail> = apiClient.compose(request)

    suspend fun sync(accountId: String? = null): ApiResult<List<SyncResult>> = apiClient.sync(accountId)

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
