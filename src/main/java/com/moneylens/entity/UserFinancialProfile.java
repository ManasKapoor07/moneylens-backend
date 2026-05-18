package com.moneylens.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * User-level aggregated financial profile.
 *
 * Unlike FinancialProfile (which is scoped to a single statement),
 * this entity represents the merged, deduplicated view of a user's
 * entire financial history across ALL uploaded statements.
 *
 * This is the source of truth for:
 *   - The main dashboard
 *   - Chat AI context
 *   - Health score / risk level shown to the user
 *
 * It is recomputed (async) whenever:
 *   a) A new statement is marked COMPLETED
 *   b) A user correction is detected in chat
 *   c) A manual refresh is triggered
 *
 * Per-statement FinancialProfile rows are kept for drill-down
 * and audit purposes — they are NOT used for top-level UX.
 */
@Entity
@Table(
        name = "user_financial_profiles",
        indexes = {
                @Index(name = "idx_user_financial_profiles_user_id", columnList = "user_id", unique = true)
        }
)
public class UserFinancialProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Owner ─────────────────────────────────────────────────────────────────

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // ── Aggregated AI context ─────────────────────────────────────────────────

    /**
     * The rendered prompt-context string produced by AIContextBuilderService
     * across ALL of the user's transactions.
     *
     * Stored as a plain text block (the same format that goes into the LLM
     * prompt). Not JSON — human-readable, token-efficient.
     *
     * Example excerpt:
     *   INCOME
     *     Total received: ₹85,000
     *     Salary: ₹72,000 on 2026-01-01 from DATOPIC TECHN
     *   EXPENSES
     *     Total spent: ₹61,200
     *     ...
     */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    /**
     * The full AIAnalysisResponse serialized as JSON.
     * Contains: summary, moneyPersonality, spendingPulse, risks,
     * positiveHabits, recommendations, nextActions, projections,
     * behavioralSignals, hiddenPatterns.
     */
    @Column(name = "analysis_json", columnDefinition = "TEXT")
    private String analysisJson;

    // ── Scalar scores (denormalized for fast reads) ───────────────────────────

    /**
     * Overall financial health score (0–100).
     * Derived from AIContextBuilderService.HealthScore.
     */
    @Column(name = "health_score")
    private Integer healthScore;

    /**
     * Risk level: "LOW", "MEDIUM", or "HIGH".
     * Derived from RiskProfile flags in the aggregated context.
     */
    @Column(name = "risk_level", length = 10)
    private String riskLevel;

    // ── Timeline metadata ─────────────────────────────────────────────────────

    /**
     * Earliest transaction date across all statements.
     * Used to label the profile period on the dashboard.
     */
    @Column(name = "period_from")
    private LocalDate periodFrom;

    /**
     * Latest transaction date across all statements.
     */
    @Column(name = "period_to")
    private LocalDate periodTo;

    /**
     * Number of completed statements merged into this profile.
     * Displayed on the dashboard as "Based on X months of data".
     */
    @Column(name = "statement_count")
    private Integer statementCount;

    /**
     * Total number of deduplicated transactions in the canonical timeline.
     * Useful for logging and transparency UI ("analysed 847 transactions").
     */
    @Column(name = "transaction_count")
    private Integer transactionCount;

    // ── Audit ─────────────────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Incremented each time the profile is recomputed.
     * Useful for debugging ("profile was rebuilt 3 times").
     */
    @Column(name = "rebuild_count")
    private Integer rebuildCount = 0;

    /**
     * Reason for the last rebuild.
     * One of: "NEW_STATEMENT", "USER_CORRECTION", "MANUAL_REFRESH"
     */
    @Column(name = "last_rebuild_reason", length = 50)
    private String lastRebuildReason;

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()                    { return id; }
    public User getUser()                  { return user; }
    public String getContextJson()         { return contextJson; }
    public String getAnalysisJson()        { return analysisJson; }
    public Integer getHealthScore()        { return healthScore; }
    public String getRiskLevel()           { return riskLevel; }
    public LocalDate getPeriodFrom()       { return periodFrom; }
    public LocalDate getPeriodTo()         { return periodTo; }
    public Integer getStatementCount()     { return statementCount; }
    public Integer getTransactionCount()   { return transactionCount; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public LocalDateTime getUpdatedAt()    { return updatedAt; }
    public Integer getRebuildCount()       { return rebuildCount; }
    public String getLastRebuildReason()   { return lastRebuildReason; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setUser(User user)                              { this.user               = user; }
    public void setContextJson(String contextJson)              { this.contextJson         = contextJson; }
    public void setAnalysisJson(String analysisJson)            { this.analysisJson        = analysisJson; }
    public void setHealthScore(Integer healthScore)             { this.healthScore         = healthScore; }
    public void setRiskLevel(String riskLevel)                  { this.riskLevel           = riskLevel; }
    public void setPeriodFrom(LocalDate periodFrom)             { this.periodFrom          = periodFrom; }
    public void setPeriodTo(LocalDate periodTo)                 { this.periodTo            = periodTo; }
    public void setStatementCount(Integer statementCount)       { this.statementCount      = statementCount; }
    public void setTransactionCount(Integer transactionCount)   { this.transactionCount    = transactionCount; }
    public void setUpdatedAt(LocalDateTime updatedAt)           { this.updatedAt           = updatedAt; }
    public void setRebuildCount(Integer rebuildCount)           { this.rebuildCount        = rebuildCount; }
    public void setLastRebuildReason(String lastRebuildReason)  { this.lastRebuildReason   = lastRebuildReason; }

    // ── Convenience ───────────────────────────────────────────────────────────

    /**
     * Call this each time the profile is rebuilt to keep audit fields in sync.
     */
    public void recordRebuild(String reason) {
        this.rebuildCount        = (this.rebuildCount == null ? 0 : this.rebuildCount) + 1;
        this.lastRebuildReason   = reason;
        this.updatedAt           = LocalDateTime.now();
    }
}