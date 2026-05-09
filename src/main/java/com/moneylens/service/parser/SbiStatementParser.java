package com.moneylens.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileReader;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class SbiStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(SbiStatementParser.class);

    @Override
    public boolean supports(String bankName) {
        return "SBI".equals(bankName);
    }

    @Override
    public List<Map<String, String>> parse(Path filePath, String contentType) throws Exception {
        if ("application/pdf".equals(contentType)) return parsePdf(filePath);
        return parseCsv(filePath);
    }

    // ─────────────────────────────────────────────────────────────────
    // PDF PARSER
    //
    // SBI PDF structure (observed from raw lines):
    //
    //   [prev block's type marker]  ← "WDL TFR" or "DEP TFR" on its own line
    //   dd/MM/yyyy dd/MM/yyyy UPI/DR/.../Payee - debit - balance
    //   continuation narration line(s)
    //   ref AT branchcode
    //   BRANCH NAME
    //
    // Key observations:
    //   1. Every transaction line STARTS with two dates: dd/MM/yyyy dd/MM/yyyy
    //   2. The "-" placeholders for absent debit or credit are literal dashes (not amounts)
    //   3. /DR/ in the reference = debit, /CR/ = credit (most reliable signal)
    //   4. The summary footer also contains "balance"/"debit"/"credit" text —
    //      so we cannot use those keywords to find the data start.
    //   5. Data start = first line that matches the two-date transaction pattern.
    // ─────────────────────────────────────────────────────────────────

    private static final Pattern TWO_DATE_LINE  = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}/\\d{2}/\\d{4})\\s+(.+)$"
    );
    private static final Pattern AMOUNT_PAT = Pattern.compile("[\\d,]+\\.\\d{2}");
    private static final Pattern DATE_ANYWHERE = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");

    private List<Map<String, String>> parsePdf(Path filePath) throws Exception {
        String text;
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(doc);
        }

        List<String> lines = Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .toList();

        // Debug — first 60 lines
        log.debug("=== SBI PDF RAW (first 60 lines) ===");
        for (int i = 0; i < Math.min(lines.size(), 60); i++) {
            log.debug("L[{}]: '{}'", i, lines.get(i));
        }

        // Find data start: first line that begins with two consecutive dates
        // This is far more reliable than looking for header keywords, which also
        // appear in the footer summary.
        int dataStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isTransactionLine(lines.get(i))) {
                dataStart = i;
                log.info("SBI data start at line {}: '{}'", i, lines.get(i));
                break;
            }
        }

        if (dataStart < 0) {
            log.warn("SBI: no transaction lines found in PDF");
            return Collections.emptyList();
        }

        // ── Group lines into transaction blocks ──────────────────────
        // A new block starts whenever we see a line beginning with two dates.
        // All subsequent lines (until the next such line) belong to that block.
        List<List<String>> blocks = new ArrayList<>();
        List<String> currentBlock = null;

        for (int i = dataStart; i < lines.size(); i++) {
            String line = lines.get(i);

            // Skip page-break artifacts and column header repetitions
            if (isNoise(line)) continue;

            // Stop collecting once we hit the statement summary footer —
            // everything after this is boilerplate that must not bleed into
            // the last transaction block's description.
            if (isFooter(line)) {
                log.debug("SBI footer detected at line {}, stopping block collection: '{}'", i, line);
                break;
            }

            if (isTransactionLine(line)) {
                if (currentBlock != null && !currentBlock.isEmpty()) {
                    blocks.add(new ArrayList<>(currentBlock));
                }
                currentBlock = new ArrayList<>();
                currentBlock.add(line);
            } else if (currentBlock != null) {
                currentBlock.add(line);
            }
        }
        if (currentBlock != null && !currentBlock.isEmpty()) {
            blocks.add(currentBlock);
        }

        log.info("SBI found {} transaction blocks", blocks.size());

        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> block : blocks) {
            Map<String, String> row = parseBlock(block);
            if (row != null) rows.add(row);
        }

        // Fallback to flat-line parser if block parser got nothing
        if (rows.isEmpty()) {
            log.warn("SBI block parser got 0 rows, trying flat line parser");
            rows = parseFlatLines(lines, dataStart);
        }

        log.info("SBI PDF parsed {} rows", rows.size());
        return rows;
    }

    // ─────────────────────────────────────────────────────────────────
    // BLOCK PARSER
    //
    // Input block example:
    //   "02/04/2025 02/04/2025 UPI/DR/509288717272/Khuma - 20.00 - 85.31"
    //   "Ram/YESB/paytmqr699/UPI"
    //   "0097693162093 AT 11326"
    //   "MANGAL PARAO"
    //
    // The first line always has:
    //   valueDate  postDate  narration  ["-" or amount]  ["-" or amount]  balance
    //
    // Because "-" is stripped by AMOUNT_PAT, amounts list = [debit_or_credit, balance]
    // or just [balance] when the amount line has only one number.
    // ─────────────────────────────────────────────────────────────────

    private Map<String, String> parseBlock(List<String> block) {
        if (block.isEmpty()) return null;

        String firstLine = block.get(0);
        Matcher m = TWO_DATE_LINE.matcher(firstLine);
        if (!m.matches()) return null;

        String date      = m.group(1);   // value date
        String remainder = m.group(3);   // everything after the two dates

        // Collect all amounts across the whole block
        List<String> amounts = new ArrayList<>();
        for (String line : block) {
            Matcher am = AMOUNT_PAT.matcher(line);
            while (am.find()) {
                amounts.add(am.group().replace(",", ""));
            }
        }

        if (amounts.isEmpty()) return null;

        // Build description from non-amount, non-date parts of the first line,
        // plus any continuation lines that look like narration (not ref numbers).
        String desc = buildDescription(remainder, block);

        // Determine debit vs credit from the UPI reference in the first line.
        // /DR/ = debit, /CR/ = credit.  Fall back to WDL/DEP markers in block text.
        String fullText = String.join(" ", block);
        boolean isDebit  = fullText.contains("/DR/") || fullText.contains("WDL TFR");
        boolean isCredit = fullText.contains("/CR/") || fullText.contains("DEP TFR");

        Map<String, String> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("description", desc);

        // amounts layout: [..., txnAmount, balance]
        // We only need the last two; anything before is noise (e.g. account numbers
        // that happen to match the amount pattern).
        String balance = amounts.get(amounts.size() - 1);
        row.put("balance", balance);

        if (amounts.size() >= 2) {
            String txnAmount = amounts.get(amounts.size() - 2);
            if (isDebit) {
                row.put("debit",  txnAmount);
                row.put("credit", "");
            } else if (isCredit) {
                row.put("credit", txnAmount);
                row.put("debit",  "");
            } else {
                // Cannot determine direction — default to credit
                row.put("credit", txnAmount);
                row.put("debit",  "");
            }
        } else {
            // Only balance available
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
        // Strip amounts and dashes from remainder of first line
        String cleaned = remainder
                .replaceAll("[\\d,]+\\.\\d{2}", "")
                .replaceAll("\\s+-\\s+", " ")
                .replaceAll("-$", "")
                .trim();

        StringBuilder sb = new StringBuilder(cleaned);

        // Append narration continuation lines (lines 1..N, skip ref/branch lines)
        for (int i = 1; i < block.size(); i++) {
            String line = block.get(i);
            // Skip: pure reference numbers, branch codes, "AT NNNNN", noise
            if (line.matches("\\d{10,}.*")) continue;           // long ref number
            if (line.matches(".*\\bAT\\s+\\d+\\b.*")) continue; // "AT 11326"
            if (line.matches("[A-Z ]{3,}")) continue;            // "MANGAL PARAO"
            if (isNoise(line)) continue;

            if (sb.length() > 0) sb.append(" ");
            sb.append(line);
        }

        String desc = sb.toString().trim();
        if (desc.isBlank()) desc = "SBI Transaction";
        // Hard cap at 490 chars — safety net against footer bleed or unusually long narrations
        return desc.length() > 490 ? desc.substring(0, 490) : desc;
    }

    // ─────────────────────────────────────────────────────────────────
    // FOOTER DETECTION
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns true for lines that signal the end of transaction data.
     * Block collection must STOP (break) when a footer line is detected,
     * so it cannot bleed into the last transaction's description.
     */
    private boolean isFooter(String line) {
        String lower = line.toLowerCase();
        return lower.contains("statement summary")
                || lower.contains("please do not share")
                || lower.contains("computer generated statement")
                || lower.contains("power of attorney")
                || lower.startsWith("brought forward")
                || lower.startsWith("closing balance");
    }

    // ─────────────────────────────────────────────────────────────────
    // FLAT LINE FALLBACK
    // ─────────────────────────────────────────────────────────────────

    private List<Map<String, String>> parseFlatLines(List<String> lines, int startFrom) {
        List<Map<String, String>> rows = new ArrayList<>();

        for (int i = startFrom; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isTransactionLine(line)) continue;

            Matcher m = TWO_DATE_LINE.matcher(line);
            if (!m.matches()) continue;

            String date      = m.group(1);
            String remainder = m.group(3);

            List<String> amounts = new ArrayList<>();
            Matcher am = AMOUNT_PAT.matcher(line);
            while (am.find()) amounts.add(am.group().replace(",", ""));

            if (amounts.isEmpty()) continue;

            boolean isDebit  = line.contains("/DR/");
            boolean isCredit = line.contains("/CR/");

            String desc = remainder
                    .replaceAll("[\\d,]+\\.\\d{2}", "")
                    .replaceAll("\\s+-\\s+", " ")
                    .replaceAll("-$", "")
                    .trim();

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        date);
            row.put("description", desc.isBlank() ? "SBI Transaction" : desc);
            row.put("balance",     amounts.get(amounts.size() - 1));

            if (amounts.size() >= 2) {
                String txnAmount = amounts.get(amounts.size() - 2);
                if (isDebit)       { row.put("debit", txnAmount); row.put("credit", ""); }
                else if (isCredit) { row.put("credit", txnAmount); row.put("debit", ""); }
                else               { row.put("credit", txnAmount); row.put("debit", ""); }
            } else {
                row.put("credit", ""); row.put("debit", "");
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
        try (com.opencsv.CSVReader reader =
                     new com.opencsv.CSVReader(new FileReader(filePath.toFile()))) {

            List<String[]> all = reader.readAll();
            if (all.size() < 2) return rows;

            // Find header row
            int headerIdx = 0;
            for (int i = 0; i < Math.min(all.size(), 15); i++) {
                String joined = String.join(",", all.get(i)).toLowerCase();
                if (joined.contains("value date") || joined.contains("narration")
                        || joined.contains("withdrawal")) {
                    headerIdx = i;
                    break;
                }
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

                    if ((h.contains("txn date") || h.contains("value date")
                            || h.equals("date")) && !row.containsKey("date")) {
                        row.put("date", v);
                    } else if (h.contains("narration") || h.contains("description")
                            || h.contains("details") || h.contains("particulars")) {
                        row.put("description", v);
                    } else if (h.contains("debit") || h.contains("withdrawal")) {
                        row.put("debit", v.replace(",", "").replace("-", "").trim());
                    } else if (h.contains("credit") || h.contains("deposit")) {
                        row.put("credit", v.replace(",", "").replace("-", "").trim());
                    } else if (h.contains("balance")) {
                        row.put("balance", v.replace(",", "").replace("-", "").trim());
                    }
                }

                if (row.containsKey("date") && !row.get("date").isBlank())
                    rows.add(row);
            }
        }
        log.info("SBI CSV parsed {} rows", rows.size());
        return rows;
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns true if the line is a genuine SBI transaction line.
     * These always start with two consecutive dd/MM/yyyy dates.
     */
    private boolean isTransactionLine(String line) {
        return TWO_DATE_LINE.matcher(line).matches();
    }

    /**
     * Returns true for lines that are page-break artifacts or repeated column headers
     * that should be ignored during block grouping.
     */
    private boolean isNoise(String line) {
        String lower = line.toLowerCase();
        return lower.equals("balance")
                || lower.equals("wdl tfr")
                || lower.equals("dep tfr")
                || lower.startsWith("page no.")
                || lower.startsWith("total debit")
                || lower.startsWith("total credit");
    }
}