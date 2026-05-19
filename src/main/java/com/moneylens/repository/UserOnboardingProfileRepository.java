package com.moneylens.repository;

import com.moneylens.entity.User;
import com.moneylens.entity.UserOnboardingProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserOnboardingProfileRepository extends JpaRepository<UserOnboardingProfile, UUID> {

    Optional<UserOnboardingProfile> findByUser(User user);

    boolean existsByUser(User user);
}