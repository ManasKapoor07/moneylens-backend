package com.moneylens.service;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionClarification;
import com.moneylens.entity.TransactionClarification.ClarificationType;
import com.moneylens.entity.User;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionClarificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ClarificationGeneratorService
 *
 * Runs after Step 6 in TransactionExtractionWorker.
 * Scans the extracted transactions and generates clarification cards
 * for three types of ambiguous data:
 *
 *   Type A — RECURRING_P2P
 *     Same merchant (normalized), 2+ transactions, total > ₹5,000
 *     Question: "₹X goes to [Name] regularly. What is this?"
 *
 *   Type B — UNCONFIRMED_SALARY
 *     Largest credit doesn't contain SAL-/SALARY/PAYROLL pattern
 *     AND amount > ₹10,000
 *     Question: "₹X was credited on [date]. Was this your salary?"
 *
 *   Type C — LOW_CONFIDENCE_CATEGORY
 *     categoryConfidence < 0.60, debit only, top 5 by amount
 *     Question: "What was this ₹X payment to [merchant]?"
 *
 * Idempotent: skips generation if cards already exist for this statement.
 * Max 5 cards total per statement to avoid overwhelming the user.
 */
@Service
public class ClarificationGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ClarificationGeneratorService.class);

    private static final BigDecimal P2P_AMOUNT_THRESHOLD       = BigDecimal.valueOf(5_000);
    private static final BigDecimal SALARY_AMOUNT_THRESHOLD    = BigDecimal.valueOf(10_000);
    private static final double     LOW_CONFIDENCE_THRESHOLD   = 0.60;
    private static final int        MAX_CARDS_PER_STATEMENT    = 5;
    private static final int        MAX_LOW_CONFIDENCE_CARDS   = 3;

    private final TransactionClarificationRepository clarificationRepository;
    private final StatementRepository                statementRepository;
    private final MerchantRegistry                   merchantRegistry;

    public ClarificationGeneratorService(
            TransactionClarificationRepository clarificationRepository,
            StatementRepository statementRepository,
            MerchantRegistry merchantRegistry
    ) {
        this.clarificationRepository = clarificationRepository;
        this.statementRepository     = statementRepository;
        this.merchantRegistry        = merchantRegistry;
    }

    @Transactional
    public void generateForStatement(UUID statementId, UUID userId, List<Transaction> transactions) {

        // Idempotency — don't regenerate if already done
        if (clarificationRepository.existsByStatementId(statementId)) {
            log.info("Clarifications already exist for statement: {} — skipping", statementId);
            return;
        }

        Statement statement = statementRepository.findById(statementId).orElse(null);
        if (statement == null) {
            log.warn("Statement not found for clarification generation: {}", statementId);
            return;
        }

        User user = statement.getUser();
        List<TransactionClarification> cards = new ArrayList<>();

        List<Transaction> debits  = transactions.stream()
                .filter(t -> t.getType() == Transaction.Type.DEBIT).toList();
        List<Transaction> credits = transactions.stream()
                .filter(t -> t.getType() == Transaction.Type.CREDIT).toList();

        // ── Type A: Recurring P2P ─────────────────────────────────────────────
        cards.addAll(generateRecurringP2pCards(user, statement, debits));

        // ── Type B: Unconfirmed salary ────────────────────────────────────────
        generateUnconfirmedSalaryCard(user, statement, credits).ifPresent(cards::add);

        // ── Type C: Low confidence categories ────────────────────────────────
        cards.addAll(generateLowConfidenceCards(user, statement, debits));

        // Cap at MAX_CARDS_PER_STATEMENT — prioritise A > B > C
        List<TransactionClarification> toSave = cards.stream()
                .limit(MAX_CARDS_PER_STATEMENT)
                .collect(Collectors.toList());

        if (!toSave.isEmpty()) {
            clarificationRepository.saveAll(toSave);
            log.info("Generated {} clarification cards for statement: {}", toSave.size(), statementId);
        } else {
            log.info("No clarification cards needed for statement: {}", statementId);
        }
    }

    // ── Type A: Recurring P2P ─────────────────────────────────────────────────

    private List<TransactionClarification> generateRecurringP2pCards(
            User user, Statement statement, List<Transaction> debits
    ) {
        List<TransactionClarification> cards = new ArrayList<>();

        // Group by normalized merchant name — only P2P category
        Map<String, List<Transaction>> p2pByMerchant = debits.stream()
                .filter(t -> "P2P Transfer".equals(t.getCategory()))
                .collect(Collectors.groupingBy(
                        t -> merchantRegistry.normalize(t.getDescription())
                ));

        p2pByMerchant.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .filter(e -> sum(e.getValue()).compareTo(P2P_AMOUNT_THRESHOLD) >= 0)
                .sorted(Comparator.comparing(
                        (Map.Entry<String, List<Transaction>> e) -> sum(e.getValue())).reversed()
                )
                .limit(2) // max 2 P2P cards
                .forEach(e -> {
                    String merchant = e.getKey();
                    BigDecimal total = sum(e.getValue());
                    BigDecimal perTx = total.divide(
                            BigDecimal.valueOf(e.getValue().size()), 0, RoundingMode.HALF_UP);

                    TransactionClarification card = TransactionClarification.builder()
                            .user(user)
                            .statement(statement)
                            .transactionId(e.getValue().get(0).getId())
                            .clarificationType(ClarificationType.RECURRING_P2P)
                            .questionText(String.format(
                                    "₹%s goes to %s regularly (%d times, avg ₹%s each). What is this?",
                                    fmt(total), merchant, e.getValue().size(), fmt(perTx)
                            ))
                            .options(List.of("Rent", "Family support", "Loan repayment", "Business expense", "Other"))
                            .build();
                    cards.add(card);
                });

        return cards;
    }

    // ── Type B: Unconfirmed salary ────────────────────────────────────────────

    private Optional<TransactionClarification> generateUnconfirmedSalaryCard(
            User user, Statement statement, List<Transaction> credits
    ) {
        if (credits.isEmpty()) return Optional.empty();

        // Find the largest credit
        Transaction largest = credits.stream()
                .max(Comparator.comparing(Transaction::getAmount))
                .orElse(null);

        if (largest == null) return Optional.empty();
        if (largest.getAmount().compareTo(SALARY_AMOUNT_THRESHOLD) < 0) return Optional.empty();

        // Check if it already looks like a salary — if so, no need to ask
        String desc = largest.getDescription().toLowerCase();
        boolean looksLikeSalary = desc.contains("sal") || desc.contains("salary")
                || desc.contains("payroll") || desc.contains("stipend")
                || "Payroll Disbursed".equals(largest.getCategory());

        if (looksLikeSalary) return Optional.empty();

        TransactionClarification card = TransactionClarification.builder()
                .user(user)
                .statement(statement)
                .transactionId(largest.getId())
                .clarificationType(ClarificationType.UNCONFIRMED_SALARY)
                .questionText(String.format(
                        "₹%s was credited on %s. What was this?",
                        fmt(largest.getAmount()), largest.getDate().toString()
                ))
                .options(List.of("My salary", "Freelance / project payment", "One-time transfer", "Business income", "Other"))
                .build();

        return Optional.of(card);
    }

    // ── Type C: Low confidence categories ────────────────────────────────────

    private List<TransactionClarification> generateLowConfidenceCards(
            User user, Statement statement, List<Transaction> debits
    ) {
        return debits.stream()
                .filter(t -> t.getCategoryConfidence() != null
                        && t.getCategoryConfidence() < LOW_CONFIDENCE_THRESHOLD)
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(MAX_LOW_CONFIDENCE_CARDS)
                .map(t -> TransactionClarification.builder()
                        .user(user)
                        .statement(statement)
                        .transactionId(t.getId())
                        .clarificationType(ClarificationType.LOW_CONFIDENCE_CATEGORY)
                        .questionText(String.format(
                                "What was this ₹%s payment to \"%s\"?",
                                fmt(t.getAmount()),
                                merchantRegistry.normalize(t.getDescription())
                        ))
                        .options(List.of(
                                "Rent", "Food", "Shopping", "EMI / Loan",
                                "Investment", "Utilities", "Healthcare", "Other"
                        ))
                        .build()
                )
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal sum(List<Transaction> txs) {
        return txs.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String fmt(BigDecimal v) {
        return v == null ? "0" : String.format("%,.0f", v);
    }
}