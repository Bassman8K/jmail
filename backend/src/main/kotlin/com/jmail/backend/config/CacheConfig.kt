package com.jmail.backend.config

import com.jmail.backend.category.CategorizationEngine
import org.springframework.cache.CacheManager
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * In-process caching only.
 *
 * The one thing JMail caches is a user's category rule set, which is small, read once per
 * sync burst and explicitly evicted whenever the user edits a rule. That does not justify
 * the operational weight of Redis; if this ever runs multi-instance, the eviction points
 * are already in place and only this bean needs to change.
 */
@Configuration
class CacheConfig {

    @Bean
    fun cacheManager(): CacheManager = ConcurrentMapCacheManager(
        CategorizationEngine.RULE_SET_CACHE,
    ).apply {
        isAllowNullValues = false
    }
}
