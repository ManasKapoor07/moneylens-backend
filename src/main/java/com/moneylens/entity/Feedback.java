package com.moneylens.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "first_impression", columnDefinition = "TEXT")
    private String firstImpression;

    @Column(name = "accurate_insight", columnDefinition = "TEXT")
    private String accurateInsight;

    @Column(name = "personalization", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    private Personalization personalization;

    @Column(name = "wanted_insights", columnDefinition = "TEXT[]")
    @org.hibernate.annotations.Array(length = 20)
    private List<String> wantedInsights;

    @Column(name = "describe_to_friend", columnDefinition = "TEXT")
    private String describeToFriend;

    @Column(name = "holy_shit_insight", columnDefinition = "TEXT")
    private String holyShitInsight;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_ok", nullable = false)
    private boolean contactOk;

    @CreationTimestamp
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private OffsetDateTime submittedAt;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    // ── Enum ──────────────────────────────────────────────────────────────────
    public enum Personalization {
        EXTREMELY_PERSONALIZED,
        MOSTLY_PERSONALIZED,
        SOMEWHAT_GENERIC,
        VERY_GENERIC
    }

    // ── Constructors ──────────────────────────────────────────────────────────
    public Feedback() {}

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

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

    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime v) { this.submittedAt = v; }

    public String getIpHash() { return ipHash; }
    public void setIpHash(String v) { this.ipHash = v; }

    // ── Builder ───────────────────────────────────────────────────────────────
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String firstImpression;
        private String accurateInsight;
        private Personalization personalization;
        private List<String> wantedInsights;
        private String describeToFriend;
        private String holyShitInsight;
        private String contactEmail;
        private boolean contactOk;
        private OffsetDateTime submittedAt;
        private String ipHash;

        public Builder id(UUID v)                         { this.id = v; return this; }
        public Builder firstImpression(String v)          { this.firstImpression = v; return this; }
        public Builder accurateInsight(String v)          { this.accurateInsight = v; return this; }
        public Builder personalization(Personalization v) { this.personalization = v; return this; }
        public Builder wantedInsights(List<String> v)     { this.wantedInsights = v; return this; }
        public Builder describeToFriend(String v)         { this.describeToFriend = v; return this; }
        public Builder holyShitInsight(String v)          { this.holyShitInsight = v; return this; }
        public Builder contactEmail(String v)             { this.contactEmail = v; return this; }
        public Builder contactOk(boolean v)               { this.contactOk = v; return this; }
        public Builder submittedAt(OffsetDateTime v)      { this.submittedAt = v; return this; }
        public Builder ipHash(String v)                   { this.ipHash = v; return this; }

        public Feedback build() {
            Feedback f = new Feedback();
            f.id               = this.id;
            f.firstImpression  = this.firstImpression;
            f.accurateInsight  = this.accurateInsight;
            f.personalization  = this.personalization;
            f.wantedInsights   = this.wantedInsights;
            f.describeToFriend = this.describeToFriend;
            f.holyShitInsight  = this.holyShitInsight;
            f.contactEmail     = this.contactEmail;
            f.contactOk        = this.contactOk;
            f.submittedAt      = this.submittedAt;
            f.ipHash           = this.ipHash;
            return f;
        }
    }
}