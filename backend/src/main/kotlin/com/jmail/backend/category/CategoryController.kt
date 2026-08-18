package com.jmail.backend.category

import com.jmail.backend.auth.AuthenticatedUser
import com.jmail.backend.category.dto.CategoryResponse
import com.jmail.backend.category.dto.CategoryRuleResponse
import com.jmail.backend.category.dto.CreateCategoryRequest
import com.jmail.backend.category.dto.CreateRuleRequest
import com.jmail.backend.category.dto.ReclassifyResponse
import com.jmail.backend.category.dto.ReorderCategoriesRequest
import com.jmail.backend.category.dto.UpdateCategoryRequest
import com.jmail.backend.category.dto.UpdateRuleRequest
import com.jmail.backend.mail.MessageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/categories")
@Validated
@Tag(name = "Categories", description = "The built-in and user-defined categories, and their rules")
class CategoryController(
    private val categoryService: CategoryService,
    private val messageService: MessageService,
) {

    @GetMapping
    @Operation(summary = "List every category visible to the user, with badge counts")
    fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "true") withCounts: Boolean,
    ): List<CategoryResponse> = categoryService.list(user, withCounts)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a category")
    fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: CreateCategoryRequest,
    ): CategoryResponse = categoryService.create(user, request)

    @PatchMapping("/{categoryId}")
    @Operation(
        summary = "Update a category",
        description = "Built-in categories cannot be changed; create your own instead.",
    )
    fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable categoryId: UUID,
        @Valid @RequestBody request: UpdateCategoryRequest,
    ): CategoryResponse = categoryService.update(user, categoryId, request)

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Delete a category",
        description = "Its messages are kept and become uncategorised.",
    )
    fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable categoryId: UUID,
    ) = categoryService.delete(user, categoryId)

    @PutMapping("/order")
    @Operation(summary = "Reorder the sidebar")
    fun reorder(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: ReorderCategoriesRequest,
    ): List<CategoryResponse> = categoryService.reorder(user, request)

    @GetMapping("/{categoryId}/rules")
    @Operation(summary = "The rules that file messages into a category")
    fun rules(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable categoryId: UUID,
    ): List<CategoryRuleResponse> = categoryService.rules(user, categoryId)

    @PostMapping("/{categoryId}/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a rule")
    fun addRule(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable categoryId: UUID,
        @Valid @RequestBody request: CreateRuleRequest,
    ): CategoryRuleResponse = categoryService.addRule(user, categoryId, request)

    @PatchMapping("/{categoryId}/rules/{ruleId}")
    @Operation(summary = "Update a rule")
    fun updateRule(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable categoryId: UUID,
        @PathVariable ruleId: UUID,
        @Valid @RequestBody request: UpdateRuleRequest,
    ): CategoryRuleResponse = categoryService.updateRule(user, categoryId, ruleId, request)

    @DeleteMapping("/{categoryId}/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a rule")
    fun deleteRule(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable categoryId: UUID,
        @PathVariable ruleId: UUID,
    ) = categoryService.deleteRule(user, categoryId, ruleId)

    @PostMapping("/reclassify")
    @Operation(
        summary = "Re-run classification over stored messages",
        description = "Use after editing rules. Messages filed by hand are left alone.",
    )
    fun reclassify(@AuthenticationPrincipal user: AuthenticatedUser): ReclassifyResponse =
        ReclassifyResponse(messageService.reclassify(user))
}
