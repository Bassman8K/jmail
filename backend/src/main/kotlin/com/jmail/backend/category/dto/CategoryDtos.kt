package com.jmail.backend.category.dto

import com.jmail.backend.category.Category
import com.jmail.backend.category.CategoryRule
import com.jmail.backend.category.RuleField
import com.jmail.backend.category.RuleOperation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

@Schema(description = "A category, with the badge counts the sidebar needs")
data class CategoryResponse(
    val id: UUID,
    @get:Schema(description = "Stable key the client maps to an icon", example = "promotions")
    val key: String,
    val name: String,
    val description: String?,
    val color: String,
    val icon: String,
    val position: Int,
    @get:Schema(description = "True for the built-in categories, which cannot be deleted")
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val total: Long = 0,
    val unread: Long = 0,
    val ruleCount: Long = 0,
) {
    companion object {
        fun from(category: Category, total: Long = 0, unread: Long = 0, ruleCount: Long = 0) = CategoryResponse(
            id = category.id,
            key = category.key,
            name = category.name,
            description = category.description,
            color = category.color,
            icon = category.icon,
            position = category.position,
            isSystem = category.isSystem,
            isEnabled = category.isEnabled,
            total = total,
            unread = unread,
            ruleCount = ruleCount,
        )
    }
}

data class CreateCategoryRequest(
    @field:NotBlank(message = "A name is required")
    @field:Size(max = 100)
    val name: String,

    @field:Size(max = 300)
    val description: String? = null,

    @field:Pattern(
        regexp = "^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
        message = "Use a hex colour such as #4F46E5",
    )
    val color: String = "#4F46E5",

    @field:Size(max = 64)
    val icon: String = "label",
)

data class UpdateCategoryRequest(
    @field:Size(max = 100)
    val name: String? = null,

    @field:Size(max = 300)
    val description: String? = null,

    @field:Pattern(
        regexp = "^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
        message = "Use a hex colour such as #4F46E5",
    )
    val color: String? = null,

    @field:Size(max = 64)
    val icon: String? = null,

    val isEnabled: Boolean? = null,
)

data class ReorderCategoriesRequest(
    @get:Schema(description = "Category ids in the order they should appear")
    val orderedIds: List<UUID>,
)

@Schema(description = "One condition that files a message into a category")
data class CategoryRuleResponse(
    val id: UUID,
    val categoryId: UUID,
    val field: RuleField,
    val operation: RuleOperation,
    val value: String,
    @get:Schema(description = "1–100; higher weights win when several categories match")
    val weight: Int,
    val isEnabled: Boolean,
) {
    companion object {
        fun from(rule: CategoryRule) = CategoryRuleResponse(
            id = rule.id,
            categoryId = rule.categoryId,
            field = rule.field,
            operation = rule.operation,
            value = rule.value,
            weight = rule.weight,
            isEnabled = rule.isEnabled,
        )
    }
}

data class CreateRuleRequest(
    val field: RuleField,
    val operation: RuleOperation,

    @field:NotBlank(message = "A value to match is required")
    @field:Size(max = 500)
    val value: String,

    @field:Min(1) @field:Max(100)
    val weight: Int = 40,

    val isEnabled: Boolean = true,
)

data class UpdateRuleRequest(
    val field: RuleField? = null,
    val operation: RuleOperation? = null,
    @field:Size(max = 500) val value: String? = null,
    @field:Min(1) @field:Max(100) val weight: Int? = null,
    val isEnabled: Boolean? = null,
)

@Schema(description = "Result of re-running classification over stored messages")
data class ReclassifyResponse(val reclassified: Int)
