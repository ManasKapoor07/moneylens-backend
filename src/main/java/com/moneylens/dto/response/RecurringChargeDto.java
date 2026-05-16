package com.moneylens.dto.response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

public class RecurringChargeDto {

    private String    merchant;       // resolved display name
    private String    rawDescription; // original description (most recent hit)
    private BigDecimal avgAmount;
    private BigDecimal totalSpent;
    private int       occurrences;
    private LocalDate firstSeen;
    private LocalDate lastSeen;
    private String    category;
    private int       variancePct;    // % variance across amounts — low = consistent subscription
    private BigDecimal estimatedMonthly;
    private BigDecimal estimatedAnnual;

    private RecurringChargeDto() {}

    public static RecurringChargeDto from(
            String    merchant,
            String    rawDescription,
            List<BigDecimal> amounts,
            List<LocalDate>  dates,
            String    category,
            int       periodDays   // total days spanned by the statement
    ) {
        BigDecimal sum = amounts.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(
                BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);

        BigDecimal max = amounts.stream().max(BigDecimal::compareTo).orElse(avg);
        BigDecimal min = amounts.stream().min(BigDecimal::compareTo).orElse(avg);

        int variance = avg.compareTo(BigDecimal.ZERO) == 0 ? 0
                : max.subtract(min)
                .divide(avg, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .intValue();

        LocalDate first = dates.stream().min(LocalDate::compareTo).orElse(null);
        LocalDate last  = dates.stream().max(LocalDate::compareTo).orElse(null);

        // Extrapolate to monthly / annual based on observed frequency
        // occurrences / periodDays * 30 = estimated hits per month
        BigDecimal monthlyEst;
        if (periodDays > 0) {
            BigDecimal hitsPerMonth = BigDecimal.valueOf(amounts.size())
                    .divide(BigDecimal.valueOf(periodDays), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(30));
            monthlyEst = avg.multiply(hitsPerMonth).setScale(2, RoundingMode.HALF_UP);
        } else {
            monthlyEst = avg;
        }

        RecurringChargeDto dto = new RecurringChargeDto();
        dto.merchant          = merchant;
        dto.rawDescription    = rawDescription;
        dto.avgAmount         = avg;
        dto.totalSpent        = sum.setScale(2, RoundingMode.HALF_UP);
        dto.occurrences       = amounts.size();
        dto.firstSeen         = first;
        dto.lastSeen          = last;
        dto.category          = category;
        dto.variancePct       = variance;
        dto.estimatedMonthly  = monthlyEst;
        dto.estimatedAnnual   = monthlyEst.multiply(BigDecimal.valueOf(12))
                .setScale(2, RoundingMode.HALF_UP);
        return dto;
    }

    // ── Getters ──────────────────────────────────────────────────

    public String    getMerchant()          { return merchant; }
    public String    getRawDescription()    { return rawDescription; }
    public BigDecimal getAvgAmount()        { return avgAmount; }
    public BigDecimal getTotalSpent()       { return totalSpent; }
    public int       getOccurrences()       { return occurrences; }
    public LocalDate getFirstSeen()         { return firstSeen; }
    public LocalDate getLastSeen()          { return lastSeen; }
    public String    getCategory()          { return category; }
    public int       getVariancePct()       { return variancePct; }
    public BigDecimal getEstimatedMonthly() { return estimatedMonthly; }
    public BigDecimal getEstimatedAnnual()  { return estimatedAnnual; }
}