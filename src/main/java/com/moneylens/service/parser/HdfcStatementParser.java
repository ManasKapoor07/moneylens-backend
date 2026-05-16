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
    private static final Pattern HDFC_PDF = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{2,4})" +
                    "\\s+(.+?)" +
                    "(?:\\s+(\\d{10,16}))?" +
                    "\\s+(\\d{2}/\\d{2}/\\d{2,4})" +
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +
                    "\\s+([\\d,]+\\.\\d{2})$"
    );

    private static final Pattern AMOUNT_PAT  = Pattern.compile("[\\d,]+\\.\\d{2}");
    private static final Pattern OPENING_BAL = Pattern.compile(
            "Opening\\s+Balance[:\\s]+([\\d,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_BAL = Pattern.compile(
            "Closing\\s+Balance[:\\s]+([\\d,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE);

    private static final List<String> CREDIT_SIGNALS = List.of(
            "salary", " sal", "neft cr", "/cr/", "credit", "refund",
            "cashback", "deposit", "reversal", "interest", "dividend",
            "stipend", "payroll", "inward", "receipt", "received"
    );

    @Override
    public boolean supports(String bankName) { return "HDFC".equals(bankName); }

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

        BigDecimal openingBalance = extractBalance(text, OPENING_BAL);
        BigDecimal closingBalance = extractBalance(text, CLOSING_BAL);
        log.info("HDFC opening={} closing={}", openingBalance, closingBalance);

        List<Map<String, String>> rows      = new ArrayList<>();
        List<String>              failedLines = new ArrayList<>();
        BigDecimal                prevBalance = openingBalance;

        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.matches("^\\d{2}/\\d{2}/\\d{2,4}.*")) continue;

            Matcher m = HDFC_PDF.matcher(line);
            if (!m.matches()) { failedLines.add(line); continue; }

            String withdrawal = m.group(5);
            String deposit    = m.group(6);
            String balanceStr = m.group(7);
            String descRaw    = m.group(2).trim();

            // Single-amount correction via balance delta
            if (withdrawal != null && !withdrawal.isBlank()
                    && (deposit == null || deposit.isBlank())) {

                boolean resolvedAsCredit = false;

                if (prevBalance != null) {
                    BigDecimal currentBalance = parseBD(balanceStr);
                    if (currentBalance != null) {
                        resolvedAsCredit = currentBalance.compareTo(prevBalance) > 0;
                    }
                } else {
                    String descLower = descRaw.toLowerCase();
                    resolvedAsCredit = CREDIT_SIGNALS.stream().anyMatch(descLower::contains);
                }

                if (resolvedAsCredit) {
                    deposit    = withdrawal;
                    withdrawal = null;
                }
            }

            BigDecimal currentBal = parseBD(balanceStr);
            if (currentBal != null) prevBalance = currentBal;

            if ((withdrawal == null || withdrawal.isBlank()) &&
                    (deposit    == null || deposit.isBlank())) {
                failedLines.add(line);
                continue;
            }

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        m.group(1));
            row.put("description", descRaw);
            if (withdrawal != null && !withdrawal.isBlank()) row.put("debit",  withdrawal.replace(",", ""));
            if (deposit    != null && !deposit.isBlank())    row.put("credit", deposit.replace(",", ""));
            if (balanceStr != null && !balanceStr.isBlank()) row.put("balance", balanceStr.replace(",", ""));
            rows.add(row);
        }

        if (rows.isEmpty() && !failedLines.isEmpty()) {
            log.warn("HDFC PDF regex failed on {} lines, trying heuristic", failedLines.size());
            rows = parseHeuristic(failedLines, openingBalance);
        }

        injectBalanceMetadata(rows, openingBalance,
                closingBalance != null ? closingBalance : prevBalance);

        log.info("HDFC PDF parsed {} rows", rows.size());
        return rows;
    }

    private List<Map<String, String>> parseCsv(Path filePath) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (var reader = new com.opencsv.CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> all = reader.readAll();
            if (all.size() < 2) return rows;

            int headerIdx = 0;
            for (int i = 0; i < Math.min(all.size(), 15); i++) {
                String joined = String.join(",", all.get(i)).toLowerCase();
                if (joined.contains("narration") || joined.contains("date")) { headerIdx = i; break; }
            }

            String[]   headers        = all.get(headerIdx);
            BigDecimal closingBalance = null;

            for (int i = headerIdx + 1; i < all.size(); i++) {
                String[] vals = all.get(i);
                if (vals.length < 4) continue;

                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.length && j < vals.length; j++) {
                    String h = headers[j].trim().toLowerCase();
                    String v = vals[j].trim();
                    if (h.contains("date") && !row.containsKey("date"))              row.put("date",        v);
                    else if (h.contains("narration") || h.contains("description"))   row.put("description", v);
                    else if (h.contains("debit"))                                     row.put("debit",       clean(v));
                    else if (h.contains("credit"))                                    row.put("credit",      clean(v));
                    else if (h.contains("balance"))                                 { row.put("balance",     clean(v));
                        closingBalance = parseBD(clean(v)); }
                }
                if (row.containsKey("date") && !row.get("date").isBlank()) rows.add(row);
            }

            // CSV doesn't typically have an opening balance line; infer it
            BigDecimal openingBalance = inferOpeningBalance(rows);
            injectBalanceMetadata(rows, openingBalance, closingBalance);
        }
        log.info("HDFC CSV parsed {} rows", rows.size());
        return rows;
    }

    private List<Map<String, String>> parseHeuristic(List<String> lines, BigDecimal openingBalance) {
        List<Map<String, String>> rows = new ArrayList<>();
        Pattern datePat = Pattern.compile("(\\d{2}/\\d{2}/\\d{2,4})");

        BigDecimal prevBalance = openingBalance;

        for (String line : lines) {
            Matcher dm = datePat.matcher(line);
            if (!dm.find()) continue;

            String date = dm.group();
            Matcher amtM = AMOUNT_PAT.matcher(line);
            List<String> amounts = new ArrayList<>();
            String desc = line.replaceAll("\\d{2}/\\d{2}/\\d{2,4}", "")
                    .replaceAll("[\\d,]+\\.\\d{2}", "").trim();
            while (amtM.find()) amounts.add(amtM.group().replace(",", ""));
            if (amounts.isEmpty()) continue;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        date);
            row.put("description", desc.isBlank() ? "HDFC Transaction" : desc);

            if (amounts.size() >= 2) {
                String     balStr  = amounts.get(amounts.size() - 1);
                String     txnAmt  = amounts.get(amounts.size() - 2);
                BigDecimal curBal  = parseBD(balStr);

                boolean resolvedAsCredit = (prevBalance != null && curBal != null)
                        ? curBal.compareTo(prevBalance) > 0
                        : CREDIT_SIGNALS.stream().anyMatch(line.toLowerCase()::contains);

                row.put("balance", balStr);
                row.put(resolvedAsCredit ? "credit" : "debit", txnAmt);
                prevBalance = curBal;
            } else {
                row.put("balance", amounts.get(0));
                prevBalance = parseBD(amounts.get(0));
            }
            rows.add(row);
        }
        return rows;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void injectBalanceMetadata(List<Map<String, String>> rows,
                                       BigDecimal opening, BigDecimal closing) {
        if (rows.isEmpty()) return;
        Map<String, String> first = rows.get(0);
        if (opening != null) first.put("__opening_balance__", opening.toPlainString());
        if (closing != null) first.put("__closing_balance__", closing.toPlainString());
    }

    /**
     * Infers opening balance from the first transaction row:
     * openingBalance = firstBalance +/- firstTxAmount
     */
    private BigDecimal inferOpeningBalance(List<Map<String, String>> rows) {
        if (rows.isEmpty()) return null;
        Map<String, String> first = rows.get(0);

        BigDecimal balance = parseBD(first.get("balance"));
        if (balance == null) return null;

        String debitStr  = first.get("debit");
        String creditStr = first.get("credit");

        if (debitStr != null && !debitStr.isBlank()) {
            BigDecimal amt = parseBD(debitStr);
            return amt != null ? balance.add(amt) : null;       // opening = balance + debit
        }
        if (creditStr != null && !creditStr.isBlank()) {
            BigDecimal amt = parseBD(creditStr);
            return amt != null ? balance.subtract(amt) : null;  // opening = balance - credit
        }
        return null;
    }

    private BigDecimal extractBalance(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (m.find()) return parseBD(m.group(1));
        return null;
    }

    private BigDecimal parseBD(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return new BigDecimal(raw.replace(",", "").trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String clean(String v) {
        return v == null ? "" : v.replace(",", "").replace("-", "").trim();
    }
}