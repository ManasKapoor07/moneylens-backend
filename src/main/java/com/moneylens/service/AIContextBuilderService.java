package com.moneylens.service;

import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionInsight;
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
 * Converts raw transactions + pre-computed insights into a compact,
 * semantically rich payload designed for LLM consumption.
 *
 * Responsibilities:
 *   1. Aggregate numbers (backend does math — LLM does reasoning)
 *   2. Normalize merchants (UPI-ZOMATO-PAYZOMATO → "Zomato")
 *   3. Detect behavioral patterns
 *   4. Emit risk signals
 *   5. Build a minimal, token-efficient prompt context
 *
 * The output of buildContext() goes directly into the AI prompt.
 * Never send raw transactions to the LLM.
 */
@Service
public class AIContextBuilderService {

    private static final Logger log = LoggerFactory.getLogger(AIContextBuilderService.class);

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Primary entry point.
     * Returns a structured AIContext ready to be serialised into the LLM prompt.
     */
    public AIContext buildContext(List<Transaction> transactions,
                                  List<TransactionInsight> insights) {

        List<Transaction> debits  = filter(transactions, Transaction.Type.DEBIT);
        List<Transaction> credits = filter(transactions, Transaction.Type.CREDIT);

        IncomeProfile      income      = buildIncomeProfile(credits);
        ExpenseProfile     expenses    = buildExpenseProfile(debits);
        MerchantProfile    merchants   = buildMerchantProfile(debits);
        BehaviorProfile    behavior    = buildBehaviorProfile(transactions, debits, credits, income);
        RiskProfile        risk        = buildRiskProfile(debits, credits, income, expenses, behavior);
        HealthScore        health      = computeHealthScore(income, expenses, risk, behavior);

        return AIContext.builder()
                .periodLabel(resolvePeriodLabel(transactions))
                .income(income)
                .expenses(expenses)
                .merchants(merchants)
                .behavior(behavior)
                .risk(risk)
                .healthScore(health)
                .build();
    }

    /**
     * Renders the AIContext as a concise, structured text block
     * that slots directly into an LLM system or user prompt.
     *
     * Example usage:
     *   String ctx = service.buildPromptContext(transactions, insights);
     *   // → pass to OpenAI / Claude as context
     */
    public String buildPromptContext(List<Transaction> transactions,
                                     List<TransactionInsight> insights) {
        AIContext ctx = buildContext(transactions, insights);
        String prompt = renderPromptContext(ctx);
        log.info("AIContext built — period: {} | health: {}/100 ({}) | burn: {}% | savings: {}%",
                ctx.periodLabel(),
                ctx.healthScore().score(),
                ctx.healthScore().label(),
                ctx.risk().burnRatePct(),
                ctx.risk().savingsRate());
        log.info("AIContext prompt payload:\n{}", prompt);
        return prompt;
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. INCOME PROFILE
    // ─────────────────────────────────────────────────────────────────

    private IncomeProfile buildIncomeProfile(List<Transaction> credits) {
        BigDecimal totalCredit = sum(credits);

        // Salary = largest single credit (heuristic for UPI payroll statements)
        Optional<Transaction> salaryTx = credits.stream()
                .max(Comparator.comparing(Transaction::getAmount));

        boolean salaryDetected = salaryTx
                .map(t -> t.getAmount().compareTo(BigDecimal.valueOf(5000)) > 0)
                .orElse(false);

        BigDecimal salaryAmount  = salaryTx.map(Transaction::getAmount).orElse(BigDecimal.ZERO);
        String     salaryDate    = salaryTx.map(t -> t.getDate().toString()).orElse(null);
        String     employer      = salaryTx.map(t -> inferEmployer(t.getDescription())).orElse(null);

        // Secondary income = other credits besides the primary salary tx
        BigDecimal secondaryIncome = credits.stream()
                .filter(t -> salaryTx.map(s -> !s.getId().equals(t.getId())).orElse(true))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Top 5 credit sources
        List<CreditSource> sources = credits.stream()
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .map(t -> new CreditSource(
                        normalizeMerchant(t.getDescription()),
                        t.getAmount(),
                        t.getDate().toString(),
                        "Payroll Disbursed".equals(t.getCategory()) ? "SALARY" :
                                "P2P Transfer".equals(t.getCategory())      ? "P2P"    : "OTHER"))
                .collect(Collectors.toList());

        return new IncomeProfile(
                totalCredit,
                salaryDetected,
                salaryAmount,
                salaryDate,
                employer,
                secondaryIncome,
                sources
        );
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. EXPENSE PROFILE
    // ─────────────────────────────────────────────────────────────────

    private ExpenseProfile buildExpenseProfile(List<Transaction> debits) {
        BigDecimal totalDebit = sum(debits);

        // Category breakdown
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

        // Largest single transactions
        List<SingleTransaction> largest = debits.stream()
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .map(t -> new SingleTransaction(
                        normalizeMerchant(t.getDescription()),
                        t.getAmount(),
                        t.getDate().toString(),
                        t.getCategory()))
                .collect(Collectors.toList());

        // EMI load
        BigDecimal emiTotal = sumCategory(debits, "EMI / Loan");

        // Daily average
        long distinctDays = debits.stream().map(Transaction::getDate).distinct().count();
        BigDecimal avgDaily = distinctDays == 0 ? BigDecimal.ZERO
                : totalDebit.divide(BigDecimal.valueOf(distinctDays), 0, RoundingMode.HALF_UP);

        return new ExpenseProfile(totalDebit, breakdown, largest, emiTotal, avgDaily);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. MERCHANT PROFILE
    // ─────────────────────────────────────────────────────────────────

    private MerchantProfile buildMerchantProfile(List<Transaction> debits) {

        // Aggregate by normalized merchant name
        Map<String, BigDecimal> byMerchant = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> normalizeMerchant(t.getDescription()),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        Map<String, Long> countByMerchant = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> normalizeMerchant(t.getDescription()),
                        Collectors.counting()));

        List<MerchantSummary> top = byMerchant.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .map(e -> new MerchantSummary(
                        e.getKey(),
                        e.getValue(),
                        countByMerchant.getOrDefault(e.getKey(), 0L).intValue()))
                .collect(Collectors.toList());

        // Recurring = merchants with 3+ transactions in the period
        List<String> recurring = countByMerchant.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return new MerchantProfile(top, recurring);
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. BEHAVIOR PROFILE
    // ─────────────────────────────────────────────────────────────────

    private BehaviorProfile buildBehaviorProfile(List<Transaction> all,
                                                 List<Transaction> debits,
                                                 List<Transaction> credits,
                                                 IncomeProfile income) {
        BigDecimal totalDebit  = sum(debits);
        BigDecimal totalCredit = sum(credits);

        // Post-salary drain: spend in 3 days after salary
        BigDecimal postSalaryDrain = BigDecimal.ZERO;
        int        postSalaryDrainPct = 0;
        if (income.salaryDate() != null) {
            LocalDate sd = LocalDate.parse(income.salaryDate());
            postSalaryDrain = sum(debits.stream()
                    .filter(t -> !t.getDate().isBefore(sd) && !t.getDate().isAfter(sd.plusDays(3)))
                    .collect(Collectors.toList()));
            if (totalDebit.compareTo(BigDecimal.ZERO) > 0) {
                postSalaryDrainPct = postSalaryDrain.multiply(BigDecimal.valueOf(100))
                        .divide(totalDebit, 0, RoundingMode.HALF_UP).intValue();
            }
        }

        // Micro-payment behaviour (< ₹200)
        List<Transaction> micro = debits.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.valueOf(200)) < 0)
                .collect(Collectors.toList());
        BigDecimal microTotal = sum(micro);
        int        microCount = micro.size();

        // Food delivery frequency
        List<Transaction> food = debits.stream()
                .filter(t -> "Food & Dining".equals(t.getCategory()))
                .collect(Collectors.toList());
        BigDecimal foodTotal = sum(food);
        int        foodCount = food.size();

        // P2P volume
        BigDecimal p2pTotal = sumCategory(debits, "P2P Transfer");

        // Weekend vs weekday avg
        Map<Boolean, List<Transaction>> wkSplit = debits.stream()
                .collect(Collectors.partitioningBy(t -> isWeekend(t.getDate())));
        BigDecimal avgWeekend = averageDaily(wkSplit.get(true));
        BigDecimal avgWeekday = averageDaily(wkSplit.get(false));

        // Low-balance events: days where closing balance < 500
        long lowBalanceDays = all.stream()
                .filter(t -> t.getBalance() != null && t.getBalance().compareTo(BigDecimal.valueOf(500)) < 0)
                .map(Transaction::getDate)
                .distinct()
                .count();

        // Named patterns (human-readable, fed directly to LLM)
        List<String> patterns = new ArrayList<>();
        if (postSalaryDrainPct > 50) patterns.add("Spends over 50% of monthly budget within 3 days of salary credit");
        if (foodCount >= 8)          patterns.add("Heavy food delivery habit (" + foodCount + " orders/month)");
        if (micro.size() > 80)       patterns.add("Very high frequency of small UPI payments (under ₹200)");
        if (lowBalanceDays > 5)      patterns.add("Frequently runs low on balance before salary (" + lowBalanceDays + " days under ₹500)");
        if (p2pTotal.compareTo(BigDecimal.valueOf(5000)) > 0)
            patterns.add("High peer-to-peer transfers — possible shared expenses or informal borrowing");
        if (avgWeekend.compareTo(avgWeekday) > 0)
            patterns.add("Spends more on weekends than weekdays");

        return new BehaviorProfile(
                postSalaryDrain, postSalaryDrainPct,
                microTotal, microCount,
                foodTotal, foodCount,
                p2pTotal,
                avgWeekend, avgWeekday,
                (int) lowBalanceDays,
                patterns
        );
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. RISK PROFILE
    // ─────────────────────────────────────────────────────────────────

    private RiskProfile buildRiskProfile(List<Transaction> debits,
                                         List<Transaction> credits,
                                         IncomeProfile income,
                                         ExpenseProfile expenses,
                                         BehaviorProfile behavior) {
        BigDecimal totalCredit = sum(credits);
        BigDecimal totalDebit  = sum(debits);

        // Burn rate: what % of income was spent
        int burnRatePct = 0;
        if (totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            burnRatePct = totalDebit.multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 0, RoundingMode.HALF_UP).intValue();
        }

        // EMI burden as % of income
        int emiBurdenPct = 0;
        if (totalCredit.compareTo(BigDecimal.ZERO) > 0 && expenses.emiTotal().compareTo(BigDecimal.ZERO) > 0) {
            emiBurdenPct = expenses.emiTotal().multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 0, RoundingMode.HALF_UP).intValue();
        }

        // Savings rate
        BigDecimal netFlow     = totalCredit.subtract(totalDebit);
        int        savingsRate = 0;
        if (totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            savingsRate = netFlow.max(BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 0, RoundingMode.HALF_UP).intValue();
        }

        boolean overspending      = burnRatePct > 100;
        boolean highEmiPressure   = emiBurdenPct > 30;
        boolean cashflowInstable  = behavior.lowBalanceDays() > 5;
        boolean postSalaryDrain   = behavior.postSalaryDrainPct() > 50;
        boolean noSavings         = savingsRate == 0;

        // Spending spikes: days where daily debit > 2x the average
        Map<LocalDate, BigDecimal> dailyDebit = debits.stream()
                .collect(Collectors.groupingBy(Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        BigDecimal avgDaily = expenses.avgDaily();
        List<String> spikes = dailyDebit.entrySet().stream()
                .filter(e -> avgDaily.compareTo(BigDecimal.ZERO) > 0
                        && e.getValue().compareTo(avgDaily.multiply(BigDecimal.valueOf(2))) > 0)
                .sorted(Map.Entry.<LocalDate, BigDecimal>comparingByValue().reversed())
                .limit(3)
                .map(e -> e.getKey().format(DateTimeFormatter.ofPattern("d MMM")) +
                        " (₹" + fmt(e.getValue()) + ")")
                .collect(Collectors.toList());

        return new RiskProfile(
                burnRatePct, savingsRate, emiBurdenPct,
                overspending, highEmiPressure, cashflowInstable, postSalaryDrain, noSavings,
                spikes
        );
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. HEALTH SCORE  (0–100)
    // ─────────────────────────────────────────────────────────────────

    private HealthScore computeHealthScore(IncomeProfile income,
                                           ExpenseProfile expenses,
                                           RiskProfile risk,
                                           BehaviorProfile behavior) {
        int score = 100;

        // Deductions
        if (risk.overspending())     score -= 25;
        if (risk.noSavings())        score -= 20;
        if (risk.cashflowInstable()) score -= 15;
        if (risk.postSalaryDrain())  score -= 10;
        if (risk.highEmiPressure())  score -= 10;
        if (behavior.foodCount() > 10) score -= 5;
        if (!risk.spendingSpikes().isEmpty()) score -= 5;

        score = Math.max(0, Math.min(100, score));

        String grade = score >= 80 ? "A" : score >= 65 ? "B" : score >= 50 ? "C" : score >= 35 ? "D" : "F";
        String label = score >= 80 ? "Healthy" : score >= 65 ? "Fair" : score >= 50 ? "Needs Attention" : "At Risk";

        return new HealthScore(score, grade, label);
    }

    // ─────────────────────────────────────────────────────────────────
    // 7. PROMPT RENDERER
    // ─────────────────────────────────────────────────────────────────

    /**
     * Converts AIContext into a structured text block for LLM input.
     *
     * Design principles:
     *  - Numbers are pre-computed. LLM does NOT recalculate.
     *  - Concise but complete. ~300-500 tokens max.
     *  - Structured with clear section headers.
     *  - Patterns expressed in plain English.
     */
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

        // Merchants
        sb.append("TOP MERCHANTS\n");
        ctx.merchants().top().stream().limit(7).forEach(m ->
                sb.append("  - ").append(m.name())
                        .append(": ₹").append(fmt(m.totalSpent()))
                        .append(" · ").append(m.transactionCount()).append("x\n"));
        if (!ctx.merchants().recurring().isEmpty()) {
            sb.append("  Recurring: ").append(String.join(", ", ctx.merchants().recurring())).append("\n");
        }
        sb.append("\n");

        // Behavior patterns
        sb.append("BEHAVIORAL PATTERNS\n");
        ctx.behavior().patterns().forEach(p -> sb.append("  • ").append(p).append("\n"));
        sb.append("  P2P transfers: ₹").append(fmt(ctx.behavior().p2pTotal())).append("\n");
        sb.append("  Small payments (<₹200): ")
                .append(ctx.behavior().microCount()).append(" payments · ₹")
                .append(fmt(ctx.behavior().microTotal())).append("\n");
        sb.append("\n");

        // Risk & Health
        sb.append("FINANCIAL HEALTH\n");
        sb.append("  Score: ").append(ctx.healthScore().score())
                .append("/100 (").append(ctx.healthScore().grade())
                .append(" — ").append(ctx.healthScore().label()).append(")\n");
        sb.append("  Burn rate: ").append(ctx.risk().burnRatePct()).append("% of income spent\n");
        sb.append("  Savings rate: ").append(ctx.risk().savingsRate()).append("%\n");
        if (!ctx.risk().spendingSpikes().isEmpty()) {
            sb.append("  Spending spikes: ").append(String.join(", ", ctx.risk().spendingSpikes())).append("\n");
        }
        sb.append("  Flags: ");
        List<String> flags = new ArrayList<>();
        if (ctx.risk().overspending())     flags.add("overspending");
        if (ctx.risk().noSavings())        flags.add("zero savings");
        if (ctx.risk().cashflowInstable()) flags.add("cashflow instability");
        if (ctx.risk().postSalaryDrain())  flags.add("post-salary drain");
        if (ctx.risk().highEmiPressure())  flags.add("high EMI burden");
        sb.append(flags.isEmpty() ? "none" : String.join(", ", flags)).append("\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // MERCHANT NORMALIZER
    // ─────────────────────────────────────────────────────────────────

    private static final Map<String, String> MERCHANT_ALIAS = new LinkedHashMap<>();
    static {
        // Food
        MERCHANT_ALIAS.put("zomato",    "Zomato");
        MERCHANT_ALIAS.put("swiggy",    "Swiggy");
        MERCHANT_ALIAS.put("bistro",    "Blinkit Bistro");
        MERCHANT_ALIAS.put("eatsure",   "EatSure");
        // Grocery
        MERCHANT_ALIAS.put("zepto",     "Zepto");
        MERCHANT_ALIAS.put("blinkit",   "Blinkit");
        MERCHANT_ALIAS.put("bigbasket", "BigBasket");
        MERCHANT_ALIAS.put("ekart",     "Flipkart/Ekart");
        // Transport
        MERCHANT_ALIAS.put("uber",      "Uber");
        MERCHANT_ALIAS.put("rapido",    "Rapido");
        MERCHANT_ALIAS.put("roppen",    "Rapido (Roppen)");
        MERCHANT_ALIAS.put("ola",       "Ola");
        // Shopping
        MERCHANT_ALIAS.put("amazon",    "Amazon");
        MERCHANT_ALIAS.put("flipkart",  "Flipkart");
        MERCHANT_ALIAS.put("myntra",    "Myntra");
        MERCHANT_ALIAS.put("visage",    "Visage Lines");
        // Services
        MERCHANT_ALIAS.put("stazy",     "Stazy");
        MERCHANT_ALIAS.put("playall",   "PlayAll");
        MERCHANT_ALIAS.put("google",    "Google");
        MERCHANT_ALIAS.put("airtel",    "Airtel");
        MERCHANT_ALIAS.put("curelink",  "Curelink Health");
    }

    private String normalizeMerchant(String description) {
        if (description == null) return "Unknown";
        String lower = description.toLowerCase();
        for (Map.Entry<String, String> entry : MERCHANT_ALIAS.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        // Fall back: extract the first meaningful token after "UPI-"
        String clean = description
                .replaceAll("(?i)UPI-", "")
                .replaceAll("@[^\\s]+", "")
                .replaceAll("[0-9]{5,}", "")
                .replaceAll("[^a-zA-Z\\s]", " ")
                .trim();
        String[] parts = clean.split("\\s+");
        return parts.length > 0 && parts[0].length() > 1
                ? capitalize(parts[0])
                : description.substring(0, Math.min(20, description.length()));
    }

    private String inferEmployer(String description) {
        if (description == null) return null;
        // Pattern: "50200031942646-TPT-MAR SAL-DATOPIC TECHN"
        String upper = description.toUpperCase();
        int salIdx = upper.indexOf("SAL-");
        if (salIdx > 0 && salIdx + 4 < description.length()) {
            String after = description.substring(salIdx + 4).trim();
            return after.substring(0, Math.min(after.length(), 30)).trim();
        }
        return null;
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

    private BigDecimal averageDaily(List<Transaction> txs) {
        if (txs.isEmpty()) return BigDecimal.ZERO;
        long days = txs.stream().map(Transaction::getDate).distinct().count();
        return days == 0 ? BigDecimal.ZERO
                : sum(txs).divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP);
    }

    private boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek().getValue() >= 6;
    }

    private String resolvePeriodLabel(List<Transaction> txs) {
        Optional<LocalDate> min = txs.stream().map(Transaction::getDate).min(Comparator.naturalOrder());
        Optional<LocalDate> max = txs.stream().map(Transaction::getDate).max(Comparator.naturalOrder());
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM yyyy");
        if (min.isPresent() && max.isPresent() && min.get().getMonth() == max.get().getMonth()) {
            return min.get().format(f);
        }
        return min.map(d -> d.format(f)).orElse("Unknown Period")
                + " – " + max.map(d -> d.format(f)).orElse("");
    }

    private String fmt(BigDecimal val) {
        return val == null ? "0" : String.format("%,.0f", val);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    // ─────────────────────────────────────────────────────────────────
    // VALUE OBJECTS  (static nested — keep in same file for clarity,
    //                move to separate files in production)
    // ─────────────────────────────────────────────────────────────────

    public record AIContext(
            String          periodLabel,
            IncomeProfile   income,
            ExpenseProfile  expenses,
            MerchantProfile merchants,
            BehaviorProfile behavior,
            RiskProfile     risk,
            HealthScore     healthScore
    ) {
        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private String periodLabel; private IncomeProfile income;
            private ExpenseProfile expenses; private MerchantProfile merchants;
            private BehaviorProfile behavior; private RiskProfile risk;
            private HealthScore healthScore;
            public Builder periodLabel(String v)  { this.periodLabel = v; return this; }
            public Builder income(IncomeProfile v)  { this.income = v; return this; }
            public Builder expenses(ExpenseProfile v){ this.expenses = v; return this; }
            public Builder merchants(MerchantProfile v){ this.merchants = v; return this; }
            public Builder behavior(BehaviorProfile v){ this.behavior = v; return this; }
            public Builder risk(RiskProfile v)     { this.risk = v; return this; }
            public Builder healthScore(HealthScore v){ this.healthScore = v; return this; }
            public AIContext build() {
                return new AIContext(periodLabel, income, expenses, merchants, behavior, risk, healthScore);
            }
        }
    }

    public record IncomeProfile(
            BigDecimal totalCredit,
            boolean    salaryDetected,
            BigDecimal salaryAmount,
            String     salaryDate,
            String     employer,
            BigDecimal secondaryIncome,
            List<CreditSource> topSources
    ) {}

    public record CreditSource(String name, BigDecimal amount, String date, String type) {}

    public record ExpenseProfile(
            BigDecimal          totalDebit,
            List<CategorySpend> breakdown,
            List<SingleTransaction> largestTransactions,
            BigDecimal          emiTotal,
            BigDecimal          avgDaily
    ) {}

    public record CategorySpend(String category, BigDecimal amount, BigDecimal percentOfTotal) {}
    public record SingleTransaction(String merchant, BigDecimal amount, String date, String category) {}

    public record MerchantProfile(List<MerchantSummary> top, List<String> recurring) {}
    public record MerchantSummary(String name, BigDecimal totalSpent, int transactionCount) {}

    public record BehaviorProfile(
            BigDecimal postSalaryDrain,
            int        postSalaryDrainPct,
            BigDecimal microTotal,
            int        microCount,
            BigDecimal foodTotal,
            int        foodCount,
            BigDecimal p2pTotal,
            BigDecimal avgWeekendSpend,
            BigDecimal avgWeekdaySpend,
            int        lowBalanceDays,
            List<String> patterns
    ) {}

    public record RiskProfile(
            int  burnRatePct,
            int  savingsRate,
            int  emiBurdenPct,
            boolean overspending,
            boolean highEmiPressure,
            boolean cashflowInstable,
            boolean postSalaryDrain,
            boolean noSavings,
            List<String> spendingSpikes
    ) {}

    public record HealthScore(int score, String grade, String label) {}
}