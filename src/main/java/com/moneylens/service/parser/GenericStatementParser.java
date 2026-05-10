package com.moneylens.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class GenericStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(GenericStatementParser.class);

    private static final Pattern DATE_PAT = Pattern.compile(
            "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\b");
    private static final Pattern AMT_PAT = Pattern.compile("[\\d,]+\\.\\d{2}");

    private static final List<String> CREDIT_SIGNALS = List.of(
            "salary", " sal", "neft cr", "/cr/", "credit", "refund",
            "cashback", "deposit", "reversal", "interest", "dividend",
            "stipend", "payroll", "inward", "receipt", "received"
    );
    private static final List<String> DEBIT_SIGNALS = List.of(
            "/dr/", "wdl", "withdrawal", "debit"
    );

    @Override
    public boolean supports(String bankName) { return "GENERIC".equals(bankName); }

    @Override
    public List<Map<String, String>> parse(Path filePath, String contentType) throws Exception {
        log.warn("Using generic parser — bank not detected");

        String text;
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            text = s.getText(doc);
        }

        return parseHeuristic(Arrays.stream(text.split("\n"))
                .map(String::trim).filter(l -> !l.isBlank()).toList());
    }

    private List<Map<String, String>> parseHeuristic(List<String> lines) {
        List<Map<String, String>> rows = new ArrayList<>();
        BigDecimal prevBalance = null;

        for (String line : lines) {
            Matcher dm = DATE_PAT.matcher(line);
            if (!dm.find()) continue;

            String date = dm.group();
            String rest = line.substring(dm.end()).trim();

            Matcher amtM = AMT_PAT.matcher(rest);
            List<String> amounts = new ArrayList<>();
            String description   = rest;

            while (amtM.find()) {
                if (amounts.isEmpty()) description = rest.substring(0, amtM.start()).trim();
                amounts.add(amtM.group().replace(",", ""));
            }

            if (amounts.isEmpty()) continue;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        date);
            row.put("description", description.isBlank() ? "Unknown" : description);

            if (amounts.size() >= 2) {
                String balStr  = amounts.get(amounts.size() - 1);
                String txnAmt  = amounts.get(amounts.size() - 2);
                row.put("balance", balStr);

                // ── DEBIT/CREDIT RESOLUTION ───────────────────────────────
                // Priority 1: balance delta
                // Priority 2: keyword signals in line
                // Priority 3: default to credit (generic — we don't know the format)
                boolean resolvedAsCredit;
                BigDecimal curBal = parseBD(balStr);

                if (prevBalance != null && curBal != null) {
                    resolvedAsCredit = curBal.compareTo(prevBalance) > 0;
                    log.debug("GENERIC balance delta: prev={} cur={} → {}",
                            prevBalance, curBal, resolvedAsCredit ? "CREDIT" : "DEBIT");
                } else {
                    String lower = line.toLowerCase();
                    if (DEBIT_SIGNALS.stream().anyMatch(lower::contains))        resolvedAsCredit = false;
                    else if (CREDIT_SIGNALS.stream().anyMatch(lower::contains))  resolvedAsCredit = true;
                    else                                                          resolvedAsCredit = true; // safe default
                }

                row.put(resolvedAsCredit ? "credit" : "debit", txnAmt);
                prevBalance = curBal;
            } else {
                // Only one amount — treat as balance, no transaction amount extractable
                row.put("balance", amounts.get(0));
                prevBalance = parseBD(amounts.get(0));
            }

            rows.add(row);
        }

        log.info("Generic heuristic parsed {} rows", rows.size());
        return rows;
    }

    private BigDecimal parseBD(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return new BigDecimal(raw.replace(",", "").trim()); }
        catch (NumberFormatException e) { return null; }
    }
}