package com.moneylens.repository;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // ── Per-statement queries (existing) ─────────────────────────────────────

    List<Transaction> findByStatement(Statement statement);

    Page<Transaction> findByStatement(Statement statement, Pageable pageable);

    Page<Transaction> findByStatementAndType(
            Statement statement,
            Transaction.Type type,
            Pageable pageable
    );

    Page<Transaction> findByStatementAndCategory(
            Statement statement,
            String category,
            Pageable pageable
    );

    Page<Transaction> findByStatementAndTypeAndCategory(
            Statement statement,
            Transaction.Type type,
            String category,
            Pageable pageable
    );

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.statement.id = :statementId
        ORDER BY t.date ASC
        """)
    List<Transaction> findAllByStatementIdOrdered(@Param("statementId") UUID statementId);

    // ── Cross-statement / user-level queries (new) ───────────────────────────

    /**
     * Canonical timeline query.
     *
     * Returns ALL deduplicated transactions for a user across ALL their
     * completed statements, ordered chronologically.
     *
     * This is the primary input to UserProfileAggregatorService and replaces
     * per-statement transaction queries for top-level analytics.
     *
     * Deduplication is enforced by the UNIQUE constraint on dedup_hash
     * at the DB level (see migration V3), so this query naturally returns
     * only one row per unique transaction even if the same transaction
     * appears in two overlapping uploaded statements.
     */
    @Query("""
        SELECT t FROM Transaction t
        JOIN t.statement s
        WHERE s.user.id = :userId
          AND s.status = 'COMPLETED'
        ORDER BY t.date ASC, t.amount ASC
        """)
    List<Transaction> findCanonicalTimelineByUserId(@Param("userId") UUID userId);

    /**
     * Canonical timeline within a date range.
     *
     * Used when recomputing only the affected window after a new
     * statement is uploaded that overlaps with existing data.
     */
    @Query("""
        SELECT t FROM Transaction t
        JOIN t.statement s
        WHERE s.user.id = :userId
          AND s.status = 'COMPLETED'
          AND t.date >= :from
          AND t.date <= :to
        ORDER BY t.date ASC
        """)
    List<Transaction> findCanonicalTimelineByUserIdAndDateRange(
            @Param("userId") UUID userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /**
     * Dedup hash existence check.
     *
     * Called before inserting a new transaction to detect duplicates.
     * If a transaction with the same hash already exists for this user
     * (across ANY statement), we skip insertion.
     *
     * We scope by userId — not globally — because the same hash could
     * legitimately appear for two different users (e.g. both have a
     * ₹500 Swiggy order on the same day).
     */
    @Query("""
        SELECT t FROM Transaction t
        JOIN t.statement s
        WHERE s.user.id = :userId
          AND t.dedupHash = :hash
        """)
    Optional<Transaction> findByUserIdAndDedupHash(
            @Param("userId") UUID userId,
            @Param("hash") String hash
    );

    /**
     * Batch dedup check.
     *
     * Given a list of hashes, returns only those that already exist for
     * this user. The caller subtracts this set from the incoming batch
     * to determine which transactions are genuinely new.
     *
     * More efficient than N individual exists() calls when processing
     * a full statement (which may have 200–500 transactions).
     */
    @Query("""
        SELECT t.dedupHash FROM Transaction t
        JOIN t.statement s
        WHERE s.user.id = :userId
          AND t.dedupHash IN :hashes
        """)
    List<String> findExistingDedupHashes(
            @Param("userId") UUID userId,
            @Param("hashes") List<String> hashes
    );

    /**
     * Count total deduplicated transactions for a user.
     * Used for the "analysed N transactions" display on the dashboard.
     */
    @Query("""
        SELECT COUNT(t) FROM Transaction t
        JOIN t.statement s
        WHERE s.user.id = :userId
          AND s.status = 'COMPLETED'
        """)
    long countByUserId(@Param("userId") UUID userId);

    /**
     * Returns the earliest transaction date for a user.
     * Used to set periodFrom on UserFinancialProfile.
     */
    @Query("""
        SELECT MIN(t.date) FROM Transaction t
        JOIN t.statement s
        WHERE s.user.id = :userId
          AND s.status = 'COMPLETED'
        """)
    Optional<LocalDate> findEarliestDateByUserId(@Param("userId") UUID userId);

    /**
     * Returns the latest transaction date for a user.
     * Used to set periodTo on UserFinancialProfile.
     */
    @Query("""
        SELECT MAX(t.date) FROM Transaction t
        JOIN t.statement s
        WHERE s.user.id = :userId
          AND s.status = 'COMPLETED'
        """)
    Optional<LocalDate> findLatestDateByUserId(@Param("userId") UUID userId);

    @Query("SELECT t FROM Transaction t WHERE t.statement.user.id = :userId ORDER BY t.date DESC")
    Page<Transaction> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.statement.user.id = :userId AND t.type = :type ORDER BY t.date DESC")
    Page<Transaction> findByUserAndType(@Param("userId") UUID userId, @Param("type") Transaction.Type type, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.statement.user.id = :userId AND t.category = :category ORDER BY t.date DESC")
    Page<Transaction> findByUserAndCategory(@Param("userId") UUID userId, @Param("category") String category, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.statement.user.id = :userId AND t.type = :type AND t.category = :category ORDER BY t.date DESC")
    Page<Transaction> findByUserAndTypeAndCategory(@Param("userId") UUID userId, @Param("type") Transaction.Type type, @Param("category") String category, Pageable pageable);
}