package com.moneylens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.entity.User;
import com.moneylens.entity.UserFinancialProfile;
import com.moneylens.repository.UserFinancialProfileRepository;
import com.moneylens.repository.UserRepository;
import com.moneylens.service.UserProfileAggregatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AIAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AIAnalysisController.class);

    private final UserRepository                 userRepository;
    private final UserFinancialProfileRepository financialProfileRepository;
    private final UserProfileAggregatorService   aggregatorService;
    private final ObjectMapper                   objectMapper;

    public AIAnalysisController(
            UserRepository userRepository,
            UserFinancialProfileRepository financialProfileRepository,
            UserProfileAggregatorService aggregatorService,
            ObjectMapper objectMapper
    ) {
        this.userRepository            = userRepository;
        this.financialProfileRepository = financialProfileRepository;
        this.aggregatorService         = aggregatorService;
        this.objectMapper              = objectMapper;
    }

    /**
     * GET /api/v1/ai/analysis
     *
     * Returns the stored analysisJson plus a top-level `isStale` flag.
     * Frontend uses isStale to show a soft "Your insights may be outdated — Refresh" nudge.
     *
     * Response shape:
     * {
     *   "isStale": true,
     *   "analysis": { ...AIAnalysisResponse fields... }
     * }
     */
    @GetMapping("/analysis")
    public ResponseEntity<Map<String, Object>> getAnalysis(Authentication authentication) {
        User user = resolveUser(authentication.getName());

        UserFinancialProfile profile = financialProfileRepository.findByUser(user)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No analysis available yet — upload a statement first"));

        if (profile.getAnalysisJson() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Analysis not yet generated for this profile");
        }

        try {
            Object analysisObj = objectMapper.readValue(profile.getAnalysisJson(), Object.class);
            return ResponseEntity.ok(Map.of(
                    "isStale",  profile.isAnalysisStale(),
                    "analysis", analysisObj
            ));
        } catch (Exception e) {
            log.error("Failed to deserialize analysisJson for user: {}", user.getId(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Analysis data corrupted");
        }
    }

    /**
     * POST /api/v1/ai/analysis/refresh
     *
     * Triggers a synchronous rebuild of the user's aggregated profile.
     * Clears the stale flag on completion (handled inside recordRebuild()).
     *
     * Only does work if the profile is actually stale — idempotent if already fresh.
     */
    @PostMapping("/analysis/refresh")
    public ResponseEntity<Map<String, Object>> refreshAnalysis(Authentication authentication) {
        User user = resolveUser(authentication.getName());

        UserFinancialProfile profile = financialProfileRepository.findByUser(user).orElse(null);

        if (profile != null && !profile.isAnalysisStale()) {
            // Already fresh — return current analysis without recomputing
            log.info("Analysis refresh requested but already fresh for user: {}", user.getId());
            try {
                Object analysisObj = objectMapper.readValue(profile.getAnalysisJson(), Object.class);
                return ResponseEntity.ok(Map.of(
                        "isStale",  false,
                        "analysis", analysisObj
                ));
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Analysis data corrupted");
            }
        }

        // Stale — rebuild synchronously so response contains fresh data
        log.info("Rebuilding stale analysis for user: {}", user.getId());
        aggregatorService.recompute(user.getId(), "MANUAL_REFRESH");

        // Reload after rebuild
        UserFinancialProfile refreshed = financialProfileRepository.findByUser(user)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Profile missing after rebuild"));

        try {
            Object analysisObj = objectMapper.readValue(refreshed.getAnalysisJson(), Object.class);
            return ResponseEntity.ok(Map.of(
                    "isStale",  refreshed.isAnalysisStale(), // should be false after rebuild
                    "analysis", analysisObj
            ));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Analysis data corrupted");
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private User resolveUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}