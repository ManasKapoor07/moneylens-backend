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
public class SbiStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(SbiStatementParser.class);

    // SBI lines always start with two dd/MM/yyyy dates
    private static final Pattern TWO_DATE_LINE = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}/\\d{2}/\\d{4})\\s+(.+)$"
    );
    private static final Pattern AMOUNT_PAT = Pattern.compile("[\\d,]+\\.\\d{2}");

    // SBI uses /DR/ and /CR/ in UPI refs — most reliable signal
    // Balance delta is primary; these are used as secondary confirmation
    private static final List<String> CREDIT_SIGNALS = List.of(
            "/cr/", "salary", " sal", "neft cr", "credit", "refund",
            "cashback", "deposit", "reversal", "interest", "dividend",
            "stipend", "payroll", "dep tfr", "inward", "received"
    );
    private static final List<String> DEBIT_SIGNALS = List.of(
            "/dr/", "wdl tfr"
    );

    @Override
    public boolean supports(String bankName) { return "SBI".equals(bankName); }

    @Override
    public List<Map<String, String>> parse(Path filePath, String contentType) throws Exception {
        if ("application/pdf".equals(contentType)) return parsePdf(filePath);
        return parseCsv(filePath);
    }

    // ─────────────────────────────────────────────────────────────────
    // PDF PARSER
    // ─────────────────────────────────────────────────────────────────

    private List<Map<String, String>> parsePdf(Path filePath) throws Exception {
        String text;
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(doc);
        }

        List<String> lines = Arrays.stream(text.split("\n"))
                .map(String::trim).filter(l -> !l.isBlank()).toList();

        // Find data start
        int dataStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (TWO_DATE_LINE.matcher(lines.get(i)).matches()) { dataStart = i; break; }
        }
        if (dataStart < 0) { log.warn("SBI: no transaction lines found"); return Collections.emptyList(); }

        // Group into blocks
        List<List<String>> blocks = new ArrayList<>();
        List<String> currentBlock = null;

        for (int i = dataStart; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isNoise(line)) continue;
            if (isFooter(line)) break;

            if (TWO_DATE_LINE.matcher(line).matches()) {
                if (currentBlock != null && !currentBlock.isEmpty()) blocks.add(new ArrayList<>(currentBlock));
                currentBlock = new ArrayList<>();
                currentBlock.add(line);
            } else if (currentBlock != null) {
                currentBlock.add(line);
            }
        }
        if (currentBlock != null && !currentBlock.isEmpty()) blocks.add(currentBlock);

        log.info("SBI found {} transaction blocks", blocks.size());

        // Parse blocks with balance delta tracking
        List<Map<String, String>> rows = new ArrayList<>();
        BigDecimal prevBalance = null;

        for (List<String> block : blocks) {
            Map<String, String> row = parseBlock(block, prevBalance);
            if (row != null) {
                rows.add(row);
                prevBalance = parseBD(row.get("balance"));
            }
        }

        if (rows.isEmpty()) {
            log.warn("SBI block parser got 0 rows, trying flat line fallback");
            rows = parseFlatLines(lines, dataStart);
        }

        log.info("SBI PDF parsed {} rows", rows.size());
        return rows;
    }

    // ─────────────────────────────────────────────────────────────────
    // BLOCK PARSER
    // ─────────────────────────────────────────────────────────────────

    private Map<String, String> parseBlock(List<String> block, BigDecimal prevBalance) {
        if (block.isEmpty()) return null;

        String firstLine = block.get(0);
        Matcher m = TWO_DATE_LINE.matcher(firstLine);
        if (!m.matches()) return null;

        String date      = m.group(1);
        String remainder = m.group(3);

        // Collect all amounts across the block
        List<String> amounts = new ArrayList<>();
        for (String line : block) {
            Matcher am = AMOUNT_PAT.matcher(line);
            while (am.find()) amounts.add(am.group().replace(",", ""));
        }
        if (amounts.isEmpty()) return null;

        String desc      = buildDescription(remainder, block);
        String fullText  = String.join(" ", block).toLowerCase();
        String balanceStr = amounts.get(amounts.size() - 1);

        Map<String, String> row = new LinkedHashMap<>();
        row.put("date",        date);
        row.put("description", desc);
        row.put("balance",     balanceStr);

        if (amounts.size() >= 2) {
            String txnAmount = amounts.get(amounts.size() - 2);

            // ── DEBIT/CREDIT RESOLUTION ───────────────────────────────
            // Priority 1: balance delta (most reliable for ALL transaction types)
            // Priority 2: /DR/ or /CR/ in UPI reference string
            // Priority 3: keyword signals in description
            boolean resolvedAsCredit;
            BigDecimal curBal = parseBD(balanceStr);

            if (prevBalance != null && curBal != null) {
                resolvedAsCredit = curBal.compareTo(prevBalance) > 0;
                log.debug("SBI balance delta: prev={} cur={} → {}",
                        prevBalance, curBal, resolvedAsCredit ? "CREDIT" : "DEBIT");
            } else if (DEBIT_SIGNALS.stream().anyMatch(fullText::contains)) {
                resolvedAsCredit = false;
            } else if (CREDIT_SIGNALS.stream().anyMatch(fullText::contains)) {
                resolvedAsCredit = true;
            } else {
                // Cannot determine — default debit (safer for sanity check warnings)
                resolvedAsCredit = false;
                log.warn("SBI: could not determine direction for: {}", desc);
            }

            if (resolvedAsCredit) {
                row.put("credit", txnAmount);
                row.put("debit",  "");
            } else {
                row.put("debit",  txnAmount);
                row.put("credit", "");
            }
        } else {
            row.put("credit", "");
            row.put("debit",  "");
        }

        log.debug("SBI block → date={} debit={} credit={} bal={} desc={}",
                row.get("date"), row.get("debit"), row.get("credit"),
                row.get("balance"), row.get("description"));
        return row;
    }

    // ─────────────────────────────────────────────────────────────────
    // DESCRIPTION BUILDER
    // ─────────────────────────────────────────────────────────────────

    private String buildDescription(String remainder, List<String> block) {
        String cleaned = remainder
                .replaceAll("[\\d,]+\\.\\d{2}", "")
                .replaceAll("\\s+-\\s+", " ")
                .replaceAll("-$", "")
                .trim();

        StringBuilder sb = new StringBuilder(cleaned);
        for (int i = 1; i < block.size(); i++) {
            String line = block.get(i);
            if (line.matches("\\d{10,}.*")) continue;
            if (line.matches(".*\\bAT\\s+\\d+\\b.*")) continue;
            if (line.matches("[A-Z ]{3,}")) continue;
            if (isNoise(line)) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(line);
        }

        String desc = sb.toString().trim();
        if (desc.isBlank()) desc = "SBI Transaction";
        return desc.length() > 490 ? desc.substring(0, 490) : desc;
    }

    // ─────────────────────────────────────────────────────────────────
    // FLAT LINE FALLBACK
    // ─────────────────────────────────────────────────────────────────

    private List<Map<String, String>> parseFlatLines(List<String> lines, int startFrom) {
        List<Map<String, String>> rows = new ArrayList<>();
        BigDecimal prevBalance = null;

        for (int i = startFrom; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!TWO_DATE_LINE.matcher(line).matches()) continue;

            Matcher m = TWO_DATE_LINE.matcher(line);
            if (!m.matches()) continue;

            String date      = m.group(1);
            String remainder = m.group(3);

            List<String> amounts = new ArrayList<>();
            Matcher am = AMOUNT_PAT.matcher(line);
            while (am.find()) amounts.add(am.group().replace(",", ""));
            if (amounts.isEmpty()) continue;

            String balanceStr = amounts.get(amounts.size() - 1);
            String desc = remainder
                    .replaceAll("[\\d,]+\\.\\d{2}", "").replaceAll("\\s+-\\s+", " ")
                    .replaceAll("-$", "").trim();

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        date);
            row.put("description", desc.isBlank() ? "SBI Transaction" : desc);
            row.put("balance",     balanceStr);

            if (amounts.size() >= 2) {
                String txnAmount  = amounts.get(amounts.size() - 2);
                String lineLower  = line.toLowerCase();

                boolean resolvedAsCredit;
                BigDecimal curBal = parseBD(balanceStr);

                if (prevBalance != null && curBal != null) {
                    resolvedAsCredit = curBal.compareTo(prevBalance) > 0;
                } else if (DEBIT_SIGNALS.stream().anyMatch(lineLower::contains)) {
                    resolvedAsCredit = false;
                } else {
                    resolvedAsCredit = CREDIT_SIGNALS.stream().anyMatch(lineLower::contains);
                }

                row.put(resolvedAsCredit ? "credit" : "debit", txnAmount);
                row.put(resolvedAsCredit ? "debit"  : "credit", "");
                prevBalance = curBal;
            } else {
                row.put("credit", ""); row.put("debit", "");
                prevBalance = parseBD(balanceStr);
            }

            rows.add(row);
        }

        log.info("SBI flat-line fallback parsed {} rows", rows.size());
        return rows;
    }

    // ─────────────────────────────────────────────────────────────────
    // CSV PARSER
    // ─────────────────────────────────────────────────────────────────

    private List<Map<String, String>> parseCsv(Path filePath) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (com.opencsv.CSVReader reader = new com.opencsv.CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> all = reader.readAll();
            if (all.size() < 2) return rows;

            int headerIdx = 0;
            for (int i = 0; i < Math.min(all.size(), 15); i++) {
                String joined = String.join(",", all.get(i)).toLowerCase();
                if (joined.contains("value date") || joined.contains("narration")
                        || joined.contains("withdrawal")) { headerIdx = i; break; }
            }

            String[] headers = all.get(headerIdx);
            log.info("SBI CSV headers: {}", Arrays.toString(headers));

            for (int i = headerIdx + 1; i < all.size(); i++) {
                String[] vals = all.get(i);
                if (vals.length < 4) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.length && j < vals.length; j++) {
                    String h = headers[j].trim().toLowerCase();
                    String v = vals[j].trim();
                    if ((h.contains("txn date") || h.contains("value date") || h.equals("date"))
                            && !row.containsKey("date"))                           row.put("date", v);
                    else if (h.contains("narration") || h.contains("description")
                            || h.contains("details") || h.contains("particulars")) row.put("description", v);
                    else if (h.contains("debit") || h.contains("withdrawal"))      row.put("debit",   clean(v));
                    else if (h.contains("credit") || h.contains("deposit"))        row.put("credit",  clean(v));
                    else if (h.contains("balance"))                                 row.put("balance", clean(v));
                }
                if (row.containsKey("date") && !row.get("date").isBlank()) rows.add(row);
            }
        }
        log.info("SBI CSV parsed {} rows", rows.size());
        return rows;
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    private boolean isFooter(String line) {
        String lower = line.toLowerCase();
        return lower.contains("statement summary") || lower.contains("please do not share")
                || lower.contains("computer generated") || lower.contains("power of attorney")
                || lower.startsWith("brought forward") || lower.startsWith("closing balance");
    }

    private boolean isNoise(String line) {
        String lower = line.toLowerCase();
        return lower.equals("balance") || lower.equals("wdl tfr") || lower.equals("dep tfr")
                || lower.startsWith("page no.") || lower.startsWith("total debit")
                || lower.startsWith("total credit");
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