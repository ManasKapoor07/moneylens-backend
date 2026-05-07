package com.moneylens.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    @NotBlank @Email
    private String email;

    @NotBlank
    private String password;

    private String deviceInfo;

    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getDeviceInfo() { return deviceInfo; }
    public void setEmail(String v) { this.email = v; }
    public void setPassword(String v) { this.password = v; }
    public void setDeviceInfo(String v) { this.deviceInfo = v; }
}