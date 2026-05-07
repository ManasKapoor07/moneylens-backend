package com.moneylens.controller;

import com.moneylens.dto.request.*;
import com.moneylens.dto.response.ApiResponse;
import com.moneylens.dto.response.AuthResponse;
import com.moneylens.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /api/v1/auth/register
     * Register a new user. Returns JWT tokens on success.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful", response));
    }

    /**
     * POST /api/v1/auth/login
     * Authenticate and return JWT + refresh token.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    /**
     * POST /api/v1/auth/refresh
     * Exchange a valid refresh token for a new access token.
     * Implements refresh token rotation — old token is immediately invalidated.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", response));
    }

    /**
     * POST /api/v1/auth/logout
     * Revoke the current refresh token (single device logout).
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully"));
    }

    /**
     * POST /api/v1/auth/logout-all
     * Revoke all refresh tokens for the authenticated user (all devices).
     */
    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(
            @AuthenticationPrincipal UserDetails userDetails) {
        authService.logoutAll(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Logged out from all devices"));
    }

    /**
     * GET /api/v1/auth/verify-email?token=xxx
     * Verify email address using token from verification email.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(ApiResponse.success("Email verified successfully. You can now login."));
    }

    /**
     * POST /api/v1/auth/forgot-password
     * Trigger password reset email. Always returns 200 to prevent email enumeration.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody PasswordRequest.ForgotPassword request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.success(
            "If this email is registered, you'll receive a password reset link shortly."));
    }

    /**
     * POST /api/v1/auth/reset-password
     * Reset password using token from email.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody PasswordRequest.ResetPassword request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success(
            "Password reset successful. Please login with your new password."));
    }

    /**
     * POST /api/v1/auth/change-password  [PROTECTED]
     * Change password for the authenticated user.
     */
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PasswordRequest.ChangePassword request) {
        authService.changePassword(userDetails.getUsername(), request);
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully."));
    }

    /**
     * GET /api/v1/auth/me  [PROTECTED]
     * Quick health check — returns 200 if the JWT is valid.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<String>> me(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.success(
            "Token is valid", userDetails.getUsername()));
    }
}
