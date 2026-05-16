package com.moneylens.service;

import com.moneylens.dto.response.GoalPlanDto;
import com.moneylens.entity.GoalPlan;
import com.moneylens.entity.UserGoal;
import com.moneylens.exception.PlanDurationExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class PlanCreationHelper {

    private static final Logger log = LoggerFactory.getLogger(PlanCreationHelper.class);

    private final GoalService     goalService;
    private final GoalPlanService goalPlanService;

    public PlanCreationHelper(GoalService goalService, GoalPlanService goalPlanService) {
        this.goalService     = goalService;
        this.goalPlanService = goalPlanService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GoalPlanDto tryCreate(UUID userId, UUID statementId,
                                 String goalName, boolean weekly,
                                 String financialContext) {
        List<UserGoal> active = goalService.getActiveGoals(userId);

        UserGoal match = active.stream()
                .filter(g -> g.getName() != null &&
                        g.getName().equalsIgnoreCase(goalName.trim()))
                .findFirst()
                .orElse(null);

        if (match == null) {
            match = active.stream()
                    .filter(g -> g.getName() != null &&
                            (g.getName().toLowerCase().contains(goalName.toLowerCase()) ||
                                    goalName.toLowerCase().contains(g.getName().toLowerCase())))
                    .findFirst()
                    .orElse(null);
        }

        if (match == null) return null;

        GoalPlan plan = goalPlanService.generateAndSavePlan(match, userId, financialContext, weekly);
        return GoalPlanDto.from(plan);
    }
}