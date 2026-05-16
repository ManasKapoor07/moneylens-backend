package com.moneylens.controller;

import com.moneylens.dto.response.GoalPlanDto;
import com.moneylens.entity.GoalPlanTask;
import com.moneylens.repository.UserRepository;
import com.moneylens.service.GoalPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/plans")
public class GoalPlanController {

    private final GoalPlanService goalPlanService;
    private final UserRepository  userRepository;

    public GoalPlanController(GoalPlanService goalPlanService,
                              UserRepository userRepository) {
        this.goalPlanService = goalPlanService;
        this.userRepository  = userRepository;
    }

    private UUID resolveUserId(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"))
                .getId();
    }

    // GET /api/v1/plans — all plans for the current user
    @GetMapping
    public ResponseEntity<List<GoalPlanDto>> getPlans(Authentication auth) {
        return ResponseEntity.ok(goalPlanService.getPlansForUser(resolveUserId(auth)));
    }

    // GET /api/v1/plans/{planId} — single plan with all weeks
    @GetMapping("/{planId}")
    public ResponseEntity<GoalPlanDto> getPlan(@PathVariable UUID planId) {
        return ResponseEntity.ok(goalPlanService.getPlan(planId));
    }

    // GET /api/v1/plans/goal/{goalId}/active — active plan for a specific goal
    @GetMapping("/goal/{goalId}/active")
    public ResponseEntity<GoalPlanDto> getActivePlanForGoal(@PathVariable UUID goalId) {
        return goalPlanService.getActivePlanForGoal(goalId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/plans/{planId}/checkin
     *
     * Body:
     * {
     *   "taskId": "uuid",
     *   "savedAmount": 2000,
     *   "note": "Skipped Swiggy twice this week",
     *   "status": "DONE"    // or "SKIPPED"
     * }
     */
    @PostMapping("/{planId}/checkin")
    public ResponseEntity<GoalPlanDto> checkIn(
            @PathVariable UUID planId,
            @RequestBody Map<String, Object> body
    ) {
        UUID    taskId      = UUID.fromString((String) body.get("taskId"));
        BigDecimal saved    = body.get("savedAmount") != null
                ? new BigDecimal(body.get("savedAmount").toString()) : null;
        String  note        = (String) body.get("note");
        GoalPlanTask.CheckinStatus status = "SKIPPED".equalsIgnoreCase((String) body.get("status"))
                ? GoalPlanTask.CheckinStatus.SKIPPED
                : GoalPlanTask.CheckinStatus.DONE;

        return ResponseEntity.ok(goalPlanService.checkIn(planId, taskId, saved, note, status));
    }

    // DELETE /api/v1/plans/{planId} — abandon a plan
    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> abandonPlan(@PathVariable UUID planId) {
        goalPlanService.abandonPlan(planId);
        return ResponseEntity.noContent().build();
    }
}