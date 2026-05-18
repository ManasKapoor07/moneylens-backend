package com.moneylens.repository;

import com.moneylens.entity.BehavioralSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BehavioralSignalRepository extends JpaRepository<BehavioralSignal, UUID> {

    /** All fired signals for a statement — used by narrative layer. */
    List<BehavioralSignal> findByStatementIdAndFiredTrueOrderBySeverityDesc(UUID statementId);

    /** All signals (fired + not) for a statement — used for longitudinal tracking. */
    List<BehavioralSignal> findByStatementIdOrderBySignalTypeAsc(UUID statementId);

    /** All fired signals for a user across all statements — longitudinal feed. */
    List<BehavioralSignal> findByUserIdAndFiredTrueOrderByCreatedAtDesc(UUID userId);

    /** User-level signals only (statementId is null). */
    @Query("SELECT s FROM BehavioralSignal s WHERE s.userId = :userId AND s.statementId IS NULL AND s.fired = true ORDER BY s.severity DESC")
    List<BehavioralSignal> findUserLevelFiredSignals(@Param("userId") UUID userId);

    /** How many times a specific signal has fired for this user — for longitudinal drift. */
    @Query("SELECT COUNT(s) FROM BehavioralSignal s WHERE s.userId = :userId AND s.signalType = :type AND s.fired = true")
    long countFiredByUserAndType(
            @Param("userId") UUID userId,
            @Param("type") BehavioralSignal.SignalType type
    );

    /** Delete all signals for a statement before re-computing (idempotent recompute). */
    @Modifying
    @Query("DELETE FROM BehavioralSignal s WHERE s.statementId = :statementId")
    void deleteByStatementId(@Param("statementId") UUID statementId);

    /** Delete all user-level signals (null statementId) before re-computing. */
    @Modifying
    @Query("DELETE FROM BehavioralSignal s WHERE s.userId = :userId AND s.statementId IS NULL")
    void deleteUserLevelSignals(@Param("userId") UUID userId);
}