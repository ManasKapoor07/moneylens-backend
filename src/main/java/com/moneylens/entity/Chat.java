package com.moneylens.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a chat session tied to a specific bank statement.
 * Carries two transient conversation-state flags used by ChatService
 * to drive the goal → plan offer → plan creation flow:
 *
 *   justCreatedGoalName  — set for one turn after a goal is confirmed,
 *                          so the AI knows to offer a plan.
 *   pendingPlanGoalName  — set while awaiting the user's yes/no on the
 *                          plan offer; cleared once resolved.
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

    /**
     * Set for exactly ONE assistant turn after a goal is confirmed by the user.
     * ChatService injects [GOAL_JUST_CREATED: <name>] into the financial context
     * so the AI knows to offer a savings plan. Cleared after that turn.
     */
    @Column(name = "just_created_goal_name", length = 200)
    private String justCreatedGoalName;

    /**
     * Non-null while the AI is awaiting the user's response to a plan offer.
     * Stores the goal name the offer was made for.
     * Cleared when the user accepts (plan is created) or declines.
     */
    @Column(name = "pending_plan_goal_name", length = 200)
    private String pendingPlanGoalName;

    @OneToMany(mappedBy = "chat",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<ChatMessage> messages = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId()                                { return id; }

    public User getUser()                              { return user; }
    public void setUser(User user)                     { this.user = user; }

    public Statement getStatement()                    { return statement; }
    public void setStatement(Statement statement)      { this.statement = statement; }

    public String getTitle()                           { return title; }
    public void setTitle(String title)                 { this.title = title; }

    public String getJustCreatedGoalName()             { return justCreatedGoalName; }
    public void setJustCreatedGoalName(String name)    { this.justCreatedGoalName = name; }

    public String getPendingPlanGoalName()             { return pendingPlanGoalName; }
    public void setPendingPlanGoalName(String name)    { this.pendingPlanGoalName = name; }

    public List<ChatMessage> getMessages()             { return messages; }
    public void setMessages(List<ChatMessage> msgs)    { this.messages = msgs; }

    public LocalDateTime getCreatedAt()                { return createdAt; }

    public LocalDateTime getUpdatedAt()                { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt)  { this.updatedAt = updatedAt; }
}