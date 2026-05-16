package com.moneylens.dto.response;

import com.moneylens.dto.response.GoalPlanDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ChatResponse {

    private UUID   chatId;
    private String reply;
    private boolean newChat;
    private List<MsgDto> history;

    // ── Goal suggestion (shown as a card the user can confirm) ───────────────
    private SuggestedGoal suggestedGoal;

    // ── Plan created this turn (shown as an expandable plan card) ────────────
    private GoalPlanDto createdPlan;

    // ── Plan offer state (frontend uses this to know we're awaiting a reply) ─
    private boolean planOfferPending;
    private String  pendingPlanGoalName;
    private boolean isDuplicateGoal;
    private String  duplicateGoalName;

    // ── Premium limit error (shown as a upsell card in the frontend) ─────────
    private String planLimitError;

    // ── Token budget info ─────────────────────────────────────────────────────
    private long tokensUsedToday;
    private long tokensRemainingToday;

    // ─── Getters & Setters ────────────────────────────────────────────────────

    public UUID getChatId()                          { return chatId; }
    public void setChatId(UUID chatId)               { this.chatId = chatId; }

    public String getReply()                         { return reply; }
    public void setReply(String reply)               { this.reply = reply; }

    public boolean isNewChat()                       { return newChat; }
    public void setNewChat(boolean newChat)           { this.newChat = newChat; }

    public List<MsgDto> getHistory()                 { return history; }
    public void setHistory(List<MsgDto> history)     { this.history = history; }

    public SuggestedGoal getSuggestedGoal()          { return suggestedGoal; }
    public void setSuggestedGoal(SuggestedGoal g)    { this.suggestedGoal = g; }

    public GoalPlanDto getCreatedPlan()              { return createdPlan; }
    public void setCreatedPlan(GoalPlanDto plan)     { this.createdPlan = plan; }

    public boolean isPlanOfferPending()              { return planOfferPending; }
    public void setPlanOfferPending(boolean p)       { this.planOfferPending = p; }

    public String getPendingPlanGoalName()           { return pendingPlanGoalName; }
    public void setPendingPlanGoalName(String n)     { this.pendingPlanGoalName = n; }

    public boolean isDuplicateGoal()                 { return isDuplicateGoal; }
    public void setIsDuplicateGoal(boolean v)        { this.isDuplicateGoal = v; }

    public String getDuplicateGoalName()             { return duplicateGoalName; }
    public void setDuplicateGoalName(String name)    { this.duplicateGoalName = name; }

    public String getPlanLimitError()                { return planLimitError; }
    public void setPlanLimitError(String msg)        { this.planLimitError = msg; }

    public long getTokensUsedToday()                             { return tokensUsedToday; }
    public void setTokensUsedToday(long tokensUsedToday)         { this.tokensUsedToday = tokensUsedToday; }

    public long getTokensRemainingToday()                        { return tokensRemainingToday; }
    public void setTokensRemainingToday(long tokensRemainingToday) { this.tokensRemainingToday = tokensRemainingToday; }

    // ─── Nested: message DTO ─────────────────────────────────────────────────

    public record MsgDto(UUID id, String role, String content, LocalDateTime createdAt) {}

    // ─── Nested: suggested goal ──────────────────────────────────────────────

    /**
     * A goal detected from conversation — not yet persisted.
     * The frontend shows a "Create this goal?" card; the user confirms or dismisses.
     * On confirmation, the frontend calls POST /api/chat/{chatId}/confirm-goal.
     */
    public record SuggestedGoal(
            String     name,
            BigDecimal targetAmount,
            BigDecimal currentSaved,
            LocalDate  targetDate
    ) {}
}