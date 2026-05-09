package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id", nullable = false)
    private Statement statement;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(precision = 15, scale = 2)
    private BigDecimal balance;

    @Column
    private String category;

    @Column(name = "sub_category")
    private String subCategory;

    public enum Type {
        DEBIT,
        CREDIT
    }

    // ===== BUILDER =====

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Transaction t = new Transaction();

        public Builder statement(Statement v)   { t.statement   = v; return this; }
        public Builder date(LocalDate v)         { t.date        = v; return this; }
        public Builder description(String v)     { t.description = v; return this; }
        public Builder amount(BigDecimal v)      { t.amount      = v; return this; }
        public Builder type(Type v)              { t.type        = v; return this; }
        public Builder balance(BigDecimal v)     { t.balance     = v; return this; }
        public Builder category(String v)        { t.category    = v; return this; }  // was missing
        public Builder subCategory(String v)     { t.subCategory = v; return this; }

        public Transaction build() { return t; }
    }

    // ===== Getters =====

    public UUID getId()              { return id; }
    public Statement getStatement()  { return statement; }
    public LocalDate getDate()       { return date; }
    public String getDescription()   { return description; }
    public BigDecimal getAmount()    { return amount; }
    public Type getType()            { return type; }
    public BigDecimal getBalance()   { return balance; }
    public String getCategory()      { return category; }
    public String getSubCategory()   { return subCategory; }

    // ===== Setters =====

    public void setCategory(String category)       { this.category    = category; }
    public void setSubCategory(String subCategory) { this.subCategory = subCategory; }
}