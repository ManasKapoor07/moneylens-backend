package com.moneylens.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "is_email_verified")
    private boolean emailVerified = false;

    @Column(name = "email_verification_token", unique = true)
    private String emailVerificationToken;

    @Column(name = "email_verification_token_expiry")
    private LocalDateTime emailVerificationTokenExpiry;

    @Column(name = "password_reset_token", unique = true)
    private String passwordResetToken;

    @Column(name = "password_reset_token_expiry")
    private LocalDateTime passwordResetTokenExpiry;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Role {
        USER,
        ADMIN,
        PREMIUM
    }

    // ===== BUILDER =====

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private final User user = new User();

        public Builder fullName(String fullName) {
            user.fullName = fullName;
            return this;
        }

        public Builder email(String email) {
            user.email = email;
            return this;
        }

        public Builder password(String password) {
            user.password = password;
            return this;
        }

        public Builder role(Role role) {
            user.role = role;
            return this;
        }

        public Builder emailVerified(boolean emailVerified) {
            user.emailVerified = emailVerified;
            return this;
        }

        public User build() {
            return user;
        }
    }

    // ===== Getters =====

    public UUID getId() { return id; }

    public String getFullName() { return fullName; }

    public String getEmail() { return email; }

    public String getPassword() { return password; }

    public Role getRole() { return role; }

    public boolean isActive() { return active; }

    public boolean isEmailVerified() { return emailVerified; }

    public String getEmailVerificationToken() { return emailVerificationToken; }

    public LocalDateTime getEmailVerificationTokenExpiry() { return emailVerificationTokenExpiry; }

    public String getPasswordResetToken() { return passwordResetToken; }

    public LocalDateTime getPasswordResetTokenExpiry() { return passwordResetTokenExpiry; }

    public LocalDateTime getLastLogin() { return lastLogin; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    // ===== Setters =====

    public void setId(UUID id) { this.id = id; }

    public void setFullName(String fullName) { this.fullName = fullName; }

    public void setEmail(String email) { this.email = email; }

    public void setPassword(String password) { this.password = password; }

    public void setRole(Role role) { this.role = role; }

    public void setActive(boolean active) { this.active = active; }

    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

    public void setEmailVerificationToken(String emailVerificationToken) {
        this.emailVerificationToken = emailVerificationToken;
    }

    public void setEmailVerificationTokenExpiry(LocalDateTime emailVerificationTokenExpiry) {
        this.emailVerificationTokenExpiry = emailVerificationTokenExpiry;
    }

    public void setPasswordResetToken(String passwordResetToken) {
        this.passwordResetToken = passwordResetToken;
    }

    public void setPasswordResetTokenExpiry(LocalDateTime passwordResetTokenExpiry) {
        this.passwordResetTokenExpiry = passwordResetTokenExpiry;
    }

    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}