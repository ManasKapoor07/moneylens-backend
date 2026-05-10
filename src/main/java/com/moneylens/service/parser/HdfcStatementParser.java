package com.moneylens.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class HdfcStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(HdfcStatementParser.class);

    // HDFC PDF: Date  Narration  [Ref#]  ValueDate  [Withdrawal]  [Deposit]  Balance
    // Group 1 = date
    // Group 2 = narration
    // Group 3 = ref (optional)
    // Group 4 = value date
    // Group 5 = withdrawal / DEBIT (optional)
    // Group 6 = deposit   / CREDIT (optional)
    // Group 7 = balance
    private static final Pattern HDFC_PDF = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{2,4})" +
                    "\\s+(.+?)" +
                    "(?:\\s+(\\d{10,16}))?" +
                    "\\s+(\\d{2}/\\d{2}/\\d{2,4})" +
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +
                    "\\s+([\\d,]+\\.\\d{2})$"
    );

    // Keywords that strongly indicate a CREDIT transaction.
    // Used as a fallback when prevBalance is not yet available (first row).
    private static final List<String> CREDIT_SIGNALS = List.of(
            "salary", " sal", "neft cr", "/cr/", "credit", "refund",
            "cashback", "deposit", "reversal", "interest", "dividend",
            "stipend", "payroll", "inward", "receipt", "received"
    );

    @Override
    public boolean supports(String bankName) {
        return "HDFC".equals(bankName);
    }

    @Override
    public List<Map<String, String>> parse(Path filePath, String contentType) throws Exception {
        if ("application/pdf".equals(contentType)) return parsePdf(filePath);
        return parseCsv(filePath);
    }

    private List<Map<String, String>> parsePdf(Path filePath) throws Exception {
        String text;
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            text = s.getText(doc);
        }

        List<Map<String, String>> rows = new ArrayList<>();
        List<String> failedLines = new ArrayList<>();

        BigDecimal prevBalance = null; // closing balance of the previous row

        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.matches("^\\d{2}/\\d{2}/\\d{2,4}.*")) continue;

            Matcher m = HDFC_PDF.matcher(line);
            if (!m.matches()) {
                failedLines.add(line);
                continue;
            }

            String withdrawal = m.group(5); // DEBIT  (regex assignment)
            String deposit    = m.group(6); // CREDIT (regex assignment)
            String balanceStr = m.group(7);
            String descRaw    = m.group(2).trim();

            // ── SINGLE-AMOUNT CORRECTION ──────────────────────────────────
            // When only ONE amount appears before the balance, the regex always
            // puts it in group 5 (withdrawal/debit) because group 6 is optional.
            // This affects UPI credits, salary credits, and all inward transfers.
            //
            // Resolution priority:
            //   1. BALANCE DELTA  — most reliable for ALL transaction types
            //      balance went UP   → money came IN  → CREDIT
            //      balance went DOWN → money went OUT → DEBIT
            //
            //   2. DESCRIPTION KEYWORDS — fallback only for the very first row
            //      (when prevBalance is not yet known)
            // ─────────────────────────────────────────────────────────────
            if (withdrawal != null && !withdrawal.isBlank()
                    && (deposit == null || deposit.isBlank())) {

                boolean resolvedAsCredit = false;

                if (prevBalance != null) {
                    // Strategy 1: balance delta (handles UPI, NEFT, IMPS, salary — everything)
                    BigDecimal currentBalance = parseBD(balanceStr);
                    if (currentBalance != null) {
                        resolvedAsCredit = currentBalance.compareTo(prevBalance) > 0;
                        log.debug("Balance delta: prev={} cur={} → {}",
                                prevBalance, currentBalance,
                                resolvedAsCredit ? "CREDIT" : "DEBIT");
                    }
                } else {
                    // Strategy 2: keyword fallback (first row only)
                    String descLower = descRaw.toLowerCase();
                    resolvedAsCredit = CREDIT_SIGNALS.stream().anyMatch(descLower::contains);
                    if (resolvedAsCredit)
                        log.debug("Credit keyword fallback (first row) for: {}", descRaw);
                }

                if (resolvedAsCredit) {
                    deposit    = withdrawal;
                    withdrawal = null;
                }
            }
            // ─────────────────────────────────────────────────────────────

            // Update prevBalance for the next row
            BigDecimal currentBal = parseBD(balanceStr);
            if (currentBal != null) prevBalance = currentBal;

            // Skip rows where both debit and credit are absent
            if ((withdrawal == null || withdrawal.isBlank()) &&
                    (deposit    == null || deposit.isBlank())) {
                failedLines.add(line);
                continue;
            }

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        m.group(1));
            row.put("description", descRaw);

            if (withdrawal != null && !withdrawal.isBlank())
                row.put("debit",  withdrawal.replace(",", ""));
            if (deposit != null && !deposit.isBlank())
                row.put("credit", deposit.replace(",", ""));
            if (balanceStr != null && !balanceStr.isBlank())
                row.put("balance", balanceStr.replace(",", ""));

            rows.add(row);
            log.debug("HDFC row: date={} debit={} credit={} bal={} desc={}",
                    row.get("date"), row.get("debit"), row.get("credit"),
                    row.get("balance"), row.get("description"));
        }

        if (rows.isEmpty() && !failedLines.isEmpty()) {
            log.warn("HDFC PDF regex failed on {} lines, trying heuristic", failedLines.size());
            rows = parseHeuristic(failedLines);
        }

        log.info("HDFC PDF parsed {} rows ({} failed regex)", rows.size(), failedLines.size());
        return rows;
    }

    private List<Map<String, String>> parseCsv(Path filePath) throws Exception {
        // HDFC CSV already has separate Debit Amount / Credit Amount columns — no correction needed.
        List<Map<String, String>> rows = new ArrayList<>();
        try (var reader = new com.opencsv.CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> all = reader.readAll();
            if (all.size() < 2) return rows;

            int headerIdx = 0;
            for (int i = 0; i < Math.min(all.size(), 15); i++) {
                String joined = String.join(",", all.get(i)).toLowerCase();
                if (joined.contains("narration") || joined.contains("date")) {
                    headerIdx = i;
                    break;
                }
            }

            String[] headers = all.get(headerIdx);
            for (int i = headerIdx + 1; i < all.size(); i++) {
                String[] vals = all.get(i);
                if (vals.length < 4) continue;

                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.length && j < vals.length; j++) {
                    String h = headers[j].trim().toLowerCase();
                    String v = vals[j].trim();
                    if (h.contains("date") && !row.containsKey("date"))
                        row.put("date", v);
                    else if (h.contains("narration") || h.contains("description"))
                        row.put("description", v);
                    else if (h.contains("debit"))
                        row.put("debit", clean(v));
                    else if (h.contains("credit"))
                        row.put("credit", clean(v));
                    else if (h.contains("balance"))
                        row.put("balance", clean(v));
                }
                if (row.containsKey("date") && !row.get("date").isBlank())
                    rows.add(row);
            }
        }
        log.info("HDFC CSV parsed {} rows", rows.size());
        return rows;
    }

    private List<Map<String, String>> parseHeuristic(List<String> lines) {
        List<Map<String, String>> rows = new ArrayList<>();
        Pattern datePat = Pattern.compile("(\\d{2}/\\d{2}/\\d{2,4})");
        Pattern amtPat  = Pattern.compile("[\\d,]+\\.\\d{2}");

        BigDecimal prevBalance = null;

        for (String line : lines) {
            Matcher dm = datePat.matcher(line);
            if (!dm.find()) continue;

            String date = dm.group();
            Matcher amtM = amtPat.matcher(line);
            List<String> amounts = new ArrayList<>();
            String desc = line.replaceAll("\\d{2}/\\d{2}/\\d{2,4}", "")
                    .replaceAll("[\\d,]+\\.\\d{2}", "")
                    .trim();
            while (amtM.find()) amounts.add(amtM.group().replace(",", ""));
            if (amounts.isEmpty()) continue;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date", date);
            row.put("description", desc.isBlank() ? "HDFC Transaction" : desc);

            if (amounts.size() >= 2) {
                String balStr = amounts.get(amounts.size() - 1);
                String txnAmt = amounts.get(amounts.size() - 2);
                row.put("balance", balStr);

                boolean resolvedAsCredit = false;
                BigDecimal currentBalance = parseBD(balStr);

                if (prevBalance != null && currentBalance != null) {
                    // Balance delta
                    resolvedAsCredit = currentBalance.compareTo(prevBalance) > 0;
                } else {
                    // Keyword fallback for first row
                    String lower = line.toLowerCase();
                    resolvedAsCredit = CREDIT_SIGNALS.stream().anyMatch(lower::contains);
                }

                row.put(resolvedAsCredit ? "credit" : "debit", txnAmt);
                prevBalance = currentBalance;
            } else {
                row.put("balance", amounts.get(0));
                prevBalance = parseBD(amounts.get(0));
            }

            rows.add(row);
        }
        return rows;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal parseBD(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String clean(String v) {
        return v == null ? "" : v.replace(",", "").replace("-", "").trim();
    }
}