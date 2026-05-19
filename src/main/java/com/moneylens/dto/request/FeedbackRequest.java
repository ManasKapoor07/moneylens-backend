package com.moneylens.dto.request;

import com.moneylens.entity.Feedback.Personalization;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class FeedbackRequest {

    @Size(max = 2000)
    private String firstImpression;

    @Size(max = 2000)
    private String accurateInsight;

    @NotNull
    private Personalization personalization;

    @Size(max = 10)
    private List<String> wantedInsights;

    @Size(max = 2000)
    private String describeToFriend;

    @Size(max = 2000)
    private String holyShitInsight;

    @Email @Size(max = 255)
    private String contactEmail;

    private boolean contactOk;

    public FeedbackRequest() {}

    public String getFirstImpression() { return firstImpression; }
    public void setFirstImpression(String v) { this.firstImpression = v; }

    public String getAccurateInsight() { return accurateInsight; }
    public void setAccurateInsight(String v) { this.accurateInsight = v; }

    public Personalization getPersonalization() { return personalization; }
    public void setPersonalization(Personalization v) { this.personalization = v; }

    public List<String> getWantedInsights() { return wantedInsights; }
    public void setWantedInsights(List<String> v) { this.wantedInsights = v; }

    public String getDescribeToFriend() { return describeToFriend; }
    public void setDescribeToFriend(String v) { this.describeToFriend = v; }

    public String getHolyShitInsight() { return holyShitInsight; }
    public void setHolyShitInsight(String v) { this.holyShitInsight = v; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String v) { this.contactEmail = v; }

    public boolean isContactOk() { return contactOk; }
    public void setContactOk(boolean v) { this.contactOk = v; }
}