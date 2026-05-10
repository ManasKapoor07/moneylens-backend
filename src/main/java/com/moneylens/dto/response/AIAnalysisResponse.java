package com.moneylens.dto.response;

import java.util.List;

public class AIAnalysisResponse {

    private String summary;

    private List<String> risks;

    private List<String> positiveHabits;

    private List<String> recommendations;

    private List<String> nextActions;

    // NEW
    private List<String> projections;

    // NEW
    private List<String> behavioralSignals;

    // NEW
    private List<String> hiddenPatterns;

    // =============================================
    // SUMMARY
    // =============================================

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    // =============================================
    // RISKS
    // =============================================

    public List<String> getRisks() {
        return risks;
    }

    public void setRisks(List<String> risks) {
        this.risks = risks;
    }

    // =============================================
    // POSITIVE HABITS
    // =============================================

    public List<String> getPositiveHabits() {
        return positiveHabits;
    }

    public void setPositiveHabits(
            List<String> positiveHabits
    ) {
        this.positiveHabits = positiveHabits;
    }

    // =============================================
    // RECOMMENDATIONS
    // =============================================

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(
            List<String> recommendations
    ) {
        this.recommendations = recommendations;
    }

    // =============================================
    // NEXT ACTIONS
    // =============================================

    public List<String> getNextActions() {
        return nextActions;
    }

    public void setNextActions(
            List<String> nextActions
    ) {
        this.nextActions = nextActions;
    }

    // =============================================
    // PROJECTIONS
    // =============================================

    public List<String> getProjections() {
        return projections;
    }

    public void setProjections(
            List<String> projections
    ) {
        this.projections = projections;
    }

    // =============================================
    // BEHAVIORAL SIGNALS
    // =============================================

    public List<String> getBehavioralSignals() {
        return behavioralSignals;
    }

    public void setBehavioralSignals(
            List<String> behavioralSignals
    ) {
        this.behavioralSignals = behavioralSignals;
    }

    // =============================================
    // HIDDEN PATTERNS
    // =============================================

    public List<String> getHiddenPatterns() {
        return hiddenPatterns;
    }

    public void setHiddenPatterns(
            List<String> hiddenPatterns
    ) {
        this.hiddenPatterns = hiddenPatterns;
    }
}