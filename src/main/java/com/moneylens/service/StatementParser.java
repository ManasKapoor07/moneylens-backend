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

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

    private final StatementRepository statementRepository;
    private final TransactionExtractor transactionExtractor;
    private final BankDetector bankDetector;
    private final List<BankStatementParser> parsers;
    private final GenericStatementParser genericParser;

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

    @Async
    public void parse(UUID statementId, Path filePath, String contentType) {
        log.info("Parsing statement: {}", statementId);

        Statement statement = statementRepository.findById(statementId).orElse(null);
        if (statement == null) {
            log.error("Statement not found: {}", statementId);
            return;
        }

        try {
            statement.setStatus(Statement.Status.PARSING);
            statementRepository.save(statement);

            // Step 1 — detect bank from filename
            String bankName = bankDetector.detectFromFileName(statement.getOriginalFileName());
            log.info("Filename-based detection: {}", bankName);

            // Step 2 — if GENERIC and PDF, try content-based detection
            if ("GENERIC".equals(bankName) && "application/pdf".equals(contentType)) {
                bankName = bankDetector.detectFromContent(filePath);
                log.info("Content-based detection: {}", bankName);
            }

            log.info("Final bank: {} for statement: {}", bankName, statementId);

            // Step 3 — pick the right parser
            String finalBankName = bankName;
            BankStatementParser parser = parsers.stream()
                    .filter(p -> p.supports(finalBankName))
                    .findFirst()
                    .orElse(genericParser);

            log.info("Using parser: {}", parser.getClass().getSimpleName());

            // Step 4 — parse rows
            List<Map<String, String>> rawRows = parser.parse(filePath, contentType);
            log.info("Parsed {} raw rows for statement: {}", rawRows.size(), statementId);

            // Step 5 — populate statement metadata from parsed rows
            enrichStatementMetadata(statement, rawRows, bankName);
            statementRepository.save(statement);
            log.info("Statement metadata enriched: bank={} account={} period={} to {}",
                    statement.getBankName(), statement.getAccountNumber(),
                    statement.getPeriodFrom(), statement.getPeriodTo());

            // Step 6 — overlapping period duplicate check
            // (file-hash check already happened in UploadService synchronously)
            // This catches: same account, different file, overlapping date range.
            if (statement.getAccountNumber() != null
                    && statement.getPeriodFrom() != null
                    && statement.getPeriodTo() != null) {

                User user = statement.getUser();

                // We need to exclude the current statement from the check —
                // do this by checking if any OTHER statement matches.
                // Spring Data can't do "exclude self" cleanly, so we query
                // and filter in memory (only runs once per upload, not a hot path).
                boolean overlap = statementRepository
                        .findByUserOrderByCreatedAtDesc(user)
                        .stream()
                        .filter(s -> !s.getId().equals(statementId))  // exclude self
                        .filter(s -> statement.getAccountNumber().equals(s.getAccountNumber()))
                        .filter(s -> s.getPeriodFrom() != null && s.getPeriodTo() != null)
                        .anyMatch(s ->
                                !s.getPeriodFrom().isAfter(statement.getPeriodTo()) &&
                                        !s.getPeriodTo().isBefore(statement.getPeriodFrom())
                        );

                if (overlap) {
                    log.warn("Duplicate period detected for statement: {} account: {} period: {} to {}",
                            statementId, statement.getAccountNumber(),
                            statement.getPeriodFrom(), statement.getPeriodTo());
                    statement.setStatus(Statement.Status.FAILED);
                    statementRepository.save(statement);
                    // Note: the statement row stays in DB so the user sees
                    // FAILED status with a meaningful message. The frontend
                    // should show "Duplicate period" for FAILED statements
                    // that have periodFrom/periodTo populated.
                    return;
                }
            }

            // Step 7 — extract transactions async
            transactionExtractor.extract(statementId, rawRows);

        } catch (Exception e) {
            log.error("Parsing failed for statement: {}", statementId, e);
            statement.setStatus(Statement.Status.FAILED);
            statementRepository.save(statement);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // METADATA ENRICHMENT
    // Derives bankName, periodFrom, periodTo from parsed rows.
    // accountNumber and accountName require bank-specific extraction
    // from PDF headers — parsers can expose these via a metadata map
    // in a future iteration. For now we set what we can reliably get.
    // ─────────────────────────────────────────────────────────────────

    private void enrichStatementMetadata(Statement statement,
                                         List<Map<String, String>> rows,
                                         String bankName) {
        statement.setBankName(bankName);

        if (rows.isEmpty()) return;

        // Derive date range from all transaction dates
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

        // accountNumber: try to get from the rows if any parser puts it there
        // (e.g. a parser could add a special "__account_number__" key to the first row)
        rows.stream()
                .map(r -> r.get("__account_number__"))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .ifPresent(statement::setAccountNumber);

        rows.stream()
                .map(r -> r.get("__account_name__"))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .ifPresent(statement::setAccountName);

        // Fallback: if no account number from parser, use originalFileName
        // so the unique constraint still works (two files = two accounts)
        if (statement.getAccountNumber() == null) {
            statement.setAccountNumber(statement.getOriginalFileName());
        }
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
}