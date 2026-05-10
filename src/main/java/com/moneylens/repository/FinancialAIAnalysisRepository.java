package com.moneylens.repository;

import com.moneylens.entity.FinancialAIAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FinancialAIAnalysisRepository
        extends JpaRepository<
        FinancialAIAnalysis,
        UUID
        > {

    Optional<FinancialAIAnalysis>
    findByStatementId(UUID statementId);
}