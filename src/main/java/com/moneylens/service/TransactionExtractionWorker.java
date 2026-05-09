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

/**
 * Owns the @Transactional boundary for statement extraction.
 * Called from TransactionExtractor's @Async method so Spring's
 * proxy chain is:
 *
 *   caller
 *     → @Async proxy  (new thread)
 *     → @Transactional proxy  (opens TX on the new thread)
 *     → TransactionExtractionWorker.doExtract(…)
 *
 * Dependencies are acyclic:
 *   TransactionExtractionWorker  →  TransactionMapper  (pure computation)
 *   TransactionExtractionWorker  →  repositories
 */
@Service
public class TransactionExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(TransactionExtractionWorker.class);

    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionInsightRepository insightRepository;
    private final TransactionMapper mapper;

    public TransactionExtractionWorker(
            StatementRepository statementRepository,
            TransactionRepository transactionRepository,
            TransactionInsightRepository insightRepository,
            TransactionMapper mapper
    ) {
        this.statementRepository   = statementRepository;
        this.transactionRepository = transactionRepository;
        this.insightRepository     = insightRepository;
        this.mapper                = mapper;
    }

    @Transactional
    public void doExtract(UUID statementId, List<Map<String, String>> rawRows) {

        log.info("Extracting transactions for statement: {}", statementId);

        Statement statement = statementRepository.findById(statementId).orElse(null);
        if (statement == null) {
            log.warn("Statement not found: {}", statementId);
            return;
        }

        try {
            // Step 1: map CSV rows → Transaction entities
            statement.setStatus(Statement.Status.EXTRACTING);
            statementRepository.save(statement);

            List<Transaction> transactions = new ArrayList<>();
            for (Map<String, String> row : rawRows) {
                try {
                    Transaction tx = mapper.mapRowToTransaction(row, statement);
                    if (tx != null) transactions.add(tx);
                } catch (Exception e) {
                    log.warn("Skipping malformed row: {} — {}", row, e.getMessage());
                }
            }

            transactionRepository.saveAll(transactions);
            log.info("Saved {} transactions for statement: {}", transactions.size(), statementId);

            // Step 2: derive insights
            statement.setStatus(Statement.Status.ANALYSING);
            statementRepository.save(statement);

            List<TransactionInsight> insights = mapper.deriveInsights(statement, transactions);
            insightRepository.saveAll(insights);
            log.info("Saved {} insights for statement: {}", insights.size(), statementId);

            // Step 3: done
            statement.setStatus(Statement.Status.COMPLETED);
            statementRepository.save(statement);

        } catch (Exception e) {
            log.error("Extraction failed for statement: {}", statementId, e);
            statement.setStatus(Statement.Status.FAILED);
            statementRepository.save(statement);
        }
    }
}