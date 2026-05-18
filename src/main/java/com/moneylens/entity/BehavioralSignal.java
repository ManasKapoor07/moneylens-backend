package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * BehavioralSignal
 *
 * One row = one detected behavioral pattern for a given statement or
 * user-level timeline.
 *
 * Signals are computed DETERMINISTICALLY by BehavioralSignalEngine.
 * The LLM never generates signals — it only narrates them.
 *
 * Schema design:
 *   signalType   — machine-readable enum (e.g. SALARY_DAY_SPIKE)
 *   severity     — LOW / MEDIUM / HIGH — drives UI priority + LLM emphasis
 *   fired        — true if the signal threshold was crossed
 *   confidence   — 0.0–1.0 how reliable this detection is
 *   value        — the numeric evidence (e.g. 67.3 for 67.3% post-salary drain)
 *   unit         — what value represents (e.g. "% of monthly spend")
 *   evidence     — short human-readable evidence string for explainability
 *   statementId  — nullable: null means this is a user-level (cross-statement) signal
 *   userId       — always set (for user-level queries)
 */
@Entity
@Table(
        name = "behavioral_signals",
        indexes = {
                @Index(name = "idx_bs_statement",  columnList = "statement_id"),
                @Index(name = "idx_bs_user",       columnList = "user_id"),
                @Index(name = "idx_bs_type",       columnList = "signal_type"),
                @Index(name = "idx_bs_fired",      columnList = "fired"),
                @Index(name = "idx_bs_created",    columnList = "created_at")
        }
)
public class BehavioralSignal {

    // ── Signal type enum ──────────────────────────────────────────────────────

    public enum SignalType {

        // ── Cash flow ─────────────────────────────────────────────────────────
        /** Spent > 40% of monthly debit total within 3 days of salary credit */
        SALARY_DAY_SPIKE,

        /** Burn rate > 90% of income in the period */
        HIGH_BURN_RATE,

        /** Net flow is negative — spent more than earned */
        OVERSPENDING,

        /** Balance dropped below ₹500 on 3+ days in the period */
        LOW_BALANCE_RISK,

        /** Savings rate is 0% — nothing left after spending */
        ZERO_SAVINGS,

        // ── Spending patterns ─────────────────────────────────────────────────
        /** Food & Dining spend > 25% of total debit spend */
        FOOD_HEAVY,

        /** 8+ food delivery orders in the period */
        FOOD_DELIVERY_HABIT,

        /** Weekend avg daily spend > 1.5× weekday avg */
        WEEKEND_SPENDING_BIAS,

        /** 60+ micro-payments under ₹200 in the period */
        MICRO_PAYMENT_ACCUMULATION,

        /** Shopping > 20% of total debit spend */
        SHOPPING_HEAVY,

        // ── Recurring / subscriptions ─────────────────────────────────────────
        /** 3+ distinct subscription charges detected */
        SUBSCRIPTION_STACK,

        /** Any single merchant charged 3+ times at similar amounts (possible forgotten sub) */
        PHANTOM_SUBSCRIPTION,

        /** Same merchant + same amount within 5 days — possible duplicate payment */
        DUPLICATE_PAYMENT,

        // ── Debt / EMI ────────────────────────────────────────────────────────
        /** EMI / Loan payments > 30% of income */
        HIGH_EMI_BURDEN,

        // ── P2P / transfers ───────────────────────────────────────────────────
        /** P2P transfers > ₹10,000 in the period — possible informal rent/lending */
        HIGH_P2P_VOLUME,

        // ── Positive signals ──────────────────────────────────────────────────
        /** At least one Investment transaction detected */
        INVESTING_HABIT,

        /** Savings rate ≥ 20% */
        HEALTHY_SAVINGS,

        /** Burn rate < 70% of income */
        CONTROLLED_SPENDING,
    }

    public enum Severity { LOW, MEDIUM, HIGH }

    // ── Fields ────────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Null for user-level (cross-statement) signals. */
    @Column(name = "statement_id")
    private UUID statementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 40)
    private SignalType signalType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    /** True if the signal threshold was crossed (fired). False signals are stored
     *  for longitudinal tracking — "this month the habit did NOT fire." */
    @Column(nullable = false)
    private boolean fired;

    /** Confidence that the detection is accurate. */
    @Column(nullable = false)
    private double confidence;

    /**
     * The numeric evidence value.
     * e.g. 67.3 for SALARY_DAY_SPIKE (67.3% of monthly spend drained in 3 days)
     *      14   for FOOD_DELIVERY_HABIT (14 orders)
     *      -3500 for OVERSPENDING (₹3,500 net negative)
     */
    @Column(precision = 15, scale = 2)
    private BigDecimal value;

    /** Human-readable unit for `value`. e.g. "% of monthly spend", "orders", "₹" */
    @Column(length = 40)
    private String unit;

    /**
     * Short evidence sentence — passed verbatim to the LLM.
     * Written by the engine, never by GPT.
     *
     * Examples:
     *   "Spent ₹18,400 (67%) within 3 days of salary credit on 5 Mar"
     *   "14 food delivery orders averaging ₹340 each"
     *   "Netflix charged 3 times in 6 weeks (₹199, ₹199, ₹199)"
     */
    @Column(length = 300)
    private String evidence;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final BehavioralSignal s = new BehavioralSignal();

        public Builder userId(UUID v)          { s.userId      = v; return this; }
        public Builder statementId(UUID v)     { s.statementId = v; return this; }
        public Builder signalType(SignalType v){ s.signalType  = v; return this; }
        public Builder severity(Severity v)    { s.severity    = v; return this; }
        public Builder fired(boolean v)        { s.fired       = v; return this; }
        public Builder confidence(double v)    { s.confidence  = v; return this; }
        public Builder value(BigDecimal v)     { s.value       = v; return this; }
        public Builder unit(String v)          { s.unit        = v; return this; }
        public Builder evidence(String v)      { s.evidence    = v; return this; }

        public BehavioralSignal build() { return s; }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()             { return id; }
    public UUID getUserId()         { return userId; }
    public UUID getStatementId()    { return statementId; }
    public SignalType getSignalType(){ return signalType; }
    public Severity getSeverity()   { return severity; }
    public boolean isFired()        { return fired; }
    public double getConfidence()   { return confidence; }
    public BigDecimal getValue()    { return value; }
    public String getUnit()         { return unit; }
    public String getEvidence()     { return evidence; }
    public LocalDateTime getCreatedAt(){ return createdAt; }

    @Override
    public String toString() {
        return String.format("BehavioralSignal{type=%s, fired=%s, severity=%s, conf=%.2f, evidence='%s'}",
                signalType, fired, severity, confidence, evidence);
    }
}