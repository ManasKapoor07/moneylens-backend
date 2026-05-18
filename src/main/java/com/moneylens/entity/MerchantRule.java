package com.moneylens.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One row = one merchant pattern.
 *
 * Replaces the static KEYWORD_RULES list in TransactionMapper
 * and the MERCHANT_ALIAS map in AIContextBuilderService.
 *
 * Columns:
 *   pattern         — lowercase substring to match against the full narration
 *   normalizedName  — clean display name  (e.g. "Zomato", "BigBasket")
 *   category        — top-level category  (e.g. "Food & Dining")
 *   subCategory     — optional drill-down (e.g. "Food Delivery")
 *   confidence      — 0.0–1.0 quality signal for this rule
 *   source          — how this rule was created: SEED | USER_CORRECTION | ML
 *   priority        — lower number = checked first (allows overrides)
 *   active          — soft-delete flag
 */
@Entity
@Table(
        name = "merchant_rules",
        indexes = {
                @Index(name = "idx_mr_pattern",   columnList = "pattern"),
                @Index(name = "idx_mr_category",  columnList = "category"),
                @Index(name = "idx_mr_active",    columnList = "active"),
                @Index(name = "idx_mr_priority",  columnList = "priority")
        }
)
public class MerchantRule {

    // ── Source enum ───────────────────────────────────────────────────────────
    public enum Source { SEED, USER_CORRECTION, ML }

    // ── Fields ────────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lowercase substring matched against the full narration string. */
    @Column(nullable = false, length = 120)
    private String pattern;

    /** Human-readable merchant name shown in the UI. */
    @Column(nullable = false, length = 80)
    private String normalizedName;

    /** Top-level spending category. */
    @Column(nullable = false, length = 60)
    private String category;

    /** Optional sub-category for drill-down views. */
    @Column(length = 60)
    private String subCategory;

    /**
     * Confidence that this pattern reliably identifies the merchant/category.
     * SEED rules ship at 0.95 for well-known merchants, lower for heuristics.
     * USER_CORRECTION rules get 1.00.
     */
    @Column(nullable = false)
    private double confidence = 0.95;

    /**
     * Origin of this rule.
     * SEED          — shipped with the application
     * USER_CORRECTION — added/confirmed by a user correcting a categorization
     * ML            — added by a future ML pipeline
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.SEED;

    /**
     * Lower priority number = matched first.
     * Use this to let USER_CORRECTION rules (priority 0) win over SEED rules
     * (priority 100) without deleting the originals.
     */
    @Column(nullable = false)
    private int priority = 100;

    /** Soft-delete. Inactive rules are excluded from the cache. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ── Constructors ──────────────────────────────────────────────────────────

    public MerchantRule() {}

    /** Convenience factory for seed data. */
    public static MerchantRule seed(
            String pattern,
            String normalizedName,
            String category,
            String subCategory,
            double confidence
    ) {
        MerchantRule r = new MerchantRule();
        r.pattern        = pattern.toLowerCase();
        r.normalizedName = normalizedName;
        r.category       = category;
        r.subCategory    = subCategory;
        r.confidence     = confidence;
        r.source         = Source.SEED;
        r.priority       = 100;
        r.active         = true;
        return r;
    }

    /** Convenience factory for user corrections (wins over all SEED rules). */
    public static MerchantRule userCorrection(
            String pattern,
            String normalizedName,
            String category,
            String subCategory
    ) {
        MerchantRule r = new MerchantRule();
        r.pattern        = pattern.toLowerCase();
        r.normalizedName = normalizedName;
        r.category       = category;
        r.subCategory    = subCategory;
        r.confidence     = 1.00;
        r.source         = Source.USER_CORRECTION;
        r.priority       = 0;           // always wins
        r.active         = true;
        return r;
    }

    // ── Getters / setters ─────────────────────────────────────────────────────

    public Long getId()                   { return id; }
    public String getPattern()            { return pattern; }
    public String getNormalizedName()     { return normalizedName; }
    public String getCategory()           { return category; }
    public String getSubCategory()        { return subCategory; }
    public double getConfidence()         { return confidence; }
    public Source getSource()             { return source; }
    public int getPriority()              { return priority; }
    public boolean isActive()             { return active; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public LocalDateTime getUpdatedAt()   { return updatedAt; }

    public void setPattern(String v)          { this.pattern = v == null ? null : v.toLowerCase(); }
    public void setNormalizedName(String v)   { this.normalizedName = v; }
    public void setCategory(String v)         { this.category = v; }
    public void setSubCategory(String v)      { this.subCategory = v; }
    public void setConfidence(double v)       { this.confidence = v; }
    public void setSource(Source v)           { this.source = v; }
    public void setPriority(int v)            { this.priority = v; }
    public void setActive(boolean v)          { this.active = v; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    @Override
    public String toString() {
        return "MerchantRule{pattern='" + pattern + "', name='" + normalizedName
                + "', cat='" + category + "', conf=" + confidence + "}";
    }
}