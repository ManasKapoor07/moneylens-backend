package com.moneylens.dto.response;

import java.util.UUID;

public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private UserInfo user;

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public String getTokenType() { return tokenType; }
    public UserInfo getUser() { return user; }
    public void setAccessToken(String v) { this.accessToken = v; }
    public void setRefreshToken(String v) { this.refreshToken = v; }
    public void setTokenType(String v) { this.tokenType = v; }
    public void setUser(UserInfo v) { this.user = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final AuthResponse r = new AuthResponse();
        public Builder accessToken(String v) { r.accessToken = v; return this; }
        public Builder refreshToken(String v) { r.refreshToken = v; return this; }
        public Builder user(UserInfo v) { r.user = v; return this; }
        public AuthResponse build() { return r; }
    }

    public static class UserInfo {
        private UUID id;
        private String fullName;
        private String email;
        private String role;
        private boolean emailVerified;
        private boolean hasStatement;

        public UUID getId() { return id; }
        public String getFullName() { return fullName; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
        public boolean isEmailVerified() { return emailVerified; }
        public boolean isHasStatement() { return hasStatement; }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private final UserInfo u = new UserInfo();
            public Builder id(UUID v) { u.id = v; return this; }
            public Builder fullName(String v) { u.fullName = v; return this; }
            public Builder email(String v) { u.email = v; return this; }
            public Builder role(String v) { u.role = v; return this; }
            public Builder emailVerified(boolean v) { u.emailVerified = v; return this; }
            public Builder hasStatement(boolean v) { u.hasStatement = v; return this; }
            public UserInfo build() { return u; }
        }
    }
}