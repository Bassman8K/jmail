package com.jmail.backend.category

import com.jmail.backend.common.EmailAddresses
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The parts of a message the classifier is allowed to look at. */
data class ClassificationInput(
    val fromAddress: String,
    val fromName: String = "",
    val subject: String = "",
    val bodyText: String? = null,
    val listId: String? = null,
    val recipients: List<String> = emptyList(),
    /** Raw headers, lowercased keys. Only used by HEADER rules (List-Unsubscribe, Precedence…). */
    val headers: Map<String, String> = emptyMap(),
)

/** Where a message belongs, and how sure the engine is. */
data class Classification(
    val categoryId: UUID?,
    val categoryKey: String,
    /** 0–1. Below [CategorizationEngine.LOW_CONFIDENCE] the UI offers a "move to…" hint. */
    val confidence: Float,
)

/**
 * Decides which category a message belongs to by scoring it against the user's rules.
 *
 * Scoring rather than first-match-wins: a message can look like both a receipt and a
 * promotion, and the category with the strongest combined evidence should win. Weights let
 * a specific signal (a bank's domain) outrank a generic keyword ("payment"), and the
 * normalised score becomes the confidence the UI shows.
 */
@Component
class CategorizationEngine(
    private val categoryRepository: CategoryRepository,
    private val categoryRuleRepository: CategoryRuleRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Compiled REGEX rules, keyed by pattern; compiling on every message is wasteful. */
    private val compiledPatterns = ConcurrentHashMap<String, Regex?>()

    /**
     * The rules and categories that apply to a user, loaded once and cached.
     *
     * Sync classifies hundreds of messages in a burst; without this each one would issue two
     * queries. The cache is evicted whenever the user edits a category or rule.
     */
    @Cacheable(cacheNames = [RULE_SET_CACHE], key = "#userId")
    fun ruleSetFor(userId: UUID): RuleSet {
        val categories = categoryRepository.findVisibleFor(userId)
        val rules = categoryRuleRepository.findApplicableRules(userId)
        log.debug("Loaded {} categories and {} rules for user {}", categories.size, rules.size, userId)
        return RuleSet(categories, rules)
    }

    @CacheEvict(cacheNames = [RULE_SET_CACHE], key = "#userId")
    fun invalidate(userId: UUID) {
        log.debug("Invalidated cached rule set for user {}", userId)
    }

    @CacheEvict(cacheNames = [RULE_SET_CACHE], allEntries = true)
    fun invalidateAll() = Unit

    fun classify(userId: UUID, input: ClassificationInput): Classification =
        classify(ruleSetFor(userId), input)

    /** Classifies with a pre-loaded rule set — the form sync uses for a whole page of messages. */
    fun classify(ruleSet: RuleSet, input: ClassificationInput): Classification {
        if (ruleSet.categories.isEmpty()) {
            return Classification(null, Category.PRIMARY_KEY, 0f)
        }

        val scores = mutableMapOf<UUID, Int>()
        for (rule in ruleSet.rules) {
            if (matches(rule, input)) {
                scores.merge(rule.categoryId, rule.weight, Int::plus)
            }
        }

        val winner = scores.maxByOrNull { it.value }
        val fallback = ruleSet.primaryCategory

        if (winner == null || winner.value <= 0) {
            return Classification(fallback?.id, fallback?.key ?: Category.PRIMARY_KEY, 0f)
        }

        val category = ruleSet.categoriesById[winner.key] ?: fallback
        return Classification(
            categoryId = category?.id,
            categoryKey = category?.key ?: Category.PRIMARY_KEY,
            // Saturating rather than linear: two strong signals should read as "certain",
            // and a single weak keyword should not.
            confidence = (winner.value.toFloat() / SCORE_SATURATION).coerceIn(0f, 1f),
        )
    }

    internal fun matches(rule: CategoryRule, input: ClassificationInput): Boolean {
        val haystacks: List<String> = when (rule.field) {
            RuleField.SENDER -> listOf(input.fromAddress, input.fromName)
            RuleField.SENDER_DOMAIN -> listOf(EmailAddresses.domainOf(input.fromAddress))
            RuleField.SUBJECT -> listOf(input.subject)
            // Long bodies are truncated: the signal is in the opening, and unbounded input
            // is what turns a user-supplied regex into a denial of service.
            RuleField.BODY -> listOf(input.bodyText.orEmpty().take(MAX_BODY_CHARS))
            RuleField.LIST_ID -> listOfNotNull(input.listId)
            RuleField.HEADER -> input.headers.keys.toList()
            RuleField.RECIPIENT -> input.recipients
        }

        if (haystacks.isEmpty()) return false
        val needle = rule.value.lowercase()

        return haystacks.any { candidate ->
            val value = candidate.lowercase()
            when (rule.operation) {
                RuleOperation.CONTAINS -> value.contains(needle)
                RuleOperation.EQUALS -> value == needle
                RuleOperation.STARTS_WITH -> value.startsWith(needle)
                RuleOperation.ENDS_WITH -> value.endsWith(needle)
                RuleOperation.REGEX -> patternFor(rule.value)?.containsMatchIn(value) == true
            }
        }
    }

    /** Returns null for an invalid pattern; one bad rule must not break classification. */
    private fun patternFor(pattern: String): Regex? = compiledPatterns.computeIfAbsent(pattern) {
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
            .onFailure { log.warn("Ignoring category rule with an invalid regular expression: {}", pattern) }
            .getOrNull()
    }

    /** Categories and rules for one user, resolved together so lookups are local. */
    data class RuleSet(
        val categories: List<Category>,
        val rules: List<CategoryRule>,
    ) {
        val categoriesById: Map<UUID, Category> = categories.associateBy(Category::id)

        val primaryCategory: Category? = categories.firstOrNull { it.key == Category.PRIMARY_KEY }
            ?: categories.firstOrNull()
    }

    companion object {
        const val RULE_SET_CACHE = "categoryRuleSets"

        /** Score at which confidence reaches 1.0. Two strong rules, or several weak ones. */
        const val SCORE_SATURATION = 100f

        /** Below this the client shows "Not sure? Move it" affordances on the message. */
        const val LOW_CONFIDENCE = 0.35f

        private const val MAX_BODY_CHARS = 8_000
    }
}
