package com.jmail.backend.mail

import com.jmail.backend.auth.AuthenticatedUser
import com.jmail.backend.common.PageResponse
import com.jmail.backend.mail.dto.AssignCategoryRequest
import com.jmail.backend.mail.dto.BulkActionResponse
import com.jmail.backend.mail.dto.ComposeRequest
import com.jmail.backend.mail.dto.FolderResponse
import com.jmail.backend.mail.dto.MailboxCountsResponse
import com.jmail.backend.mail.dto.MessageActionRequest
import com.jmail.backend.mail.dto.MessageDetail
import com.jmail.backend.mail.dto.MessageSummary
import com.jmail.backend.mail.dto.SyncResponse
import com.jmail.backend.mail.dto.ThreadResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.core.io.ByteArrayResource
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The mailbox API.
 *
 * Sorting is fixed to newest-first rather than client-supplied: it is the only order that
 * matches the indexes, and letting a client sort by an arbitrary column is how a mail list
 * turns into a sequential scan of every message a user has ever received.
 */
@RestController
@RequestMapping("/api/v1/messages")
@Validated
@Tag(name = "Messages", description = "Read, search, organise and send mail")
class MessageController(private val messageService: MessageService) {

    @GetMapping
    @Operation(summary = "List messages, newest first")
    fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) folderId: UUID?,
        @RequestParam(required = false) folderType: FolderType?,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "false") starredOnly: Boolean,
        @RequestParam(defaultValue = "false") withAttachmentsOnly: Boolean,
        @RequestParam(defaultValue = "false") includeTrashed: Boolean,
        @RequestParam(defaultValue = "false") includeSpam: Boolean,
        @RequestParam(required = false) from: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @Parameter(description = "Maximum 200 to keep responses bounded")
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) size: Int,
    ): PageResponse<MessageSummary> = messageService.list(
        user = user,
        filter = MessageFilter(
            accountIds = emptyList(), // replaced by the service with the caller's own accounts
            accountId = accountId,
            folderId = folderId,
            folderType = folderType,
            categoryId = categoryId,
            unreadOnly = unreadOnly,
            starredOnly = starredOnly,
            withAttachmentsOnly = withAttachmentsOnly,
            includeTrashed = includeTrashed,
            includeSpam = includeSpam,
            from = from,
        ),
        pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt")),
    )

    @GetMapping("/search")
    @Operation(
        summary = "Full-text search across every connected mailbox",
        description = "Ranked with PostgreSQL full-text search, with a trigram fallback so " +
            "partial tokens such as order numbers still match.",
    )
    fun search(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam q: String,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) size: Int,
    ): PageResponse<MessageSummary> = messageService.search(user, q, PageRequest.of(page, size))

    @GetMapping("/counts")
    @Operation(summary = "Badge counts for every category and folder in one request")
    fun counts(@AuthenticationPrincipal user: AuthenticatedUser): MailboxCountsResponse =
        messageService.counts(user)

    @GetMapping("/folders")
    @Operation(summary = "Every folder across every connected mailbox")
    fun folders(@AuthenticationPrincipal user: AuthenticatedUser): List<FolderResponse> =
        messageService.folders(user)

    @GetMapping("/threads/{threadId}")
    @Operation(summary = "A whole conversation, oldest message first")
    fun thread(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable threadId: String,
    ): ThreadResponse = messageService.thread(user, threadId)

    @GetMapping("/{messageId}")
    @Operation(
        summary = "One message with its body",
        description = "Remote images are blocked unless loadRemoteImages is true, so opening " +
            "a message does not confirm to a sender that the address is live.",
    )
    fun detail(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable messageId: UUID,
        @RequestParam(defaultValue = "false") loadRemoteImages: Boolean,
    ): MessageDetail = messageService.detail(user, messageId, loadRemoteImages)

    @GetMapping("/{messageId}/attachments/{attachmentId}")
    @Operation(summary = "Download an attachment")
    fun downloadAttachment(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable messageId: UUID,
        @PathVariable attachmentId: UUID,
    ): ResponseEntity<ByteArrayResource> {
        val (attachment, bytes) = messageService.downloadAttachment(user, messageId, attachmentId)

        return ResponseEntity.ok()
            .contentType(
                runCatching { MediaType.parseMediaType(attachment.mimeType) }
                    .getOrDefault(MediaType.APPLICATION_OCTET_STREAM),
            )
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                // `attachment` rather than `inline`: an HTML or SVG attachment rendered
                // inline would execute in the app's own origin.
                ContentDisposition.attachment().filename(attachment.filename).build().toString(),
            )
            .contentLength(bytes.size.toLong())
            .body(ByteArrayResource(bytes))
    }

    @PostMapping("/actions")
    @Operation(
        summary = "Change flags on one or more messages",
        description = "Applied locally straight away and pushed to the provider afterwards; " +
            "any message whose upstream update failed is listed in failedRemoteSync.",
    )
    fun applyAction(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: MessageActionRequest,
    ): BulkActionResponse = messageService.applyAction(user, request)

    @PostMapping("/categorize")
    @Operation(
        summary = "File messages into a category by hand",
        description = "Marks them as user-filed, so automatic classification will not move " +
            "them again.",
    )
    fun assignCategory(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: AssignCategoryRequest,
    ): BulkActionResponse = messageService.assignCategory(user, request)

    @PostMapping
    @Operation(summary = "Send a message, or save it as a draft")
    fun compose(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: ComposeRequest,
    ): MessageDetail = messageService.compose(user, request)

    @PostMapping("/sync")
    @Operation(summary = "Sync now, rather than waiting for the scheduled run")
    fun sync(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(required = false) accountId: UUID?,
    ): List<SyncResponse> = messageService.syncNow(user, accountId)
}
