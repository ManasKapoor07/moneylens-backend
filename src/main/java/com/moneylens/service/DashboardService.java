package com.moneylens.service;

import com.moneylens.dto.response.DashboardSummaryDto;
import com.moneylens.dto.response.DashboardSummaryDto.MonthlyOverviewItem;
import com.moneylens.dto.response.TransactionDto;
import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.User;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DashboardService — rewritten to be timeline-centric.
 *
 * ── Two modes ────────────────────────────────────────────────────────────────
 *
 *   1. User-level (statementId == null)
 *      Reads from the canonical cross-statement timeline.
 *      This is what powers the main dashboard.
 *      Top cards, monthly overview, category breakdown, recent transactions
 *      all span ALL completed statements.
 *
 *   2. Statement drill-down (statementId != null)
 *      Scopes all numbers to the single selected statement.
 *      Used when a user taps into a specific uploaded statement.
 *      Change % comparisons still use the previous statement for context.
 *
 * ── What changed from the old version ───────────────────────────────────────
 *
 *   - buildSummary() now accepts nullable statementId
 *   - When statementId is null: currentTx comes from the canonical timeline
 *     (all completed statements, deduplicated)
 *   - When statementId is provided: behaviour is identical to the old version
 *   - historicalTx is always from the canonical timeline (for charts)
 *   - Previous statement comparison uses the most recent completed statement
 *     that isn't the selected one (unchanged logic)
 */
@Service
public class DashboardService {

    private static final int RECENT_TX_LIMIT = 5;

    private final UserRepository        userRepository;
    private final StatementRepository   statementRepository;
    private final TransactionRepository transactionRepository;

    public DashboardService(
            UserRepository userRepository,
            StatementRepository statementRepository,
            TransactionRepository transactionRepository
    ) {
        this.userRepository        = userRepository;
        this.statementRepository   = statementRepository;
        this.transactionRepository = transactionRepository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds the dashboard summary.
     *
     * @param email       authenticated user's email (required)
     * @param statementId optional — when null, returns the full user-level view;
     *                    when provided, scopes to that statement's data
     */
    public DashboardSummaryDto buildSummary(String email, UUID statementId) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        // ── Guard: at least one completed statement ───────────────────────────

        List<Statement> completedStatements =
                statementRepository
                        .findByUserAndStatusOrderByPeriodToDesc(user, Statement.Status.COMPLETED)
                        .stream()
                        .filter(s -> s.getPeriodFrom() != null && s.getPeriodTo() != null)
                        .collect(Collectors.toList());

        if (completedStatements.isEmpty()) {
            return emptyDashboard();
        }

        // ── Canonical timeline (always cross-statement, for charts) ───────────

        List<Transaction> canonicalTimeline =
                transactionRepository.findCanonicalTimelineByUserId(user.getId());

        // ── Current transactions: scoped or full ──────────────────────────────

        List<Transaction> currentTx;
        Statement         selected;

        if (statementId != null) {

            // Drill-down mode: scope to the specific statement
            selected = statementRepository.findById(statementId)
                    .orElseThrow(() -> new RuntimeException("Statement not found"));

            if (!selected.getUser().getId().equals(user.getId())) {
                throw new RuntimeException("Unauthorized access");
            }
            if (selected.getStatus() != Statement.Status.COMPLETED) {
                throw new RuntimeException("Statement processing not completed");
            }

            currentTx = transactionRepository.findByStatement(selected);

        } else {
            selected = null;

            // User-level mode: full canonical timeline IS the current view
            currentTx = canonicalTimeline;
        }

        // ── Balance ───────────────────────────────────────────────────────────

        // In drill-down mode: use statement closing balance if available.
        // In user-level mode: derive from the most recent completed statement.
        BigDecimal totalBalance;

        if (selected != null && selected.getClosingBalance() != null) {
            totalBalance = selected.getClosingBalance();
        } else {
            Statement mostRecent = completedStatements.get(0); // already sorted desc
            totalBalance = completedStatements.stream()
                    .collect(Collectors.toMap(
                            s -> s.getBankName(),          // group by bank (or accountId if you have it)
                            s -> s,
                            (s1, s2) -> s1                 // keep most recent (list is already sorted desc)
                    ))
                    .values().stream()
                    .map(Statement::getClosingBalance)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        // ── Income / spending for current scope ───────────────────────────────

        BigDecimal totalIncome    = sumCredits(currentTx);
        BigDecimal totalSpending  = sumDebits(currentTx);

        // ── Savings ───────────────────────────────────────────────────────────

        BigDecimal savings;

        if (selected != null
                && selected.getClosingBalance() != null
                && selected.getOpeningBalance() != null) {
            savings = selected.getClosingBalance().subtract(selected.getOpeningBalance());
        } else {
            savings = totalIncome.subtract(totalSpending);
        }

        // ── Previous statement for change % comparisons ───────────────────────

        Statement previous = completedStatements.stream()
                .filter(s -> selected == null || !s.getId().equals(selected.getId()))
                .findFirst()
                .orElse(null);

        List<Transaction> previousTx = previous == null
                ? List.of()
                : transactionRepository.findByStatement(previous);

        BigDecimal previousIncome   = sumCredits(previousTx);
        BigDecimal previousSpending = sumDebits(previousTx);
        BigDecimal previousNet      = previousIncome.subtract(previousSpending);
        BigDecimal currentNet       = totalIncome.subtract(totalSpending);

        double spendingChangePct = calculatePercentageChange(totalSpending, previousSpending);
        double incomeChangePct   = calculatePercentageChange(totalIncome,   previousIncome);
        double balanceChangePct  = calculatePercentageChange(currentNet,    previousNet);

        // ── Monthly overview (always from full canonical timeline) ────────────

        List<MonthlyOverviewItem> monthlyOverview = buildMonthlyOverview(canonicalTimeline);

        // ── Spending by category (scoped to currentTx) ───────────────────────

        Map<String, BigDecimal> spendingByCategory = currentTx.stream()
                .filter(t -> t.getType() == Transaction.Type.DEBIT
                        && t.getCategory() != null
                        && !t.getCategory().isBlank())
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ));

        // ── Recent transactions (scoped to currentTx) ─────────────────────────

        List<TransactionDto> recentTransactions = currentTx.stream()
                .sorted(Comparator.comparing(Transaction::getDate).reversed())
                .limit(RECENT_TX_LIMIT)
                .map(TransactionDto::from)
                .collect(Collectors.toList());

        return DashboardSummaryDto.builder()
                .totalBalance(totalBalance)
                .totalSpending(totalSpending)
                .totalIncome(totalIncome)
                .savings(savings)
                .balanceChangePercent(balanceChangePct)
                .spendingChangePercent(spendingChangePct)
                .incomeChangePercent(incomeChangePct)
                .monthlyOverview(monthlyOverview)
                .spendingByCategory(spendingByCategory)
                .recentTransactions(recentTransactions)
                .build();
    }

    /**
     * Convenience overload for the original per-statement call signature.
     * Kept for backward compatibility with any callers that pass statementId first.
     *
     * @deprecated Prefer buildSummary(email, statementId). Will be removed once
     *             all controllers are updated.
     */
    @Deprecated
    public DashboardSummaryDto buildSummary(UUID statementId, String email) {
        return buildSummary(email, statementId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private BigDecimal sumCredits(List<Transaction> txList) {
        return txList.stream()
                .filter(t -> t.getType() == Transaction.Type.CREDIT)
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumDebits(List<Transaction> txList) {
        return txList.stream()
                .filter(t -> t.getType() == Transaction.Type.DEBIT)
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private double calculatePercentageChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return current
                .subtract(previous)
                .divide(previous.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private List<MonthlyOverviewItem> buildMonthlyOverview(List<Transaction> txList) {

        Map<String, BigDecimal> debitByMonth  = new TreeMap<>();
        Map<String, BigDecimal> creditByMonth = new TreeMap<>();

        for (Transaction tx : txList) {
            String key = tx.getDate().getYear()
                    + "-"
                    + String.format("%02d", tx.getDate().getMonthValue());

            if (tx.getType() == Transaction.Type.DEBIT) {
                debitByMonth.merge(key, tx.getAmount(), BigDecimal::add);
            } else {
                creditByMonth.merge(key, tx.getAmount(), BigDecimal::add);
            }
        }

        Set<String> allMonths = new TreeSet<>();
        allMonths.addAll(debitByMonth.keySet());
        allMonths.addAll(creditByMonth.keySet());

        List<MonthlyOverviewItem> result = new ArrayList<>();

        for (String key : allMonths) {
            String[] parts = key.split("-");
            Month    month = Month.of(Integer.parseInt(parts[1]));
            String   label = month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + " " + parts[0];

            result.add(new MonthlyOverviewItem(
                    label,
                    debitByMonth.getOrDefault(key,  BigDecimal.ZERO),
                    creditByMonth.getOrDefault(key, BigDecimal.ZERO)
            ));
        }

        return result;
    }

    private DashboardSummaryDto emptyDashboard() {
        return DashboardSummaryDto.builder()
                .totalBalance(BigDecimal.ZERO)
                .totalSpending(BigDecimal.ZERO)
                .totalIncome(BigDecimal.ZERO)
                .savings(BigDecimal.ZERO)
                .balanceChangePercent(0.0)
                .spendingChangePercent(0.0)
                .incomeChangePercent(0.0)
                .monthlyOverview(List.of())
                .spendingByCategory(Map.of())
                .recentTransactions(List.of())
                .build();
    }
}