package com.moneylens.dto.response;

import java.math.BigDecimal;

public class WeeklySpendDto {

    private String label;       // "Week 1", "Week 2", …
    private String rangeFrom;   // "2026-04-02"
    private String rangeTo;     // "2026-04-08"
    private BigDecimal debit;
    private BigDecimal credit;
    private int txCount;

    public WeeklySpendDto(String label, String rangeFrom, String rangeTo,
                          BigDecimal debit, BigDecimal credit, int txCount) {
        this.label     = label;
        this.rangeFrom = rangeFrom;
        this.rangeTo   = rangeTo;
        this.debit     = debit;
        this.credit    = credit;
        this.txCount   = txCount;
    }

    public String getLabel()       { return label; }
    public String getRangeFrom()   { return rangeFrom; }
    public String getRangeTo()     { return rangeTo; }
    public BigDecimal getDebit()   { return debit; }
    public BigDecimal getCredit()  { return credit; }
    public int getTxCount()        { return txCount; }
}