package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A time-bound savings plan generated for a UserGoal.
 * Supports both WEEKLY and MONTHLY frequency plans.
 * Tasks (GoalPlanTask) represent individual periods within the plan.
 */
@Entity
@Table(name = "goal_plans")
public class GoalPlan {

    public enum Status    { ACTIVE, COMPLETED, ABANDONED }
    public enum Frequency { WEEKLY, MONTHLY }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goal_id", nullable = false)
    private UserGoal goal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * WEEKLY or MONTHLY. Defaults to WEEKLY for backwards compatibility.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Frequency frequency = Frequency.WEEKLY;

    /**
     * Total number of periods (weeks or months) in this plan.
     * Named totalWeeks for legacy reasons but applies to months too.
     */
    @Column(name = "total_weeks", nullable = false)
    private int totalWeeks;

    /**
     * Target saving amount per period (weekly or monthly depending on frequency).
     * Named weeklySavingTarget for legacy; applies per-period.
     */
    @Column(name = "weekly_saving_target", precision = 12, scale = 2)
    private BigDecimal weeklySavingTarget;

    @Column(length = 500)
    private String summary;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Status status = Status.ACTIVE;

    /** 0–100: percentage of periods checked in as DONE */
    @Column(name = "progress_pct", nullable = false)
    private int progressPct = 0;

    @OneToMany(mappedBy = "plan",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("weekNumber ASC")
    private List<GoalPlanTask> tasks = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId()                                    { return id; }

    public UserGoal getGoal()                              { return goal; }
    public void setGoal(UserGoal goal)                     { this.goal = goal; }

    public User getUser()                                  { return user; }
    public void setUser(User user)                         { this.user = user; }

    public Frequency getFrequency()                        { return frequency; }
    public void setFrequency(Frequency frequency)          { this.frequency = frequency; }

    public int getTotalWeeks()                             { return totalWeeks; }
    public void setTotalWeeks(int totalWeeks)              { this.totalWeeks = totalWeeks; }

    public BigDecimal getWeeklySavingTarget()              { return weeklySavingTarget; }
    public void setWeeklySavingTarget(BigDecimal t)        { this.weeklySavingTarget = t; }

    public String getSummary()                             { return summary; }
    public void setSummary(String summary)                 { this.summary = summary; }

    public LocalDate getStartDate()                        { return startDate; }
    public void setStartDate(LocalDate startDate)          { this.startDate = startDate; }

    public LocalDate getEndDate()                          { return endDate; }
    public void setEndDate(LocalDate endDate)              { this.endDate = endDate; }

    public Status getStatus()                              { return status; }
    public void setStatus(Status status)                   { this.status = status; }

    public int getProgressPct()                            { return progressPct; }
    public void setProgressPct(int progressPct)            { this.progressPct = progressPct; }

    public List<GoalPlanTask> getTasks()                   { return tasks; }
    public void setTasks(List<GoalPlanTask> tasks)         { this.tasks = tasks; }

    public LocalDateTime getCreatedAt()                    { return createdAt; }

    public LocalDateTime getUpdatedAt()                    { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt)      { this.updatedAt = updatedAt; }
}