package com.moneylens.service;

import com.moneylens.entity.Statement;
import com.moneylens.entity.User;
import com.moneylens.repository.StatementRepository;
import com.moneylens.service.parser.BankDetector;
import com.moneylens.service.parser.BankStatementParser;
import com.moneylens.service.parser.GenericStatementParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class StatementParser {

    private static final Logger log = LoggerFactory.getLogger(StatementParser.class);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd MMM yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yy")
    );

    private final StatementRepository       statementRepository;
    private final TransactionExtractor      transactionExtractor;
    private final BankDetector              bankDetector;
    private final List<BankStatementParser> parsers;
    private final GenericStatementParser    genericParser;

    public StatementParser(
            StatementRepository statementRepository,
            TransactionExtractor transactionExtractor,
            BankDetector bankDetector,
            List<BankStatementParser> parsers,
            GenericStatementParser genericParser
    ) {
        this.statementRepository  = statementRepository;
        this.transactionExtractor = transactionExtractor;
        this.bankDetector         = bankDetector;
        this.parsers              = parsers;
        this.genericParser        = genericParser;
    }

    public void parse(UUID statementId, Path filePath, String contentType, String bankName) {
        log.info("Parsing statement: {}", statementId);

        Statement statement = statementRepository.findById(statementId).orElse(null);
        if (statement == null) {
            log.error("Statement not found: {}", statementId);
            return;
        }

        try {
            statement.setStatus(Statement.Status.PARSING);
            statementRepository.save(statement);

            // Step 1 — use user-supplied bankName; fall back to auto-detection only if absent
            String resolvedBank = (bankName != null && !bankName.isBlank())
                    ? bankName.toUpperCase()
                    : bankDetector.detectFromFileName(statement.getOriginalFileName());

            if ("GENERIC".equals(resolvedBank) && "application/pdf".equals(contentType)) {
                resolvedBank = bankDetector.detectFromContent(filePath);
                log.info("Content-based detection: {}", resolvedBank);
            }

            log.info("Final bank: {} for statement: {}", resolvedBank, statementId);

            // Step 2 — pick parser
            String finalResolvedBank = resolvedBank;
            BankStatementParser parser = parsers.stream()
                    .filter(p -> p.supports(finalResolvedBank))
                    .findFirst()
                    .orElse(genericParser);

            log.info("Using parser: {}", parser.getClass().getSimpleName());

            // Step 3 — parse rows
            List<Map<String, String>> rawRows = parser.parse(filePath, contentType);
            log.info("Parsed {} raw rows for statement: {}", rawRows.size(), statementId);

            // Step 4 — enrich statement metadata (dates, balances, account info)
            enrichStatementMetadata(statement, rawRows, resolvedBank);
            statementRepository.save(statement);
            log.info("Statement metadata enriched: bank={} account={} period={} to {} opening={} closing={}",
                    statement.getBankName(), statement.getFileName(),
                    statement.getPeriodFrom(), statement.getPeriodTo(),
                    statement.getOpeningBalance(), statement.getClosingBalance());

            // Step 5 — overlapping period duplicate check
            if (statement.getFileName() != null
                    && statement.getPeriodFrom() != null
                    && statement.getPeriodTo() != null) {

                User user = statement.getUser();

                boolean overlap = statementRepository
                        .findByUserOrderByCreatedAtDesc(user)
                        .stream()
                        .filter(s -> !s.getId().equals(statementId))
                        .filter(s -> statement.getFileName().equals(s.getFileName()))
                        .filter(s -> s.getPeriodFrom() != null && s.getPeriodTo() != null)
                        .anyMatch(s ->
                                !s.getPeriodFrom().isAfter(statement.getPeriodTo()) &&
                                        !s.getPeriodTo().isBefore(statement.getPeriodFrom())
                        );

                if (overlap) {
                    log.warn("Duplicate period detected for statement: {} account: {} period: {} to {}",
                            statementId, statement.getFileName(),
                            statement.getPeriodFrom(), statement.getPeriodTo());
                    statement.setStatus(Statement.Status.FAILED);
                    statementRepository.save(statement);
                    return;
                }
            }

            // Step 6 — extract transactions async
            transactionExtractor.extract(statementId, rawRows);

        } catch (Exception e) {
            log.error("Parsing failed for statement: {}", statementId, e);
            statement.setStatus(Statement.Status.FAILED);
            statementRepository.save(statement);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // METADATA ENRICHMENT
    // ─────────────────────────────────────────────────────────────────────────

    private void enrichStatementMetadata(Statement statement,
                                         List<Map<String, String>> rows,
                                         String resolvedBank) {
        statement.setBankName(resolvedBank);

        if (rows.isEmpty()) return;

        extractAndRemoveMetaKey(rows, "__opening_balance__").ifPresent(v -> {
            BigDecimal bd = parseBD(v);
            if (bd != null) {
                statement.setOpeningBalance(bd);
                log.info("Statement opening balance set: {}", bd);
            }
        });

        extractAndRemoveMetaKey(rows, "__closing_balance__").ifPresent(v -> {
            BigDecimal bd = parseBD(v);
            if (bd != null) {
                statement.setClosingBalance(bd);
                log.info("Statement closing balance set: {}", bd);
            }
        });

        extractAndRemoveMetaKey(rows, "__account_number__")
                .ifPresent(statement::setFileName);

        extractAndRemoveMetaKey(rows, "__account_name__")
                .ifPresent(statement::setAccountName);

        List<LocalDate> dates = rows.stream()
                .map(r -> r.get("date"))
                .filter(d -> d != null && !d.isBlank())
                .map(this::tryParseDate)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        if (!dates.isEmpty()) {
            statement.setPeriodFrom(dates.get(0));
            statement.setPeriodTo(dates.get(dates.size() - 1));
        }

        if (statement.getClosingBalance() == null) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                String balStr = rows.get(i).get("balance");
                if (balStr != null && !balStr.isBlank()) {
                    BigDecimal bd = parseBD(balStr);
                    if (bd != null) {
                        statement.setClosingBalance(bd);
                        log.info("Statement closing balance inferred from last transaction: {}", bd);
                        break;
                    }
                }
            }
        }

        if (statement.getFileName() == null || statement.getFileName().isBlank()) {
            statement.setFileName(statement.getOriginalFileName());
        }
    }

    private Optional<String> extractAndRemoveMetaKey(List<Map<String, String>> rows, String key) {
        Optional<String> value = rows.stream()
                .map(r -> r.get(key))
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
        rows.forEach(r -> r.remove(key));
        return value;
    }

    private LocalDate tryParseDate(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(cleaned, fmt); }
            catch (Exception ignored) {}
        }
        return null;
    }

    private BigDecimal parseBD(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return new BigDecimal(raw.replace(",", "").trim()); }
        catch (NumberFormatException e) { return null; }
    }
}