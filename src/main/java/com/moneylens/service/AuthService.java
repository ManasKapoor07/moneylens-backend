package com.moneylens.service;

import com.moneylens.dto.request.*;
import com.moneylens.dto.response.AuthResponse;
import com.moneylens.entity.RefreshToken;
import com.moneylens.entity.User;
import com.moneylens.exception.*;
import com.moneylens.repository.RefreshTokenRepository;
import com.moneylens.repository.UserRepository;
import com.moneylens.security.JwtUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final EmailService emailService;

    @Value("${app.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    @Value("${app.email-verification.enabled:false}")
    private boolean emailVerificationEnabled;

    @Value("${app.email-verification.token-expiry-hours:24}")
    private int emailVerificationTokenExpiryHours;

    // ===== CONSTRUCTOR =====

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService,
            EmailService emailService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.emailService = emailService;
    }

    // ===== REGISTER =====

    @Transactional
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException(
                    "Email is already registered: " + request.getEmail()
            );
        }

        User user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail().toLowerCase().trim())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.USER)
                // If verification is enabled, mark as unverified
                .emailVerified(!emailVerificationEnabled)
                .build();

        if (emailVerificationEnabled) {

            String verificationToken = UUID.randomUUID().toString();

            user.setEmailVerificationToken(verificationToken);

            user.setEmailVerificationTokenExpiry(
                    LocalDateTime.now().plusHours(emailVerificationTokenExpiryHours)
            );

            emailService.sendVerificationEmail(
                    user.getEmail(),
                    user.getFullName(),
                    verificationToken
            );
        }

        user = userRepository.save(user);

        log.info("New user registered: {}", user.getEmail());

        UserDetails userDetails =
                userDetailsService.loadUserByUsername(user.getEmail());

        String accessToken = jwtUtil.generateAccessToken(userDetails);

        // Note: tokens are issued even if email is unverified.
        // Access to sensitive endpoints should be gated by emailVerified check
        // in the security layer or per-endpoint guards.
        String refreshToken = createRefreshToken(user, null);

        return buildAuthResponse(accessToken, refreshToken, user);
    }

    // ===== LOGIN =====

    @Transactional
    public AuthResponse login(LoginRequest request) {

        try {

            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail().toLowerCase().trim(),
                            request.getPassword()
                    )
            );

        } catch (BadCredentialsException e) {

            throw new InvalidCredentialsException(
                    "Invalid email or password"
            );
        }

        User user = userRepository
                .findByEmail(request.getEmail().toLowerCase().trim())
                .orElseThrow(() ->
                        new UserNotFoundException("User not found")
                );

        if (!user.isActive()) {
            throw new AccountDisabledException(
                    "Your account has been disabled. Please contact support."
            );
        }

        userRepository.updateLastLogin(user.getId(), LocalDateTime.now());

        refreshTokenRepository.deleteExpiredAndRevokedTokens(user);

        UserDetails userDetails =
                userDetailsService.loadUserByUsername(user.getEmail());

        String accessToken = jwtUtil.generateAccessToken(userDetails);

        String refreshToken = createRefreshToken(user, request.getDeviceInfo());

        log.info("User logged in: {}", user.getEmail());

        return buildAuthResponse(accessToken, refreshToken, user);
    }

    // ===== REFRESH TOKEN =====

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {

        RefreshToken storedToken = refreshTokenRepository
                .findByToken(request.getRefreshToken())
                .orElseThrow(() ->
                        new InvalidTokenException(
                                "Refresh token not found or already used"
                        )
                );

        if (!storedToken.isValid()) {

            // Revoke all tokens for this user as a security measure
            // (possible token theft / replay attack)
            refreshTokenRepository.revokeAllUserTokens(storedToken.getUser());

            throw new InvalidTokenException(
                    "Refresh token expired or revoked. Please login again."
            );
        }

        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        User user = storedToken.getUser();

        UserDetails userDetails =
                userDetailsService.loadUserByUsername(user.getEmail());

        String newAccessToken = jwtUtil.generateAccessToken(userDetails);

        String newRefreshToken = createRefreshToken(
                user,
                storedToken.getDeviceInfo()
        );

        return buildAuthResponse(newAccessToken, newRefreshToken, user);
    }

    // ===== LOGOUT =====

    @Transactional
    public void logout(String refreshToken) {

        refreshTokenRepository
                .findByToken(refreshToken)
                .ifPresent(token -> {

                    token.setRevoked(true);
                    refreshTokenRepository.save(token);

                    log.info("User logged out: {}", token.getUser().getEmail());
                });
    }

    @Transactional
    public void logoutAll(String userEmail) {

        User user = userRepository
                .findByEmail(userEmail)
                .orElseThrow(() ->
                        new UserNotFoundException("User not found")
                );

        refreshTokenRepository.revokeAllUserTokens(user);

        log.info("All sessions revoked for: {}", userEmail);
    }

    // ===== EMAIL VERIFICATION =====

    @Transactional
    public void verifyEmail(String token) {

        // Expiry check is now handled inside the query itself
        User user = userRepository
                .findByValidEmailVerificationToken(token, LocalDateTime.now())
                .orElseThrow(() ->
                        new InvalidTokenException(
                                "Invalid or expired verification token. Please request a new one."
                        )
                );

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);

        userRepository.save(user);

        log.info("Email verified for: {}", user.getEmail());
    }

    // ===== PASSWORD RESET =====

    @Transactional
    public void forgotPassword(PasswordRequest.ForgotPassword request) {

        userRepository
                .findByEmail(request.getEmail().toLowerCase().trim())
                .ifPresent(user -> {

                    String resetToken = UUID.randomUUID().toString();

                    user.setPasswordResetToken(resetToken);
                    user.setPasswordResetTokenExpiry(
                            LocalDateTime.now().plusHours(1)
                    );

                    userRepository.save(user);

                    emailService.sendPasswordResetEmail(
                            user.getEmail(),
                            user.getFullName(),
                            resetToken
                    );

                    log.info("Password reset requested for: {}", user.getEmail());
                });

        // No else/log here intentionally — avoids email enumeration.
        // Add a DEBUG-level log here if internal observability is needed.
    }

    @Transactional
    public void resetPassword(PasswordRequest.ResetPassword request) {

        // Expiry check is now handled inside the query itself
        User user = userRepository
                .findByValidPasswordResetToken(request.getToken(), LocalDateTime.now())
                .orElseThrow(() ->
                        new InvalidTokenException(
                                "Invalid or expired reset token. Please request a new one."
                        )
                );

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiry(null);

        userRepository.save(user);

        // Revoke all sessions after password reset
        refreshTokenRepository.revokeAllUserTokens(user);

        log.info("Password reset successful for: {}", user.getEmail());
    }

    @Transactional
    public void changePassword(
            String userEmail,
            PasswordRequest.ChangePassword request
    ) {

        User user = userRepository
                .findByEmail(userEmail)
                .orElseThrow(() ->
                        new UserNotFoundException("User not found")
                );

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));

        userRepository.save(user);

        // Revoke all sessions after password change so other devices
        // are forced to re-authenticate with the new password
        refreshTokenRepository.revokeAllUserTokens(user);

        log.info("Password changed for: {}", userEmail);
    }

    // ===== HELPERS =====

    private String createRefreshToken(User user, String deviceInfo) {

        RefreshToken refreshToken = RefreshToken.builder()
                .token(UUID.randomUUID().toString())
                .user(user)
                .expiryDate(
                        LocalDateTime.now()
                                .plusSeconds(refreshExpirationMs / 1000)
                )
                .deviceInfo(deviceInfo)
                .build();

        return refreshTokenRepository.save(refreshToken).getToken();
    }

    private AuthResponse buildAuthResponse(
            String accessToken,
            String refreshToken,
            User user
    ) {

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(
                        AuthResponse.UserInfo.builder()
                                .id(user.getId())
                                .fullName(user.getFullName())
                                .email(user.getEmail())
                                .role(user.getRole().name())
                                .emailVerified(user.isEmailVerified())
                                .build()
                )
                .build();
    }
}