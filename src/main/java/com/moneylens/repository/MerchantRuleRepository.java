package com.moneylens.repository;

import com.moneylens.entity.MerchantRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantRuleRepository extends JpaRepository<MerchantRule, Long> {

    /**
     * Load all active rules ordered by priority ascending.
     * Called once on startup to warm the in-memory cache.
     */
    @Query("SELECT r FROM MerchantRule r WHERE r.active = true ORDER BY r.priority ASC, r.id ASC")
    List<MerchantRule> findAllActiveOrderByPriority();

    /**
     * Find any active rule whose pattern exactly matches.
     * Used before inserting a USER_CORRECTION to avoid duplicates.
     */
    Optional<MerchantRule> findByPatternAndActiveTrue(String pattern);

    /**
     * Find all rules for a normalized merchant name.
     * Useful for admin views and future ML feedback aggregation.
     */
    List<MerchantRule> findByNormalizedNameAndActiveTrue(String normalizedName);

    /**
     * Find all active rules in a given category.
     */
    List<MerchantRule> findByCategoryAndActiveTrue(String category);

    /**
     * Count rules by source — useful for dashboard / health checks.
     */
    @Query("SELECT r.source, COUNT(r) FROM MerchantRule r WHERE r.active = true GROUP BY r.source")
    List<Object[]> countBySource();
}