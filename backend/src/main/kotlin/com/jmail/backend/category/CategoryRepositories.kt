package com.jmail.backend.category

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CategoryRepository : JpaRepository<Category, UUID> {

    /**
     * The categories a user sees: the shared system set plus their own, in display order.
     * One query rather than two so the ordering is decided by the database.
     */
    @Query(
        """
        SELECT category FROM Category category
        WHERE (category.userId IS NULL OR category.userId = :userId)
          AND category.isEnabled = true
        ORDER BY category.position ASC, category.name ASC
        """,
    )
    fun findVisibleFor(@Param("userId") userId: UUID): List<Category>

    @Query(
        """
        SELECT category FROM Category category
        WHERE category.key = :key AND (category.userId IS NULL OR category.userId = :userId)
        ORDER BY category.userId ASC NULLS LAST
        """,
    )
    fun findByKeyFor(@Param("key") key: String, @Param("userId") userId: UUID): List<Category>

    fun findByIdAndUserId(id: UUID, userId: UUID): Category?

    fun findAllByUserIdOrderByPositionAsc(userId: UUID): List<Category>

    fun existsByUserIdAndKey(userId: UUID, key: String): Boolean

    fun findAllByUserIdIsNullOrderByPositionAsc(): List<Category>

    @Query("SELECT COALESCE(MAX(category.position), -1) FROM Category category WHERE category.userId = :userId")
    fun maxPositionFor(@Param("userId") userId: UUID): Int
}

@Repository
interface CategoryRuleRepository : JpaRepository<CategoryRule, UUID> {

    /**
     * Every enabled rule that applies to a user, loaded in one query. The classifier holds
     * these in memory for the duration of a sync run rather than querying per message.
     */
    @Query(
        """
        SELECT rule FROM CategoryRule rule
        WHERE rule.isEnabled = true
          AND rule.categoryId IN (
            SELECT category.id FROM Category category
            WHERE (category.userId IS NULL OR category.userId = :userId) AND category.isEnabled = true
          )
        ORDER BY rule.weight DESC
        """,
    )
    fun findApplicableRules(@Param("userId") userId: UUID): List<CategoryRule>

    fun findAllByCategoryIdOrderByWeightDesc(categoryId: UUID): List<CategoryRule>

    fun deleteAllByCategoryId(categoryId: UUID)

    fun countByCategoryId(categoryId: UUID): Long
}
