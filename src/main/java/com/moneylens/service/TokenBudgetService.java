package com.moneylens.service;

import com.moneylens.entity.DailyTokenUsage;
import com.moneylens.entity.User;
import com.moneylens.exception.DailyLimitExceededException;
import com.moneylens.repository.DailyTokenUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class TokenBudgetService {

    // ── Per-role daily limits ─────────────────────────────────────────────────
    private static final int FREE_DAILY_LIMIT    = 20_000;
    private static final int PREMIUM_DAILY_LIMIT = 100_000;
    private static final int ADMIN_DAILY_LIMIT   = Integer.MAX_VALUE; // unlimited

    // Reserve headroom for one full turn before allowing the call.
    // A typical chat turn uses ~4,500–6,000 tokens total.
    private static final int TURN_RESERVATION = 6_000;

    private final DailyTokenUsageRepository usageRepo;

    public TokenBudgetService(DailyTokenUsageRepository usageRepo) {
        this.usageRepo = usageRepo;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHECK — call this BEFORE making any expensive OpenAI call
    // Throws DailyLimitExceededException if the user is at or near their limit.
    // ─────────────────────────────────────────────────────────────────────────
    public void checkBudget(UUID userId, User.Role role) {
        int limit = resolveLimit(role);
        if (limit == Integer.MAX_VALUE) return; // admins are unlimited

        int used = getUsedToday(userId);
        if (used + TURN_RESERVATION > limit) {
            throw new DailyLimitExceededException(limit, used);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RECORD — call this AFTER every OpenAI response with actual token count
    // Uses upsert: increment if row exists, insert if not.
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public void record(UUID userId, int tokensUsed) {
        if (tokensUsed <= 0) return;
        LocalDate today = LocalDate.now();

        int updated = usageRepo.incrementTokens(userId, today, tokensUsed);
        if (updated == 0) {
            // Row didn't exist yet for today — create it
            DailyTokenUsage row = new DailyTokenUsage();
            row.setUserId(userId);
            row.setUsageDate(today);
            row.setTokensUsed(tokensUsed);
            usageRepo.save(row);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────────────────
    public int getUsedToday(UUID userId) {
        return usageRepo
                .findByUserIdAndUsageDate(userId, LocalDate.now())
                .map(DailyTokenUsage::getTokensUsed)
                .orElse(0);
    }

    public int getRemainingToday(UUID userId, User.Role role) {
        int limit = resolveLimit(role);
        if (limit == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return Math.max(0, limit - getUsedToday(userId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE
    // ─────────────────────────────────────────────────────────────────────────
    private int resolveLimit(User.Role role) {
        return switch (role) {
            case PREMIUM -> PREMIUM_DAILY_LIMIT;
            case ADMIN   -> ADMIN_DAILY_LIMIT;
            case USER    -> FREE_DAILY_LIMIT;
        };
    }
}