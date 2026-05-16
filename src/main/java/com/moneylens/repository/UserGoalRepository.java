package com.moneylens.repository;

import com.moneylens.entity.UserGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserGoalRepository extends JpaRepository<UserGoal, UUID> {
    List<UserGoal> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, UserGoal.Status status);
    List<UserGoal> findByUserIdOrderByCreatedAtDesc(UUID userId);
}