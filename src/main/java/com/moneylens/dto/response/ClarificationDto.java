package com.moneylens.dto.response;

import com.moneylens.entity.TransactionClarification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the frontend actually needs — no Hibernate proxies, no lazy fields.
 * Controller returns this; entity stays internal.
 */
public record ClarificationDto(
        UUID            id,
        UUID            transactionId,
        String          clarificationType,
        String          questionText,
        List<String>    options,
        String          selectedAnswer,
        String          status,
        LocalDateTime   createdAt,
        LocalDateTime   resolvedAt
) {
    public static ClarificationDto from(TransactionClarification c) {
        return new ClarificationDto(
                c.getId(),
                c.getTransactionId(),
                c.getClarificationType().name(),
                c.getQuestionText(),
                c.getOptions(),
                c.getSelectedAnswer(),
                c.getStatus().name(),
                c.getCreatedAt(),
                c.getResolvedAt()
        );
    }
}