package com.moneylens.controller;

import com.moneylens.dto.response.ChatResponse;
import com.moneylens.dto.response.ChatResponse.SuggestedGoal;
import com.moneylens.repository.UserRepository;
import com.moneylens.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final UserRepository userRepository;

    public ChatController(ChatService chatService, UserRepository userRepository) {
        this.chatService = chatService;
        this.userRepository = userRepository;
    }

    private UUID resolveUserId(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"))
                .getId();
    }

    /**
     * POST /api/v1/chat/send
     * Body: { statementId, chatId (optional), message }
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(
            Authentication auth,
            @RequestBody Map<String, Object> body
    ) {
        String message = (String) body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        UUID userId      = resolveUserId(auth);
        UUID statementId = UUID.fromString((String) body.get("statementId"));
        UUID chatId      = body.get("chatId") != null
                ? UUID.fromString((String) body.get("chatId"))
                : null;

        ChatResponse response = chatService.chat(userId, statementId, chatId, message);
        return ResponseEntity.ok(Map.of("data", response));
    }

    /**
     * POST /api/v1/chat/{chatId}/confirm-goal
     * Body: { name, targetAmount, currentSaved, targetDate }
     */
    @PostMapping("/{chatId}/confirm-goal")
    public ResponseEntity<Map<String, Object>> confirmGoal(
            Authentication auth,
            @PathVariable UUID chatId,
            @RequestBody ConfirmGoalRequest body
    ) {
        UUID userId = resolveUserId(auth);
        ChatResponse response = chatService.confirmGoalAndOfferPlan(
                userId, chatId, body.toSuggestedGoal());
        return ResponseEntity.ok(Map.of("data", response));
    }

    /**
     * GET /api/v1/chat/{chatId}/history
     */
    @GetMapping("/{chatId}/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @PathVariable UUID chatId
    ) {
        return ResponseEntity.ok(Map.of("data", chatService.getHistory(chatId)));
    }

    /**
     * GET /api/v1/chat/list?statementId=...
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listChats(
            Authentication auth,
            @RequestParam UUID statementId
    ) {
        UUID userId = resolveUserId(auth);
        return ResponseEntity.ok(Map.of("data", chatService.listChats(userId, statementId)));
    }

    // ── Inner DTO ──────────────────────────────────────────────────────────

    public static class ConfirmGoalRequest {
        public String name;
        public java.math.BigDecimal targetAmount;
        public java.math.BigDecimal currentSaved;
        public java.time.LocalDate targetDate;

        public SuggestedGoal toSuggestedGoal() {
            return new SuggestedGoal(name, targetAmount, currentSaved, targetDate);
        }
    }
}