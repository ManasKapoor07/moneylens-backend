package com.moneylens.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class DashboardSummaryDto {

    // ── Top cards ────────────────────────────────────────────────
    private BigDecimal totalBalance;
    private BigDecimal totalSpending;
    private BigDecimal totalIncome;
    private BigDecimal savings;          // closing - opening balance

    private double balanceChangePercent;
    private double spendingChangePercent;
    private double incomeChangePercent;

    // ── Chart data ───────────────────────────────────────────────
    private List<MonthlyOverviewItem> monthlyOverview;

    // ── Category breakdown ───────────────────────────────────────
    private Map<String, BigDecimal> spendingByCategory;

    // ── Recent transactions widget ───────────────────────────────
    private List<TransactionDto> recentTransactions;

    private DashboardSummaryDto() {}

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final DashboardSummaryDto dto = new DashboardSummaryDto();

        public Builder totalBalance(BigDecimal v)                    { dto.totalBalance           = v; return this; }
        public Builder totalSpending(BigDecimal v)                   { dto.totalSpending          = v; return this; }
        public Builder totalIncome(BigDecimal v)                     { dto.totalIncome            = v; return this; }
        public Builder savings(BigDecimal v)                         { dto.savings                = v; return this; }
        public Builder balanceChangePercent(double v)                { dto.balanceChangePercent   = v; return this; }
        public Builder spendingChangePercent(double v)               { dto.spendingChangePercent  = v; return this; }
        public Builder incomeChangePercent(double v)                 { dto.incomeChangePercent    = v; return this; }
        public Builder monthlyOverview(List<MonthlyOverviewItem> v)  { dto.monthlyOverview        = v; return this; }
        public Builder spendingByCategory(Map<String, BigDecimal> v) { dto.spendingByCategory     = v; return this; }
        public Builder recentTransactions(List<TransactionDto> v)    { dto.recentTransactions     = v; return this; }

        public DashboardSummaryDto build() { return dto; }
    }

    // ── Getters ──────────────────────────────────────────────────

    public BigDecimal getTotalBalance()                       { return totalBalance; }
    public BigDecimal getTotalSpending()                      { return totalSpending; }
    public BigDecimal getTotalIncome()                        { return totalIncome; }
    public BigDecimal getSavings()                            { return savings; }
    public double getBalanceChangePercent()                   { return balanceChangePercent; }
    public double getSpendingChangePercent()                  { return spendingChangePercent; }
    public double getIncomeChangePercent()                    { return incomeChangePercent; }
    public List<MonthlyOverviewItem> getMonthlyOverview()     { return monthlyOverview; }
    public Map<String, BigDecimal> getSpendingByCategory()    { return spendingByCategory; }
    public List<TransactionDto> getRecentTransactions()       { return recentTransactions; }

    // ── Nested value object ──────────────────────────────────────

    public static class MonthlyOverviewItem {
        private final String     month;  // e.g. "Apr 2026"
        private final BigDecimal debit;
        private final BigDecimal credit;

        public MonthlyOverviewItem(String month, BigDecimal debit, BigDecimal credit) {
            this.month  = month;
            this.debit  = debit;
            this.credit = credit;
        }

        public String     getMonth()  { return month; }
        public BigDecimal getDebit()  { return debit; }
        public BigDecimal getCredit() { return credit; }
    }
}