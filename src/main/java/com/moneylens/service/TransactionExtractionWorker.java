package com.moneylens.service;

import com.moneylens.entity.BehavioralSignal;
import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionInsight;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionInsightRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.signal.engine.BehavioralSignalEngine;
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

    private static final Logger log = LoggerFactory.getLogger(TransactionExtractionWorker.class);

    private final StatementRepository                statementRepository;
    private final TransactionRepository              transactionRepository;
    private final TransactionInsightRepository       insightRepository;
    private final TransactionMapper                  mapper;
    private final AIContextBuilderService            aiContextBuilder;
    private final BehavioralSignalEngine             signalEngine;
    private final FinancialProfilePersistenceService financialProfilePersistenceService;
    private final UserProfileAggregatorService       userProfileAggregatorService;
    private final ClarificationGeneratorService      clarificationGeneratorService; // NEW

    public TransactionExtractionWorker(
            StatementRepository statementRepository,
            TransactionRepository transactionRepository,
            TransactionInsightRepository insightRepository,
            TransactionMapper mapper,
            AIContextBuilderService aiContextBuilder,
            BehavioralSignalEngine signalEngine,
            FinancialProfilePersistenceService financialProfilePersistenceService,
            UserProfileAggregatorService userProfileAggregatorService,
            ClarificationGeneratorService clarificationGeneratorService
    ) {
        this.statementRepository             = statementRepository;
        this.transactionRepository           = transactionRepository;
        this.insightRepository               = insightRepository;
        this.mapper                          = mapper;
        this.aiContextBuilder                = aiContextBuilder;
        this.signalEngine                    = signalEngine;
        this.financialProfilePersistenceService = financialProfilePersistenceService;
        this.userProfileAggregatorService    = userProfileAggregatorService;
        this.clarificationGeneratorService   = clarificationGeneratorService;
    }

    @Transactional
    public void doExtract(UUID statementId, List<Map<String, String>> rawRows) {

        log.info("Extracting transactions for statement: {} ({} raw rows)",
                statementId, rawRows.size());

        Statement statement = statementRepository.findById(statementId).orElse(null);
        if (statement == null) {
            log.warn("Statement not found: {}", statementId);
            return;
        }

        try {
            // ── STEP 1: Transaction extraction ────────────────────────────────
            statement.setStatus(Statement.Status.EXTRACTING);
            statementRepository.save(statement);

            List<Transaction> transactions = new ArrayList<>();
            int skipped = 0;

            for (Map<String, String> row : rawRows) {
                try {
                    Transaction tx = mapper.mapRowToTransaction(row, statement);
                    if (tx != null) transactions.add(tx);
                    else skipped++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("Malformed row skipped: {} — {}", row, e.getMessage());
                }
            }

            long debitCount  = transactions.stream().filter(t -> t.getType() == Transaction.Type.DEBIT).count();
            long creditCount = transactions.stream().filter(t -> t.getType() == Transaction.Type.CREDIT).count();

            log.info("Statement {}: {} mapped | {} debit | {} credit | {} skipped",
                    statementId, transactions.size(), debitCount, creditCount, skipped);

            if (transactions.size() > 5 && creditCount == 0)
                log.error("SANITY FAIL: 0 credits — possible parser bug for statement: {}", statementId);
            if (transactions.size() > 5 && debitCount == 0)
                log.error("SANITY FAIL: 0 debits — possible parser bug for statement: {}", statementId);

            transactionRepository.saveAll(transactions);

            // ── STEP 2: Insights ──────────────────────────────────────────────
            statement.setStatus(Statement.Status.ANALYSING);
            statementRepository.save(statement);

            List<TransactionInsight> insights = mapper.deriveInsights(statement, transactions);
            insightRepository.saveAll(insights);
            log.info("Saved {} insights for statement: {}", insights.size(), statementId);

            // ── STEP 2.5: Behavioral signals ──────────────────────────────────
            UUID userId = statement.getUser().getId();
            List<BehavioralSignal> signals = signalEngine.computeForStatement(
                    statementId, userId, transactions
            );
            log.info("Computed {} signals for statement: {} ({} fired)",
                    signals.size(),
                    statementId,
                    signals.stream().filter(BehavioralSignal::isFired).count());

            // ── STEP 3: AI context (with signals) ─────────────────────────────
            AIContextBuilderService.AIContext context = aiContextBuilder.buildContext(
                    transactions, insights, signals
            );

            // ── STEP 4: Persist financial profile ─────────────────────────────
            financialProfilePersistenceService.saveProfile(statement, context);
            log.info("Financial profile persisted for statement: {}", statementId);

            // ── STEP 5: Complete ──────────────────────────────────────────────
            statement.setStatus(Statement.Status.COMPLETED);
            statementRepository.save(statement);

            // ── STEP 6: Rebuild user profile ──────────────────────────────────
            try {
                userProfileAggregatorService.recompute(userId, UserProfileAggregatorService.REASON_NEW_STATEMENT);
                log.info("User profile rebuilt for user: {}", userId);
            } catch (Exception e) {
                log.warn("Profile rebuild failed after upload — will retry on next startup", e);
            }

            // ── STEP 7: Generate clarification cards ──────────────────────────
            // Runs last — non-blocking, failure here must NOT affect statement status
            try {
                clarificationGeneratorService.generateForStatement(statementId, userId, transactions);
            } catch (Exception e) {
                log.warn("Clarification generation failed for statement: {} — non-fatal", statementId, e);
            }

        } catch (Exception e) {
            log.error("Extraction failed for statement: {}", statementId, e);
            statement.setStatus(Statement.Status.FAILED);
            statementRepository.save(statement);
        }
    }
}