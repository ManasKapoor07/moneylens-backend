package com.moneylens.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_onboarding_profiles")
public class UserOnboardingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_goal")
    private PrimaryGoal primaryGoal;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type")
    private EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "dependents")
    private Dependents dependents;

    @Enumerated(EnumType.STRING)
    @Column(name = "city_tier")
    private CityTier cityTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "income_range")
    private IncomeRange incomeRange;

    @Column(name = "skipped", nullable = false)
    private boolean skipped = false;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum PrimaryGoal {
        SAVE_FOR_GOAL,
        PAY_OFF_DEBT,
        BUILD_EMERGENCY_FUND,
        TRACK_SPENDING,
        GROW_WEALTH
    }

    public enum EmploymentType {
        SALARIED,
        FREELANCE,
        BUSINESS_OWNER,
        STUDENT,
        OTHER
    }

    public enum Dependents {
        JUST_ME,
        SUPPORTING_FAMILY,
        HAVE_KIDS,
        BOTH
    }

    public enum CityTier {
        METRO,
        TIER_2,
        TIER_3
    }

    public enum IncomeRange {
        BELOW_30K,
        RANGE_30K_60K,
        RANGE_60K_1L,
        RANGE_1L_2L,
        ABOVE_2L
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final UserOnboardingProfile p = new UserOnboardingProfile();
        public Builder user(User v)                    { p.user           = v; return this; }
        public Builder primaryGoal(PrimaryGoal v)      { p.primaryGoal    = v; return this; }
        public Builder employmentType(EmploymentType v){ p.employmentType = v; return this; }
        public Builder dependents(Dependents v)        { p.dependents     = v; return this; }
        public Builder cityTier(CityTier v)            { p.cityTier       = v; return this; }
        public Builder incomeRange(IncomeRange v)      { p.incomeRange    = v; return this; }
        public Builder skipped(boolean v)              { p.skipped        = v; return this; }
        public Builder completedAt(LocalDateTime v)    { p.completedAt    = v; return this; }
        public UserOnboardingProfile build()           { return p; }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()                       { return id; }
    public User getUser()                     { return user; }
    public PrimaryGoal getPrimaryGoal()       { return primaryGoal; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public Dependents getDependents()         { return dependents; }
    public CityTier getCityTier()             { return cityTier; }
    public IncomeRange getIncomeRange()       { return incomeRange; }
    public boolean isSkipped()                { return skipped; }
    public LocalDateTime getCompletedAt()     { return completedAt; }
    public LocalDateTime getCreatedAt()       { return createdAt; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setUser(User v)               { this.user           = v; }
    public void setPrimaryGoal(PrimaryGoal v) { this.primaryGoal    = v; }
    public void setEmploymentType(EmploymentType v) { this.employmentType = v; }
    public void setDependents(Dependents v)   { this.dependents     = v; }
    public void setCityTier(CityTier v)       { this.cityTier       = v; }
    public void setIncomeRange(IncomeRange v) { this.incomeRange    = v; }
    public void setSkipped(boolean v)         { this.skipped        = v; }
    public void setCompletedAt(LocalDateTime v){ this.completedAt   = v; }
}