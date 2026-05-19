package com.moneylens.controller;

import com.moneylens.dto.request.FeedbackRequest;
import com.moneylens.dto.response.FeedbackAnalytics;
import com.moneylens.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/feedback")
@CrossOrigin(
        origins = {
                "http://localhost:3000",
                "http://13.62.30.38"
        }
)
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    /**
     * POST /api/feedback
     * Submit one feedback response.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submit(
            @Valid @RequestBody FeedbackRequest req,
            HttpServletRequest httpReq
    ) {

        String ip = resolveClientIp(httpReq);

        UUID id = service.submit(req, ip);

        return ResponseEntity
                .created(URI.create("/api/feedback/" + id))
                .body(
                        Map.of(
                                "id",
                                id.toString()
                        )
                );
    }

    /**
     * GET /api/feedback/analytics
     */
    @GetMapping("/analytics")
    public ResponseEntity<FeedbackAnalytics> analytics() {

        return ResponseEntity.ok(
                service.getAnalytics()
        );
    }

    // ─────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────

    private String resolveClientIp(
            HttpServletRequest req
    ) {

        String forwarded =
                req.getHeader("X-Forwarded-For");

        if (
                forwarded != null &&
                        !forwarded.isBlank()
        ) {

            return forwarded
                    .split(",")[0]
                    .trim();
        }

        return req.getRemoteAddr();
    }
}