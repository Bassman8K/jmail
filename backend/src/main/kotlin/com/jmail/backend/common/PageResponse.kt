package com.jmail.backend.common

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.data.domain.Page

@Schema(description = "A page of results with the cursor the client needs to fetch the next one")
data class PageResponse<T>(
    val items: List<T>,
    @get:Schema(description = "Zero-based page index", example = "0")
    val page: Int,
    @get:Schema(description = "Requested page size", example = "50")
    val size: Int,
    @get:Schema(description = "Total matching rows", example = "1284")
    val totalElements: Long,
    val totalPages: Int,
    @get:Schema(description = "True when another page exists after this one")
    val hasMore: Boolean,
) {
    companion object {
        fun <T> of(page: Page<T>): PageResponse<T> = PageResponse(
            items = page.content,
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            hasMore = page.hasNext(),
        )

        fun <S, T> of(page: Page<S>, transform: (S) -> T): PageResponse<T> = PageResponse(
            items = page.content.map(transform),
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            hasMore = page.hasNext(),
        )
    }
}
