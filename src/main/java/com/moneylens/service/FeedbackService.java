package com.moneylens.service;

import com.moneylens.dto.response.FeedbackAnalytics;
import com.moneylens.dto.request.FeedbackRequest;
import com.moneylens.entity.Feedback;
import com.moneylens.repository.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final FeedbackRepository repo;

    public FeedbackService(FeedbackRepository repo) {
        this.repo = repo;
    }

    // ── Submit ────────────────────────────────────────────────────────────────
    @Transactional
    public UUID submit(FeedbackRequest req, String clientIp) {
        Feedback entity = Feedback.builder()
                .firstImpression(req.getFirstImpression())
                .accurateInsight(req.getAccurateInsight())
                .personalization(req.getPersonalization())
                .wantedInsights(req.getWantedInsights())
                .describeToFriend(req.getDescribeToFriend())
                .holyShitInsight(req.getHolyShitInsight())
                .contactEmail(req.isContactOk() ? req.getContactEmail() : null)
                .contactOk(req.isContactOk())
                .ipHash(hashIp(clientIp))
                .build();

        Feedback saved = repo.save(entity);
        log.info("Feedback saved id={}", saved.getId());
        return saved.getId();
    }

    // ── Analytics ─────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public FeedbackAnalytics getAnalytics() {

        Map<String, Long> personalizationMap = repo.countByPersonalization().stream()
                .collect(Collectors.toMap(
                        r -> r.getLabel().name(),
                        FeedbackRepository.PersonalizationCount::getCnt,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        Map<String, Long> insightsMap = repo.countWantedInsights().stream()
                .collect(Collectors.toMap(
                        FeedbackRepository.InsightCount::getTag,
                        FeedbackRepository.InsightCount::getCnt,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        return FeedbackAnalytics.builder()
                .totalResponses(repo.count())
                .personalizationBreakdown(personalizationMap)
                .topWantedInsights(insightsMap)
                .contactOptIns(repo.countByContactOkTrue())
                .recentDescriptions(repo.findRecentDescriptions())
                .recentHolyShit(repo.findRecentHolyShit())
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String hashIp(String ip) {
        if (ip == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 not available, skipping IP hash");
            return null;
        }
    }
}