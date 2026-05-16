package com.moneylens.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "daily_token_usage",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "usage_date"})
)
public class DailyTokenUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "tokens_used", nullable = false)
    private int tokensUsed = 0;

    // ── Getters ───────────────────────────────────────────────────────────────
    public UUID getId()             { return id; }
    public UUID getUserId()         { return userId; }
    public LocalDate getUsageDate() { return usageDate; }
    public int getTokensUsed()      { return tokensUsed; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setUserId(UUID userId)        { this.userId = userId; }
    public void setUsageDate(LocalDate date)  { this.usageDate = date; }
    public void setTokensUsed(int tokens)     { this.tokensUsed = tokens; }
}