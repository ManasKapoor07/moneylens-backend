package com.moneylens.dto.response;

import com.moneylens.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class TransactionDto {

    private UUID id;
    private LocalDate date;
    private String description;
    private BigDecimal amount;
    private String type;
    private BigDecimal balance;
    private String category;
    private String subCategory;

    private TransactionDto() {}

    public static TransactionDto from(Transaction t) {
        TransactionDto dto = new TransactionDto();
        dto.id          = t.getId();
        dto.date        = t.getDate();
        dto.description = t.getDescription();
        dto.amount      = t.getAmount();
        dto.type        = t.getType().name();
        dto.balance     = t.getBalance();
        dto.category    = t.getCategory();
        dto.subCategory = t.getSubCategory();
        return dto;
    }

    public UUID getId()              { return id; }
    public LocalDate getDate()       { return date; }
    public String getDescription()   { return description; }
    public BigDecimal getAmount()    { return amount; }
    public String getType()          { return type; }
    public BigDecimal getBalance()   { return balance; }
    public String getCategory()      { return category; }
    public String getSubCategory()   { return subCategory; }
}