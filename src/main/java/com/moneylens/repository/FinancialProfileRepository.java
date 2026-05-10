package com.moneylens.repository;

import com.moneylens.entity.FinancialProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FinancialProfileRepository
        extends JpaRepository<FinancialProfile, UUID> {

    Optional<FinancialProfile>
    findByStatementId(UUID statementId);
}