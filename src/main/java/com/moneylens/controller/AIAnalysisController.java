package com.moneylens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.response.AIAnalysisResponse;
import com.moneylens.entity.User;
import com.moneylens.entity.UserFinancialProfile;
import com.moneylens.repository.UserFinancialProfileRepository;
import com.moneylens.repository.UserRepository;
import com.moneylens.service.FinancialAIAnalysisService;
import com.moneylens.service.UserProfileAggregatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for AI-powered financial analysis.
 *
 * Endpoints:
 *   GET  /api/ai/analysis               — Get (or generate) the merged user-level analysis
 *   POST /api/ai/analysis/refresh       — Force-rebuild the user profile + re-run AI
 *   GET  /api/ai/analysis/{statementId} — Per-statement drill-down analysis
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AIAnalysisController {

    private final FinancialAIAnalysisService     aiAnalysisService;
    private final UserFinancialProfileRepository userFinancialProfileRepository;
    private final UserProfileAggregatorService   userProfileAggregatorService;
    private final UserRepository                 userRepository;
    private final ObjectMapper                   objectMapper;

    public AIAnalysisController(
            FinancialAIAnalysisService aiAnalysisService,
            UserFinancialProfileRepository userFinancialProfileRepository,
            UserProfileAggregatorService userProfileAggregatorService,
            UserRepository userRepository,
            ObjectMapper objectMapper
    ) {
        this.aiAnalysisService             = aiAnalysisService;
        this.userFinancialProfileRepository = userFinancialProfileRepository;
        this.userProfileAggregatorService  = userProfileAggregatorService;
        this.userRepository                = userRepository;
        this.objectMapper                  = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/ai/analysis
    //
    // Serves cached analysisJson from UserFinancialProfile if available.
    // Falls back to a synchronous recompute if no profile exists yet.
    // Returns 204 if the user has no completed statements at all.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/analysis")
    public ResponseEntity<AIAnalysisResponse> getUserAnalysis(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        UUID userId = user.getId();

        UserFinancialProfile profile = userFinancialProfileRepository
                .findByUserId(userId)
                .orElse(null);

        if (profile != null && profile.getAnalysisJson() != null) {
            return ResponseEntity.ok(parseAnalysis(profile.getAnalysisJson()));
        }

        UserFinancialProfile fresh = userProfileAggregatorService
                .recompute(userId, UserProfileAggregatorService.REASON_MANUAL_REFRESH);

        if (fresh == null || fresh.getAnalysisJson() == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(parseAnalysis(fresh.getAnalysisJson()));
    }

    @PostMapping("/analysis/refresh")
    public ResponseEntity<AIAnalysisResponse> refreshAnalysis(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        UserFinancialProfile fresh = userProfileAggregatorService
                .recompute(user.getId(), UserProfileAggregatorService.REASON_MANUAL_REFRESH);

        if (fresh == null || fresh.getAnalysisJson() == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(parseAnalysis(fresh.getAnalysisJson()));
    }

    @GetMapping("/analysis/{statementId}")
    public ResponseEntity<AIAnalysisResponse> getStatementAnalysis(
            @PathVariable UUID statementId,
            Authentication authentication
    ) {
        AIAnalysisResponse analysis = aiAnalysisService.analyze(statementId);
        return ResponseEntity.ok(analysis);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER
    // ─────────────────────────────────────────────────────────────────────────

    private AIAnalysisResponse parseAnalysis(String analysisJson) {
        try {
            return objectMapper.readValue(analysisJson, AIAnalysisResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse cached analysis JSON", e);
        }
    }
}