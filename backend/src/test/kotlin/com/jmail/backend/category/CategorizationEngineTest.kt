package com.jmail.backend.category

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The classifier decides where every message lands, so its scoring behaviour is specified
 * here rather than left to emerge. The cases that matter are the ambiguous ones: a message
 * that looks like two categories at once, and a message that looks like none.
 */
class CategorizationEngineTest {

    private val categoryRepository: CategoryRepository = mockk()
    private val ruleRepository: CategoryRuleRepository = mockk()
    private lateinit var engine: CategorizationEngine

    private val userId = UUID.randomUUID()

    private val primary = category("primary", "Primary")
    private val promotions = category("promotions", "Promotions")
    private val finance = category("finance", "Finance")
    private val receipts = category("receipts", "Receipts")

    @BeforeEach
    fun setUp() {
        engine = CategorizationEngine(categoryRepository, ruleRepository)
    }

    @Nested
    @DisplayName("scoring")
    inner class Scoring {

        @Test
        fun `files a message into the category whose rules match`() {
            val ruleSet = ruleSetOf(
                rules = listOf(
                    rule(promotions.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "% off", weight = 45),
                ),
            )

            val result = engine.classify(
                ruleSet,
                input(subject = "Everything is 30% off this weekend"),
            )

            assertThat(result.categoryKey).isEqualTo("promotions")
        }

        @Test
        fun `the strongest evidence wins when two categories both match`() {
            // "Your order" is a stronger receipts signal than "payment" is a finance one,
            // and that ordering is the whole point of weights.
            val ruleSet = ruleSetOf(
                rules = listOf(
                    rule(receipts.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "your order", weight = 65),
                    rule(finance.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "payment", weight = 50),
                ),
            )

            val result = engine.classify(
                ruleSet,
                input(subject = "Your order — payment received"),
            )

            assertThat(result.categoryKey).isEqualTo("receipts")
        }

        @Test
        fun `several matching rules accumulate, so combined evidence beats one strong signal`() {
            val ruleSet = ruleSetOf(
                rules = listOf(
                    rule(promotions.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "sale", weight = 35),
                    rule(promotions.id, RuleField.SENDER, RuleOperation.STARTS_WITH, "marketing@", weight = 45),
                    rule(finance.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "invoice", weight = 60),
                ),
            )

            val result = engine.classify(
                ruleSet,
                input(subject = "Invoice: end of season sale", fromAddress = "marketing@shop.example"),
            )

            assertThat(result.categoryKey).isEqualTo("promotions") // 35 + 45 beats 60
        }

        @Test
        fun `falls back to primary with zero confidence when nothing matches`() {
            val ruleSet = ruleSetOf(
                rules = listOf(
                    rule(promotions.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "% off", weight = 45),
                ),
            )

            val result = engine.classify(ruleSet, input(subject = "Lunch on Thursday?"))

            assertThat(result.categoryKey).isEqualTo("primary")
            assertThat(result.confidence).isEqualTo(0f)
        }

        @Test
        fun `confidence grows with the strength of the match and saturates at one`() {
            val weak = engine.classify(
                ruleSetOf(listOf(rule(promotions.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "deal", 10))),
                input(subject = "A deal for you"),
            )
            val strong = engine.classify(
                ruleSetOf(
                    listOf(
                        rule(promotions.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "deal", 60),
                        rule(promotions.id, RuleField.SENDER, RuleOperation.CONTAINS, "promo", 60),
                    ),
                ),
                input(subject = "A deal for you", fromAddress = "promo@shop.example"),
            )

            assertThat(weak.confidence).isBetween(0.01f, 0.2f)
            assertThat(strong.confidence).isEqualTo(1f) // saturated, never above 1
            assertThat(strong.confidence).isGreaterThan(weak.confidence)
        }

        @Test
        fun `an empty category list classifies to nothing rather than failing`() {
            val result = engine.classify(
                CategorizationEngine.RuleSet(categories = emptyList(), rules = emptyList()),
                input(subject = "Anything"),
            )

            assertThat(result.categoryId).isNull()
            assertThat(result.categoryKey).isEqualTo("primary")
        }
    }

    @Nested
    @DisplayName("rule matching")
    inner class Matching {

        @Test
        fun `contains equals starts-with and ends-with all match case insensitively`() {
            assertThat(
                engine.matches(
                    rule(promotions.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "SALE"),
                    input(subject = "Summer sale now on"),
                ),
            ).isTrue()

            assertThat(
                engine.matches(
                    rule(promotions.id, RuleField.SENDER, RuleOperation.STARTS_WITH, "Marketing@"),
                    input(fromAddress = "marketing@shop.example"),
                ),
            ).isTrue()

            assertThat(
                engine.matches(
                    rule(promotions.id, RuleField.SENDER_DOMAIN, RuleOperation.ENDS_WITH, "SHOP.EXAMPLE"),
                    input(fromAddress = "marketing@shop.example"),
                ),
            ).isTrue()

            assertThat(
                engine.matches(
                    rule(promotions.id, RuleField.SUBJECT, RuleOperation.EQUALS, "exact subject"),
                    input(subject = "Exact Subject"),
                ),
            ).isTrue()
        }

        @Test
        fun `sender rules consider the display name as well as the address`() {
            assertThat(
                engine.matches(
                    rule(promotions.id, RuleField.SENDER, RuleOperation.CONTAINS, "wanderlust"),
                    input(fromAddress = "hello@wl.example", fromName = "Wanderlust Travel"),
                ),
            ).isTrue()
        }

        @Test
        fun `header rules match on the header name, which is how List-Unsubscribe is detected`() {
            assertThat(
                engine.matches(
                    rule(promotions.id, RuleField.HEADER, RuleOperation.CONTAINS, "list-unsubscribe"),
                    input(headers = mapOf("list-unsubscribe" to "<mailto:stop@shop.example>")),
                ),
            ).isTrue()
        }

        @Test
        fun `regex rules work and an invalid pattern is ignored rather than thrown`() {
            assertThat(
                engine.matches(
                    rule(finance.id, RuleField.SUBJECT, RuleOperation.REGEX, """invoice #\d+"""),
                    input(subject = "Your Invoice #12345 is ready"),
                ),
            ).isTrue()

            // A user can type anything into a rule; a broken pattern must not break sync.
            assertThat(
                engine.matches(
                    rule(finance.id, RuleField.SUBJECT, RuleOperation.REGEX, "invoice ["),
                    input(subject = "invoice ["),
                ),
            ).isFalse()
        }

        @Test
        fun `a rule against a field the message does not have simply does not match`() {
            assertThat(
                engine.matches(
                    rule(promotions.id, RuleField.LIST_ID, RuleOperation.CONTAINS, "anything"),
                    input(listId = null),
                ),
            ).isFalse()
        }

        @Test
        fun `body rules match within the truncation window`() {
            assertThat(
                engine.matches(
                    rule(finance.id, RuleField.BODY, RuleOperation.CONTAINS, "statement"),
                    input(bodyText = "Your monthly statement is attached"),
                ),
            ).isTrue()
        }

        @Test
        fun `recipient rules match any recipient`() {
            assertThat(
                engine.matches(
                    rule(primary.id, RuleField.RECIPIENT, RuleOperation.CONTAINS, "team@"),
                    input(recipients = listOf("ada@example.com", "team@example.com")),
                ),
            ).isTrue()
        }
    }

    @Test
    fun `ruleSetFor loads categories and rules once for the user`() {
        every { categoryRepository.findVisibleFor(userId) } returns listOf(primary, promotions)
        every { ruleRepository.findApplicableRules(userId) } returns
            listOf(rule(promotions.id, RuleField.SUBJECT, RuleOperation.CONTAINS, "sale"))

        val ruleSet = engine.ruleSetFor(userId)

        assertThat(ruleSet.categories.size).isEqualTo(2)
        assertThat(ruleSet.rules.size).isEqualTo(1)
        assertThat(ruleSet.primaryCategory?.key).isEqualTo("primary")
    }

    // ---- helpers ----------------------------------------------------------

    private fun ruleSetOf(rules: List<CategoryRule>) = CategorizationEngine.RuleSet(
        categories = listOf(primary, promotions, finance, receipts),
        rules = rules,
    )

    private fun category(key: String, name: String) = Category(
        id = UUID.randomUUID(),
        key = key,
        name = name,
        isSystem = true,
    )

    private fun rule(
        categoryId: UUID,
        field: RuleField,
        operation: RuleOperation,
        value: String,
        weight: Int = 40,
    ) = CategoryRule(
        categoryId = categoryId,
        field = field,
        operation = operation,
        value = value,
        weight = weight,
    )

    private fun input(
        fromAddress: String = "someone@example.com",
        fromName: String = "",
        subject: String = "",
        bodyText: String? = null,
        listId: String? = null,
        recipients: List<String> = emptyList(),
        headers: Map<String, String> = emptyMap(),
    ) = ClassificationInput(
        fromAddress = fromAddress,
        fromName = fromName,
        subject = subject,
        bodyText = bodyText,
        listId = listId,
        recipients = recipients,
        headers = headers,
    )
}
