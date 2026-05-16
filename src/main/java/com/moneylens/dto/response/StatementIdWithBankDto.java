package com.moneylens.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public class StatementIdWithBankDto {

    private UUID id;
    private String bankName;
    private LocalDate periodFrom;
    private LocalDate periodTo;

    public StatementIdWithBankDto(
            UUID id,
            String bankName,
            LocalDate periodFrom,
            LocalDate periodTo
    ) {
        this.id = id;
        this.bankName = bankName;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
    }

    public UUID getId() {
        return id;
    }

    public String getBankName() {
        return bankName;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }
}