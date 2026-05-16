package com.moneylens.service;

import com.moneylens.dto.response.DashboardSummaryDto;
import com.moneylens.dto.response.DashboardSummaryDto.MonthlyOverviewItem;
import com.moneylens.dto.response.TransactionDto;
import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.User;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final int RECENT_TX_LIMIT = 5;

    private final UserRepository userRepository;
    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;

    public DashboardService(
            UserRepository userRepository,
            StatementRepository statementRepository,
            TransactionRepository transactionRepository
    ) {
        this.userRepository = userRepository;
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
    }

    public DashboardSummaryDto buildSummary(
            UUID statementId,
            String email
    ) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: " + email
                        ));

        // ─────────────────────────────────────────────
        // COMPLETED STATEMENTS ONLY
        // ─────────────────────────────────────────────

        List<Statement> completedStatements =
                statementRepository
                        .findByUserAndStatusOrderByPeriodToDesc(
                                user,
                                Statement.Status.COMPLETED
                        )
                        .stream()
                        .filter(s ->
                                s.getPeriodFrom() != null
                                        && s.getPeriodTo() != null
                        )
                        .collect(Collectors.toList());

        if (completedStatements.isEmpty()) {
            return emptyDashboard();
        }

        // ─────────────────────────────────────────────
        // SELECTED STATEMENT
        // ─────────────────────────────────────────────

        Statement selected = statementRepository.findById(statementId)
                .orElseThrow(() ->
                        new RuntimeException("Statement not found"));

        if (!selected.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        if (selected.getStatus() != Statement.Status.COMPLETED) {
            throw new RuntimeException(
                    "Statement processing not completed"
            );
        }

        // ─────────────────────────────────────────────
        // CURRENT STATEMENT TRANSACTIONS
        // ─────────────────────────────────────────────

        List<Transaction> currentTx =
                transactionRepository.findByStatement(selected);

        // ─────────────────────────────────────────────
        // HISTORICAL TRANSACTIONS
        // ONLY FOR CHARTS/TRENDS
        // ─────────────────────────────────────────────

        List<Transaction> historicalTx =
                completedStatements.stream()
                        .flatMap(s ->
                                transactionRepository
                                        .findByStatement(s)
                                        .stream()
                        )
                        .collect(Collectors.toList());

        // ─────────────────────────────────────────────
        // TOTAL INCOME
        // ─────────────────────────────────────────────

        BigDecimal totalIncome = currentTx.stream()
                .filter(t ->
                        t.getType() == Transaction.Type.CREDIT
                )
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ─────────────────────────────────────────────
        // TOTAL SPENDING
        // ─────────────────────────────────────────────

        BigDecimal totalSpending = currentTx.stream()
                .filter(t ->
                        t.getType() == Transaction.Type.DEBIT
                )
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ─────────────────────────────────────────────
        // BALANCE
        // ─────────────────────────────────────────────

        BigDecimal totalBalance =
                selected.getClosingBalance() != null
                        ? selected.getClosingBalance()
                        : totalIncome.subtract(totalSpending);

        // ─────────────────────────────────────────────
        // SAVINGS
        // ─────────────────────────────────────────────

        BigDecimal savings;

        if (
                selected.getClosingBalance() != null
                        && selected.getOpeningBalance() != null
        ) {

            savings = selected
                    .getClosingBalance()
                    .subtract(selected.getOpeningBalance());

        } else {

            savings = totalIncome.subtract(totalSpending);
        }

        // ─────────────────────────────────────────────
        // PREVIOUS STATEMENT
        // ─────────────────────────────────────────────

        Statement previous = completedStatements.stream()
                .filter(s ->
                        !s.getId().equals(selected.getId())
                )
                .findFirst()
                .orElse(null);

        // ─────────────────────────────────────────────
        // PREVIOUS TRANSACTIONS
        // ─────────────────────────────────────────────

        List<Transaction> previousTx =
                previous == null
                        ? List.of()
                        : transactionRepository.findByStatement(previous);

        // ─────────────────────────────────────────────
        // CURRENT TOTALS
        // ─────────────────────────────────────────────

        BigDecimal currentIncome = currentTx.stream()
                .filter(t ->
                        t.getType() == Transaction.Type.CREDIT
                )
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentSpending = currentTx.stream()
                .filter(t ->
                        t.getType() == Transaction.Type.DEBIT
                )
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentNet =
                currentIncome.subtract(currentSpending);

        // ─────────────────────────────────────────────
        // PREVIOUS TOTALS
        // ─────────────────────────────────────────────

        BigDecimal previousIncome = previousTx.stream()
                .filter(t ->
                        t.getType() == Transaction.Type.CREDIT
                )
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal previousSpending = previousTx.stream()
                .filter(t ->
                        t.getType() == Transaction.Type.DEBIT
                )
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal previousNet =
                previousIncome.subtract(previousSpending);

        // ─────────────────────────────────────────────
        // CHANGE PERCENTAGES
        // ─────────────────────────────────────────────

        double spendingChangePct =
                calculatePercentageChange(
                        currentSpending,
                        previousSpending
                );

        double incomeChangePct =
                calculatePercentageChange(
                        currentIncome,
                        previousIncome
                );

        double balanceChangePct =
                calculatePercentageChange(
                        currentNet,
                        previousNet
                );

        // ─────────────────────────────────────────────
        // MONTHLY OVERVIEW
        // ─────────────────────────────────────────────

        List<MonthlyOverviewItem> monthlyOverview =
                buildMonthlyOverview(historicalTx);

        // ─────────────────────────────────────────────
        // SPENDING BY CATEGORY
        // ─────────────────────────────────────────────

        Map<String, BigDecimal> spendingByCategory =
                currentTx.stream()
                        .filter(t ->
                                t.getType()
                                        == Transaction.Type.DEBIT
                                        && t.getCategory() != null
                                        && !t.getCategory().isBlank()
                        )
                        .collect(Collectors.groupingBy(
                                Transaction::getCategory,
                                Collectors.reducing(
                                        BigDecimal.ZERO,
                                        Transaction::getAmount,
                                        BigDecimal::add
                                )
                        ));

        // ─────────────────────────────────────────────
        // RECENT TRANSACTIONS
        // ─────────────────────────────────────────────

        List<TransactionDto> recentTransactions =
                currentTx.stream()
                        .sorted(
                                Comparator.comparing(
                                        Transaction::getDate
                                ).reversed()
                        )
                        .limit(RECENT_TX_LIMIT)
                        .map(TransactionDto::from)
                        .collect(Collectors.toList());

        return DashboardSummaryDto.builder()
                .totalBalance(totalBalance)
                .totalSpending(totalSpending)
                .totalIncome(totalIncome)
                .savings(savings)
                .balanceChangePercent(balanceChangePct)
                .spendingChangePercent(spendingChangePct)
                .incomeChangePercent(incomeChangePct)
                .monthlyOverview(monthlyOverview)
                .spendingByCategory(spendingByCategory)
                .recentTransactions(recentTransactions)
                .build();
    }

    // ─────────────────────────────────────────────
    // PERCENTAGE CHANGE
    // ─────────────────────────────────────────────

    private double calculatePercentageChange(
            BigDecimal current,
            BigDecimal previous
    ) {

        if (
                previous == null
                        || previous.compareTo(BigDecimal.ZERO) == 0
        ) {
            return 0.0;
        }

        return current
                .subtract(previous)
                .divide(
                        previous.abs(),
                        4,
                        RoundingMode.HALF_UP
                )
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    // ─────────────────────────────────────────────
    // MONTHLY OVERVIEW
    // ─────────────────────────────────────────────

    private List<MonthlyOverviewItem> buildMonthlyOverview(
            List<Transaction> txList
    ) {

        Map<String, BigDecimal> debitByMonth =
                new TreeMap<>();

        Map<String, BigDecimal> creditByMonth =
                new TreeMap<>();

        for (Transaction tx : txList) {

            String key =
                    tx.getDate().getYear()
                            + "-"
                            + String.format(
                            "%02d",
                            tx.getDate().getMonthValue()
                    );

            if (tx.getType() == Transaction.Type.DEBIT) {

                debitByMonth.merge(
                        key,
                        tx.getAmount(),
                        BigDecimal::add
                );

            } else {

                creditByMonth.merge(
                        key,
                        tx.getAmount(),
                        BigDecimal::add
                );
            }
        }

        Set<String> allMonths = new TreeSet<>();

        allMonths.addAll(debitByMonth.keySet());
        allMonths.addAll(creditByMonth.keySet());

        List<MonthlyOverviewItem> result =
                new ArrayList<>();

        for (String key : allMonths) {

            String[] parts = key.split("-");

            Month month = Month.of(
                    Integer.parseInt(parts[1])
            );

            String label =
                    month.getDisplayName(
                            TextStyle.SHORT,
                            Locale.ENGLISH
                    )
                            + " "
                            + parts[0];

            result.add(
                    new MonthlyOverviewItem(
                            label,
                            debitByMonth.getOrDefault(
                                    key,
                                    BigDecimal.ZERO
                            ),
                            creditByMonth.getOrDefault(
                                    key,
                                    BigDecimal.ZERO
                            )
                    )
            );
        }

        return result;
    }

    // ─────────────────────────────────────────────
    // EMPTY DASHBOARD
    // ─────────────────────────────────────────────

    private DashboardSummaryDto emptyDashboard() {

        return DashboardSummaryDto.builder()
                .totalBalance(BigDecimal.ZERO)
                .totalSpending(BigDecimal.ZERO)
                .totalIncome(BigDecimal.ZERO)
                .savings(BigDecimal.ZERO)
                .balanceChangePercent(0.0)
                .spendingChangePercent(0.0)
                .incomeChangePercent(0.0)
                .monthlyOverview(List.of())
                .spendingByCategory(Map.of())
                .recentTransactions(List.of())
                .build();
    }
}