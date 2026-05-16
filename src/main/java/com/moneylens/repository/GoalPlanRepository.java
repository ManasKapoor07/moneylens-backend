package com.moneylens.repository;

import com.moneylens.entity.GoalPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalPlanRepository extends JpaRepository<GoalPlan, UUID> {

    List<GoalPlan> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<GoalPlan> findByGoalIdOrderByCreatedAtDesc(UUID goalId);

    /** Returns the single active plan for a goal, if one exists. */
    Optional<GoalPlan> findByGoalIdAndStatus(UUID goalId, GoalPlan.Status status);

    /** All active plans for a user (used for check-in reminders). */
    List<GoalPlan> findByUserIdAndStatus(UUID userId, GoalPlan.Status status);
}