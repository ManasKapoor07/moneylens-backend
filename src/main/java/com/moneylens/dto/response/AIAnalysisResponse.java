package com.moneylens.dto.response;

import java.util.List;

public class AIAnalysisResponse {

    // =============================================
    // SUMMARY
    // 2–3 sentences. Behavior-first. Feels personal.
    // =============================================

    private String summary;

    // =============================================
    // MONEY PERSONALITY
    // The "who you are with money" identity card.
    // =============================================

    private MoneyPersonality moneyPersonality;

    // =============================================
    // SPENDING PULSE
    // One-liner vibe check on cash flow health.
    // =============================================

    private SpendingPulse spendingPulse;

    // =============================================
    // RISKS
    // Real, behavior-backed, specific.
    // =============================================

    private List<String> risks;

    // =============================================
    // POSITIVE HABITS
    // Stabilizing behaviors — honest, not flattering.
    // =============================================

    private List<String> positiveHabits;

    // =============================================
    // RECOMMENDATIONS
    // High-leverage, behavior-specific improvements.
    // =============================================

    private List<String> recommendations;

    // =============================================
    // NEXT ACTIONS
    // Things they can do THIS week.
    // =============================================

    private List<String> nextActions;

    // =============================================
    // PROJECTIONS
    // Structured cards — headline + real-life impact.
    // =============================================

    private List<ProjectionCard> projections;

    // =============================================
    // BEHAVIORAL SIGNALS
    // Named patterns with emotion + intensity.
    // =============================================

    private List<BehavioralSignal> behavioralSignals;

    // =============================================
    // HIDDEN PATTERNS
    // Things they never noticed — the "woah" section.
    // =============================================

    private List<HiddenPattern> hiddenPatterns;

    // =============================================
    // GETTERS & SETTERS
    // =============================================

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public MoneyPersonality getMoneyPersonality() {
        return moneyPersonality;
    }

    public void setMoneyPersonality(
            MoneyPersonality moneyPersonality
    ) {
        this.moneyPersonality = moneyPersonality;
    }

    public SpendingPulse getSpendingPulse() {
        return spendingPulse;
    }

    public void setSpendingPulse(
            SpendingPulse spendingPulse
    ) {
        this.spendingPulse = spendingPulse;
    }

    public List<String> getRisks() {
        return risks;
    }

    public void setRisks(List<String> risks) {
        this.risks = risks;
    }

    public List<String> getPositiveHabits() {
        return positiveHabits;
    }

    public void setPositiveHabits(
            List<String> positiveHabits
    ) {
        this.positiveHabits = positiveHabits;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(
            List<String> recommendations
    ) {
        this.recommendations = recommendations;
    }

    public List<String> getNextActions() {
        return nextActions;
    }

    public void setNextActions(
            List<String> nextActions
    ) {
        this.nextActions = nextActions;
    }

    public List<ProjectionCard> getProjections() {
        return projections;
    }

    public void setProjections(
            List<ProjectionCard> projections
    ) {
        this.projections = projections;
    }

    public List<BehavioralSignal> getBehavioralSignals() {
        return behavioralSignals;
    }

    public void setBehavioralSignals(
            List<BehavioralSignal> behavioralSignals
    ) {
        this.behavioralSignals = behavioralSignals;
    }

    public List<HiddenPattern> getHiddenPatterns() {
        return hiddenPatterns;
    }

    public void setHiddenPatterns(
            List<HiddenPattern> hiddenPatterns
    ) {
        this.hiddenPatterns = hiddenPatterns;
    }
}