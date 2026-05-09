package com.moneylens.service;

import com.moneylens.dto.response.StatementDetailDto;
import com.moneylens.dto.response.TransactionDto;
import com.moneylens.dto.response.TransactionInsightDto;
import com.moneylens.entity.Statement;
import com.moneylens.entity.User;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionInsightRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class StatementService {

    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionInsightRepository insightRepository;
    private final UserRepository userRepository;

    public StatementService(
            StatementRepository statementRepository,
            TransactionRepository transactionRepository,
            TransactionInsightRepository insightRepository,
            UserRepository userRepository
    ) {
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
        this.insightRepository = insightRepository;
        this.userRepository = userRepository;
    }

    /**
     * Returns all statements (with transactions + insights) belonging to the user.
     */
    @Transactional(readOnly = true)
    public List<StatementDetailDto> getAllForUser(String email) {
        User user = findUser(email);
        return statementRepository
                .findByUserOrderByCreatedAtDesc(user)   // matches existing repo method
                .stream()
                .map(this::toDetail)
                .toList();
    }

    /**
     * Returns a single statement with transactions + insights.
     * Throws 404 if not found, 403 if it belongs to a different user.
     */
    @Transactional(readOnly = true)
    public StatementDetailDto getOneForUser(UUID statementId, String email) {
        User user = findUser(email);

        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Statement not found"));

        if (!statement.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return toDetail(statement);
    }

    // ─────────────────────────────────────────────────────────────────

    private StatementDetailDto toDetail(Statement statement) {
        List<TransactionDto> transactions = transactionRepository
                .findByStatementOrderByDateDesc(statement)
                .stream()
                .map(TransactionDto::from)
                .toList();

        List<TransactionInsightDto> insights = insightRepository
                .findByStatementOrderByCreatedAtAsc(statement)  // matches existing repo method
                .stream()
                .map(TransactionInsightDto::from)
                .toList();

        return StatementDetailDto.from(statement, transactions, insights);
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));
    }
}