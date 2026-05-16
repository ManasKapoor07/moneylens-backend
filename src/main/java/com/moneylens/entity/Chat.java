package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a chat session tied to a specific bank statement.
 *
 * ── Conversation-state machine ──────────────────────────────────────────────
 *
 * States are mutually exclusive.  Only ONE of the three "pending" groups should
 * be active at a time.  The service layer is responsible for clearing stale state
 * before entering a new state.
 *
 * State A — Goal suggestion shown, awaiting user confirmation:
 *   pendingSuggestedGoalName      non-null
 *   pendingSuggestedGoalAmount    may be null
 *   pendingSuggestedGoalDate      may be null
 *   pendingSuggestedGoalVersion   monotonically-increasing counter so the frontend
 *                                 can detect when a *new* suggestion has replaced
 *                                 an old one.
 *
 * State B — Goal already confirmed, plan offer sent, awaiting yes/no:
 *   pendingPlanGoalName           non-null
 *   (State A fields must be null when entering State B)
 *
 * State C — Plan accepted but target amount still unknown; waiting for user to
 *   supply the amount before generating the plan:
 *   pendingGoalAwaitingAmount     goal name (non-null)
 *   pendingGoalAwaitingFrequency  "WEEKLY" | "MONTHLY"
 *   pendingGoalAwaitingDate       may be null
 *
 * Transient (one-turn only):
 *   justCreatedGoalName           cleared at the START of the very next turn
 */
@Entity
@Table(name = "chats")
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id", nullable = false)
    private Statement statement;

    @Column(length = 160)
    private String title;

    // ── Transient one-turn flag ───────────────────────────────────────────────

    /** Set for exactly one turn after a goal is created so the AI knows to offer a plan. */
    @Column(name = "just_created_goal_name", length = 200)
    private String justCreatedGoalName;

    // ── State B ───────────────────────────────────────────────────────────────

    /** Non-null while awaiting the user's yes/no on a plan offer. */
    @Column(name = "pending_plan_goal_name", length = 200)
    private String pendingPlanGoalName;

    // ── State A ───────────────────────────────────────────────────────────────

    /** Non-null while a goal suggestion card has been shown but not yet confirmed. */
    @Column(name = "pending_suggested_goal_name", length = 200)
    private String pendingSuggestedGoalName;

    @Column(name = "pending_suggested_goal_amount", precision = 12, scale = 2)
    private BigDecimal pendingSuggestedGoalAmount;

    @Column(name = "pending_suggested_goal_date")
    private LocalDate pendingSuggestedGoalDate;

    /**
     * Incremented every time a genuinely different goal suggestion replaces the
     * previous one.  The frontend uses this to decide whether to re-render the
     * suggestion card.
     */
    @Column(name = "pending_suggested_goal_version", nullable = false)
    private int pendingSuggestedGoalVersion = 0;

    // ── State C ───────────────────────────────────────────────────────────────

    /**
     * Goal name stored while waiting for the user to supply the target amount.
     * Once the amount arrives the service creates the goal + plan in one shot.
     */
    @Column(name = "pending_goal_awaiting_amount", length = 200)
    private String pendingGoalAwaitingAmount;

    /** "WEEKLY" or "MONTHLY" — preserved while waiting for the amount. */
    @Column(name = "pending_goal_awaiting_frequency", length = 10)
    private String pendingGoalAwaitingFrequency;

    /** Target date carried from the original suggestion while waiting for amount. */
    @Column(name = "pending_goal_awaiting_date")
    private LocalDate pendingGoalAwaitingDate;

    // ── Messages ──────────────────────────────────────────────────────────────

    @OneToMany(
            mappedBy     = "chat",
            cascade      = CascadeType.ALL,
            orphanRemoval = true,
            fetch        = FetchType.LAZY
    )
    @OrderBy("createdAt ASC")
    private List<ChatMessage> messages = new ArrayList<>();

    // ── Audit ─────────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ── Convenience helpers (no business logic — keep service-layer clean) ────

    /** @return true when a suggestion card is currently shown to the user. */
    public boolean hasPendingSuggestion() {
        return pendingSuggestedGoalName != null && !pendingSuggestedGoalName.isBlank();
    }

    /** @return true when a plan offer has been sent and we are awaiting a response. */
    public boolean hasPendingPlanOffer() {
        return pendingPlanGoalName != null && !pendingPlanGoalName.isBlank();
    }

    /**
     * Replace the pending suggestion with a new one and bump the version counter.
     * Automatically clears State B (plan offer) when called so the states stay
     * mutually exclusive.
     */
    public void replacePendingSuggestion(String name, BigDecimal amount, LocalDate date) {
        this.pendingSuggestedGoalName    = name;
        this.pendingSuggestedGoalAmount  = amount;
        this.pendingSuggestedGoalDate    = date;
        this.pendingSuggestedGoalVersion += 1;
        // A new suggestion supersedes any existing plan offer
        this.pendingPlanGoalName = null;
    }

    /**
     * Clear State A completely.
     * Call this when a suggestion is confirmed, rejected, or superseded.
     */
    public void clearPendingSuggestion() {
        this.pendingSuggestedGoalName   = null;
        this.pendingSuggestedGoalAmount = null;
        this.pendingSuggestedGoalDate   = null;
        // intentionally do NOT reset version — frontend needs to detect the cleared state
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId()                                              { return id; }

    public User getUser()                                            { return user; }
    public void setUser(User user)                                   { this.user = user; }

    public Statement getStatement()                                  { return statement; }
    public void setStatement(Statement statement)                    { this.statement = statement; }

    public String getTitle()                                         { return title; }
    public void setTitle(String title)                               { this.title = title; }

    public String getJustCreatedGoalName()                           { return justCreatedGoalName; }
    public void setJustCreatedGoalName(String name)                  { this.justCreatedGoalName = name; }

    public String getPendingPlanGoalName()                           { return pendingPlanGoalName; }
    public void setPendingPlanGoalName(String name)                  { this.pendingPlanGoalName = name; }

    public String getPendingSuggestedGoalName()                      { return pendingSuggestedGoalName; }
    public void setPendingSuggestedGoalName(String name)             { this.pendingSuggestedGoalName = name; }

    public BigDecimal getPendingSuggestedGoalAmount()                { return pendingSuggestedGoalAmount; }
    public void setPendingSuggestedGoalAmount(BigDecimal amount)     { this.pendingSuggestedGoalAmount = amount; }

    public LocalDate getPendingSuggestedGoalDate()                   { return pendingSuggestedGoalDate; }
    public void setPendingSuggestedGoalDate(LocalDate date)          { this.pendingSuggestedGoalDate = date; }

    public int getPendingSuggestedGoalVersion()                      { return pendingSuggestedGoalVersion; }

    public String getPendingGoalAwaitingAmount()                     { return pendingGoalAwaitingAmount; }
    public void setPendingGoalAwaitingAmount(String name)            { this.pendingGoalAwaitingAmount = name; }

    public String getPendingGoalAwaitingFrequency()                  { return pendingGoalAwaitingFrequency; }
    public void setPendingGoalAwaitingFrequency(String freq)         { this.pendingGoalAwaitingFrequency = freq; }

    public LocalDate getPendingGoalAwaitingDate()                    { return pendingGoalAwaitingDate; }
    public void setPendingGoalAwaitingDate(LocalDate date)           { this.pendingGoalAwaitingDate = date; }

    public List<ChatMessage> getMessages()                           { return messages; }
    public void setMessages(List<ChatMessage> msgs)                  { this.messages = msgs; }

    public LocalDateTime getCreatedAt()                              { return createdAt; }

    public LocalDateTime getUpdatedAt()                              { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt)                { this.updatedAt = updatedAt; }
}