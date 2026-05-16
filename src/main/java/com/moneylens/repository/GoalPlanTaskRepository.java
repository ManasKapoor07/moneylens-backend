package com.moneylens.repository;

import com.moneylens.entity.GoalPlanTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalPlanTaskRepository extends JpaRepository<GoalPlanTask, UUID> {

    List<GoalPlanTask> findByPlanIdOrderByWeekNumberAsc(UUID planId);

    /** Fetch the task for the current week of a plan. */
    Optional<GoalPlanTask> findByPlanIdAndWeekNumber(UUID planId, int weekNumber);

    /** All pending tasks whose week has started but not yet been checked in. */
    List<GoalPlanTask> findByPlanIdAndCheckinStatusAndWeekStartLessThanEqual(
            UUID planId, GoalPlanTask.CheckinStatus status, LocalDate today);
}