package com.moneylens.repository;

import com.moneylens.entity.TransactionClarification;
import com.moneylens.entity.TransactionClarification.Status;
import com.moneylens.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionClarificationRepository extends JpaRepository<TransactionClarification, UUID> {

    // fetch all pending cards for a user — used by the dashboard
    List<TransactionClarification> findByUserAndStatusOrderByCreatedAtAsc(User user, Status status);

    // check if cards already generated for this statement (idempotency)
    boolean existsByStatementId(UUID statementId);

    void deleteByStatementId(UUID statementId);
}
