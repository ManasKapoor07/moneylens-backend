package com.moneylens.service;

import com.moneylens.dto.request.*;
import com.moneylens.dto.response.AuthResponse;
import com.moneylens.entity.RefreshToken;
import com.moneylens.entity.Statement;
import com.moneylens.entity.User;
import com.moneylens.exception.*;
import com.moneylens.repository.RefreshTokenRepository;
import com.moneylens.repository.StatementRepository;
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

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final StatementRepository statementRepository;
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
            StatementRepository statementRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService,
            EmailService emailService
    ) {
        this.statementRepository = statementRepository;
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

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtUtil.generateAccessToken(userDetails);
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
            throw new InvalidCredentialsException("Invalid email or password");
        }

        User user = userRepository
                .findByEmail(request.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (!user.isActive()) {
            throw new AccountDisabledException(
                    "Your account has been disabled. Please contact support."
            );
        }

        userRepository.updateLastLogin(user.getId(), LocalDateTime.now());
        refreshTokenRepository.deleteExpiredAndRevokedTokens(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
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
                        new InvalidTokenException("Refresh token not found or already used")
                );

        if (!storedToken.isValid()) {
            refreshTokenRepository.revokeAllUserTokens(storedToken.getUser());
            throw new InvalidTokenException(
                    "Refresh token expired or revoked. Please login again."
            );
        }

        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        User user = storedToken.getUser();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String newAccessToken = jwtUtil.generateAccessToken(userDetails);
        String newRefreshToken = createRefreshToken(user, storedToken.getDeviceInfo());

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
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        refreshTokenRepository.revokeAllUserTokens(user);
        log.info("All sessions revoked for: {}", userEmail);
    }

    // ===== EMAIL VERIFICATION =====

    @Transactional
    public void verifyEmail(String token) {
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
                    user.setPasswordResetTokenExpiry(LocalDateTime.now().plusHours(1));
                    userRepository.save(user);
                    emailService.sendPasswordResetEmail(
                            user.getEmail(),
                            user.getFullName(),
                            resetToken
                    );
                    log.info("Password reset requested for: {}", user.getEmail());
                });
    }

    @Transactional
    public void resetPassword(PasswordRequest.ResetPassword request) {
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
        refreshTokenRepository.revokeAllUserTokens(user);
        log.info("Password reset successful for: {}", user.getEmail());
    }

    @Transactional
    public void changePassword(String userEmail, PasswordRequest.ChangePassword request) {
        User user = userRepository
                .findByEmail(userEmail)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        refreshTokenRepository.revokeAllUserTokens(user);
        log.info("Password changed for: {}", userEmail);
    }

    // ===== HELPERS =====

    private String createRefreshToken(User user, String deviceInfo) {
        RefreshToken refreshToken = RefreshToken.builder()
                .token(UUID.randomUUID().toString())
                .user(user)
                .expiryDate(LocalDateTime.now().plusSeconds(refreshExpirationMs / 1000))
                .deviceInfo(deviceInfo)
                .build();
        return refreshTokenRepository.save(refreshToken).getToken();
    }

    private AuthResponse buildAuthResponse(String accessToken, String refreshToken, User user) {

        // ✅ Fixed: only fetch COMPLETED statements (not any statement)
        UUID latestStatementId = statementRepository
                .findTopByUserAndStatusOrderByPeriodToDesc(user, Statement.Status.COMPLETED)
                .map(Statement::getId)
                .orElse(null);

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
                                .hasStatement(user.getHasStatement())
                                .latestStatementId(latestStatementId) // ✅ now serialized via getter
                                .build()
                )
                .build();
    }
}