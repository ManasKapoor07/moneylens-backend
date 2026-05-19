package com.moneylens.service;

import com.moneylens.entity.TransactionClarification;
import com.moneylens.entity.User;
import com.moneylens.entity.UserFinancialProfile;
import com.moneylens.entity.UserOnboardingProfile;
import com.moneylens.repository.TransactionClarificationRepository;
import com.moneylens.repository.UserFinancialProfileRepository;
import com.moneylens.repository.UserOnboardingProfileRepository;
import com.moneylens.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private final UserRepository                     userRepository;
    private final UserOnboardingProfileRepository    onboardingRepository;
    private final TransactionClarificationRepository clarificationRepository;
    private final UserFinancialProfileRepository     financialProfileRepository;

    public OnboardingService(
            UserRepository userRepository,
            UserOnboardingProfileRepository onboardingRepository,
            TransactionClarificationRepository clarificationRepository,
            UserFinancialProfileRepository financialProfileRepository
    ) {
        this.userRepository            = userRepository;
        this.onboardingRepository      = onboardingRepository;
        this.clarificationRepository   = clarificationRepository;
        this.financialProfileRepository = financialProfileRepository;
    }

    // ── Phase 1: Onboarding profile ───────────────────────────────────────────

    public UserOnboardingProfile getProfile(String email) {
        User user = resolveUser(email);
        return onboardingRepository.findByUser(user).orElse(null);
    }

    @Transactional
    public UserOnboardingProfile saveProfile(String email, OnboardingRequest request) {
        User user = resolveUser(email);

        UserOnboardingProfile profile = onboardingRepository
                .findByUser(user)
                .orElseGet(() -> UserOnboardingProfile.builder().user(user).build());

        profile.setPrimaryGoal(request.primaryGoal());
        profile.setEmploymentType(request.employmentType());
        profile.setDependents(request.dependents());
        profile.setCityTier(request.cityTier());
        profile.setIncomeRange(request.incomeRange());
        profile.setSkipped(false);
        profile.setCompletedAt(LocalDateTime.now());

        UserOnboardingProfile saved = onboardingRepository.save(profile);

        // Onboarding answers enrich AI context — mark analysis stale
        markAnalysisStale(user, "ONBOARDING_COMPLETED");

        return saved;
    }

    @Transactional
    public void skipProfile(String email) {
        User user = resolveUser(email);

        UserOnboardingProfile profile = onboardingRepository
                .findByUser(user)
                .orElseGet(() -> UserOnboardingProfile.builder().user(user).build());

        profile.setSkipped(true);
        profile.setCompletedAt(LocalDateTime.now());
        onboardingRepository.save(profile);
        // Skip doesn't enrich context — no stale mark needed
    }

    // ── Phase 2: Clarification cards ─────────────────────────────────────────

    public List<TransactionClarification> getPendingClarifications(String email) {
        User user = resolveUser(email);
        return clarificationRepository.findByUserAndStatusOrderByCreatedAtAsc(
                user, TransactionClarification.Status.PENDING);
    }

    @Transactional
    public TransactionClarification resolveClarification(String email, UUID clarificationId, String answer) {
        User user = resolveUser(email);

        TransactionClarification card = clarificationRepository.findById(clarificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clarification not found"));

        if (!card.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        card.setSelectedAnswer(answer);
        card.setStatus(TransactionClarification.Status.RESOLVED);
        card.setResolvedAt(LocalDateTime.now());
        clarificationRepository.save(card);

        // Resolved clarification enriches AI context — mark stale so next
        // explicit refresh picks it up. No eager regeneration (avoids race conditions).
        markAnalysisStale(user, "CLARIFICATION_RESOLVED");

        return card;
    }

    @Transactional
    public void skipClarification(String email, UUID clarificationId) {
        User user = resolveUser(email);

        TransactionClarification card = clarificationRepository.findById(clarificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clarification not found"));

        if (!card.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        card.setStatus(TransactionClarification.Status.SKIPPED);
        card.setResolvedAt(LocalDateTime.now());
        clarificationRepository.save(card);
        // Skipped card adds no new info — no stale mark needed
    }

    // ── Stale marker ──────────────────────────────────────────────────────────

    /**
     * Marks the user's aggregated financial profile as stale.
     * Safe to call multiple times — idempotent.
     * Does nothing if no profile exists yet (upload hasn't happened).
     */
    private void markAnalysisStale(User user, String reason) {
        financialProfileRepository.findByUser(user).ifPresent(profile -> {
            if (!profile.isAnalysisStale()) { // skip if already stale
                profile.markStale();
                financialProfileRepository.save(profile);
                log.info("AI analysis marked stale for user: {} — reason: {}", user.getId(), reason);
            }
        });
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private User resolveUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    public record OnboardingRequest(
            UserOnboardingProfile.PrimaryGoal    primaryGoal,
            UserOnboardingProfile.EmploymentType employmentType,
            UserOnboardingProfile.Dependents     dependents,
            UserOnboardingProfile.CityTier       cityTier,
            UserOnboardingProfile.IncomeRange    incomeRange
    ) {}
}