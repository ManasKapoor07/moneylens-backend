package com.moneylens.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transaction_insights")
public class TransactionInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id", nullable = false)
    private Statement statement;

    // e.g. SUMMARY, CATEGORY, TOP_MERCHANT, SUBSCRIPTION, MONTHLY_TREND,
    //      LARGEST_TRANSACTION, SAVING_OPPORTUNITY
    @Column(nullable = false)
    private String type;

    // Human-readable label e.g. "Food & Dining", "Total Spent"
    @Column(nullable = false, length = 500)
    private String label;

    // The main value e.g. "₹18,400.00", "31%"
    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    // Optional extra context e.g. "31%", "3 occurrences", "2026-01-15"
    @Column(columnDefinition = "TEXT")
    private String meta;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ===== BUILDER =====

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final TransactionInsight i = new TransactionInsight();
        public Builder statement(Statement v) { i.statement = v; return this; }
        public Builder type(String v) { i.type = v; return this; }
        public Builder label(String v) { i.label = v; return this; }
        public Builder value(String v) { i.value = v; return this; }
        public Builder meta(String v) { i.meta = v; return this; }
        public TransactionInsight build() { return i; }
    }

    // ===== Getters =====

    public UUID getId() { return id; }
    public Statement getStatement() { return statement; }
    public String getType() { return type; }
    public String getLabel() { return label; }
    public String getValue() { return value; }
    public String getMeta() { return meta; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}