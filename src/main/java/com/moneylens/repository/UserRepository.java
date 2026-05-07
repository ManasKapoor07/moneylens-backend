package com.moneylens.repository;

import com.moneylens.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // ===== EMAIL LOOKUPS =====

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // ===== TOKEN LOOKUPS (expiry-aware) =====

    @Query("SELECT u FROM User u WHERE u.emailVerificationToken = :token AND u.emailVerificationTokenExpiry > :now")
    Optional<User> findByValidEmailVerificationToken(
            @Param("token") String token,
            @Param("now") LocalDateTime now
    );

    @Query("SELECT u FROM User u WHERE u.passwordResetToken = :token AND u.passwordResetTokenExpiry > :now")
    Optional<User> findByValidPasswordResetToken(
            @Param("token") String token,
            @Param("now") LocalDateTime now
    );

    // ===== UPDATES =====

    @Modifying
    @Query("UPDATE User u SET u.lastLogin = :lastLogin WHERE u.id = :id")
    void updateLastLogin(
            @Param("id") UUID id,
            @Param("lastLogin") LocalDateTime lastLogin
    );
}