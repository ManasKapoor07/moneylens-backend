package com.moneylens.repository;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // ── Non-paginated (used by DashboardService for bulk aggregation) ──

    List<Transaction> findByStatement(Statement statement);

    // ── Paginated (used by StatementService.getTransactionsPaged) ──────

    Page<Transaction> findByStatement(
            Statement statement,
            Pageable pageable);

    Page<Transaction> findByStatementAndType(
            Statement statement,
            Transaction.Type type,
            Pageable pageable);

    Page<Transaction> findByStatementAndCategory(
            Statement statement,
            String category,
            Pageable pageable);

    Page<Transaction> findByStatementAndTypeAndCategory(
            Statement statement,
            Transaction.Type type,
            String category,
            Pageable pageable);

    // ── Aggregations (used by DashboardService) ──────────────────────

    @Query("""
           SELECT t.category, SUM(t.amount)
           FROM Transaction t
           WHERE t.statement.id = :statementId
             AND t.type = 'DEBIT'
           GROUP BY t.category
           """)
    List<Object[]> sumDebitByCategory(@Param("statementId") UUID statementId);


    @Query("""
       SELECT t
       FROM Transaction t
       WHERE t.statement.id = :statementId
         AND t.type IN ('DEBIT', 'CREDIT')
       ORDER BY t.date ASC
       """)
    List<Transaction> findAllByStatementIdOrdered(@Param("statementId") UUID statementId);
}