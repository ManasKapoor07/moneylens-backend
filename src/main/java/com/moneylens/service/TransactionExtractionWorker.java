package com.moneylens.service;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionInsight;

import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionInsightRepository;
import com.moneylens.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransactionExtractionWorker {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TransactionExtractionWorker.class
            );

    private final StatementRepository statementRepository;

    private final TransactionRepository transactionRepository;

    private final TransactionInsightRepository insightRepository;

    private final TransactionMapper mapper;

    private final AIContextBuilderService aiContextBuilder;

    private final FinancialProfilePersistenceService
            financialProfilePersistenceService;

    public TransactionExtractionWorker(

            StatementRepository statementRepository,

            TransactionRepository transactionRepository,

            TransactionInsightRepository insightRepository,

            TransactionMapper mapper,

            AIContextBuilderService aiContextBuilder,

            FinancialProfilePersistenceService
                    financialProfilePersistenceService

    ) {

        this.statementRepository =
                statementRepository;

        this.transactionRepository =
                transactionRepository;

        this.insightRepository =
                insightRepository;

        this.mapper =
                mapper;

        this.aiContextBuilder =
                aiContextBuilder;

        this.financialProfilePersistenceService =
                financialProfilePersistenceService;
    }

    @Transactional
    public void doExtract(
            UUID statementId,
            List<Map<String, String>> rawRows
    ) {

        log.info(
                "Extracting transactions for statement: {} ({} raw rows)",
                statementId,
                rawRows.size()
        );

        Statement statement =
                statementRepository
                        .findById(statementId)
                        .orElse(null);

        if (statement == null) {

            log.warn(
                    "Statement not found: {}",
                    statementId
            );

            return;
        }

        try {

            // =====================================================
            // STEP 1 — TRANSACTION EXTRACTION
            // =====================================================

            statement.setStatus(
                    Statement.Status.EXTRACTING
            );

            statementRepository.save(statement);

            List<Transaction> transactions =
                    new ArrayList<>();

            int skipped = 0;

            for (Map<String, String> row : rawRows) {

                try {

                    Transaction tx =
                            mapper.mapRowToTransaction(
                                    row,
                                    statement
                            );

                    if (tx != null) {

                        transactions.add(tx);

                    } else {

                        skipped++;

                        log.debug(
                                "Row skipped (null): {}",
                                row
                        );
                    }

                } catch (Exception e) {

                    skipped++;

                    log.warn(
                            "Malformed row skipped: {} — {}",
                            row,
                            e.getMessage()
                    );
                }
            }

            // =====================================================
            // SANITY AUDIT
            // =====================================================

            long debitCount =
                    transactions.stream()
                            .filter(t ->
                                    t.getType()
                                            == Transaction.Type.DEBIT
                            )
                            .count();

            long creditCount =
                    transactions.stream()
                            .filter(t ->
                                    t.getType()
                                            == Transaction.Type.CREDIT
                            )
                            .count();

            log.info(
                    "Statement {}: {} mapped | {} debit | {} credit | {} skipped",
                    statementId,
                    transactions.size(),
                    debitCount,
                    creditCount,
                    skipped
            );

            if (
                    transactions.size() > 5
                            && creditCount == 0
            ) {

                log.error(
                        "SANITY FAIL: 0 credits detected — possible parser bug for statement: {}",
                        statementId
                );
            }

            if (
                    transactions.size() > 5
                            && debitCount == 0
            ) {

                log.error(
                        "SANITY FAIL: 0 debits detected — possible parser bug for statement: {}",
                        statementId
                );
            }

            transactionRepository.saveAll(
                    transactions
            );

            log.info(
                    "Saved {} transactions for statement: {}",
                    transactions.size(),
                    statementId
            );

            // =====================================================
            // STEP 2 — INSIGHTS
            // =====================================================

            statement.setStatus(
                    Statement.Status.ANALYSING
            );

            statementRepository.save(statement);

            List<TransactionInsight> insights =
                    mapper.deriveInsights(
                            statement,
                            transactions
                    );

            insightRepository.saveAll(
                    insights
            );

            log.info(
                    "Saved {} insights for statement: {}",
                    insights.size(),
                    statementId
            );

            // =====================================================
            // STEP 3 — AI CONTEXT
            // =====================================================

            AIContextBuilderService.AIContext context =
                    aiContextBuilder.buildContext(
                            transactions,
                            insights
                    );

            log.info(
                    "AI context built for statement: {}",
                    statementId
            );

            // =====================================================
            // STEP 4 — PERSIST FINANCIAL PROFILE
            // =====================================================

            financialProfilePersistenceService
                    .saveProfile(
                            statement,
                            context
                    );

            log.info(
                    "Financial profile persisted for statement: {}",
                    statementId
            );

            // =====================================================
            // STEP 5 — COMPLETE
            // =====================================================

            statement.setStatus(
                    Statement.Status.COMPLETED
            );

            statementRepository.save(statement);

        } catch (Exception e) {

            log.error(
                    "Extraction failed for statement: {}",
                    statementId,
                    e
            );

            statement.setStatus(
                    Statement.Status.FAILED
            );

            statementRepository.save(statement);
        }
    }
}