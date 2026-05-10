package com.moneylens.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "financial_profiles")
public class FinancialProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false
    )
    private User user;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "statement_id",
            nullable = false,
            unique = true
    )
    private Statement statement;

    @Column(
            name = "context_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String contextJson;

    @Column(name = "health_score")
    private Integer healthScore;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "schema_version")
    private String schemaVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt =
            LocalDateTime.now();

    // =============================================
    // GETTERS / SETTERS
    // =============================================

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Statement getStatement() {
        return statement;
    }

    public void setStatement(
            Statement statement
    ) {
        this.statement = statement;
    }

    public String getContextJson() {
        return contextJson;
    }

    public void setContextJson(
            String contextJson
    ) {
        this.contextJson = contextJson;
    }

    public Integer getHealthScore() {
        return healthScore;
    }

    public void setHealthScore(
            Integer healthScore
    ) {
        this.healthScore = healthScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(
            String riskLevel
    ) {
        this.riskLevel = riskLevel;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(
            String schemaVersion
    ) {
        this.schemaVersion = schemaVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}