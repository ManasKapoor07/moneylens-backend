package com.moneylens.controller;

import com.moneylens.dto.response.GoalDto;
import com.moneylens.entity.UserGoal;
import com.moneylens.repository.UserRepository;
import com.moneylens.service.GoalService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    private final GoalService    goalService;
    private final UserRepository userRepository;

    public GoalController(GoalService goalService, UserRepository userRepository) {
        this.goalService    = goalService;
        this.userRepository = userRepository;
    }

    private UUID resolveUserId(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"))
                .getId();
    }

    // GET /api/v1/goals — all goals
    @GetMapping
    public ResponseEntity<List<GoalDto>> getGoals(Authentication auth) {
        return ResponseEntity.ok(
                goalService.getAllGoals(resolveUserId(auth))
                        .stream().map(GoalDto::from).collect(Collectors.toList())
        );
    }

    // GET /api/v1/goals/active — active goals only
    @GetMapping("/active")
    public ResponseEntity<List<GoalDto>> getActiveGoals(Authentication auth) {
        return ResponseEntity.ok(
                goalService.getActiveGoals(resolveUserId(auth))
                        .stream().map(GoalDto::from).collect(Collectors.toList())
        );
    }

    // POST /api/v1/goals — create goal (manual or confirmed from suggestion card)
    @PostMapping
    public ResponseEntity<GoalDto> createGoal(
            Authentication auth,
            @RequestBody Map<String, Object> body
    ) {
        UUID userId = resolveUserId(auth);

        UUID statementId = body.get("statementId") != null
                ? UUID.fromString((String) body.get("statementId")) : null;
        String name = (String) body.get("name");
        BigDecimal targetAmount = body.get("targetAmount") != null
                ? new BigDecimal(body.get("targetAmount").toString()) : null;
        BigDecimal currentSaved = body.get("currentSaved") != null
                ? new BigDecimal(body.get("currentSaved").toString()) : null;
        LocalDate targetDate = body.get("targetDate") != null
                ? LocalDate.parse((String) body.get("targetDate")) : null;

        // source: "AI_EXTRACTED" when confirmed from suggestion card, else "MANUAL"
        UserGoal.Source source = "AI_EXTRACTED".equals(body.get("source"))
                ? UserGoal.Source.AI_EXTRACTED
                : UserGoal.Source.MANUAL;

        UserGoal goal = goalService.createGoal(
                userId, statementId, name,
                targetAmount, currentSaved,
                targetDate, source
        );
        return ResponseEntity.ok(GoalDto.from(goal));
    }

    // PATCH /api/v1/goals/{goalId} — update fields
    @PatchMapping("/{goalId}")
    public ResponseEntity<GoalDto> updateGoal(
            @PathVariable UUID goalId,
            @RequestBody Map<String, Object> body
    ) {
        String name = (String) body.get("name");
        BigDecimal targetAmount = body.get("targetAmount") != null
                ? new BigDecimal(body.get("targetAmount").toString()) : null;
        BigDecimal currentSaved = body.get("currentSaved") != null
                ? new BigDecimal(body.get("currentSaved").toString()) : null;
        LocalDate targetDate = body.get("targetDate") != null
                ? LocalDate.parse((String) body.get("targetDate")) : null;

        return ResponseEntity.ok(
                GoalDto.from(goalService.updateGoal(goalId, name, targetAmount, currentSaved, targetDate))
        );
    }

    // PATCH /api/v1/goals/{goalId}/saved — update only saved amount
    @PatchMapping("/{goalId}/saved")
    public ResponseEntity<GoalDto> updateSaved(
            @PathVariable UUID goalId,
            @RequestBody Map<String, Object> body
    ) {
        BigDecimal newSaved = new BigDecimal(body.get("currentSaved").toString());
        return ResponseEntity.ok(GoalDto.from(goalService.updateSaved(goalId, newSaved)));
    }

    // DELETE /api/v1/goals/{goalId} — cancel goal
    @DeleteMapping("/{goalId}")
    public ResponseEntity<Void> cancelGoal(@PathVariable UUID goalId) {
        goalService.cancelGoal(goalId);
        return ResponseEntity.noContent().build();
    }
}