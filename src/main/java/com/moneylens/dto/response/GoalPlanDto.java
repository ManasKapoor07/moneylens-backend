package com.moneylens.dto.response;

import com.moneylens.entity.GoalPlan;
import com.moneylens.entity.GoalPlanTask;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class GoalPlanDto {

    public UUID   id;
    public UUID   goalId;
    public String goalName;
    public String summary;
    public int    totalWeeks;
    public BigDecimal weeklySavingTarget;
    public String status;
    public LocalDate startDate;
    public LocalDate endDate;
    public int    progressPct;
    public List<WeekDto> weeks;
    public LocalDateTime createdAt;

    public static class WeekDto {
        public UUID   id;
        public int    weekNumber;
        public LocalDate weekStart;
        public LocalDate weekEnd;
        public BigDecimal savingTarget;
        public BigDecimal savedAmount;
        public List<String> actions;   // parsed from stored comma-separated string
        public String tip;
        public String checkinStatus;
        public String checkinNote;
        public LocalDateTime checkedInAt;
        public boolean isCurrent;      // is this week active right now?

        public static WeekDto from(GoalPlanTask t) {
            WeekDto d = new WeekDto();
            d.id            = t.getId();
            d.weekNumber    = t.getWeekNumber();
            d.weekStart     = t.getWeekStart();
            d.weekEnd       = t.getWeekEnd();
            d.savingTarget  = t.getSavingTarget();
            d.savedAmount   = t.getSavedAmount();
            d.tip           = t.getTip();
            d.checkinStatus = t.getCheckinStatus().name();
            d.checkinNote   = t.getCheckinNote();
            d.checkedInAt   = t.getCheckedInAt();
            d.isCurrent     = !LocalDate.now().isBefore(t.getWeekStart())
                    && !LocalDate.now().isAfter(t.getWeekEnd());
            // Actions stored as newline-delimited string → split to list
            d.actions = t.getActions() != null
                    ? Arrays.stream(t.getActions().split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList())
                    : List.of();
            return d;
        }
    }

    public static GoalPlanDto from(GoalPlan plan) {
        GoalPlanDto d = new GoalPlanDto();
        d.id                 = plan.getId();
        d.goalId             = plan.getGoal().getId();
        d.goalName           = plan.getGoal().getName();
        d.summary            = plan.getSummary();
        d.totalWeeks         = plan.getTotalWeeks();
        d.weeklySavingTarget = plan.getWeeklySavingTarget();
        d.status             = plan.getStatus().name();
        d.startDate          = plan.getStartDate();
        d.endDate            = plan.getEndDate();
        d.progressPct        = plan.getProgressPct();
        d.createdAt          = plan.getCreatedAt();
        d.weeks = plan.getTasks().stream()
                .map(WeekDto::from)
                .collect(Collectors.toList());
        return d;
    }
}