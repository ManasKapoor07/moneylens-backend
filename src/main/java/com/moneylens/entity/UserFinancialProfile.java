package com.moneylens.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private User user;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "analysis_json", columnDefinition = "TEXT")
    private String analysisJson;

    @Column(name = "health_score")
    private Integer healthScore;

    @Column(name = "risk_level", length = 10)
    private String riskLevel;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "statement_count")
    private Integer statementCount;

    @Column(name = "transaction_count")
    private Integer transactionCount;

    /**
     * Set to true when the user resolves a clarification card or completes
     * onboarding — signals that analysisJson is stale and should be regenerated
     * on the next explicit refresh. Cleared automatically inside recordRebuild().
     */
    @Column(name = "analysis_stale", nullable = false)
    private boolean analysisStale = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "rebuild_count")
    private Integer rebuildCount = 0;

    @Column(name = "last_rebuild_reason", length = 50)
    private String lastRebuildReason;

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()                  { return id; }
    public User getUser()                { return user; }
    public String getContextJson()       { return contextJson; }
    public String getAnalysisJson()      { return analysisJson; }
    public Integer getHealthScore()      { return healthScore; }
    public String getRiskLevel()         { return riskLevel; }
    public LocalDate getPeriodFrom()     { return periodFrom; }
    public LocalDate getPeriodTo()       { return periodTo; }
    public Integer getStatementCount()   { return statementCount; }
    public Integer getTransactionCount() { return transactionCount; }
    public boolean isAnalysisStale()     { return analysisStale; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getUpdatedAt()  { return updatedAt; }
    public Integer getRebuildCount()     { return rebuildCount; }
    public String getLastRebuildReason() { return lastRebuildReason; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setUser(User v)                { this.user               = v; }
    public void setContextJson(String v)       { this.contextJson         = v; }
    public void setAnalysisJson(String v)      { this.analysisJson        = v; }
    public void setHealthScore(Integer v)      { this.healthScore         = v; }
    public void setRiskLevel(String v)         { this.riskLevel           = v; }
    public void setPeriodFrom(LocalDate v)     { this.periodFrom          = v; }
    public void setPeriodTo(LocalDate v)       { this.periodTo            = v; }
    public void setStatementCount(Integer v)   { this.statementCount      = v; }
    public void setTransactionCount(Integer v) { this.transactionCount    = v; }
    public void setAnalysisStale(boolean v)    { this.analysisStale       = v; }
    public void setUpdatedAt(LocalDateTime v)  { this.updatedAt           = v; }
    public void setRebuildCount(Integer v)     { this.rebuildCount        = v; }
    public void setLastRebuildReason(String v) { this.lastRebuildReason   = v; }

    // ── Convenience ───────────────────────────────────────────────────────────

    public void recordRebuild(String reason) {
        this.rebuildCount      = (this.rebuildCount == null ? 0 : this.rebuildCount) + 1;
        this.lastRebuildReason = reason;
        this.updatedAt         = LocalDateTime.now();
        this.analysisStale     = false; // fresh rebuild clears the flag
    }

    /** Called after clarification resolve or onboarding save. */
    public void markStale() {
        this.analysisStale = true;
        this.updatedAt     = LocalDateTime.now();
    }
}