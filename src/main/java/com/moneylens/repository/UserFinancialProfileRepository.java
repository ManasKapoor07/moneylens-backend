package com.moneylens.repository;

import com.moneylens.entity.UserFinancialProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserFinancialProfileRepository extends JpaRepository<UserFinancialProfile, UUID> {

    /**
     * Primary lookup — find by user ID.
     * Used by DashboardService, ChatService, and FinancialAIAnalysisService.
     */
    @Query("SELECT p FROM UserFinancialProfile p WHERE p.user.id = :userId")
    Optional<UserFinancialProfile> findByUserId(@Param("userId") UUID userId);

    /**
     * Exists check — used before deciding whether to INSERT or UPDATE.
     */
    @Query("SELECT COUNT(p) > 0 FROM UserFinancialProfile p WHERE p.user.id = :userId")
    boolean existsByUserId(@Param("userId") UUID userId);

    /**
     * Delete by user — used if a user wants to wipe all data.
     */
    @Query("DELETE FROM UserFinancialProfile p WHERE p.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}