package com.jmail.backend.category

import com.jmail.backend.auth.AuthenticatedUser
import com.jmail.backend.category.dto.CategoryResponse
import com.jmail.backend.category.dto.CategoryRuleResponse
import com.jmail.backend.category.dto.CreateCategoryRequest
import com.jmail.backend.category.dto.CreateRuleRequest
import com.jmail.backend.category.dto.ReorderCategoriesRequest
import com.jmail.backend.category.dto.UpdateCategoryRequest
import com.jmail.backend.category.dto.UpdateRuleRequest
import com.jmail.backend.common.BadRequestException
import com.jmail.backend.common.ConflictException
import com.jmail.backend.common.ForbiddenException
import com.jmail.backend.common.NotFoundException
import com.jmail.backend.mail.MessageRepository
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.MailAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.util.UUID

/**
 * Categories and the rules that fill them.
 *
 * System categories are shared and immutable; a user can hide one but never edit or delete
 * it, because other users depend on the same rows. Their own categories sit alongside in one
 * ordered list, which is what makes the sidebar feel like a single, coherent set.
 */
@Service
class CategoryService(
    private val categoryRepository: CategoryRepository,
    private val categoryRuleRepository: CategoryRuleRepository,
    private val messageRepository: MessageRepository,
    private val mailAccountRepository: MailAccountRepository,
    private val categorizationEngine: CategorizationEngine,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(user: AuthenticatedUser, withCounts: Boolean = true): List<CategoryResponse> {
        val categories = categoryRepository.findVisibleFor(user.userId)
        if (!withCounts) return categories.map { CategoryResponse.from(it) }

        val accountIds = accountIdsOf(user)
        val counts = if (accountIds.isEmpty()) {
            emptyMap()
        } else {
            messageRepository.countsByCategory(accountIds).associateBy { it.categoryId }
        }

        return categories.map { category ->
            val count = counts[category.id]
            CategoryResponse.from(
                category = category,
                total = count?.total ?: 0,
                unread = count?.unread ?: 0,
                ruleCount = categoryRuleRepository.countByCategoryId(category.id),
            )
        }
    }

    @Transactional
    fun create(user: AuthenticatedUser, request: CreateCategoryRequest): CategoryResponse {
        val key = slugify(request.name)
        if (key.isBlank()) {
            throw BadRequestException("invalid_category_name", "That name cannot be turned into a category")
        }
        if (categoryRepository.existsByUserIdAndKey(user.userId, key)) {
            throw ConflictException("category_exists", "You already have a category called \"${request.name}\"")
        }

        val category = categoryRepository.save(
            Category(
                userId = user.userId,
                key = key,
                name = request.name.trim(),
                description = request.description?.trim(),
                color = request.color,
                icon = request.icon,
                // New categories go to the end of the user's own list, after the system set.
                position = categoryRepository.maxPositionFor(user.userId) + SYSTEM_CATEGORY_COUNT + 1,
                isSystem = false,
            ),
        )

        categorizationEngine.invalidate(user.userId)
        log.info("User {} created category {}", user.userId, category.key)
        return CategoryResponse.from(category)
    }

    @Transactional
    fun update(user: AuthenticatedUser, categoryId: UUID, request: UpdateCategoryRequest): CategoryResponse {
        val category = ownedCategory(user, categoryId)

        request.name?.let { category.name = it.trim() }
        request.description?.let { category.description = it.trim() }
        request.color?.let { category.color = it }
        request.icon?.let { category.icon = it }
        request.isEnabled?.let { category.isEnabled = it }

        val saved = categoryRepository.save(category)
        categorizationEngine.invalidate(user.userId)
        return CategoryResponse.from(saved)
    }

    @Transactional
    fun delete(user: AuthenticatedUser, categoryId: UUID) {
        val category = ownedCategory(user, categoryId)

        // Messages are not deleted with their category — they fall back to uncategorised and
        // are picked up by the classifier again on the next reclassify.
        messageRepository.detachCategory(category.id)
        categoryRuleRepository.deleteAllByCategoryId(category.id)
        categoryRepository.delete(category)

        categorizationEngine.invalidate(user.userId)
        log.info("User {} deleted category {}", user.userId, category.key)
    }

    @Transactional
    fun reorder(user: AuthenticatedUser, request: ReorderCategoriesRequest): List<CategoryResponse> {
        val visible = categoryRepository.findVisibleFor(user.userId).associateBy(Category::id)
        val ownCategories = mutableListOf<Category>()

        request.orderedIds.forEachIndexed { index, id ->
            val category = visible[id] ?: throw NotFoundException("Category", id)
            // System categories are shared rows; a user's ordering of them is not persisted
            // globally, so only their own categories move.
            if (category.userId == user.userId) {
                category.position = index
                ownCategories += category
            }
        }

        categoryRepository.saveAll(ownCategories)
        return list(user)
    }

    // ---- rules ------------------------------------------------------------

    @Transactional(readOnly = true)
    fun rules(user: AuthenticatedUser, categoryId: UUID): List<CategoryRuleResponse> {
        readableCategory(user, categoryId)
        return categoryRuleRepository.findAllByCategoryIdOrderByWeightDesc(categoryId)
            .map(CategoryRuleResponse::from)
    }

    @Transactional
    fun addRule(user: AuthenticatedUser, categoryId: UUID, request: CreateRuleRequest): CategoryRuleResponse {
        val category = ownedCategory(user, categoryId)
        validateRuleValue(request.field, request.operation, request.value)

        val rule = categoryRuleRepository.save(
            CategoryRule(
                categoryId = category.id,
                field = request.field,
                operation = request.operation,
                value = request.value.trim(),
                weight = request.weight,
                isEnabled = request.isEnabled,
            ),
        )

        categorizationEngine.invalidate(user.userId)
        return CategoryRuleResponse.from(rule)
    }

    @Transactional
    fun updateRule(
        user: AuthenticatedUser,
        categoryId: UUID,
        ruleId: UUID,
        request: UpdateRuleRequest,
    ): CategoryRuleResponse {
        ownedCategory(user, categoryId)
        val rule = categoryRuleRepository.findById(ruleId).orElseThrow { NotFoundException("Rule", ruleId) }
        if (rule.categoryId != categoryId) throw NotFoundException("Rule", ruleId)

        request.field?.let { rule.field = it }
        request.operation?.let { rule.operation = it }
        request.value?.let { rule.value = it.trim() }
        request.weight?.let { rule.weight = it }
        request.isEnabled?.let { rule.isEnabled = it }
        validateRuleValue(rule.field, rule.operation, rule.value)

        val saved = categoryRuleRepository.save(rule)
        categorizationEngine.invalidate(user.userId)
        return CategoryRuleResponse.from(saved)
    }

    @Transactional
    fun deleteRule(user: AuthenticatedUser, categoryId: UUID, ruleId: UUID) {
        ownedCategory(user, categoryId)
        val rule = categoryRuleRepository.findById(ruleId).orElseThrow { NotFoundException("Rule", ruleId) }
        if (rule.categoryId != categoryId) throw NotFoundException("Rule", ruleId)

        categoryRuleRepository.delete(rule)
        categorizationEngine.invalidate(user.userId)
    }

    // ---- helpers ----------------------------------------------------------

    /** A category the user may modify: their own, never a shared system one. */
    private fun ownedCategory(user: AuthenticatedUser, categoryId: UUID): Category {
        val category = categoryRepository.findById(categoryId).orElseThrow {
            NotFoundException("Category", categoryId)
        }
        if (category.isSystem || category.userId == null) {
            throw ForbiddenException("Built-in categories cannot be changed. Create your own instead.")
        }
        if (category.userId != user.userId) throw NotFoundException("Category", categoryId)
        return category
    }

    private fun readableCategory(user: AuthenticatedUser, categoryId: UUID): Category {
        val category = categoryRepository.findById(categoryId).orElseThrow {
            NotFoundException("Category", categoryId)
        }
        if (category.userId != null && category.userId != user.userId) {
            throw NotFoundException("Category", categoryId)
        }
        return category
    }

    private fun validateRuleValue(field: RuleField, operation: RuleOperation, value: String) {
        if (value.isBlank()) {
            throw BadRequestException("empty_rule_value", "A rule needs something to match on")
        }
        if (operation == RuleOperation.REGEX) {
            runCatching { Regex(value) }.onFailure {
                throw BadRequestException(
                    "invalid_regex",
                    "That is not a valid regular expression: ${it.message}",
                    mapOf("field" to "value"),
                )
            }
        }
        if (field == RuleField.SENDER_DOMAIN && value.contains('@')) {
            throw BadRequestException(
                "invalid_domain",
                "A domain rule matches the part after the @, so leave it out",
                mapOf("field" to "value"),
            )
        }
    }

    /** "Work / Clients!" becomes "work-clients", which is what the client uses for icons. */
    internal fun slugify(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(64)

    private fun accountIdsOf(user: AuthenticatedUser): List<UUID> =
        mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(user.userId).map(MailAccount::id)

    private companion object {
        /** Keeps user categories sorted after the built-in set by default. */
        const val SYSTEM_CATEGORY_COUNT = 8
    }
}
