package com.moneylens.dto.response;

import java.util.UUID;

public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private UserInfo user;

    public AuthResponse() {
        this.tokenType = "Bearer";
    }

    // Getters
    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public String getTokenType() { return tokenType; }
    public UserInfo getUser() { return user; }

    // Setters
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    public void setUser(UserInfo user) { this.user = user; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {

        private final AuthResponse response;

        public Builder() { response = new AuthResponse(); }

        public Builder accessToken(String accessToken) {
            response.accessToken = accessToken;
            return this;
        }

        public Builder refreshToken(String refreshToken) {
            response.refreshToken = refreshToken;
            return this;
        }

        public Builder tokenType(String tokenType) {
            response.tokenType = tokenType;
            return this;
        }

        public Builder user(UserInfo user) {
            response.user = user;
            return this;
        }

        public AuthResponse build() { return response; }
    }

    public static class UserInfo {

        private UUID id;
        private String fullName;
        private String email;
        private String role;
        private boolean emailVerified;
        private boolean hasStatement;
        private UUID latestStatementId; // ✅ field exists

        // Getters
        public UUID getId() { return id; }
        public String getFullName() { return fullName; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
        public boolean isEmailVerified() { return emailVerified; }
        public boolean isHasStatement() { return hasStatement; }
        public UUID getLatestStatementId() { return latestStatementId; } // ✅ was missing

        // Setters
        public void setId(UUID id) { this.id = id; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public void setEmail(String email) { this.email = email; }
        public void setRole(String role) { this.role = role; }
        public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
        public void setHasStatement(boolean hasStatement) { this.hasStatement = hasStatement; }
        public void setLatestStatementId(UUID latestStatementId) { this.latestStatementId = latestStatementId; }

        public static Builder builder() { return new Builder(); }

        public static class Builder {

            private final UserInfo userInfo;

            public Builder() { userInfo = new UserInfo(); }

            public Builder id(UUID id) {
                userInfo.id = id;
                return this;
            }

            public Builder fullName(String fullName) {
                userInfo.fullName = fullName;
                return this;
            }

            public Builder email(String email) {
                userInfo.email = email;
                return this;
            }

            public Builder role(String role) {
                userInfo.role = role;
                return this;
            }

            public Builder emailVerified(boolean emailVerified) {
                userInfo.emailVerified = emailVerified;
                return this;
            }

            public Builder hasStatement(boolean hasStatement) {
                userInfo.hasStatement = hasStatement;
                return this;
            }

            public Builder latestStatementId(UUID latestStatementId) {
                userInfo.latestStatementId = latestStatementId;
                return this;
            }

            public UserInfo build() { return userInfo; }
        }
    }
}