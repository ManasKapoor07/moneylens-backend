package com.moneylens.repository;

import com.moneylens.dto.response.StatementIdWithBankDto;
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

    List<Statement> findByUserOrderByCreatedAtDesc(User user);

    List<Statement> findByUserAndStatus(User user, Statement.Status status);

    /**
     * All COMPLETED statements for a user, newest first.
     * Used by DashboardService — only completed statements have reliable data.
     */
    List<Statement> findByUserAndStatusOrderByPeriodToDesc(
            User user,
            Statement.Status status
    );

    /**
     * The single most-recent COMPLETED statement for a user.
     * Used by DashboardService to scope totals to the latest period.
     */
    Optional<Statement> findTopByUserAndStatusOrderByPeriodToDesc(
            User user,
            Statement.Status status
    );


    // ── Duplicate detection ──────────────────────────────────────

    boolean existsByUserAndFileHash(User user, String fileHash);
    Optional<Statement> findTopByUserOrderByCreatedAtDesc(User user);
    boolean existsByUserAndFileNameAndPeriodFromLessThanEqualAndPeriodToGreaterThanEqual(
            User user,
            String fileName,
            LocalDate periodTo,
            LocalDate periodFrom
    );

    @Query("""
    SELECT new com.moneylens.dto.response.StatementIdWithBankDto(
        s.id,
        s.bankName,
        s.periodFrom,
        s.periodTo
    )
    FROM Statement s
    WHERE s.user = :user
    ORDER BY s.createdAt DESC
""")
    List<StatementIdWithBankDto> findIdsWithBankByUser(
            @Param("user") User user
    );
}