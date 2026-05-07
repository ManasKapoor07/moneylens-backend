package com.moneylens.repository;

import com.moneylens.entity.RefreshToken;
import com.moneylens.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user = :user")
    void revokeAllUserTokens(User user);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.user = :user AND (r.revoked = true OR r.expiryDate < CURRENT_TIMESTAMP)")
    void deleteExpiredAndRevokedTokens(User user);
}
