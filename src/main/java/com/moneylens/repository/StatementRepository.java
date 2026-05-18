package com.moneylens.repository;

import com.moneylens.entity.Statement;
import com.moneylens.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatementRepository extends JpaRepository<Statement, UUID> {

    // ── Existing queries ──────────────────────────────────────────────────────

    List<Statement> findByUserOrderByCreatedAtDesc(User user);

    List<Statement> findByUserAndStatusOrderByPeriodToDesc(User user, Statement.Status status);

    @Query("""
        SELECT new com.moneylens.dto.response.StatementIdWithBankDto(
            s.id, s.bankName, s.periodFrom, s.periodTo
        )
        FROM Statement s
        WHERE s.user = :user
          AND s.status = 'COMPLETED'
        ORDER BY s.periodTo DESC
        """)
    List<com.moneylens.dto.response.StatementIdWithBankDto> findIdsWithBankByUser(
            @Param("user") User user
    );

    // ── New queries ───────────────────────────────────────────────────────────

    /**
     * Finds all COMPLETED statements for a user whose period overlaps
     * with the given date range.
     *
     * Two periods overlap when:
     *   existing.from <= new.to  AND  existing.to >= new.from
     *
     * Used by UserProfileAggregatorService to detect whether a newly
     * uploaded statement overlaps with existing data.
     */
    @Query("""
        SELECT s FROM Statement s
        WHERE s.user.id = :userId
          AND s.status = 'COMPLETED'
          AND s.periodFrom IS NOT NULL
          AND s.periodTo IS NOT NULL
          AND s.periodFrom <= :toDate
          AND s.periodTo >= :fromDate
        ORDER BY s.periodFrom ASC
        """)
    List<Statement> findByUserIdAndPeriodOverlapping(
            @Param("userId") UUID userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );

    /**
     * Count of completed statements for a user.
     * Used to populate UserFinancialProfile.statementCount.
     */
    @Query("""
        SELECT COUNT(s) FROM Statement s
        WHERE s.user.id = :userId
          AND s.status = 'COMPLETED'
        """)
    long countCompletedByUserId(@Param("userId") UUID userId);

    /**
     * Checks whether a completed statement already exists for this user
     * covering the exact same period and bank.
     *
     * Used as a fast-path duplicate detection before full dedup hash
     * processing — if the same PDF is uploaded twice, we can short-circuit
     * at the statement level.
     */
    @Query("""
        SELECT s FROM Statement s
        WHERE s.user.id = :userId
          AND s.bankName = :bankName
          AND s.periodFrom = :periodFrom
          AND s.periodTo = :periodTo
          AND s.status = 'COMPLETED'
        """)
    Optional<Statement> findExactDuplicate(
            @Param("userId") UUID userId,
            @Param("bankName") String bankName,
            @Param("periodFrom") LocalDate periodFrom,
            @Param("periodTo") LocalDate periodTo
    );

    Optional<Statement> findTopByUserAndStatusOrderByPeriodToDesc(User user, Statement.Status status);

    boolean existsByUserAndFileHash(User user, String fileHash);

}