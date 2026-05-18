package com.moneylens.repository;

import com.moneylens.entity.Chat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

    List<Chat> findByUserIdAndStatementIdOrderByUpdatedAtDesc(UUID userId, UUID statementId);

    List<Chat> findByUserIdOrderByUpdatedAtDesc(UUID userId);


}