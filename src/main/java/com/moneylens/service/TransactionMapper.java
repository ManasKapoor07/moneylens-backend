package com.moneylens.service;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionInsight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TransactionMapper
 *
 * CHANGED FROM ORIGINAL:
 *   - Removed KEYWORD_RULES static list (now lives in MerchantRegistry / DB)
 *   - Removed KNOWN_SERVICES set (MerchantRegistry handles P2P detection)
 *   - Removed categorise() / normalizeMerchant() / inferEmployer() methods
 *   - All categorization now delegated to MerchantRegistry.resolve()
 *   - Transaction now stores confidence + categorySource from CategoryResult
 *
 * STILL OWNED HERE (parsing concerns only):
 *   - Date / amount / column parsing
 *   - Debit vs credit type resolution
 *   - cleanMerchant() for display name (delegates to MerchantRegistry.normalize())
 *   - deriveInsights() — behavioral insight building
 */
@Component
public class TransactionMapper {

    private static final Logger log = LoggerFactory.getLogger(TransactionMapper.class);

    private final MerchantRegistry merchantRegistry;

    // ── Date formats tried in order ───────────────────────────────────────────
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd MMM yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yy")
    );

    public TransactionMapper(MerchantRegistry merchantRegistry) {
        this.merchantRegistry = merchantRegistry;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // mapRowToTransaction
    // ═════════════════════════════════════════════════════════════════════════

    public Transaction mapRowToTransaction(Map<String, String> row, Statement statement) {

        String dateStr    = getCol(row, "date");
        String desc       = getCol(row, "description","narration","particulars","details","remarks");
        String debitStr   = getCol(row, "debit","withdrawal","dr","debit amount","withdrawal amt.","withdrawal amt");
        String creditStr  = getCol(row, "credit","deposit","cr","credit amount","deposit amt.","deposit amt");
        String balanceStr = getCol(row, "balance","closing balance","running balance","closing bal");

        if (dateStr == null || desc == null) {
            log.debug("Skip row — missing date or desc: {}", row);
            return null;
        }

        LocalDate date = parseDate(dateStr);
        if (date == null) return null;

        BigDecimal debit   = parseAmount(debitStr);
        BigDecimal credit  = parseAmount(creditStr);
        BigDecimal balance = parseAmount(balanceStr);

        if (debit  != null && debit.compareTo(BigDecimal.ZERO)  == 0) debit  = null;
        if (credit != null && credit.compareTo(BigDecimal.ZERO) == 0) credit = null;
        if (debit == null && credit == null) {
            log.debug("Skip — no non-zero amount: {}", row);
            return null;
        }

        Transaction.Type type;
        BigDecimal amount;

        if (debit != null && credit == null) {
            type = Transaction.Type.DEBIT;   amount = debit;
        } else if (credit != null && debit == null) {
            type = Transaction.Type.CREDIT;  amount = credit;
        } else {
            boolean creditSig = isCreditKeyword(desc);
            type   = creditSig ? Transaction.Type.CREDIT : Transaction.Type.DEBIT;
            amount = creditSig ? credit : debit;
        }

        // Delegate categorisation to MerchantRegistry
        CategoryResult cat = merchantRegistry.resolve(desc, type);

        log.debug("TX {} | {} | {} ({}) conf={} | ₹{} | {}",
                date, type, cat.category(), cat.source(), String.format("%.2f", cat.confidence()),
                amount, desc.substring(0, Math.min(desc.length(), 60)));

        return Transaction.builder()
                .statement(statement)
                .date(date)
                .description(desc.trim())
                .amount(amount)
                .type(type)
                .balance(balance)
                .category(cat.category())
                .subCategory(cat.subCategory())
                .categoryConfidence(cat.confidence())
                .categorySource(cat.source())
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // cleanMerchant — delegates to MerchantRegistry
    // ═════════════════════════════════════════════════════════════════════════

    public String cleanMerchant(String raw) {
        return merchantRegistry.normalize(raw);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // deriveInsights — unchanged from original
    // ═════════════════════════════════════════════════════════════════════════

    public List<TransactionInsight> deriveInsights(Statement statement, List<Transaction> txns) {

        List<TransactionInsight> out = new ArrayList<>();
        if (txns.isEmpty()) {
            out.add(ins(statement, "SUMMARY", "No Transactions", "0", "Parser found 0 transactions"));
            return out;
        }

        List<Transaction> debits  = txns.stream().filter(t -> t.getType() == Transaction.Type.DEBIT).toList();
        List<Transaction> credits = txns.stream().filter(t -> t.getType() == Transaction.Type.CREDIT).toList();

        BigDecimal totalDebit  = sum(debits);
        BigDecimal totalCredit = sum(credits);
        BigDecimal netFlow     = totalCredit.subtract(totalDebit);

        BigDecimal savingsRate = totalCredit.compareTo(BigDecimal.ZERO) > 0
                ? netFlow.max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100))
                .divide(totalCredit, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Daily aggregates
        Map<LocalDate, BigDecimal> dailyDebit = debits.stream()
                .collect(Collectors.groupingBy(Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        Map<LocalDate, BigDecimal> dailyCredit = credits.stream()
                .collect(Collectors.groupingBy(Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        TreeSet<LocalDate> allDates = new TreeSet<>();
        allDates.addAll(dailyDebit.keySet());
        allDates.addAll(dailyCredit.keySet());

        long totalDays = Math.max(allDates.size(), 1);
        BigDecimal avgDay = totalDebit.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : totalDebit.divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_UP);

        // ── SUMMARY ───────────────────────────────────────────────────────────
        out.add(ins(statement, "SUMMARY", "Total Spent",        "₹" + fmt(totalDebit),  null));
        out.add(ins(statement, "SUMMARY", "Total Received",     "₹" + fmt(totalCredit), null));
        out.add(ins(statement, "SUMMARY", "Net Flow",           "₹" + fmt(netFlow),     null));
        out.add(ins(statement, "SUMMARY", "Total Transactions", String.valueOf(txns.size()), null));
        out.add(ins(statement, "SUMMARY", "Debit Count",        String.valueOf(debits.size()),  null));
        out.add(ins(statement, "SUMMARY", "Credit Count",       String.valueOf(credits.size()), null));
        out.add(ins(statement, "SUMMARY", "Savings Rate",       savingsRate + "%",
                savingsRate.compareTo(BigDecimal.valueOf(20)) < 0 ? "⚠ Below recommended 20%" : "✓ Healthy"));

        // ── INCOME ────────────────────────────────────────────────────────────
        if (credits.isEmpty()) {
            out.add(ins(statement, "INCOME", "No Income Detected", "₹0.00",
                    "No credit found in this period"));
        } else {
            credits.stream()
                    .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                    .limit(5)
                    .forEach(t -> out.add(ins(statement, "INCOME",
                            cleanMerchant(t.getDescription()),
                            "₹" + fmt(t.getAmount()),
                            "Credited on " + t.getDate())));
        }

        // ── CATEGORY BREAKDOWN ────────────────────────────────────────────────
        Map<String, BigDecimal> byCategory = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "Other",
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> {
                    BigDecimal pct = totalDebit.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                            : e.getValue().multiply(BigDecimal.valueOf(100))
                            .divide(totalDebit, 1, RoundingMode.HALF_UP);
                    out.add(ins(statement, "CATEGORY", e.getKey(),
                            "₹" + fmt(e.getValue()), pct + "%"));
                });

        // ── TOP MERCHANTS ─────────────────────────────────────────────────────
        debits.stream()
                .collect(Collectors.groupingBy(
                        t -> cleanMerchant(t.getDescription()),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
                .entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> out.add(ins(statement, "TOP_MERCHANT",
                        e.getKey(), "₹" + fmt(e.getValue()), null)));

        // ── SUBSCRIPTION DETECTION ────────────────────────────────────────────
        debits.stream()
                .filter(t -> "Subscriptions".equals(t.getCategory()))
                .collect(Collectors.groupingBy(
                        t -> cleanMerchant(t.getDescription()) + "||" + t.getAmount().toPlainString()))
                .entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .forEach(e -> {
                    Transaction s     = e.getValue().get(0);
                    BigDecimal monthly = s.getAmount();
                    out.add(ins(statement, "SUBSCRIPTION",
                            cleanMerchant(s.getDescription()),
                            "₹" + fmt(monthly) + "/mo",
                            e.getValue().size() + " occurrences · est. ₹"
                                    + fmt(monthly.multiply(BigDecimal.valueOf(12))) + "/yr"));
                });

        // ── P2P RECURRING ─────────────────────────────────────────────────────
        debits.stream()
                .filter(t -> "P2P Transfer".equals(t.getCategory()))
                .collect(Collectors.groupingBy(t -> cleanMerchant(t.getDescription())))
                .entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .sorted(Comparator.comparing(
                        (Map.Entry<String, List<Transaction>> e) -> sum(e.getValue())).reversed())
                .limit(10)
                .forEach(e -> {
                    BigDecimal total = sum(e.getValue());
                    String meta = e.getValue().size() + " transfers";
                    if (total.compareTo(BigDecimal.valueOf(3000)) > 0)
                        meta += " · ⚠ Possible rent/shared expense";
                    out.add(ins(statement, "P2P_TRANSFER",
                            e.getKey(), "₹" + fmt(total), meta));
                });

        // ── MONTHLY TREND ─────────────────────────────────────────────────────
        debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getDate().format(DateTimeFormatter.ofPattern("MMM yyyy")),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
                .forEach((month, total) ->
                        out.add(ins(statement, "MONTHLY_TREND", month, "₹" + fmt(total), null)));

        // ── WEEKLY BREAKDOWN ──────────────────────────────────────────────────
        Map<Integer, List<Transaction>> byWeek = debits.stream()
                .collect(Collectors.groupingBy(t -> weekOfMonth(t.getDate())));
        for (int w = 1; w <= 4; w++) {
            List<Transaction> wTxs = byWeek.getOrDefault(w, List.of());
            BigDecimal wTotal = sum(wTxs);
            BigDecimal wPct   = totalDebit.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : wTotal.multiply(BigDecimal.valueOf(100)).divide(totalDebit, 1, RoundingMode.HALF_UP);
            out.add(ins(statement, "WEEKLY_BREAKDOWN", "Week " + w,
                    "₹" + fmt(wTotal),
                    wPct + "% of monthly spend · " + wTxs.size() + " transactions"));
        }

        // ── DAILY SPEND SERIES ────────────────────────────────────────────────
        StringBuilder series = new StringBuilder("[");
        boolean first = true;
        for (LocalDate d : allDates) {
            if (!first) series.append(",");
            series.append(String.format("{\"date\":\"%s\",\"debit\":%.2f,\"credit\":%.2f}",
                    d,
                    dailyDebit.getOrDefault(d, BigDecimal.ZERO),
                    dailyCredit.getOrDefault(d, BigDecimal.ZERO)));
            first = false;
        }
        series.append("]");
        out.add(ins(statement, "DAILY_SPEND_SERIES", "Daily Chart Data", series.toString(), null));

        // ── LARGEST TRANSACTIONS ──────────────────────────────────────────────
        debits.stream()
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .forEach(t -> out.add(ins(statement, "LARGEST_TRANSACTION",
                        cleanMerchant(t.getDescription()),
                        "₹" + fmt(t.getAmount()),
                        t.getDate().toString())));

        // ── BEHAVIORAL INTELLIGENCE ───────────────────────────────────────────

        LocalDate peakDay = dailyDebit.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        out.add(ins(statement, "BEHAVIORAL", "Avg Daily Spend", "₹" + fmt(avgDay),
                peakDay != null ? "Highest on "
                        + peakDay.format(DateTimeFormatter.ofPattern("d MMM")) : null));

        BigDecimal weekendSpend = sum(debits.stream().filter(t -> isWeekend(t.getDate())).toList());
        BigDecimal weekdaySpend = sum(debits.stream().filter(t -> !isWeekend(t.getDate())).toList());
        long wkendDays = debits.stream().filter(t -> isWeekend(t.getDate())).map(Transaction::getDate).distinct().count();
        long wkdayDays = debits.stream().filter(t -> !isWeekend(t.getDate())).map(Transaction::getDate).distinct().count();
        if (wkendDays > 0 && wkdayDays > 0) {
            BigDecimal avgWkend = weekendSpend.divide(BigDecimal.valueOf(wkendDays), 0, RoundingMode.HALF_UP);
            BigDecimal avgWkday = weekdaySpend.divide(BigDecimal.valueOf(wkdayDays), 0, RoundingMode.HALF_UP);
            if (avgWkday.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ratio = avgWkend.divide(avgWkday, 1, RoundingMode.HALF_UP);
                out.add(ins(statement, "BEHAVIORAL", "Weekend vs Weekday",
                        ratio + "x higher on weekends",
                        "Weekend avg ₹" + fmt(avgWkend) + " · Weekday avg ₹" + fmt(avgWkday)));
            }
        }

        List<Transaction> micro = debits.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.valueOf(200)) < 0).toList();
        BigDecimal microTotal = sum(micro);
        if (!micro.isEmpty()) {
            BigDecimal microAvg = microTotal.divide(BigDecimal.valueOf(micro.size()), 0, RoundingMode.HALF_UP);
            out.add(ins(statement, "BEHAVIORAL", "Small UPI Payments", "₹" + fmt(microTotal),
                    micro.size() + " payments under ₹200 · avg ₹" + fmt(microAvg)));
        }

        credits.stream().max(Comparator.comparing(Transaction::getAmount)).ifPresent(salary -> {
            LocalDate sd = salary.getDate();
            BigDecimal drain = sum(debits.stream()
                    .filter(t -> !t.getDate().isBefore(sd) && !t.getDate().isAfter(sd.plusDays(3)))
                    .toList());
            if (drain.compareTo(BigDecimal.ZERO) > 0 && totalDebit.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pct = drain.multiply(BigDecimal.valueOf(100))
                        .divide(totalDebit, 1, RoundingMode.HALF_UP);
                out.add(ins(statement, "BEHAVIORAL", "Post-Salary Drain",
                        "₹" + fmt(drain) + " in 3 days",
                        pct + "% of monthly spend right after salary credit"));
            }
        });

        List<Transaction> food = debits.stream()
                .filter(t -> "Food & Dining".equals(t.getCategory())).toList();
        BigDecimal foodTotal = sum(food);
        if (!food.isEmpty()) {
            BigDecimal avgOrder = foodTotal.divide(BigDecimal.valueOf(food.size()), 0, RoundingMode.HALF_UP);
            out.add(ins(statement, "BEHAVIORAL", "Food Delivery Habit",
                    "₹" + fmt(foodTotal) + " · " + food.size() + " orders",
                    "Avg ₹" + fmt(avgOrder) + " · ₹"
                            + fmt(foodTotal.multiply(BigDecimal.valueOf(12))) + " projected/yr"));
        }

        BigDecimal doubleAvg = avgDay.multiply(BigDecimal.valueOf(2));
        if (doubleAvg.compareTo(BigDecimal.ZERO) > 0) {
            dailyDebit.entrySet().stream()
                    .filter(e -> e.getValue().compareTo(doubleAvg) > 0)
                    .sorted(Map.Entry.<LocalDate, BigDecimal>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> {
                        BigDecimal ratio = avgDay.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE
                                : e.getValue().divide(avgDay, 1, RoundingMode.HALF_UP);
                        out.add(ins(statement, "BEHAVIORAL", "Spending Spike",
                                "₹" + fmt(e.getValue()) + " on "
                                        + e.getKey().format(DateTimeFormatter.ofPattern("d MMM")),
                                ratio + "× your daily average"));
                    });
        }

        List<Transaction> shopping = debits.stream()
                .filter(t -> "Shopping".equals(t.getCategory())).toList();
        if (!shopping.isEmpty()) {
            BigDecimal shopTotal = sum(shopping);
            out.add(ins(statement, "BEHAVIORAL", "Online Shopping",
                    "₹" + fmt(shopTotal) + " · " + shopping.size() + " orders",
                    "Avg ₹" + fmt(shopTotal.divide(BigDecimal.valueOf(shopping.size()), 0, RoundingMode.HALF_UP)) + " per order"));
        }

        // ── FINANCIAL HEALTH ──────────────────────────────────────────────────
        BigDecimal emiTotal = sum(debits.stream()
                .filter(t -> "EMI / Loan".equals(t.getCategory())).toList());
        if (emiTotal.compareTo(BigDecimal.ZERO) > 0 && totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal emiBurden = emiTotal.multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 1, RoundingMode.HALF_UP);
            String flag = emiBurden.compareTo(BigDecimal.valueOf(40)) > 0 ? "🚨 Very high" :
                    emiBurden.compareTo(BigDecimal.valueOf(30)) > 0 ? "⚠ High"       : "✓ Manageable";
            out.add(ins(statement, "FINANCIAL_HEALTH", "EMI Burden",
                    emiBurden + "% of income", flag + " · recommended below 30%"));
        }

        String savingsFlag =
                savingsRate.compareTo(BigDecimal.valueOf(30)) >= 0 ? "🏆 Excellent saver" :
                        savingsRate.compareTo(BigDecimal.valueOf(20)) >= 0 ? "✓ On track"          :
                                savingsRate.compareTo(BigDecimal.valueOf(10)) >= 0 ? "⚠ Below target"      : "🚨 Critical";
        out.add(ins(statement, "FINANCIAL_HEALTH", "Savings Rate Score", savingsRate + "%", savingsFlag));

        if (totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal burnRate = totalDebit.multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 1, RoundingMode.HALF_UP);
            out.add(ins(statement, "FINANCIAL_HEALTH", "Burn Rate",
                    burnRate + "% of income spent",
                    burnRate.compareTo(BigDecimal.valueOf(100)) > 0 ? "🚨 Spending more than earning" :
                            burnRate.compareTo(BigDecimal.valueOf(80))  > 0 ? "⚠ Very little left to save"   : "✓ Within limits"));
        }

        // ── SAVING OPPORTUNITIES ──────────────────────────────────────────────
        if (foodTotal.compareTo(BigDecimal.valueOf(500)) > 0) {
            BigDecimal save = pct40(foodTotal);
            out.add(ins(statement, "SAVING_OPPORTUNITY", "Cut food delivery by 40%",
                    "Save ₹" + fmt(save) + "/mo",
                    "Invested in SIP → ₹" + fmt(sipFV(save, 5)) + " in 5 years"));
        }
        if (microTotal.compareTo(BigDecimal.valueOf(1000)) > 0) {
            BigDecimal save = pct50(microTotal);
            out.add(ins(statement, "SAVING_OPPORTUNITY", "Reduce impulse payments under ₹200",
                    "Save ₹" + fmt(save) + "/mo",
                    micro.size() + " transactions · SIP → ₹" + fmt(sipFV(save, 10)) + " in 10 years"));
        }
        BigDecimal p2pTotal = sum(debits.stream()
                .filter(t -> "P2P Transfer".equals(t.getCategory())).toList());
        if (p2pTotal.compareTo(BigDecimal.valueOf(3000)) > 0) {
            out.add(ins(statement, "SAVING_OPPORTUNITY", "Review peer transfers",
                    "₹" + fmt(p2pTotal) + " sent to individuals",
                    "Categorise recurring ones as Rent / Savings / Shared expenses"));
        }

        // ── LOW CONFIDENCE FLAGS ─────────────────────────────────────────────
        // Surface transactions that need user review
        long lowConfidenceCount = debits.stream()
                .filter(t -> t.getCategoryConfidence() != null && t.getCategoryConfidence() < 0.60)
                .count();
        if (lowConfidenceCount > 0) {
            out.add(ins(statement, "DATA_QUALITY", "Low Confidence Categorizations",
                    String.valueOf(lowConfidenceCount),
                    lowConfidenceCount + " transactions need review — tap to correct"));
        }

        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private boolean isCreditKeyword(String desc) {
        if (desc == null) return false;
        String lo = desc.toLowerCase();
        return lo.contains("salary")   || lo.contains("stipend")  ||
                lo.contains("neft cr")  || lo.contains("/cr/")     ||
                lo.contains("credit")   || lo.contains("refund")   ||
                lo.contains("cashback") || lo.contains("reversal") ||
                lo.contains("interest") || lo.contains("dividend") ||
                lo.contains("deposit")  || lo.contains("inward")   ||
                lo.contains("received") || lo.contains("payroll")  ||
                (lo.contains("school") && lo.contains("trf"));
    }

    private boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private int weekOfMonth(LocalDate d) {
        int day = d.getDayOfMonth();
        return day <= 7 ? 1 : day <= 14 ? 2 : day <= 21 ? 3 : 4;
    }

    private BigDecimal sipFV(BigDecimal monthly, int years) {
        double r  = 0.12 / 12;
        int    n  = years * 12;
        double fv = monthly.doubleValue() * ((Math.pow(1 + r, n) - 1) / r) * (1 + r);
        return BigDecimal.valueOf(fv).setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal pct40(BigDecimal v) {
        return v.multiply(BigDecimal.valueOf(40)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal pct50(BigDecimal v) {
        return v.multiply(BigDecimal.valueOf(50)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<Transaction> txs) {
        return txs.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String fmt(BigDecimal v) {
        return v == null ? "0.00" : String.format("%,.2f", v);
    }

    private TransactionInsight ins(Statement s, String type, String label, String value, String meta) {
        return TransactionInsight.builder()
                .statement(s).type(type).label(label).value(value).meta(meta).build();
    }

    private String getCol(Map<String, String> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey().trim().equalsIgnoreCase(key)) {
                    String v = e.getValue();
                    return (v != null && !v.isBlank()) ? v : null;
                }
            }
        }
        return null;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(cleaned, fmt); }
            catch (DateTimeParseException ignored) {}
        }
        log.warn("Could not parse date: '{}'", raw);
        return null;
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String c = raw.replaceAll("[₹,\\s]", "").trim();
            return c.isEmpty() ? null : new BigDecimal(c);
        } catch (NumberFormatException e) { return null; }
    }
}