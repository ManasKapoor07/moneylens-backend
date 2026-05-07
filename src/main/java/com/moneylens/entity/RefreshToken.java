package com.moneylens.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    @Column(name = "is_revoked")
    private boolean revoked = false;

    @Column(name = "device_info")
    private String deviceInfo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // ── Getters ──
    public UUID getId() { return id; }
    public String getToken() { return token; }
    public User getUser() { return user; }
    public LocalDateTime getExpiryDate() { return expiryDate; }
    public boolean isRevoked() { return revoked; }
    public String getDeviceInfo() { return deviceInfo; }

    // ── Setters ──
    public void setToken(String token) { this.token = token; }
    public void setUser(User user) { this.user = user; }
    public void setExpiryDate(LocalDateTime expiryDate) { this.expiryDate = expiryDate; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }

    // ── Helpers ──
    public boolean isExpired() { return LocalDateTime.now().isAfter(this.expiryDate); }
    public boolean isValid() { return !revoked && !isExpired(); }

    // ── Builder ──
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final RefreshToken t = new RefreshToken();
        public Builder token(String v) { t.token = v; return this; }
        public Builder user(User v) { t.user = v; return this; }
        public Builder expiryDate(LocalDateTime v) { t.expiryDate = v; return this; }
        public Builder deviceInfo(String v) { t.deviceInfo = v; return this; }
        public RefreshToken build() { return t; }
    }
}