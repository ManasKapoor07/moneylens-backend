package com.moneylens.dto.response;

import java.util.List;
import java.util.Map;

public class FeedbackAnalytics {

    private long totalResponses;
    private Map<String, Long> personalizationBreakdown;
    private Map<String, Long> topWantedInsights;
    private long contactOptIns;
    private List<String> recentDescriptions;
    private List<String> recentHolyShit;

    public FeedbackAnalytics() {}

    public FeedbackAnalytics(long totalResponses,
                             Map<String, Long> personalizationBreakdown,
                             Map<String, Long> topWantedInsights,
                             long contactOptIns,
                             List<String> recentDescriptions,
                             List<String> recentHolyShit) {
        this.totalResponses           = totalResponses;
        this.personalizationBreakdown = personalizationBreakdown;
        this.topWantedInsights        = topWantedInsights;
        this.contactOptIns            = contactOptIns;
        this.recentDescriptions       = recentDescriptions;
        this.recentHolyShit           = recentHolyShit;
    }

    public long getTotalResponses() { return totalResponses; }
    public void setTotalResponses(long v) { this.totalResponses = v; }

    public Map<String, Long> getPersonalizationBreakdown() { return personalizationBreakdown; }
    public void setPersonalizationBreakdown(Map<String, Long> v) { this.personalizationBreakdown = v; }

    public Map<String, Long> getTopWantedInsights() { return topWantedInsights; }
    public void setTopWantedInsights(Map<String, Long> v) { this.topWantedInsights = v; }

    public long getContactOptIns() { return contactOptIns; }
    public void setContactOptIns(long v) { this.contactOptIns = v; }

    public List<String> getRecentDescriptions() { return recentDescriptions; }
    public void setRecentDescriptions(List<String> v) { this.recentDescriptions = v; }

    public List<String> getRecentHolyShit() { return recentHolyShit; }
    public void setRecentHolyShit(List<String> v) { this.recentHolyShit = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private long totalResponses;
        private Map<String, Long> personalizationBreakdown;
        private Map<String, Long> topWantedInsights;
        private long contactOptIns;
        private List<String> recentDescriptions;
        private List<String> recentHolyShit;

        public Builder totalResponses(long v)                        { this.totalResponses = v; return this; }
        public Builder personalizationBreakdown(Map<String, Long> v) { this.personalizationBreakdown = v; return this; }
        public Builder topWantedInsights(Map<String, Long> v)        { this.topWantedInsights = v; return this; }
        public Builder contactOptIns(long v)                         { this.contactOptIns = v; return this; }
        public Builder recentDescriptions(List<String> v)            { this.recentDescriptions = v; return this; }
        public Builder recentHolyShit(List<String> v)                { this.recentHolyShit = v; return this; }

        public FeedbackAnalytics build() {
            return new FeedbackAnalytics(
                    totalResponses, personalizationBreakdown, topWantedInsights,
                    contactOptIns, recentDescriptions, recentHolyShit
            );
        }
    }
}