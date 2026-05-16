package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_goals")
public class UserGoal {

    public enum Status { ACTIVE, COMPLETED, CANCELLED }
    public enum Source { MANUAL, AI_EXTRACTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id")
    private Statement statement;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "target_amount", precision = 12, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "current_saved", precision = 12, scale = 2)
    private BigDecimal currentSaved = BigDecimal.ZERO;

    @Column(name = "monthly_contribution", precision = 12, scale = 2)
    private BigDecimal monthlyContribution;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private Source source = Source.MANUAL;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public UUID getId() { return id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Statement getStatement() { return statement; }
    public void setStatement(Statement statement) { this.statement = statement; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal targetAmount) { this.targetAmount = targetAmount; }

    public BigDecimal getCurrentSaved() { return currentSaved; }
    public void setCurrentSaved(BigDecimal currentSaved) { this.currentSaved = currentSaved; }

    public BigDecimal getMonthlyContribution() { return monthlyContribution; }
    public void setMonthlyContribution(BigDecimal monthlyContribution) { this.monthlyContribution = monthlyContribution; }

    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}