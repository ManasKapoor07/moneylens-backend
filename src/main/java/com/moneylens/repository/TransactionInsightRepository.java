package com.moneylens.repository;

import com.moneylens.entity.Statement;
import com.moneylens.entity.TransactionInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionInsightRepository extends JpaRepository<TransactionInsight, UUID> {

    List<TransactionInsight> findByStatementOrderByCreatedAtAsc(Statement statement);

    List<TransactionInsight> findByStatementAndType(Statement statement, String type);

    List<TransactionInsight> findByStatement_IdAndType(UUID statementId, String type);
}