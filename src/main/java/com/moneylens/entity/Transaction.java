package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "transactions",
        indexes = {
                @Index(name = "idx_transactions_dedup_hash", columnList = "dedup_hash"),
                @Index(name = "idx_transactions_statement_id", columnList = "statement_id"),
                @Index(name = "idx_transactions_date", columnList = "date")
        }
)
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

    /**
     * Confidence score for the category assignment (0.0–1.0).
     * Set by MerchantRegistry.resolve() via CategoryResult.
     * Null for transactions inserted before this column was added (pre-migration rows).
     *
     * Thresholds:
     *   >= 0.85 — high confidence, no review needed
     *   0.60–0.84 — medium confidence, surface to user optionally
     *   < 0.60 — low confidence, flag for user review
     */
    @Column(name = "category_confidence")
    private Double categoryConfidence;

    /**
     * How the category was determined.
     * RULE             — matched a pattern in MerchantRegistry
     * CREDIT_SIGNAL    — credit-side keyword match (salary, refund, etc.)
     * P2P_HEURISTIC    — UPI P2P heuristic, no merchant matched
     * BANK_TRANSFER    — NEFT / RTGS / IMPS with no merchant match
     * FALLBACK         — nothing matched; category is "Other"
     * USER_CORRECTION  — user manually corrected this transaction
     */
    @Column(name = "category_source", length = 20)
    private String categorySource;

    /**
     * Deterministic deduplication hash.
     *
     * Computed from: date + amount + normalized description + type.
     * Same transaction appearing in two overlapping statements will
     * produce the same hash, allowing idempotent inserts.
     *
     * Format: SHA-256 hex (64 chars).
     * Computed by DeduplicationService before persistence.
     *
     * Unique constraint is scoped per user (enforced at service layer
     * via user_id + dedup_hash compound check) because the same
     * transaction amount/description could legitimately appear for
     * two different users.
     */
    @Column(name = "dedup_hash", length = 64)
    private String dedupHash;

    public enum Type {
        DEBIT,
        CREDIT
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Transaction t = new Transaction();

        public Builder statement(Statement v)          { t.statement          = v; return this; }
        public Builder date(LocalDate v)               { t.date               = v; return this; }
        public Builder description(String v)           { t.description        = v; return this; }
        public Builder amount(BigDecimal v)            { t.amount             = v; return this; }
        public Builder type(Type v)                    { t.type               = v; return this; }
        public Builder balance(BigDecimal v)           { t.balance            = v; return this; }
        public Builder category(String v)              { t.category           = v; return this; }
        public Builder subCategory(String v)           { t.subCategory        = v; return this; }
        public Builder categoryConfidence(Double v)    { t.categoryConfidence = v; return this; }
        public Builder categorySource(String v)        { t.categorySource     = v; return this; }
        public Builder dedupHash(String v)             { t.dedupHash          = v; return this; }

        public Transaction build() { return t; }
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getId()                    { return id; }
    public Statement getStatement()        { return statement; }
    public LocalDate getDate()             { return date; }
    public String getDescription()         { return description; }
    public BigDecimal getAmount()          { return amount; }
    public Type getType()                  { return type; }
    public BigDecimal getBalance()         { return balance; }
    public String getCategory()            { return category; }
    public String getSubCategory()         { return subCategory; }
    public Double getCategoryConfidence()  { return categoryConfidence; }
    public String getCategorySource()      { return categorySource; }
    public String getDedupHash()           { return dedupHash; }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setCategory(String v)           { this.category           = v; }
    public void setSubCategory(String v)        { this.subCategory        = v; }
    public void setCategoryConfidence(Double v) { this.categoryConfidence = v; }
    public void setCategorySource(String v)     { this.categorySource     = v; }
    public void setDedupHash(String v)          { this.dedupHash          = v; }
}