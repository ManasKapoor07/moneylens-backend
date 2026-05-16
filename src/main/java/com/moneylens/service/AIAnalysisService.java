package com.moneylens.service;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.repository.StatementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Layer 4: Takes extracted transactions and runs AI analysis.
 * Responsibilities:
 *   - Categorise each transaction (Food, Transport, Subscriptions, etc.)
 *   - Detect spending patterns and anomalies
 *   - Identify money leaks (unused subs, duplicate payments, impulse spends)
 *   - Generate saving recommendations
 *   - Compute SIP projections based on identified leaks
 * Results are saved to the DB and the statement is marked COMPLETED.
 */
@Service
public class AIAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AIAnalysisService.class);

    private final StatementRepository statementRepository;
    // TODO: inject AI client (OpenAI / Gemini / Anthropic) and AnalysisRepository

    public AIAnalysisService(StatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    public void analyse(UUID statementId, List<Transaction> transactions) {

        log.info("Starting AI analysis for statement: {} ({} transactions)",
                statementId, transactions.size());

        Statement statement = statementRepository.findById(statementId).orElse(null);
        if (statement == null) return;

        try {
            statement.setStatus(Statement.Status.ANALYSING);
            statementRepository.save(statement);

            // ── Step 1: Categorise transactions ──────────────────────────
            // Send batches of transactions to AI with a prompt like:
            // "Categorise each transaction into one of: Food & Dining,
            //  Shopping, Transport, Subscriptions, Entertainment, Utilities,
            //  Healthcare, Education, Investment, Other.
            //  Return JSON array with {id, category, subCategory, confidence}"
            categoriseTransactions(transactions);

            // ── Step 2: Detect patterns & leaks ──────────────────────────
            // Ask AI: "Given these categorised transactions, identify:
            //  1. Recurring subscriptions that appear unused (no related activity)
            //  2. Duplicate payments (same merchant, similar amounts, close dates)
            //  3. Impulse spending patterns (time-of-day, day-of-week spikes)
            //  4. Top 3 categories to cut and by how much"
            detectLeaks(transactions);

            // ── Step 3: Generate saving recommendations ───────────────────
            // Ask AI: "Based on the leaks found, generate 3-5 specific,
            //  actionable saving recommendations with exact ₹ amounts
            //  and SIP projections at 12% p.a. for 5, 10, 20 years"
            generateRecommendations(statementId, transactions);

            // ── Step 4: Mark complete ─────────────────────────────────────
            statement.setStatus(Statement.Status.COMPLETED);
            statementRepository.save(statement);

            log.info("AI analysis complete for statement: {}", statementId);

        } catch (Exception e) {
            log.error("AI analysis failed for statement: {}", statementId, e);
            statement.setStatus(Statement.Status.FAILED);
            statementRepository.save(statement);
        }
    }

    // ── Placeholder methods — implement with your AI client ──

    private void categoriseTransactions(List<Transaction> transactions) {
        // TODO: batch transactions (50 at a time to stay within token limits)
        // Call AI API → parse JSON response → update each Transaction.category
        log.info("Categorising {} transactions", transactions.size());
    }

    private void detectLeaks(List<Transaction> transactions) {
        // TODO: call AI with categorised transactions
        // Parse response → save to MoneyLeak entity
        log.info("Detecting leaks in {} transactions", transactions.size());
    }

    private void generateRecommendations(UUID statementId, List<Transaction> transactions) {
        // TODO: call AI with leaks + spending summary
        // Parse response → save to Recommendation entity
        log.info("Generating recommendations for statement: {}", statementId);
    }
}