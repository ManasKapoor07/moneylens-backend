package com.moneylens.controller;

import com.moneylens.dto.response.ClarificationDto;
import com.moneylens.entity.UserOnboardingProfile;
import com.moneylens.service.OnboardingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    // ── Phase 1: Onboarding profile ───────────────────────────────────────────

    @GetMapping("/onboarding/profile")
    public ResponseEntity<UserOnboardingProfile> getProfile(Authentication authentication) {
        UserOnboardingProfile profile = onboardingService.getProfile(authentication.getName());
        if (profile == null) {
            return ResponseEntity.noContent().build(); // 204 → show modal
        }
        return ResponseEntity.ok(profile);
    }

    @PostMapping("/onboarding/profile")
    public ResponseEntity<UserOnboardingProfile> saveProfile(
            Authentication authentication,
            @RequestBody OnboardingService.OnboardingRequest request
    ) {
        return ResponseEntity.ok(
                onboardingService.saveProfile(authentication.getName(), request));
    }

    @PostMapping("/onboarding/profile/skip")
    public ResponseEntity<Void> skipProfile(Authentication authentication) {
        onboardingService.skipProfile(authentication.getName());
        return ResponseEntity.ok().build();
    }

    // ── Phase 2: Clarification cards ─────────────────────────────────────────

    /**
     * Returns DTOs — no Hibernate proxies touch Jackson.
     */
    @GetMapping("/clarifications/pending")
    public ResponseEntity<List<ClarificationDto>> getPendingClarifications(
            Authentication authentication
    ) {
        List<ClarificationDto> cards = onboardingService
                .getPendingClarifications(authentication.getName())
                .stream()
                .map(ClarificationDto::from)
                .toList();
        return ResponseEntity.ok(cards);
    }

    @PostMapping("/clarifications/{id}/resolve")
    public ResponseEntity<ClarificationDto> resolveClarification(
            Authentication authentication,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body
    ) {
        String answer = body.get("answer");
        if (answer == null || answer.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(
                ClarificationDto.from(
                        onboardingService.resolveClarification(authentication.getName(), id, answer)
                ));
    }

    @PostMapping("/clarifications/{id}/skip")
    public ResponseEntity<Void> skipClarification(
            Authentication authentication,
            @PathVariable UUID id
    ) {
        onboardingService.skipClarification(authentication.getName(), id);
        return ResponseEntity.ok().build();
    }
}