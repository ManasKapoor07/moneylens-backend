package com.moneylens.service;
import com.moneylens.dto.response.*;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.User;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;
 import com.moneylens.dto.response.RecurringChargeDto;
 import com.moneylens.util.MerchantResolver;
 import java.math.BigDecimal;
 import java.math.RoundingMode;
 import java.time.LocalDate;
 import java.time.temporal.ChronoUnit;
 import java.util.*;
 import java.util.stream.Collectors;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StatementService {

    private final StatementRepository   statementRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository        userRepository;

    public StatementService(
            StatementRepository statementRepository,
            TransactionRepository transactionRepository,
            UserRepository userRepository
    ) {
        this.statementRepository   = statementRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository        = userRepository;
    }

    // ── Statement list & detail (no transactions embedded) ───────

    public List<StatementDetailDto> getAllForUser(String email) {
        User user = resolveUser(email);
        return statementRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(s -> StatementDetailDto.from(s, List.of(), List.of()))
                .collect(Collectors.toList());
    }

    public StatementDetailDto getOneForUser(UUID id, String email) {
        Statement statement = resolveStatement(id, email);
        return StatementDetailDto.from(statement, List.of(), List.of());
    }

    // ── Paginated transactions ────────────────────────────────────

    /**
     * Returns one page of transactions for the given statement.
     *
     * @param id        statement UUID
     * @param email     authenticated user's email (ownership check)
     * @param type      optional filter — "DEBIT" or "CREDIT" (null = no filter)
     * @param category  optional filter by category name (null = no filter)
     * @param pageable  page + size + sort from the controller
     */

    public PagedTransactionResponse getTransactionsPaged(
            UUID id,
            String email,
            String type,
            String category,
            Pageable pageable
    ) {
        Statement statement = resolveStatement(id, email);

        Page<Transaction> page;

        if (type != null && category != null) {
            Transaction.Type txType = Transaction.Type.valueOf(type.toUpperCase());
            page = transactionRepository
                    .findByStatementAndTypeAndCategory(statement, txType, category, pageable);

        } else if (type != null) {
            Transaction.Type txType = Transaction.Type.valueOf(type.toUpperCase());
            page = transactionRepository
                    .findByStatementAndType(statement, txType, pageable);

        } else if (category != null) {
            page = transactionRepository
                    .findByStatementAndCategory(statement, category, pageable);

        } else {
            page = transactionRepository
                    .findByStatement(statement, pageable);
        }

        Page<TransactionDto> dtoPage = page.map(TransactionDto::from);
        return PagedTransactionResponse.from(dtoPage);
    }

    // ── Private helpers ───────────────────────────────────────────
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    public class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    private User resolveUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found: " + email));
    }

    private Statement resolveStatement(UUID id, String email) {
        Statement statement = statementRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Statement not found: " + id));

        if (!statement.getUser().getEmail().equals(email)) {
            throw new ForbiddenException("Access denied to statement: " + id);
        }
        return statement;
    }

    public List<StatementIdWithBankDto> getIdsWithBankForUser(String email) {
        User user = resolveUser(email);
        return statementRepository.findIdsWithBankByUser(user);
    }

    public List<RecurringChargeDto> getRecurringCharges(UUID id, String email) {

        Statement statement = resolveStatement(id, email);

        List<Transaction> allTransactions = transactionRepository.findByStatement(statement);

        // Period length — used to extrapolate monthly/annual estimates
        int periodDays = (statement.getPeriodFrom() != null && statement.getPeriodTo() != null)
                ? (int) ChronoUnit.DAYS.between(statement.getPeriodFrom(), statement.getPeriodTo()) + 1
                : 30;

        // Group debits by resolved merchant name
        // Key: merchant name  →  Value: all transactions for that merchant
        Map<String, List<Transaction>> grouped = new HashMap<>();

        for (Transaction tx : allTransactions) {
            if (tx.getType() != Transaction.Type.DEBIT) continue;

            String merchant = MerchantResolver.resolve(tx.getDescription());
            grouped.computeIfAbsent(merchant, k -> new ArrayList<>()).add(tx);
        }

        List<RecurringChargeDto> results = new ArrayList<>();

        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            List<Transaction> txs = entry.getValue();

            // Need at least 2 hits to be "recurring"
            if (txs.size() < 2) continue;

            List<BigDecimal> amounts = txs.stream()
                    .map(Transaction::getAmount)
                    .collect(Collectors.toList());

            // Variance check — skip if amounts are too inconsistent
            BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(amounts.size()), 4, RoundingMode.HALF_UP);

            if (avg.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal max = amounts.stream().max(BigDecimal::compareTo).orElse(avg);
            BigDecimal min = amounts.stream().min(BigDecimal::compareTo).orElse(avg);
            int variancePct = max.subtract(min)
                    .divide(avg, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .intValue();

            if (variancePct > 20) continue;

            List<LocalDate> dates = txs.stream()
                    .map(Transaction::getDate)
                    .collect(Collectors.toList());

            // Use the most recent transaction's description as the raw reference
            String rawDescription = txs.stream()
                    .max(Comparator.comparing(Transaction::getDate))
                    .map(Transaction::getDescription)
                    .orElse("");

            String category = txs.get(0).getCategory();

            results.add(RecurringChargeDto.from(
                    entry.getKey(),
                    rawDescription,
                    amounts,
                    dates,
                    category,
                    periodDays
            ));
        }

        // Sort by total spend descending
        results.sort(Comparator.comparing(RecurringChargeDto::getTotalSpent).reversed());

        return results;
    }


    public List<WeeklySpendDto> getWeeklySpend(UUID id, String email) {

        Statement statement = resolveStatement(id, email);

        LocalDate periodFrom = statement.getPeriodFrom();
        LocalDate periodTo   = statement.getPeriodTo();

        if (periodFrom == null || periodTo == null) return List.of();

        List<Transaction> txs = transactionRepository
                .findAllByStatementIdOrdered(id);

        // Build week buckets anchored to periodFrom
        // Week 1 = days 0–6, Week 2 = days 7–13, …
        int totalDays = (int) ChronoUnit.DAYS.between(periodFrom, periodTo) + 1;
        int numWeeks  = (int) Math.ceil(totalDays / 7.0);

        // weekDebit[i], weekCredit[i], weekCount[i]
        BigDecimal[] weekDebit  = new BigDecimal[numWeeks];
        BigDecimal[] weekCredit = new BigDecimal[numWeeks];
        int[]        weekCount  = new int[numWeeks];

        Arrays.fill(weekDebit,  BigDecimal.ZERO);
        Arrays.fill(weekCredit, BigDecimal.ZERO);

        for (Transaction tx : txs) {
            int dayOffset = (int) ChronoUnit.DAYS.between(periodFrom, tx.getDate());
            if (dayOffset < 0) continue;                    // before period — skip
            int wk = Math.min(dayOffset / 7, numWeeks - 1); // clamp to last bucket

            if (tx.getType() == Transaction.Type.DEBIT) {
                weekDebit[wk] = weekDebit[wk].add(tx.getAmount());
                weekCount[wk]++;
            } else {
                weekCredit[wk] = weekCredit[wk].add(tx.getAmount());
            }
        }

        List<WeeklySpendDto> result = new ArrayList<>();
        for (int i = 0; i < numWeeks; i++) {
            LocalDate wkStart = periodFrom.plusDays((long) i * 7);
            LocalDate wkEnd   = wkStart.plusDays(6).isAfter(periodTo)
                    ? periodTo
                    : wkStart.plusDays(6);

            result.add(new WeeklySpendDto(
                    "Week " + (i + 1),
                    wkStart.toString(),
                    wkEnd.toString(),
                    weekDebit[i].setScale(2, RoundingMode.HALF_UP),
                    weekCredit[i].setScale(2, RoundingMode.HALF_UP),
                    weekCount[i]
            ));
        }

        return result;
    }
}