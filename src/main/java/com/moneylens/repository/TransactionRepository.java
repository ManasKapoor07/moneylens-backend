package com.moneylens.repository;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findByStatementOrderByDateDesc(Statement statement);
    

    List<Transaction> findByStatementAndType(Statement statement, Transaction.Type type);

    List<Transaction> findByStatementAndCategory(Statement statement, String category);

    @Query("SELECT t FROM Transaction t WHERE t.statement.id = :statementId ORDER BY t.date DESC")
    List<Transaction> findByStatementId(@Param("statementId") UUID statementId);

    @Query("SELECT t.category, SUM(t.amount) FROM Transaction t WHERE t.statement.id = :statementId AND t.type = 'DEBIT' GROUP BY t.category")
    List<Object[]> sumByCategory(@Param("statementId") UUID statementId);
}