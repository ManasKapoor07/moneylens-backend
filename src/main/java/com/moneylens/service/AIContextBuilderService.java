package com.moneylens.service;

import com.moneylens.entity.BehavioralSignal;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionInsight;
import com.moneylens.signal.engine.SignalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AIContextBuilderService
 *
 * CHANGES FROM ORIGINAL:
 *   - buildContext() now accepts List<BehavioralSignal> (replaces the raw
 *     pattern strings it used to compute itself)
 *   - buildPromptContext() accepts the same
 *   - renderPromptContext() now has a BEHAVIORAL SIGNALS section that feeds
 *     the LLM pre-computed, evidence-backed facts instead of asking it to
 *     infer patterns from raw numbers
 *   - normalizeMerchant() delegates to MerchantRegistry
 *   - MERCHANT_ALIAS static map removed
 *   - BehaviorProfile.patterns list removed (replaced by signals)
 *
 * The health score is still computed deterministically here and should be
 * read from context.healthScore().score() — NOT from GPT's spendingPulse.
 */
@Service
public class AIContextBuilderService {

    private static final Logger log = LoggerFactory.getLogger(AIContextBuilderService.class);

    private final MerchantRegistry merchantRegistry;

    public AIContextBuilderService(MerchantRegistry merchantRegistry) {
        this.merchantRegistry = merchantRegistry;
    }

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Build AIContext from transactions + pre-computed signals.
     * Signals come from BehavioralSignalEngine — never computed here.
     */
    public AIContext buildContext(
            List<Transaction> transactions,
            List<TransactionInsight> insights,
            List<BehavioralSignal> signals
    ) {
        List<Transaction> debits  = filter(transactions, Transaction.Type.DEBIT);
        List<Transaction> credits = filter(transactions, Transaction.Type.CREDIT);

        IncomeProfile   income   = buildIncomeProfile(credits);
        ExpenseProfile  expenses = buildExpenseProfile(debits);
        MerchantProfile merchants = buildMerchantProfile(debits);
        RiskProfile     risk     = buildRiskProfile(debits, credits, expenses);
        HealthScore     health   = computeHealthScore(expenses, risk, signals);

        return AIContext.builder()
                .periodLabel(resolvePeriodLabel(transactions))
                .income(income)
                .expenses(expenses)
                .merchants(merchants)
                .signals(signals)
                .risk(risk)
                .healthScore(health)
                .build();
    }

    /**
     * Convenience overload — no signals (backward compat for callers that
     * haven't wired the engine yet).
     */
    public AIContext buildContext(List<Transaction> transactions, List<TransactionInsight> insights) {
        return buildContext(transactions, insights, List.of());
    }

    /**
     * Build context and render to prompt string in one call.
     */
    public String buildPromptContext(
            List<Transaction> transactions,
            List<TransactionInsight> insights,
            List<BehavioralSignal> signals
    ) {
        AIContext ctx = buildContext(transactions, insights, signals);
        String prompt = renderPromptContext(ctx);
        log.info("AIContext built — period: {} | health: {}/100 ({}) | signals: {} fired",
                ctx.periodLabel(),
                ctx.healthScore().score(),
                ctx.healthScore().label(),
                ctx.signals().stream().filter(BehavioralSignal::isFired).count());
        return prompt;
    }

    public String buildPromptContext(List<Transaction> transactions, List<TransactionInsight> insights) {
        return buildPromptContext(transactions, insights, List.of());
    }

    // ─────────────────────────────────────────────────────────────────
    // INCOME PROFILE  (unchanged from original)
    // ─────────────────────────────────────────────────────────────────

    private IncomeProfile buildIncomeProfile(List<Transaction> credits) {
        BigDecimal totalCredit = sum(credits);

        Optional<Transaction> salaryTx = credits.stream()
                .max(Comparator.comparing(Transaction::getAmount));

        boolean salaryDetected = salaryTx
                .map(t -> t.getAmount().compareTo(BigDecimal.valueOf(5000)) > 0)
                .orElse(false);

        BigDecimal salaryAmount = salaryTx.map(Transaction::getAmount).orElse(BigDecimal.ZERO);
        String     salaryDate   = salaryTx.map(t -> t.getDate().toString()).orElse(null);
        String     employer     = salaryTx.map(t -> inferEmployer(t.getDescription())).orElse(null);

        BigDecimal secondaryIncome = credits.stream()
                .filter(t -> salaryTx.map(s -> !s.getId().equals(t.getId())).orElse(true))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CreditSource> sources = credits.stream()
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .map(t -> new CreditSource(
                        merchantRegistry.normalize(t.getDescription()),
                        t.getAmount(),
                        t.getDate().toString(),
                        "Payroll Disbursed".equals(t.getCategory()) ? "SALARY" :
                                "P2P Transfer".equals(t.getCategory()) ? "P2P" : "OTHER"))
                .collect(Collectors.toList());

        return new IncomeProfile(totalCredit, salaryDetected, salaryAmount,
                salaryDate, employer, secondaryIncome, sources);
    }

    // ─────────────────────────────────────────────────────────────────
    // EXPENSE PROFILE  (unchanged)
    // ─────────────────────────────────────────────────────────────────

    private ExpenseProfile buildExpenseProfile(List<Transaction> debits) {
        BigDecimal totalDebit = sum(debits);

        Map<String, BigDecimal> byCat = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "Other",
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        List<CategorySpend> breakdown = byCat.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> {
                    BigDecimal pct = totalDebit.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                            : e.getValue().multiply(BigDecimal.valueOf(100))
                            .divide(totalDebit, 1, RoundingMode.HALF_UP);
                    return new CategorySpend(e.getKey(), e.getValue(), pct);
                })
                .collect(Collectors.toList());

        List<SingleTransaction> largest = debits.stream()
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .map(t -> new SingleTransaction(
                        merchantRegistry.normalize(t.getDescription()),
                        t.getAmount(), t.getDate().toString(), t.getCategory()))
                .collect(Collectors.toList());

        BigDecimal emiTotal = sumCategory(debits, "EMI / Loan");

        long distinctDays = debits.stream().map(Transaction::getDate).distinct().count();
        BigDecimal avgDaily = distinctDays == 0 ? BigDecimal.ZERO
                : totalDebit.divide(BigDecimal.valueOf(distinctDays), 0, RoundingMode.HALF_UP);

        return new ExpenseProfile(totalDebit, breakdown, largest, emiTotal, avgDaily);
    }

    // ─────────────────────────────────────────────────────────────────
    // MERCHANT PROFILE  (unchanged, normalizeMerchant now uses registry)
    // ─────────────────────────────────────────────────────────────────

    private MerchantProfile buildMerchantProfile(List<Transaction> debits) {
        Map<String, BigDecimal> byMerchant = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> merchantRegistry.normalize(t.getDescription()),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        Map<String, Long> countByMerchant = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> merchantRegistry.normalize(t.getDescription()),
                        Collectors.counting()));

        List<MerchantSummary> top = byMerchant.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .map(e -> new MerchantSummary(
                        e.getKey(), e.getValue(),
                        countByMerchant.getOrDefault(e.getKey(), 0L).intValue()))
                .collect(Collectors.toList());

        List<String> recurring = countByMerchant.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return new MerchantProfile(top, recurring);
    }

    // ─────────────────────────────────────────────────────────────────
    // RISK PROFILE  (simplified — behavior patterns removed, signals own them)
    // ─────────────────────────────────────────────────────────────────

    private RiskProfile buildRiskProfile(
            List<Transaction> debits,
            List<Transaction> credits,
            ExpenseProfile expenses
    ) {
        BigDecimal totalCredit = sum(credits);
        BigDecimal totalDebit  = sum(debits);

        int burnRatePct = 0;
        if (totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            burnRatePct = totalDebit.multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 0, RoundingMode.HALF_UP).intValue();
        }

        int emiBurdenPct = 0;
        if (totalCredit.compareTo(BigDecimal.ZERO) > 0
                && expenses.emiTotal().compareTo(BigDecimal.ZERO) > 0) {
            emiBurdenPct = expenses.emiTotal().multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 0, RoundingMode.HALF_UP).intValue();
        }

        BigDecimal netFlow   = totalCredit.subtract(totalDebit);
        int savingsRate = 0;
        if (totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            savingsRate = netFlow.max(BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 0, RoundingMode.HALF_UP).intValue();
        }

        boolean overspending    = burnRatePct > 100;
        boolean highEmiPressure = emiBurdenPct > 30;
        boolean noSavings       = savingsRate == 0;

        // Spending spikes: days > 2x daily average
        Map<LocalDate, BigDecimal> dailyDebit = debits.stream()
                .collect(Collectors.groupingBy(Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        BigDecimal avgDaily = expenses.avgDaily();
        List<String> spikes = dailyDebit.entrySet().stream()
                .filter(e -> avgDaily.compareTo(BigDecimal.ZERO) > 0
                        && e.getValue().compareTo(avgDaily.multiply(BigDecimal.valueOf(2))) > 0)
                .sorted(Map.Entry.<LocalDate, BigDecimal>comparingByValue().reversed())
                .limit(3)
                .map(e -> e.getKey().format(DateTimeFormatter.ofPattern("d MMM"))
                        + " (₹" + fmt(e.getValue()) + ")")
                .collect(Collectors.toList());

        return new RiskProfile(burnRatePct, savingsRate, emiBurdenPct,
                overspending, highEmiPressure, noSavings, spikes);
    }

    // ─────────────────────────────────────────────────────────────────
    // HEALTH SCORE  — now uses signals for deductions (more accurate)
    // ─────────────────────────────────────────────────────────────────

    private HealthScore computeHealthScore(
            ExpenseProfile expenses,
            RiskProfile risk,
            List<BehavioralSignal> signals
    ) {
        int score = 100;

        // Hard risk flags
        if (risk.overspending())     score -= 25;
        if (risk.noSavings())        score -= 20;
        if (risk.highEmiPressure())  score -= 10;
        if (!risk.spendingSpikes().isEmpty()) score -= 5;

        // Signal-based deductions (fired HIGH signals each cost 10, MEDIUM cost 5)
        for (BehavioralSignal signal : signals) {
            if (!signal.isFired()) continue;
            // Positive signals add back points
            switch (signal.getSignalType()) {
                case HEALTHY_SAVINGS     -> score += 5;
                case INVESTING_HABIT     -> score += 5;
                case CONTROLLED_SPENDING -> score += 5;
                // Negative signals deduct
                case OVERSPENDING        -> {} // already counted via risk.overspending()
                case ZERO_SAVINGS        -> {} // already counted via risk.noSavings()
                case HIGH_EMI_BURDEN     -> {} // already counted via risk.highEmiPressure()
                default -> score -= signal.getSeverity() == BehavioralSignal.Severity.HIGH ? 10 : 5;
            }
        }

        score = Math.max(0, Math.min(100, score));

        String grade = score >= 80 ? "A" : score >= 65 ? "B" : score >= 50 ? "C" : score >= 35 ? "D" : "F";
        String label = score >= 80 ? "Healthy" : score >= 65 ? "Fair"
                : score >= 50 ? "Needs Attention" : "At Risk";

        return new HealthScore(score, grade, label);
    }

    // ─────────────────────────────────────────────────────────────────
    // PROMPT RENDERER
    // ─────────────────────────────────────────────────────────────────

    public String renderPromptContext(AIContext ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== FINANCIAL PROFILE: ").append(ctx.periodLabel()).append(" ===\n\n");

        // Income
        sb.append("INCOME\n");
        sb.append("  Total received: ₹").append(fmt(ctx.income().totalCredit())).append("\n");
        if (ctx.income().salaryDetected()) {
            sb.append("  Salary: ₹").append(fmt(ctx.income().salaryAmount()))
                    .append(" on ").append(ctx.income().salaryDate());
            if (ctx.income().employer() != null) sb.append(" from ").append(ctx.income().employer());
            sb.append("\n");
        }
        if (ctx.income().secondaryIncome().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("  Other income: ₹").append(fmt(ctx.income().secondaryIncome())).append("\n");
        }
        sb.append("\n");

        // Expenses
        sb.append("EXPENSES\n");
        sb.append("  Total spent: ₹").append(fmt(ctx.expenses().totalDebit())).append("\n");
        sb.append("  Average daily spend: ₹").append(fmt(ctx.expenses().avgDaily())).append("\n");
        if (ctx.expenses().emiTotal().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("  EMI/Loan payments: ₹").append(fmt(ctx.expenses().emiTotal())).append("\n");
        }
        sb.append("  Breakdown by category:\n");
        ctx.expenses().breakdown().stream()
                .filter(c -> c.amount().compareTo(BigDecimal.valueOf(100)) > 0)
                .forEach(c -> sb.append("    - ").append(c.category())
                        .append(": ₹").append(fmt(c.amount()))
                        .append(" (").append(c.percentOfTotal()).append("%)\n"));
        sb.append("\n");

        // Top merchants
        sb.append("TOP MERCHANTS\n");
        ctx.merchants().top().stream().limit(7).forEach(m ->
                sb.append("  - ").append(m.name())
                        .append(": ₹").append(fmt(m.totalSpent()))
                        .append(" · ").append(m.transactionCount()).append("x\n"));
        if (!ctx.merchants().recurring().isEmpty()) {
            sb.append("  Recurring: ")
                    .append(String.join(", ", ctx.merchants().recurring())).append("\n");
        }
        sb.append("\n");

        // ── BEHAVIORAL SIGNALS — the key change ──────────────────────────────
        // Feed the LLM pre-computed, evidence-backed facts.
        // It narrates these. It does NOT invent additional patterns.
        List<BehavioralSignal> firedSignals = ctx.signals().stream()
                .filter(BehavioralSignal::isFired)
                .sorted(Comparator.comparing(BehavioralSignal::getSeverity).reversed())
                .collect(Collectors.toList());

        if (!firedSignals.isEmpty()) {
            sb.append("BEHAVIORAL SIGNALS (pre-computed — narrate these, do not add others)\n");
            for (BehavioralSignal signal : firedSignals) {
                sb.append("  [").append(signal.getSeverity()).append("] ")
                        .append(signal.getSignalType().name()).append("\n");
                if (signal.getEvidence() != null) {
                    sb.append("    Evidence: ").append(signal.getEvidence()).append("\n");
                }
            }
            sb.append("\n");
        } else {
            sb.append("BEHAVIORAL SIGNALS\n  No significant signals detected.\n\n");
        }

        // Financial health
        sb.append("FINANCIAL HEALTH\n");
        sb.append("  Score: ").append(ctx.healthScore().score())
                .append("/100 (").append(ctx.healthScore().grade())
                .append(" — ").append(ctx.healthScore().label()).append(")\n");
        sb.append("  Burn rate: ").append(ctx.risk().burnRatePct()).append("% of income spent\n");
        sb.append("  Savings rate: ").append(ctx.risk().savingsRate()).append("%\n");
        if (!ctx.risk().spendingSpikes().isEmpty()) {
            sb.append("  Spending spikes: ")
                    .append(String.join(", ", ctx.risk().spendingSpikes())).append("\n");
        }

        List<String> flags = new ArrayList<>();
        if (ctx.risk().overspending())    flags.add("overspending");
        if (ctx.risk().noSavings())       flags.add("zero savings");
        if (ctx.risk().highEmiPressure()) flags.add("high EMI burden");
        sb.append("  Flags: ").append(flags.isEmpty() ? "none" : String.join(", ", flags))
                .append("\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    private List<Transaction> filter(List<Transaction> txs, Transaction.Type type) {
        return txs.stream().filter(t -> t.getType() == type).collect(Collectors.toList());
    }

    private BigDecimal sum(List<Transaction> txs) {
        return txs.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCategory(List<Transaction> txs, String category) {
        return sum(txs.stream().filter(t -> category.equals(t.getCategory()))
                .collect(Collectors.toList()));
    }

    private String resolvePeriodLabel(List<Transaction> txs) {
        Optional<LocalDate> min = txs.stream().map(Transaction::getDate).min(Comparator.naturalOrder());
        Optional<LocalDate> max = txs.stream().map(Transaction::getDate).max(Comparator.naturalOrder());
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM yyyy");
        if (min.isPresent() && max.isPresent()
                && min.get().getMonth() == max.get().getMonth()) {
            return min.get().format(f);
        }
        return min.map(d -> d.format(f)).orElse("Unknown Period")
                + " – " + max.map(d -> d.format(f)).orElse("");
    }

    private String inferEmployer(String description) {
        if (description == null) return null;
        String upper = description.toUpperCase();
        int salIdx = upper.indexOf("SAL-");
        if (salIdx > 0 && salIdx + 4 < description.length()) {
            String after = description.substring(salIdx + 4).trim();
            return after.substring(0, Math.min(after.length(), 30)).trim();
        }
        return null;
    }

    private String fmt(BigDecimal val) {
        return val == null ? "0" : String.format("%,.0f", val);
    }

    // ─────────────────────────────────────────────────────────────────
    // VALUE OBJECTS
    // ─────────────────────────────────────────────────────────────────

    public record AIContext(
            String periodLabel,
            IncomeProfile income,
            ExpenseProfile expenses,
            MerchantProfile merchants,
            List<BehavioralSignal> signals,       // NEW — replaces BehaviorProfile
            RiskProfile risk,
            HealthScore healthScore
    ) {
        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private String periodLabel;
            private IncomeProfile income;
            private ExpenseProfile expenses;
            private MerchantProfile merchants;
            private List<BehavioralSignal> signals = List.of();
            private RiskProfile risk;
            private HealthScore healthScore;

            public Builder periodLabel(String v)       { this.periodLabel = v; return this; }
            public Builder income(IncomeProfile v)     { this.income = v; return this; }
            public Builder expenses(ExpenseProfile v)  { this.expenses = v; return this; }
            public Builder merchants(MerchantProfile v){ this.merchants = v; return this; }
            public Builder signals(List<BehavioralSignal> v){ this.signals = v; return this; }
            public Builder risk(RiskProfile v)         { this.risk = v; return this; }
            public Builder healthScore(HealthScore v)  { this.healthScore = v; return this; }

            public AIContext build() {
                return new AIContext(periodLabel, income, expenses, merchants,
                        signals, risk, healthScore);
            }
        }
    }

    public record IncomeProfile(
            BigDecimal totalCredit, boolean salaryDetected,
            BigDecimal salaryAmount, String salaryDate, String employer,
            BigDecimal secondaryIncome, List<CreditSource> topSources
    ) {}

    public record CreditSource(String name, BigDecimal amount, String date, String type) {}

    public record ExpenseProfile(
            BigDecimal totalDebit, List<CategorySpend> breakdown,
            List<SingleTransaction> largestTransactions,
            BigDecimal emiTotal, BigDecimal avgDaily
    ) {}

    public record CategorySpend(String category, BigDecimal amount, BigDecimal percentOfTotal) {}
    public record SingleTransaction(String merchant, BigDecimal amount, String date, String category) {}
    public record MerchantProfile(List<MerchantSummary> top, List<String> recurring) {}
    public record MerchantSummary(String name, BigDecimal totalSpent, int transactionCount) {}

    public record RiskProfile(
            int burnRatePct, int savingsRate, int emiBurdenPct,
            boolean overspending, boolean highEmiPressure, boolean noSavings,
            List<String> spendingSpikes
    ) {}

    public record HealthScore(int score, String grade, String label) {}
}