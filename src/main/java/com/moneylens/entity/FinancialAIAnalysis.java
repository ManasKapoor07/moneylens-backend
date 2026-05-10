package com.moneylens.entity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "financial_ai_analysis")
public class FinancialAIAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // =============================================
    // USER
    // =============================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false
    )
    private User user;

    // =============================================
    // STATEMENT
    // =============================================

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "statement_id",
            nullable = false,
            unique = true
    )
    private Statement statement;

    // =============================================
    // AI JSON
    // =============================================

    @Column(
            name = "analysis_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String analysisJson;

    // =============================================
    // MODEL INFO
    // =============================================

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    // =============================================
    // TIMESTAMPS
    // =============================================

    @Column(name = "created_at")
    private LocalDateTime createdAt =
            LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt =
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

    public String getAnalysisJson() {
        return analysisJson;
    }

    public void setAnalysisJson(
            String analysisJson
    ) {
        this.analysisJson = analysisJson;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(
            String promptVersion
    ) {
        this.promptVersion = promptVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(
            LocalDateTime updatedAt
    ) {
        this.updatedAt = updatedAt;
    }
}