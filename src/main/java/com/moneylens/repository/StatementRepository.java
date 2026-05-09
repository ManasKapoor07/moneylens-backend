package com.moneylens.repository;

import com.moneylens.entity.Statement;
import com.moneylens.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface StatementRepository extends JpaRepository<Statement, UUID> {

    List<Statement> findByUserOrderByCreatedAtDesc(User user);

    List<Statement> findByUserAndStatus(User user, Statement.Status status);

    // ── Duplicate detection ──────────────────────────────────────────

    /**
     * Exact file duplicate: same bytes uploaded again by the same user.
     */
    boolean existsByUserAndFileHash(User user, String fileHash);

    /**
     * Overlapping period duplicate: same account + overlapping date range.
     * Used AFTER metadata is populated by the parser.
     * Checks: existing periodFrom <= newTo AND existing periodTo >= newFrom
     */
    boolean existsByUserAndAccountNumberAndPeriodFromLessThanEqualAndPeriodToGreaterThanEqual(
            User user,
            String accountNumber,
            LocalDate periodTo,      // existing.periodFrom <= newPeriodTo
            LocalDate periodFrom     // existing.periodTo   >= newPeriodFrom
    );
}