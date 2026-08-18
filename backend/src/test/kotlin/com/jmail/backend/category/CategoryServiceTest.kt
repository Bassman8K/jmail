package com.jmail.backend.category

import com.jmail.backend.auth.AuthenticatedUser
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
import com.jmail.backend.user.MailAccountRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Category management, with the ownership boundary as the main subject: a user may shape
 * their own categories freely, and must not be able to touch the shared built-in ones that
 * every other user depends on.
 */
class CategoryServiceTest {

    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val ruleRepository: CategoryRuleRepository = mockk(relaxed = true)
    private val messageRepository: MessageRepository = mockk(relaxed = true)
    private val accountRepository: MailAccountRepository = mockk(relaxed = true)
    private val engine: CategorizationEngine = mockk(relaxed = true)

    private lateinit var service: CategoryService

    private val user = AuthenticatedUser(UUID.randomUUID(), "ada@example.com", "Ada")

    @BeforeEach
    fun setUp() {
        service = CategoryService(categoryRepository, ruleRepository, messageRepository, accountRepository, engine)
        every { accountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(any()) } returns emptyList()
    }

    private fun systemCategory(key: String = "primary") = Category(
        id = UUID.randomUUID(),
        userId = null,
        key = key,
        name = key.replaceFirstChar(Char::uppercase),
        isSystem = true,
    )

    private fun userCategory(owner: UUID = user.userId, key: String = "work") = Category(
        id = UUID.randomUUID(),
        userId = owner,
        key = key,
        name = "Work",
        isSystem = false,
    )

    @Test
    fun `names become stable slugs the client can map to icons`() {
        assertEquals("work-clients", service.slugify("Work / Clients"))
        assertEquals("reunion", service.slugify("Réunion"))
        assertEquals("hello-world", service.slugify("  Hello   World!  "))
        assertEquals("a-b", service.slugify("a---b"))
        assertEquals("", service.slugify("!!!"))
        assertTrue(service.slugify("x".repeat(200)).length <= 64)
    }

    @Test
    fun `creating a category derives its key and appends it after the built-in set`() {
        every { categoryRepository.existsByUserIdAndKey(user.userId, "work-clients") } returns false
        every { categoryRepository.maxPositionFor(user.userId) } returns 2
        val saved = slot<Category>()
        every { categoryRepository.save(capture(saved)) } answers { saved.captured }

        val response = service.create(user, CreateCategoryRequest(name = "Work / Clients", color = "#123456"))

        assertEquals("work-clients", response.key)
        assertEquals(user.userId, saved.captured.userId)
        assertTrue(saved.captured.position > 2)
        verify { engine.invalidate(user.userId) }
    }

    @Test
    fun `a duplicate category name is refused`() {
        every { categoryRepository.existsByUserIdAndKey(user.userId, "work") } returns true

        assertThrows<ConflictException> { service.create(user, CreateCategoryRequest(name = "Work")) }
    }

    @Test
    fun `a name that slugifies to nothing is refused with an explanation`() {
        assertThrows<BadRequestException> { service.create(user, CreateCategoryRequest(name = "!!!")) }
    }

    @Test
    fun `a built-in category cannot be updated or deleted`() {
        val system = systemCategory()
        every { categoryRepository.findById(system.id) } returns Optional.of(system)

        assertThrows<ForbiddenException> { service.update(user, system.id, UpdateCategoryRequest(name = "Mine")) }
        assertThrows<ForbiddenException> { service.delete(user, system.id) }
    }

    @Test
    fun `another user's category is reported as not found rather than forbidden`() {
        val foreign = userCategory(owner = UUID.randomUUID())
        every { categoryRepository.findById(foreign.id) } returns Optional.of(foreign)

        // "Not found" leaks nothing about whether the id exists at all.
        assertThrows<NotFoundException> { service.update(user, foreign.id, UpdateCategoryRequest(name = "x")) }
    }

    @Test
    fun `deleting a category keeps its messages and detaches them`() {
        val category = userCategory()
        every { categoryRepository.findById(category.id) } returns Optional.of(category)

        service.delete(user, category.id)

        verify { messageRepository.detachCategory(category.id) }
        verify { ruleRepository.deleteAllByCategoryId(category.id) }
        verify { categoryRepository.delete(category) }
    }

    @Test
    fun `reordering moves the user's own categories and leaves shared ones alone`() {
        val system = systemCategory()
        val own = userCategory()
        every { categoryRepository.findVisibleFor(user.userId) } returns listOf(system, own)
        every { categoryRepository.saveAll(any<List<Category>>()) } returnsArgument 0

        service.reorder(user, ReorderCategoriesRequest(listOf(own.id, system.id)))

        val saved = slot<List<Category>>()
        verify { categoryRepository.saveAll(capture(saved)) }
        assertEquals(listOf(own.id), saved.captured.map(Category::id))
        assertEquals(0, own.position)
    }

    @Test
    fun `reordering with an unknown id fails rather than silently ignoring it`() {
        every { categoryRepository.findVisibleFor(user.userId) } returns emptyList()

        assertThrows<NotFoundException> {
            service.reorder(user, ReorderCategoriesRequest(listOf(UUID.randomUUID())))
        }
    }

    @Test
    fun `a rule is validated before it is stored`() {
        val category = userCategory()
        every { categoryRepository.findById(category.id) } returns Optional.of(category)

        assertThrows<BadRequestException> {
            service.addRule(
                user,
                category.id,
                CreateRuleRequest(RuleField.SUBJECT, RuleOperation.REGEX, "invoice ["),
            )
        }

        // A domain rule matches the part after the @, so including one is a mistake worth naming.
        assertThrows<BadRequestException> {
            service.addRule(
                user,
                category.id,
                CreateRuleRequest(RuleField.SENDER_DOMAIN, RuleOperation.ENDS_WITH, "someone@example.com"),
            )
        }

        assertThrows<BadRequestException> {
            service.addRule(
                user,
                category.id,
                CreateRuleRequest(RuleField.SUBJECT, RuleOperation.CONTAINS, "   "),
            )
        }
    }

    @Test
    fun `a valid rule is stored and invalidates the cached rule set`() {
        val category = userCategory()
        every { categoryRepository.findById(category.id) } returns Optional.of(category)
        val saved = slot<CategoryRule>()
        every { ruleRepository.save(capture(saved)) } answers { saved.captured }

        val response = service.addRule(
            user,
            category.id,
            CreateRuleRequest(RuleField.SENDER_DOMAIN, RuleOperation.ENDS_WITH, " example.com ", weight = 70),
        )

        assertEquals("example.com", response.value) // trimmed
        assertEquals(70, response.weight)
        verify { engine.invalidate(user.userId) }
    }

    @Test
    fun `updating a rule that belongs to another category is not found`() {
        val category = userCategory()
        val rule = CategoryRule(categoryId = UUID.randomUUID(), value = "x")
        every { categoryRepository.findById(category.id) } returns Optional.of(category)
        every { ruleRepository.findById(rule.id) } returns Optional.of(rule)

        assertThrows<NotFoundException> {
            service.updateRule(user, category.id, rule.id, UpdateRuleRequest(value = "y"))
        }
    }

    @Test
    fun `updating a rule applies only the fields provided`() {
        val category = userCategory()
        val rule = CategoryRule(
            categoryId = category.id,
            field = RuleField.SUBJECT,
            operation = RuleOperation.CONTAINS,
            value = "sale",
            weight = 30,
        )
        every { categoryRepository.findById(category.id) } returns Optional.of(category)
        every { ruleRepository.findById(rule.id) } returns Optional.of(rule)
        every { ruleRepository.save(any()) } returnsArgument 0

        val response = service.updateRule(user, category.id, rule.id, UpdateRuleRequest(weight = 80))

        assertEquals(80, response.weight)
        assertEquals("sale", response.value) // untouched
        assertEquals(RuleField.SUBJECT, response.field)
    }

    @Test
    fun `listing includes both shared and personal categories with their counts`() {
        val accountId = UUID.randomUUID()
        val system = systemCategory("promotions")
        every { categoryRepository.findVisibleFor(user.userId) } returns listOf(system)
        every { accountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(user.userId) } returns
            listOf(com.jmail.backend.user.MailAccount(id = accountId, userId = user.userId))
        every { messageRepository.countsByCategory(listOf(accountId)) } returns listOf(
            object : com.jmail.backend.mail.CategoryCountProjection {
                override val categoryId = system.id
                override val total = 12L
                override val unread = 4L
            },
        )
        every { ruleRepository.countByCategoryId(system.id) } returns 9

        val response = service.list(user).single()

        assertEquals(12, response.total)
        assertEquals(4, response.unread)
        assertEquals(9, response.ruleCount)
    }

    @Test
    fun `listing without counts skips the aggregation entirely`() {
        every { categoryRepository.findVisibleFor(user.userId) } returns listOf(systemCategory())

        val response = service.list(user, withCounts = false).single()

        assertEquals(0, response.total)
        verify(exactly = 0) { messageRepository.countsByCategory(any()) }
    }
}
