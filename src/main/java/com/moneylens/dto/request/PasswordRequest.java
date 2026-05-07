package com.moneylens.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PasswordRequest {

    public static class ForgotPassword {
        @NotBlank @Email
        private String email;
        public String getEmail() { return email; }
        public void setEmail(String v) { this.email = v; }
    }

    public static class ResetPassword {
        @NotBlank
        private String token;
        @NotBlank @Size(min = 8)
        private String newPassword;
        public String getToken() { return token; }
        public String getNewPassword() { return newPassword; }
        public void setToken(String v) { this.token = v; }
        public void setNewPassword(String v) { this.newPassword = v; }
    }

    public static class ChangePassword {
        @NotBlank
        private String currentPassword;
        @NotBlank @Size(min = 8)
        private String newPassword;
        public String getCurrentPassword() { return currentPassword; }
        public String getNewPassword() { return newPassword; }
        public void setCurrentPassword(String v) { this.currentPassword = v; }
        public void setNewPassword(String v) { this.newPassword = v; }
    }
}