package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One week's tasks within a GoalPlan.
 * Each week has a saving target + a list of behavioural actions.
 */
@Entity
@Table(name = "goal_plan_tasks")
public class GoalPlanTask {

    public enum CheckinStatus { PENDING, DONE, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private GoalPlan plan;

    /** 1-indexed week number within the plan */
    @Column(name = "week_number", nullable = false)
    private int weekNumber;

    /** Date this week starts (plan.startDate + (weekNumber-1)*7 days) */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;

    /** The ₹ amount the user should set aside this week */
    @Column(name = "saving_target", precision = 12, scale = 2)
    private BigDecimal savingTarget;

    /** The ₹ amount the user actually saved (filled on check-in) */
    @Column(name = "saved_amount", precision = 12, scale = 2)
    private BigDecimal savedAmount;

    /**
     * Comma-separated or JSON-array of actionable tasks for this week.
     * e.g. "Skip 2 Swiggy orders, Transfer ₹2000 to savings, Cancel unused subscription"
     * Stored as plain text; rendered as a list on the frontend.
     */
    @Column(name = "actions", columnDefinition = "TEXT")
    private String actions;

    /** AI-generated focus tip for this specific week */
    @Column(name = "tip", columnDefinition = "TEXT")
    private String tip;

    @Enumerated(EnumType.STRING)
    @Column(name = "checkin_status", nullable = false)
    private CheckinStatus checkinStatus = CheckinStatus.PENDING;

    /** User's optional note when checking in */
    @Column(name = "checkin_note", columnDefinition = "TEXT")
    private String checkinNote;

    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Getters & Setters ─────────────────────────────────────────────

    public UUID getId() { return id; }

    public GoalPlan getPlan() { return plan; }
    public void setPlan(GoalPlan plan) { this.plan = plan; }

    public int getWeekNumber() { return weekNumber; }
    public void setWeekNumber(int weekNumber) { this.weekNumber = weekNumber; }

    public LocalDate getWeekStart() { return weekStart; }
    public void setWeekStart(LocalDate weekStart) { this.weekStart = weekStart; }

    public LocalDate getWeekEnd() { return weekEnd; }
    public void setWeekEnd(LocalDate weekEnd) { this.weekEnd = weekEnd; }

    public BigDecimal getSavingTarget() { return savingTarget; }
    public void setSavingTarget(BigDecimal savingTarget) { this.savingTarget = savingTarget; }

    public BigDecimal getSavedAmount() { return savedAmount; }
    public void setSavedAmount(BigDecimal savedAmount) { this.savedAmount = savedAmount; }

    public String getActions() { return actions; }
    public void setActions(String actions) { this.actions = actions; }

    public String getTip() { return tip; }
    public void setTip(String tip) { this.tip = tip; }

    public CheckinStatus getCheckinStatus() { return checkinStatus; }
    public void setCheckinStatus(CheckinStatus checkinStatus) { this.checkinStatus = checkinStatus; }

    public String getCheckinNote() { return checkinNote; }
    public void setCheckinNote(String checkinNote) { this.checkinNote = checkinNote; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(LocalDateTime checkedInAt) { this.checkedInAt = checkedInAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}