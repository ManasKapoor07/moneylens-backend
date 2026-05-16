package com.moneylens.repository;

import com.moneylens.entity.DailyTokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DailyTokenUsageRepository extends JpaRepository<DailyTokenUsage, UUID> {

    Optional<DailyTokenUsage> findByUserIdAndUsageDate(UUID userId, LocalDate date);

    @Modifying
    @Query("""
        UPDATE DailyTokenUsage d
        SET d.tokensUsed = d.tokensUsed + :tokens
        WHERE d.userId = :userId AND d.usageDate = :date
    """)
    int incrementTokens(@Param("userId") UUID userId,
                        @Param("date") LocalDate date,
                        @Param("tokens") int tokens);
}