package com.moneylens.service.parser;

import com.opencsv.CSVReader;
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
public class AxisStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(AxisStatementParser.class);

    // Axis format: DD-MM-YYYY  Description  [Chq/Ref]  [Debit]  [Credit]  Balance
    // Group 1 = date
    // Group 2 = description
    // Group 3 = cheque/ref (optional)
    // Group 4 = debit (optional — regex always lands here when only 1 amount present)
    // Group 5 = credit (optional)
    // Group 6 = balance
    private static final Pattern AXIS_PDF_PATTERN = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4})" +
                    "\\s+(.+?)" +
                    "(?:\\s+(\\d{6,}))?" +
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +
                    "\\s+([\\d,]+\\.\\d{2})$"
    );

    private static final List<String> CREDIT_SIGNALS = List.of(
            "salary", " sal", "neft cr", "/cr/", "credit", "refund",
            "cashback", "deposit", "reversal", "interest", "dividend",
            "stipend", "payroll", "inward", "receipt", "received"
    );

    @Override
    public boolean supports(String bankName) { return "AXIS".equals(bankName); }

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
        BigDecimal prevBalance = null;

        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.matches("^\\d{2}-\\d{2}-\\d{4}.*")) continue;

            Matcher m = AXIS_PDF_PATTERN.matcher(line);
            if (!m.matches()) continue;

            String debit      = m.group(4);
            String credit     = m.group(5);
            String balanceStr = m.group(6);
            String descRaw    = m.group(2).trim();

            // ── SINGLE-AMOUNT CORRECTION ─────────────────────────────────────
            // When only group4 is populated, resolve via balance delta.
            if (debit != null && !debit.isBlank()
                    && (credit == null || credit.isBlank())) {

                boolean resolvedAsCredit;
                BigDecimal curBal = parseBD(balanceStr);

                if (prevBalance != null && curBal != null) {
                    resolvedAsCredit = curBal.compareTo(prevBalance) > 0;
                    log.debug("AXIS balance delta: prev={} cur={} → {}", prevBalance, curBal,
                            resolvedAsCredit ? "CREDIT" : "DEBIT");
                } else {
                    String lower = descRaw.toLowerCase();
                    resolvedAsCredit = CREDIT_SIGNALS.stream().anyMatch(lower::contains);
                }

                if (resolvedAsCredit) { credit = debit; debit = null; }
            }

            BigDecimal curBal = parseBD(balanceStr);
            if (curBal != null) prevBalance = curBal;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        m.group(1));
            row.put("description", descRaw);
            if (debit   != null && !debit.isBlank())   row.put("debit",   debit.replace(",", ""));
            if (credit  != null && !credit.isBlank())  row.put("credit",  credit.replace(",", ""));
            if (balanceStr != null)                    row.put("balance", balanceStr.replace(",", ""));
            rows.add(row);
        }

        log.info("AXIS PDF parsed {} rows", rows.size());
        return rows;
    }

    private List<Map<String, String>> parseCsv(Path filePath) throws Exception {
        // Axis CSV: Tran Date, PARTICULARS, CHQNO, DEBIT, CREDIT, BALANCE — separate columns, no correction needed.
        List<Map<String, String>> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> all = reader.readAll();
            if (all.size() < 2) return rows;

            int headerIdx = 0;
            for (int i = 0; i < Math.min(all.size(), 15); i++) {
                String joined = String.join(",", all.get(i)).toLowerCase();
                if (joined.contains("particulars") || joined.contains("tran date")) { headerIdx = i; break; }
            }

            String[] headers = all.get(headerIdx);
            for (int i = headerIdx + 1; i < all.size(); i++) {
                String[] vals = all.get(i);
                if (vals.length < 4) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.length && j < vals.length; j++) {
                    String h = headers[j].trim().toLowerCase();
                    String v = vals[j].trim();
                    if ((h.contains("tran date") || h.contains("date")) && !row.containsKey("date"))
                        row.put("date", v);
                    else if (h.contains("particulars") || h.contains("description"))
                        row.put("description", v);
                    else if (h.equals("debit"))   row.put("debit",   clean(v));
                    else if (h.equals("credit"))  row.put("credit",  clean(v));
                    else if (h.contains("balance")) row.put("balance", clean(v));
                }
                if (row.containsKey("date") && !row.get("date").isBlank()) rows.add(row);
            }
        }
        log.info("AXIS CSV parsed {} rows", rows.size());
        return rows;
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