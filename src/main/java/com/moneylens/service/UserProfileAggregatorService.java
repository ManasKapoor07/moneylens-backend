package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.response.AIAnalysisResponse;
import com.moneylens.entity.BehavioralSignal;
import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.User;
import com.moneylens.entity.UserFinancialProfile;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserFinancialProfileRepository;
import com.moneylens.repository.UserRepository;
import com.moneylens.signal.engine.BehavioralSignalEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * CHANGES FROM ORIGINAL:
 *   1. Injects BehavioralSignalEngine
 *   2. recompute() runs signal engine on the full cross-statement timeline
 *   3. Health score now reads from context.healthScore().score() — DETERMINISTIC
 *      (was: analysis.getSpendingPulse().stabilityScore() — GPT-generated, non-deterministic)
 *   4. buildContext() called separately from renderPromptContext() so we can
 *      read the health score from the context object before it's serialised
 */
@Service
public class UserProfileAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileAggregatorService.class);

    public static final String REASON_NEW_STATEMENT   = "NEW_STATEMENT";
    public static final String REASON_USER_CORRECTION = "USER_CORRECTION";
    public static final String REASON_MANUAL_REFRESH  = "MANUAL_REFRESH";
    public static final String REASON_BACKFILL        = "BACKFILL";

    private final TransactionRepository          transactionRepository;
    private final StatementRepository            statementRepository;
    private final UserFinancialProfileRepository userProfileRepository;
    private final UserRepository                 userRepository;
    private final AIContextBuilderService        aiContextBuilderService;
    private final FinancialAIAnalysisService     financialAIAnalysisService;
    private final BehavioralSignalEngine         signalEngine;          // NEW
    private final DeduplicationService           deduplicationService;
    private final ObjectMapper                   objectMapper;

    public UserProfileAggregatorService(
            TransactionRepository transactionRepository,
            StatementRepository statementRepository,
            UserFinancialProfileRepository userProfileRepository,
            UserRepository userRepository,
            AIContextBuilderService aiContextBuilderService,
            FinancialAIAnalysisService financialAIAnalysisService,
            BehavioralSignalEngine signalEngine,
            DeduplicationService deduplicationService,
            ObjectMapper objectMapper
    ) {
        this.transactionRepository   = transactionRepository;
        this.statementRepository     = statementRepository;
        this.userProfileRepository   = userProfileRepository;
        this.userRepository          = userRepository;
        this.aiContextBuilderService = aiContextBuilderService;
        this.financialAIAnalysisService = financialAIAnalysisService;
        this.signalEngine            = signalEngine;
        this.deduplicationService    = deduplicationService;
        this.objectMapper            = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Async
    public void recomputeAsync(UUID userId, String reason) {
        try {
            recompute(userId, reason);
        } catch (Exception e) {
            log.error("Async profile rebuild failed for user {}: {}", userId, e.getMessage(), e);
        }
    }

    @Transactional
    public UserFinancialProfile recompute(UUID userId, String reason) {

        log.info("Rebuilding user profile for {} (reason: {})", userId, reason);

        // ── 1. Load canonical timeline ────────────────────────────────────────
        List<Transaction> timeline = transactionRepository.findCanonicalTimelineByUserId(userId);

        if (timeline.isEmpty()) {
            log.info("No completed transactions for user {} — skipping rebuild", userId);
            return null;
        }

        log.info("Canonical timeline: {} transactions for user {}", timeline.size(), userId);

        // ── 2. Compute user-level signals on full timeline ────────────────────
        // This gives a cross-statement behavioral picture (e.g. salary spike
        // detected across 3 months of data, not just one statement).
        List<BehavioralSignal> signals = signalEngine.computeForUser(userId, timeline);

        log.info("User-level signals: {} computed ({} fired) for user {}",
                signals.size(),
                signals.stream().filter(BehavioralSignal::isFired).count(),
                userId);

        // ── 3. Build merged context with signals ──────────────────────────────
        // Call buildContext() and renderPromptContext() separately so we can
        // read the DETERMINISTIC health score from the context object.
        AIContextBuilderService.AIContext context =
                aiContextBuilderService.buildContext(timeline, List.of(), signals);

        String contextJson = aiContextBuilderService.renderPromptContext(context);

        // ── 4. Run AI analysis on merged context ──────────────────────────────
        String analysisJson;
        Integer healthScore;
        String  riskLevel;

        try {
            AIAnalysisResponse analysis = financialAIAnalysisService.analyzeFromContext(contextJson);
            analysisJson = objectMapper.writeValueAsString(analysis);

            // ✅ FIX: deterministic health score from backend computation
            // NOT from GPT's spendingPulse.stabilityScore()
            healthScore = context.healthScore() != null
                    ? context.healthScore().score()
                    : null;
            riskLevel = deriveRiskLevel(healthScore);

        } catch (Exception e) {
            log.error("AI analysis failed during profile rebuild for user {}: {}",
                    userId, e.getMessage(), e);
            analysisJson = null;
            healthScore  = context.healthScore() != null ? context.healthScore().score() : null;
            riskLevel    = deriveRiskLevel(healthScore);
            // Health score is still saved even if AI narrative fails —
            // the numeric score is deterministic and always available.
        }

        // ── 5. Load metadata ──────────────────────────────────────────────────
        LocalDate periodFrom = transactionRepository.findEarliestDateByUserId(userId).orElse(null);
        LocalDate periodTo   = transactionRepository.findLatestDateByUserId(userId).orElse(null);
        long statementCount  = statementRepository.countCompletedByUserId(userId);

        // ── 6. Persist ────────────────────────────────────────────────────────
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        UserFinancialProfile profile = userProfileRepository
                .findByUserId(userId)
                .orElse(new UserFinancialProfile());

        profile.setUser(user);
        profile.setContextJson(contextJson);
        profile.setAnalysisJson(analysisJson);
        profile.setHealthScore(healthScore);
        profile.setRiskLevel(riskLevel);
        profile.setPeriodFrom(periodFrom);
        profile.setPeriodTo(periodTo);
        profile.setStatementCount((int) statementCount);
        profile.setTransactionCount(timeline.size());
        profile.recordRebuild(reason);

        UserFinancialProfile saved = userProfileRepository.save(profile);

        log.info("User profile rebuilt for {} — {} transactions, {} to {}, " +
                        "health={}, risk={}, signals fired={}, rebuild #{}",
                userId, timeline.size(), periodFrom, periodTo,
                healthScore, riskLevel,
                signals.stream().filter(BehavioralSignal::isFired).count(),
                saved.getRebuildCount());

        return saved;
    }

    @Transactional
    public void backfillMissingProfiles() {
        log.info("Starting UserFinancialProfile backfill...");
        List<User> allUsers = userRepository.findAll();
        int built = 0, skipped = 0;

        for (User user : allUsers) {
            UUID userId = user.getId();
            if (userProfileRepository.existsByUserId(userId)) { skipped++; continue; }
            if (statementRepository.countCompletedByUserId(userId) == 0) { skipped++; continue; }
            try {
                recompute(userId, REASON_BACKFILL);
                built++;
            } catch (Exception e) {
                log.error("Backfill failed for user {}: {}", userId, e.getMessage(), e);
            }
        }

        log.info("Backfill complete — built: {}, skipped: {}", built, skipped);
    }

    @Transactional
    public void backfillDedupHashes() {
        log.info("Starting dedup_hash backfill...");
        List<Transaction> unprocessed = transactionRepository.findAll().stream()
                .filter(t -> t.getDedupHash() == null).toList();

        log.info("Found {} transactions without dedup_hash", unprocessed.size());
        int batchSize = 500, processed = 0;

        for (int i = 0; i < unprocessed.size(); i += batchSize) {
            List<Transaction> batch = unprocessed.subList(i,
                    Math.min(i + batchSize, unprocessed.size()));
            deduplicationService.stampAll(batch);
            transactionRepository.saveAll(batch);
            processed += batch.size();
            log.info("Dedup backfill: {}/{}", processed, unprocessed.size());
        }

        log.info("Dedup backfill complete — {} transactions processed", processed);
    }

    private String deriveRiskLevel(Integer healthScore) {
        if (healthScore == null) return null;
        if (healthScore >= 65) return "LOW";
        if (healthScore >= 40) return "MEDIUM";
        return "HIGH";
    }
}