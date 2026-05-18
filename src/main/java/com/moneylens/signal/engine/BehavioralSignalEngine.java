package com.moneylens.signal.engine;

import com.moneylens.entity.BehavioralSignal;
import com.moneylens.entity.BehavioralSignal.Severity;
import com.moneylens.entity.BehavioralSignal.SignalType;
import com.moneylens.entity.Transaction;
import com.moneylens.repository.BehavioralSignalRepository;
import com.moneylens.service.MerchantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BehavioralSignalEngine
 *
 * Computes behavioral signals DETERMINISTICALLY from transaction data.
 * No LLM involvement. Every signal has:
 *   - A numeric value (the measured fact)
 *   - An evidence string (the explainability sentence)
 *   - A confidence score
 *   - A fired boolean (did it cross the threshold?)
 *
 * The LLM receives the fired signals and their evidence strings.
 * It narrates them. It does NOT invent them.
 *
 * Two entry points:
 *   computeForStatement(statementId, userId, transactions)  — per-statement
 *   computeForUser(userId, transactions)                    — cross-statement timeline
 *
 * Both return List<BehavioralSignal> after persisting to DB.
 */
@Service
public class BehavioralSignalEngine {

    private static final Logger log = LoggerFactory.getLogger(BehavioralSignalEngine.class);

    // ── Thresholds (named constants so they're easy to tune) ─────────────────

    private static final double SALARY_SPIKE_THRESHOLD_PCT   = 40.0;  // % of monthly spend in 3 days post-salary
    private static final double HIGH_BURN_RATE_PCT           = 90.0;  // % of income spent
    private static final double FOOD_HEAVY_PCT               = 25.0;  // food as % of total debit
    private static final int    FOOD_DELIVERY_HABIT_COUNT    = 8;     // orders per period
    private static final double WEEKEND_BIAS_RATIO           = 1.5;   // weekend avg / weekday avg
    private static final int    MICRO_PAYMENT_THRESHOLD      = 60;    // count of payments < ₹200
    private static final double SHOPPING_HEAVY_PCT           = 20.0;  // shopping as % of total debit
    private static final int    SUBSCRIPTION_STACK_COUNT     = 3;     // distinct subscription merchants
    private static final int    PHANTOM_SUB_OCCURRENCES      = 3;     // same merchant+amount, 3+ times
    private static final double HIGH_EMI_BURDEN_PCT          = 30.0;  // EMI as % of income
    private static final BigDecimal HIGH_P2P_THRESHOLD       = BigDecimal.valueOf(10_000);
    private static final double HEALTHY_SAVINGS_RATE_PCT     = 20.0;
    private static final double CONTROLLED_BURN_RATE_PCT     = 70.0;
    private static final int    LOW_BALANCE_DAYS_THRESHOLD   = 3;     // days with balance < ₹500
    private static final BigDecimal LOW_BALANCE_THRESHOLD    = BigDecimal.valueOf(500);
    private static final int    DUPLICATE_WINDOW_DAYS        = 5;
    private static final double DUPLICATE_AMOUNT_VARIANCE    = 0.05;  // 5%

    private final BehavioralSignalRepository signalRepository;
    private final MerchantRegistry           merchantRegistry;

    public BehavioralSignalEngine(
            BehavioralSignalRepository signalRepository,
            MerchantRegistry merchantRegistry
    ) {
        this.signalRepository = signalRepository;
        this.merchantRegistry = merchantRegistry;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ENTRY POINTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Compute and persist all signals for a single statement.
     * Idempotent: deletes existing signals for this statement before recomputing.
     */
    @Transactional
    public List<BehavioralSignal> computeForStatement(
            UUID statementId,
            UUID userId,
            List<Transaction> transactions
    ) {
        log.info("Computing behavioral signals for statement: {} ({} transactions)",
                statementId, transactions.size());

        signalRepository.deleteByStatementId(statementId);

        List<SignalResult> results = runAllDetectors(transactions);
        List<BehavioralSignal> signals = persist(results, userId, statementId);

        long fired = signals.stream().filter(BehavioralSignal::isFired).count();
        log.info("Statement {}: {} signals computed ({} fired)", statementId, signals.size(), fired);

        return signals;
    }

    /**
     * Compute and persist user-level signals from the full cross-statement timeline.
     * Idempotent: deletes existing user-level signals before recomputing.
     */
    @Transactional
    public List<BehavioralSignal> computeForUser(UUID userId, List<Transaction> timeline) {
        log.info("Computing user-level behavioral signals for user: {} ({} transactions)",
                userId, timeline.size());

        signalRepository.deleteUserLevelSignals(userId);

        List<SignalResult> results = runAllDetectors(timeline);
        List<BehavioralSignal> signals = persist(results, userId, null); // null statementId = user-level

        long fired = signals.stream().filter(BehavioralSignal::isFired).count();
        log.info("User {}: {} user-level signals computed ({} fired)", userId, signals.size(), fired);

        return signals;
    }

    /**
     * Run all detectors and return results WITHOUT persisting.
     * Used by the prompt builder to attach signals to AIContext in memory.
     */
    public List<SignalResult> computeInMemory(List<Transaction> transactions) {
        return runAllDetectors(transactions);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DETECTOR ORCHESTRATOR
    // ═════════════════════════════════════════════════════════════════════════

    private List<SignalResult> runAllDetectors(List<Transaction> transactions) {
        if (transactions.isEmpty()) return List.of();

        List<Transaction> debits  = filter(transactions, Transaction.Type.DEBIT);
        List<Transaction> credits = filter(transactions, Transaction.Type.CREDIT);

        BigDecimal totalDebit  = sum(debits);
        BigDecimal totalCredit = sum(credits);

        // Pre-compute shared aggregates once — all detectors read from these
        Map<String, BigDecimal> byCategory = groupByCategory(debits);
        Map<LocalDate, BigDecimal> dailyDebit = groupByDate(debits);

        List<SignalResult> results = new ArrayList<>();

        // ── Cash flow signals ─────────────────────────────────────────────────
        results.add(detectSalarySpikeSignal(debits, credits, totalDebit));
        results.add(detectHighBurnRate(totalDebit, totalCredit));
        results.add(detectOverspending(totalDebit, totalCredit));
        results.add(detectLowBalanceRisk(transactions));
        results.add(detectZeroSavings(totalDebit, totalCredit));

        // ── Spending pattern signals ──────────────────────────────────────────
        results.add(detectFoodHeavy(byCategory, totalDebit));
        results.add(detectFoodDeliveryHabit(debits));
        results.add(detectWeekendSpendingBias(debits, dailyDebit));
        results.add(detectMicroPaymentAccumulation(debits));
        results.add(detectShoppingHeavy(byCategory, totalDebit));

        // ── Recurring / subscription signals ─────────────────────────────────
        results.add(detectSubscriptionStack(debits));
        results.addAll(detectPhantomSubscriptions(debits));
        results.addAll(detectDuplicatePayments(debits));

        // ── Debt signal ───────────────────────────────────────────────────────
        results.add(detectHighEmiBurden(byCategory, totalCredit));

        // ── P2P signal ────────────────────────────────────────────────────────
        results.add(detectHighP2pVolume(byCategory));

        // ── Positive signals ──────────────────────────────────────────────────
        results.add(detectInvestingHabit(byCategory));
        results.add(detectHealthySavings(totalDebit, totalCredit));
        results.add(detectControlledSpending(totalDebit, totalCredit));

        return results;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DETECTORS — one method per signal
    // ═════════════════════════════════════════════════════════════════════════

    // ── SALARY_DAY_SPIKE ──────────────────────────────────────────────────────

    private SignalResult detectSalarySpikeSignal(
            List<Transaction> debits,
            List<Transaction> credits,
            BigDecimal totalDebit
    ) {
        // Identify salary = largest single credit
        Optional<Transaction> salary = credits.stream()
                .max(Comparator.comparing(Transaction::getAmount));

        if (salary.isEmpty() || totalDebit.compareTo(BigDecimal.ZERO) == 0) {
            return SignalResult.notFired(SignalType.SALARY_DAY_SPIKE, BigDecimal.ZERO, "%");
        }

        LocalDate sd = salary.get().getDate();
        BigDecimal drain = sum(debits.stream()
                .filter(t -> !t.getDate().isBefore(sd) && !t.getDate().isAfter(sd.plusDays(3)))
                .collect(Collectors.toList()));

        BigDecimal pct = drain.multiply(BigDecimal.valueOf(100))
                .divide(totalDebit, 1, RoundingMode.HALF_UP);
        double pctDouble = pct.doubleValue();

        if (pctDouble >= SALARY_SPIKE_THRESHOLD_PCT) {
            Severity sev = pctDouble >= 60 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.SALARY_DAY_SPIKE, sev, 0.88,
                    pct, "% of monthly spend",
                    String.format("Spent ₹%s (%s%%) within 3 days of salary credit on %s",
                            fmt(drain), pct, sd.toString())
            );
        }
        return SignalResult.notFired(SignalType.SALARY_DAY_SPIKE, pct, "% of monthly spend");
    }

    // ── HIGH_BURN_RATE ─────────────────────────────────────────────────────────

    private SignalResult detectHighBurnRate(BigDecimal totalDebit, BigDecimal totalCredit) {
        if (totalCredit.compareTo(BigDecimal.ZERO) == 0) {
            return SignalResult.notFired(SignalType.HIGH_BURN_RATE, BigDecimal.ZERO, "% of income");
        }
        BigDecimal burnRate = totalDebit.multiply(BigDecimal.valueOf(100))
                .divide(totalCredit, 1, RoundingMode.HALF_UP);
        double d = burnRate.doubleValue();

        if (d >= HIGH_BURN_RATE_PCT && d < 100) {
            return SignalResult.fired(
                    SignalType.HIGH_BURN_RATE, Severity.MEDIUM, 0.95,
                    burnRate, "% of income",
                    String.format("Spent ₹%s out of ₹%s earned — %s%% burn rate",
                            fmt(totalDebit), fmt(totalCredit), burnRate)
            );
        }
        return SignalResult.notFired(SignalType.HIGH_BURN_RATE, burnRate, "% of income");
    }

    // ── OVERSPENDING ──────────────────────────────────────────────────────────

    private SignalResult detectOverspending(BigDecimal totalDebit, BigDecimal totalCredit) {
        BigDecimal net = totalCredit.subtract(totalDebit);
        if (net.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal deficit = net.abs();
            return SignalResult.fired(
                    SignalType.OVERSPENDING, Severity.HIGH, 0.99,
                    deficit.negate(), "₹ net flow",
                    String.format("Spent ₹%s more than earned — deficit of ₹%s",
                            fmt(totalDebit), fmt(deficit))
            );
        }
        BigDecimal burnPct = totalCredit.compareTo(BigDecimal.ZERO) > 0
                ? totalDebit.multiply(BigDecimal.valueOf(100)).divide(totalCredit, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return SignalResult.notFired(SignalType.OVERSPENDING, burnPct, "% burn rate");
    }

    // ── LOW_BALANCE_RISK ──────────────────────────────────────────────────────

    private SignalResult detectLowBalanceRisk(List<Transaction> all) {
        long days = all.stream()
                .filter(t -> t.getBalance() != null
                        && t.getBalance().compareTo(LOW_BALANCE_THRESHOLD) < 0)
                .map(Transaction::getDate)
                .distinct()
                .count();

        BigDecimal daysVal = BigDecimal.valueOf(days);
        if (days >= LOW_BALANCE_DAYS_THRESHOLD) {
            Severity sev = days >= 7 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.LOW_BALANCE_RISK, sev, 0.90,
                    daysVal, "days",
                    String.format("Balance dropped below ₹500 on %d day%s in this period",
                            days, days == 1 ? "" : "s")
            );
        }
        return SignalResult.notFired(SignalType.LOW_BALANCE_RISK, daysVal, "days");
    }

    // ── ZERO_SAVINGS ──────────────────────────────────────────────────────────

    private SignalResult detectZeroSavings(BigDecimal totalDebit, BigDecimal totalCredit) {
        if (totalCredit.compareTo(BigDecimal.ZERO) == 0) {
            return SignalResult.notFired(SignalType.ZERO_SAVINGS, BigDecimal.ZERO, "%");
        }
        BigDecimal net = totalCredit.subtract(totalDebit);
        BigDecimal savingsRate = net.max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100))
                .divide(totalCredit, 1, RoundingMode.HALF_UP);

        if (savingsRate.compareTo(BigDecimal.ZERO) == 0 || net.compareTo(BigDecimal.ZERO) <= 0) {
            return SignalResult.fired(
                    SignalType.ZERO_SAVINGS, Severity.HIGH, 0.99,
                    BigDecimal.ZERO, "% savings rate",
                    "Nothing saved this period — full income consumed by spending"
            );
        }
        return SignalResult.notFired(SignalType.ZERO_SAVINGS, savingsRate, "% savings rate");
    }

    // ── FOOD_HEAVY ────────────────────────────────────────────────────────────

    private SignalResult detectFoodHeavy(
            Map<String, BigDecimal> byCategory, BigDecimal totalDebit
    ) {
        BigDecimal food = byCategory.getOrDefault("Food & Dining", BigDecimal.ZERO);
        if (totalDebit.compareTo(BigDecimal.ZERO) == 0)
            return SignalResult.notFired(SignalType.FOOD_HEAVY, BigDecimal.ZERO, "%");

        BigDecimal pct = food.multiply(BigDecimal.valueOf(100))
                .divide(totalDebit, 1, RoundingMode.HALF_UP);
        double d = pct.doubleValue();

        if (d >= FOOD_HEAVY_PCT) {
            Severity sev = d >= 35 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.FOOD_HEAVY, sev, 0.90,
                    pct, "% of total spend",
                    String.format("₹%s on food (%s%% of all spending) — highest single category",
                            fmt(food), pct)
            );
        }
        return SignalResult.notFired(SignalType.FOOD_HEAVY, pct, "% of total spend");
    }

    // ── FOOD_DELIVERY_HABIT ───────────────────────────────────────────────────

    private SignalResult detectFoodDeliveryHabit(List<Transaction> debits) {
        List<Transaction> orders = debits.stream()
                .filter(t -> "Food & Dining".equals(t.getCategory()))
                .collect(Collectors.toList());

        int count = orders.size();
        BigDecimal total = sum(orders);
        BigDecimal avg   = count == 0 ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP);

        BigDecimal countVal = BigDecimal.valueOf(count);
        if (count >= FOOD_DELIVERY_HABIT_COUNT) {
            Severity sev = count >= 15 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.FOOD_DELIVERY_HABIT, sev, 0.87,
                    countVal, "orders",
                    String.format("%d food delivery orders averaging ₹%s each (₹%s total)",
                            count, fmt(avg), fmt(total))
            );
        }
        return SignalResult.notFired(SignalType.FOOD_DELIVERY_HABIT, countVal, "orders");
    }

    // ── WEEKEND_SPENDING_BIAS ─────────────────────────────────────────────────

    private SignalResult detectWeekendSpendingBias(
            List<Transaction> debits,
            Map<LocalDate, BigDecimal> dailyDebit
    ) {
        List<Transaction> weekendTxs = debits.stream()
                .filter(t -> isWeekend(t.getDate())).collect(Collectors.toList());
        List<Transaction> weekdayTxs = debits.stream()
                .filter(t -> !isWeekend(t.getDate())).collect(Collectors.toList());

        long weDays = weekendTxs.stream().map(Transaction::getDate).distinct().count();
        long wdDays = weekdayTxs.stream().map(Transaction::getDate).distinct().count();

        if (weDays == 0 || wdDays == 0)
            return SignalResult.notFired(SignalType.WEEKEND_SPENDING_BIAS, BigDecimal.ONE, "ratio");

        BigDecimal avgWe = sum(weekendTxs).divide(BigDecimal.valueOf(weDays), 0, RoundingMode.HALF_UP);
        BigDecimal avgWd = sum(weekdayTxs).divide(BigDecimal.valueOf(wdDays), 0, RoundingMode.HALF_UP);

        if (avgWd.compareTo(BigDecimal.ZERO) == 0)
            return SignalResult.notFired(SignalType.WEEKEND_SPENDING_BIAS, BigDecimal.ONE, "ratio");

        BigDecimal ratio = avgWe.divide(avgWd, 2, RoundingMode.HALF_UP);
        double d = ratio.doubleValue();

        if (d >= WEEKEND_BIAS_RATIO) {
            Severity sev = d >= 2.5 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.WEEKEND_SPENDING_BIAS, sev, 0.82,
                    ratio, "x weekend/weekday ratio",
                    String.format("Weekend avg ₹%s/day vs weekday avg ₹%s/day (%.1fx higher)",
                            fmt(avgWe), fmt(avgWd), d)
            );
        }
        return SignalResult.notFired(SignalType.WEEKEND_SPENDING_BIAS, ratio, "x ratio");
    }

    // ── MICRO_PAYMENT_ACCUMULATION ────────────────────────────────────────────

    private SignalResult detectMicroPaymentAccumulation(List<Transaction> debits) {
        List<Transaction> micro = debits.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.valueOf(200)) < 0)
                .collect(Collectors.toList());

        int count = micro.size();
        BigDecimal total = sum(micro);
        BigDecimal countVal = BigDecimal.valueOf(count);

        if (count >= MICRO_PAYMENT_THRESHOLD) {
            Severity sev = count >= 100 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.MICRO_PAYMENT_ACCUMULATION, sev, 0.85,
                    countVal, "payments",
                    String.format("%d payments under ₹200 adding up to ₹%s",
                            count, fmt(total))
            );
        }
        return SignalResult.notFired(SignalType.MICRO_PAYMENT_ACCUMULATION, countVal, "payments");
    }

    // ── SHOPPING_HEAVY ────────────────────────────────────────────────────────

    private SignalResult detectShoppingHeavy(
            Map<String, BigDecimal> byCategory, BigDecimal totalDebit
    ) {
        BigDecimal shopping = byCategory.getOrDefault("Shopping", BigDecimal.ZERO);
        if (totalDebit.compareTo(BigDecimal.ZERO) == 0)
            return SignalResult.notFired(SignalType.SHOPPING_HEAVY, BigDecimal.ZERO, "%");

        BigDecimal pct = shopping.multiply(BigDecimal.valueOf(100))
                .divide(totalDebit, 1, RoundingMode.HALF_UP);
        double d = pct.doubleValue();

        if (d >= SHOPPING_HEAVY_PCT) {
            Severity sev = d >= 30 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.SHOPPING_HEAVY, sev, 0.88,
                    pct, "% of total spend",
                    String.format("₹%s on shopping (%s%% of all spending)",
                            fmt(shopping), pct)
            );
        }
        return SignalResult.notFired(SignalType.SHOPPING_HEAVY, pct, "% of total spend");
    }

    // ── SUBSCRIPTION_STACK ────────────────────────────────────────────────────

    private SignalResult detectSubscriptionStack(List<Transaction> debits) {
        long distinctSubMerchants = debits.stream()
                .filter(t -> "Subscriptions".equals(t.getCategory()))
                .map(t -> merchantRegistry.normalize(t.getDescription()))
                .distinct()
                .count();

        BigDecimal subTotal = sum(debits.stream()
                .filter(t -> "Subscriptions".equals(t.getCategory()))
                .collect(Collectors.toList()));

        BigDecimal countVal = BigDecimal.valueOf(distinctSubMerchants);

        if (distinctSubMerchants >= SUBSCRIPTION_STACK_COUNT) {
            Severity sev = distinctSubMerchants >= 6 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.SUBSCRIPTION_STACK, sev, 0.88,
                    countVal, "active subscriptions",
                    String.format("%d active subscriptions totalling ₹%s/month",
                            distinctSubMerchants, fmt(subTotal))
            );
        }
        return SignalResult.notFired(SignalType.SUBSCRIPTION_STACK, countVal, "active subscriptions");
    }

    // ── PHANTOM_SUBSCRIPTION ─────────────────────────────────────────────────
    // Any merchant that appears 3+ times at the same amount — likely forgotten recurring

    private List<SignalResult> detectPhantomSubscriptions(List<Transaction> debits) {
        List<SignalResult> results = new ArrayList<>();

        // Group by normalized merchant name + rounded amount
        Map<String, List<Transaction>> grouped = debits.stream()
                .filter(t -> !"Subscriptions".equals(t.getCategory())) // skip known subs
                .collect(Collectors.groupingBy(
                        t -> merchantRegistry.normalize(t.getDescription())
                                + "|" + t.getAmount().setScale(0, RoundingMode.HALF_UP)
                ));

        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            List<Transaction> txs = entry.getValue();
            if (txs.size() < PHANTOM_SUB_OCCURRENCES) continue;

            String merchant = merchantRegistry.normalize(txs.get(0).getDescription());
            BigDecimal amount = txs.get(0).getAmount();
            BigDecimal total  = sum(txs);

            results.add(SignalResult.fired(
                    SignalType.PHANTOM_SUBSCRIPTION, Severity.MEDIUM, 0.75,
                    BigDecimal.valueOf(txs.size()), "occurrences",
                    String.format("%s charged %d times at ₹%s each (₹%s total) — possible forgotten subscription",
                            merchant, txs.size(), fmt(amount), fmt(total))
            ));
        }

        return results;
    }

    // ── DUPLICATE_PAYMENT ─────────────────────────────────────────────────────

    private List<SignalResult> detectDuplicatePayments(List<Transaction> debits) {
        List<SignalResult> results = new ArrayList<>();

        List<Transaction> sorted = debits.stream()
                .sorted(Comparator.comparing(Transaction::getDate))
                .collect(Collectors.toList());

        Set<String> seen = new HashSet<>();

        for (int i = 0; i < sorted.size(); i++) {
            Transaction a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                Transaction b = sorted.get(j);

                long daysDiff = Math.abs(a.getDate().toEpochDay() - b.getDate().toEpochDay());
                if (daysDiff > DUPLICATE_WINDOW_DAYS) break;

                String normA = merchantRegistry.normalize(a.getDescription());
                String normB = merchantRegistry.normalize(b.getDescription());
                if (!normA.equals(normB)) continue;

                if (!isWithinPercent(a.getAmount(), b.getAmount(), DUPLICATE_AMOUNT_VARIANCE)) continue;

                String key = normA + "|" + a.getDate() + "|" + b.getDate();
                if (seen.contains(key)) continue;
                seen.add(key);

                results.add(SignalResult.fired(
                        SignalType.DUPLICATE_PAYMENT, Severity.HIGH, 0.80,
                        a.getAmount(), "₹",
                        String.format("Possible duplicate: '%s' ₹%s charged on %s and again on %s (%d days apart)",
                                normA, fmt(a.getAmount()), a.getDate(), b.getDate(), daysDiff)
                ));
            }
        }

        return results;
    }

    // ── HIGH_EMI_BURDEN ───────────────────────────────────────────────────────

    private SignalResult detectHighEmiBurden(
            Map<String, BigDecimal> byCategory, BigDecimal totalCredit
    ) {
        BigDecimal emi = byCategory.getOrDefault("EMI / Loan", BigDecimal.ZERO);
        if (totalCredit.compareTo(BigDecimal.ZERO) == 0 || emi.compareTo(BigDecimal.ZERO) == 0)
            return SignalResult.notFired(SignalType.HIGH_EMI_BURDEN, BigDecimal.ZERO, "% of income");

        BigDecimal pct = emi.multiply(BigDecimal.valueOf(100))
                .divide(totalCredit, 1, RoundingMode.HALF_UP);
        double d = pct.doubleValue();

        if (d >= HIGH_EMI_BURDEN_PCT) {
            Severity sev = d >= 50 ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.HIGH_EMI_BURDEN, sev, 0.92,
                    pct, "% of income",
                    String.format("EMI/loan payments of ₹%s = %s%% of income (recommended max: 30%%)",
                            fmt(emi), pct)
            );
        }
        return SignalResult.notFired(SignalType.HIGH_EMI_BURDEN, pct, "% of income");
    }

    // ── HIGH_P2P_VOLUME ───────────────────────────────────────────────────────

    private SignalResult detectHighP2pVolume(Map<String, BigDecimal> byCategory) {
        BigDecimal p2p = byCategory.getOrDefault("P2P Transfer", BigDecimal.ZERO);

        if (p2p.compareTo(HIGH_P2P_THRESHOLD) >= 0) {
            Severity sev = p2p.compareTo(BigDecimal.valueOf(25_000)) >= 0
                    ? Severity.HIGH : Severity.MEDIUM;
            return SignalResult.fired(
                    SignalType.HIGH_P2P_VOLUME, sev, 0.78,
                    p2p, "₹ total P2P",
                    String.format("₹%s sent via UPI to individuals — possible informal rent, loans, or shared expenses",
                            fmt(p2p))
            );
        }
        return SignalResult.notFired(SignalType.HIGH_P2P_VOLUME, p2p, "₹ total P2P");
    }

    // ── INVESTING_HABIT ───────────────────────────────────────────────────────

    private SignalResult detectInvestingHabit(Map<String, BigDecimal> byCategory) {
        BigDecimal inv = byCategory.getOrDefault("Investment", BigDecimal.ZERO);
        if (inv.compareTo(BigDecimal.ZERO) > 0) {
            return SignalResult.fired(
                    SignalType.INVESTING_HABIT, Severity.LOW, 0.92,
                    inv, "₹ invested",
                    String.format("₹%s invested this period — SIP / stocks / mutual funds detected", fmt(inv))
            );
        }
        return SignalResult.notFired(SignalType.INVESTING_HABIT, BigDecimal.ZERO, "₹ invested");
    }

    // ── HEALTHY_SAVINGS ───────────────────────────────────────────────────────

    private SignalResult detectHealthySavings(BigDecimal totalDebit, BigDecimal totalCredit) {
        if (totalCredit.compareTo(BigDecimal.ZERO) == 0)
            return SignalResult.notFired(SignalType.HEALTHY_SAVINGS, BigDecimal.ZERO, "%");

        BigDecimal net = totalCredit.subtract(totalDebit);
        BigDecimal rate = net.max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100))
                .divide(totalCredit, 1, RoundingMode.HALF_UP);

        if (rate.doubleValue() >= HEALTHY_SAVINGS_RATE_PCT) {
            return SignalResult.fired(
                    SignalType.HEALTHY_SAVINGS, Severity.LOW, 0.95,
                    rate, "% savings rate",
                    String.format("Saved %s%% of income this period (₹%s)", rate, fmt(net))
            );
        }
        return SignalResult.notFired(SignalType.HEALTHY_SAVINGS, rate, "% savings rate");
    }

    // ── CONTROLLED_SPENDING ───────────────────────────────────────────────────

    private SignalResult detectControlledSpending(BigDecimal totalDebit, BigDecimal totalCredit) {
        if (totalCredit.compareTo(BigDecimal.ZERO) == 0)
            return SignalResult.notFired(SignalType.CONTROLLED_SPENDING, BigDecimal.ZERO, "%");

        BigDecimal burnRate = totalDebit.multiply(BigDecimal.valueOf(100))
                .divide(totalCredit, 1, RoundingMode.HALF_UP);

        if (burnRate.doubleValue() <= CONTROLLED_BURN_RATE_PCT) {
            return SignalResult.fired(
                    SignalType.CONTROLLED_SPENDING, Severity.LOW, 0.95,
                    burnRate, "% burn rate",
                    String.format("Burn rate of %s%% — spending well within income", burnRate)
            );
        }
        return SignalResult.notFired(SignalType.CONTROLLED_SPENDING, burnRate, "% burn rate");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═════════════════════════════════════════════════════════════════════════

    private List<BehavioralSignal> persist(
            List<SignalResult> results,
            UUID userId,
            UUID statementId
    ) {
        List<BehavioralSignal> entities = results.stream()
                .map(r -> BehavioralSignal.builder()
                        .userId(userId)
                        .statementId(statementId)
                        .signalType(r.signalType())
                        .severity(r.severity())
                        .fired(r.fired())
                        .confidence(r.confidence())
                        .value(r.value())
                        .unit(r.unit())
                        .evidence(r.evidence())
                        .build())
                .collect(Collectors.toList());

        return signalRepository.saveAll(entities);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private List<Transaction> filter(List<Transaction> txs, Transaction.Type type) {
        return txs.stream().filter(t -> t.getType() == type).collect(Collectors.toList());
    }

    private BigDecimal sum(List<Transaction> txs) {
        return txs.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, BigDecimal> groupByCategory(List<Transaction> txs) {
        return txs.stream()
                .filter(t -> t.getCategory() != null)
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ));
    }

    private Map<LocalDate, BigDecimal> groupByDate(List<Transaction> txs) {
        return txs.stream()
                .collect(Collectors.groupingBy(
                        Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ));
    }

    private boolean isWeekend(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> true;
            default -> false;
        };
    }

    private boolean isWithinPercent(BigDecimal a, BigDecimal b, double pct) {
        if (a.compareTo(BigDecimal.ZERO) == 0) return b.compareTo(BigDecimal.ZERO) == 0;
        BigDecimal diff      = a.subtract(b).abs();
        BigDecimal threshold = a.multiply(BigDecimal.valueOf(pct));
        return diff.compareTo(threshold) <= 0;
    }

    private String fmt(BigDecimal v) {
        return v == null ? "0" : String.format("%,.0f", v);
    }
}