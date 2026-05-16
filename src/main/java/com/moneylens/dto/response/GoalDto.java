package com.moneylens.dto.response;

import com.moneylens.entity.UserGoal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class GoalDto {

    private UUID id;
    private String name;
    private BigDecimal targetAmount;
    private BigDecimal currentSaved;
    private BigDecimal monthlyContribution;
    private LocalDate targetDate;
    private long monthsLeft;
    private double progressPercent;
    private String status;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static GoalDto from(UserGoal g) {
        GoalDto dto = new GoalDto();
        dto.id                  = g.getId();
        dto.name                = g.getName();
        dto.targetAmount        = g.getTargetAmount();
        dto.currentSaved        = g.getCurrentSaved();
        dto.monthlyContribution = g.getMonthlyContribution();
        dto.targetDate          = g.getTargetDate();
        dto.status              = g.getStatus().name();
        dto.source              = g.getSource().name();
        dto.createdAt           = g.getCreatedAt();
        dto.updatedAt           = g.getUpdatedAt();

        dto.monthsLeft = g.getTargetDate() != null
                ? Math.max(0, ChronoUnit.MONTHS.between(LocalDate.now(), g.getTargetDate()))
                : -1;

        if (g.getTargetAmount() != null && g.getTargetAmount().compareTo(BigDecimal.ZERO) > 0
                && g.getCurrentSaved() != null) {
            dto.progressPercent = g.getCurrentSaved()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(g.getTargetAmount(), 1, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
        }

        return dto;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public BigDecimal getCurrentSaved() { return currentSaved; }
    public BigDecimal getMonthlyContribution() { return monthlyContribution; }
    public LocalDate getTargetDate() { return targetDate; }
    public long getMonthsLeft() { return monthsLeft; }
    public double getProgressPercent() { return progressPercent; }
    public String getStatus() { return status; }
    public String getSource() { return source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}