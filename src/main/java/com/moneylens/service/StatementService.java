package com.moneylens.service;

import com.moneylens.dto.response.*;
import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.User;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserRepository;
import com.moneylens.util.MerchantResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
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

    // ── Statement list & detail ───────────────────────────────────

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

    public List<StatementIdWithBankDto> getIdsWithBankForUser(String email) {
        User user = resolveUser(email);
        return statementRepository.findIdsWithBankByUser(user);
    }

    // ── User-level paginated transactions (all statements) ────────

    public PagedTransactionResponse getAllTransactionsPaged(
            String email,
            String type,
            String category,
            Pageable pageable
    ) {
        User user = resolveUser(email);

        Page<Transaction> page;
        if (type != null && category != null) {
            Transaction.Type txType = Transaction.Type.valueOf(type.toUpperCase());
            page = transactionRepository.findByUserAndTypeAndCategory(
                    user.getId(), txType, category, pageable);
        } else if (type != null) {
            Transaction.Type txType = Transaction.Type.valueOf(type.toUpperCase());
            page = transactionRepository.findByUserAndType(
                    user.getId(), txType, pageable);
        } else if (category != null) {
            page = transactionRepository.findByUserAndCategory(
                    user.getId(), category, pageable);
        } else {
            page = transactionRepository.findByUserId(user.getId(), pageable);
        }

        return PagedTransactionResponse.from(page.map(TransactionDto::from));
    }

    // ── User-level weekly spend (all statements combined) ─────────

    public List<WeeklySpendDto> getAllWeeklySpend(String email) {
        User user = resolveUser(email);

        List<Transaction> txs =
                transactionRepository.findCanonicalTimelineByUserId(user.getId());
        if (txs.isEmpty()) return List.of();

        LocalDate periodFrom = txs.stream()
                .map(Transaction::getDate).min(LocalDate::compareTo).orElse(null);
        LocalDate periodTo = txs.stream()
                .map(Transaction::getDate).max(LocalDate::compareTo).orElse(null);
        if (periodFrom == null || periodTo == null) return List.of();

        int totalDays = (int) ChronoUnit.DAYS.between(periodFrom, periodTo) + 1;
        int numWeeks  = (int) Math.ceil(totalDays / 7.0);

        BigDecimal[] weekDebit  = new BigDecimal[numWeeks];
        BigDecimal[] weekCredit = new BigDecimal[numWeeks];
        int[]        weekCount  = new int[numWeeks];
        Arrays.fill(weekDebit,  BigDecimal.ZERO);
        Arrays.fill(weekCredit, BigDecimal.ZERO);

        for (Transaction tx : txs) {
            int dayOffset = (int) ChronoUnit.DAYS.between(periodFrom, tx.getDate());
            if (dayOffset < 0) continue;
            int wk = Math.min(dayOffset / 7, numWeeks - 1);
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
                    ? periodTo : wkStart.plusDays(6);
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

    // ── User-level recurring charges (all statements combined) ────

    public List<RecurringChargeDto> getAllRecurringCharges(String email) {
        User user = resolveUser(email);

        List<Transaction> allTransactions =
                transactionRepository.findCanonicalTimelineByUserId(user.getId());
        if (allTransactions.isEmpty()) return List.of();

        LocalDate periodFrom = allTransactions.stream()
                .map(Transaction::getDate).min(LocalDate::compareTo).orElse(null);
        LocalDate periodTo = allTransactions.stream()
                .map(Transaction::getDate).max(LocalDate::compareTo).orElse(null);

        int periodDays = (periodFrom != null && periodTo != null)
                ? (int) ChronoUnit.DAYS.between(periodFrom, periodTo) + 1
                : 30;

        Map<String, List<Transaction>> grouped = new HashMap<>();
        for (Transaction tx : allTransactions) {
            if (tx.getType() != Transaction.Type.DEBIT) continue;
            String merchant = MerchantResolver.resolve(tx.getDescription());
            grouped.computeIfAbsent(merchant, k -> new ArrayList<>()).add(tx);
        }

        List<RecurringChargeDto> results = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            List<Transaction> txs = entry.getValue();
            if (txs.size() < 2) continue;

            List<BigDecimal> amounts = txs.stream()
                    .map(Transaction::getAmount)
                    .collect(Collectors.toList());

            BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(
                    BigDecimal.valueOf(amounts.size()), 4, RoundingMode.HALF_UP);
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

            String rawDescription = txs.stream()
                    .max(Comparator.comparing(Transaction::getDate))
                    .map(Transaction::getDescription)
                    .orElse("");

            String category = txs.get(0).getCategory();

            results.add(RecurringChargeDto.from(
                    entry.getKey(), rawDescription, amounts, dates, category, periodDays
            ));
        }

        results.sort(Comparator.comparing(RecurringChargeDto::getTotalSpent).reversed());
        return results;
    }

    // ── Exception types ───────────────────────────────────────────

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    public class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }

    // ── Private helpers ───────────────────────────────────────────

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
}