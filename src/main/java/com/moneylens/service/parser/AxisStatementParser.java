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

/**
 * Axis Bank Statement Parser — PDF + CSV
 *
 * PDF Strategy (balance-delta method):
 *   1. Extract opening balance from the "OPENING BALANCE" line.
 *   2. Collect all physical lines that begin with DD-MM-YYYY.
 *   3. For each such line, extract ALL decimal numbers positionally:
 *      - Last number  → running balance for this transaction.
 *      - 2nd-to-last → transaction amount.
 *   4. Determine DEBIT vs CREDIT by balance delta (100% reliable for Axis).
 *   5. Inject __opening_balance__ and __closing_balance__ into the first row
 *      so StatementParser.enrichStatementMetadata() can persist them on the
 *      Statement entity.
 */
@Component
public class AxisStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(AxisStatementParser.class);

    // ── Patterns ─────────────────────────────────────────────────────────────

    private static final Pattern DATE_LINE    = Pattern.compile("^(\\d{2}-\\d{2}-\\d{4})\\s+(.*)$");
    private static final Pattern AMOUNT_PAT   = Pattern.compile("[\\d,]+\\.\\d{2}");
    private static final Pattern OPENING_BAL  = Pattern.compile(
            "OPENING\\s+BALANCE\\s+([\\d,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_BAL  = Pattern.compile(
            "CLOSING\\s+BALANCE\\s+([\\d,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE);

    private static final Set<String> SKIP_KEYWORDS = Set.of(
            "OPENING BALANCE", "CLOSING BALANCE", "TRANSACTION TOTAL",
            "Tran Date", "Date");

    // ── BankStatementParser ───────────────────────────────────────────────────

    @Override
    public boolean supports(String bankName) {
        return "AXIS".equalsIgnoreCase(bankName);
    }

    @Override
    public List<Map<String, String>> parse(Path filePath, String contentType) throws Exception {
        if ("application/pdf".equalsIgnoreCase(contentType)
                || filePath.toString().toLowerCase().endsWith(".pdf")) {
            return parsePdf(filePath);
        }
        return parseCsv(filePath);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PDF PARSER
    // ═════════════════════════════════════════════════════════════════════════

    private List<Map<String, String>> parsePdf(Path filePath) throws Exception {

        String rawText;
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            rawText = stripper.getText(doc);
        }

        log.debug("AXIS PDF raw text length: {}", rawText.length());

        // Step 1 — extract opening and closing balances
        BigDecimal openingBalance = extractOpeningBalance(rawText);
        BigDecimal closingBalance = extractClosingBalance(rawText);
        log.info("AXIS opening balance: {}  closing balance: {}", openingBalance, closingBalance);

        // Step 2 — stitch continuation lines
        List<String> lines    = Arrays.asList(rawText.split("\r?\n"));
        List<String> stitched = stitchContinuationLines(lines);

        // Step 3 — parse each stitched transaction line
        BigDecimal prevBalance = openingBalance;
        List<Map<String, String>> rows = new ArrayList<>();

        for (String line : stitched) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher dm = DATE_LINE.matcher(line);
            if (!dm.matches()) continue;

            String date = dm.group(1);   // DD-MM-YYYY
            String rest = dm.group(2).trim();

            // Skip header / summary lines
            boolean skip = false;
            for (String kw : SKIP_KEYWORDS) {
                if (rest.toUpperCase().startsWith(kw.toUpperCase())) { skip = true; break; }
            }
            if (skip) continue;

            // Extract all decimal numbers from rest
            List<BigDecimal> nums   = new ArrayList<>();
            List<String>     numRaw = new ArrayList<>();
            Matcher am = AMOUNT_PAT.matcher(rest);
            while (am.find()) {
                String tok = am.group();
                nums.add(parseBD(tok));
                numRaw.add(tok.replace(",", ""));
            }

            if (nums.size() < 2) {
                log.debug("AXIS skip (fewer than 2 numbers): [{}]", line);
                continue;
            }

            BigDecimal curBalance = nums.get(nums.size() - 1);
            String     balStr     = numRaw.get(nums.size() - 1);
            BigDecimal txAmount   = nums.get(nums.size() - 2);
            String     amtStr     = numRaw.get(nums.size() - 2);

            // Description = everything before the first AMOUNT_PAT match
            Matcher fm = AMOUNT_PAT.matcher(rest);
            String desc = rest;
            if (fm.find()) {
                desc = rest.substring(0, fm.start()).trim();
            }
            desc = desc.replaceAll("\\s+\\d{3,4}$", "").trim();
            if (desc.isEmpty()) desc = "AXIS Transaction";

            // Debit / Credit via balance delta
            boolean isCredit;
            if (prevBalance != null && curBalance != null) {
                int cmp = curBalance.compareTo(prevBalance);
                isCredit = cmp > 0;

                BigDecimal delta = curBalance.subtract(prevBalance).abs();
                if (delta.subtract(txAmount).abs().compareTo(BigDecimal.valueOf(0.05)) > 0) {
                    log.warn("AXIS delta mismatch: prev={} cur={} delta={} amount={} desc=[{}]",
                            prevBalance, curBalance, delta, txAmount, desc);
                }
            } else {
                isCredit = isCreditKeyword(desc);
                log.warn("AXIS keyword fallback (no prevBalance): [{}] → {}",
                        desc, isCredit ? "CREDIT" : "DEBIT");
            }

            prevBalance = curBalance;

            String isoDate = toIsoDate(date);

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        isoDate);
            row.put("description", desc);
            if (isCredit) {
                row.put("credit", amtStr);
                row.put("debit",  "");
            } else {
                row.put("debit",  amtStr);
                row.put("credit", "");
            }
            row.put("balance", balStr);
            rows.add(row);
        }

        // Step 4 — inject balance metadata into the FIRST row so
        //           StatementParser.enrichStatementMetadata() can persist them.
        injectBalanceMetadata(rows, openingBalance, closingBalance);

        log.info("AXIS PDF → {} transactions parsed", rows.size());
        return rows;
    }

    /**
     * Injects __opening_balance__ and __closing_balance__ into the first row.
     * StatementParser reads these special keys to populate Statement fields.
     */
    private void injectBalanceMetadata(List<Map<String, String>> rows,
                                       BigDecimal opening,
                                       BigDecimal closing) {
        if (rows.isEmpty()) return;
        Map<String, String> first = rows.get(0);
        if (opening != null) first.put("__opening_balance__", opening.toPlainString());
        if (closing != null) first.put("__closing_balance__", closing.toPlainString());
    }

    /**
     * Stitch continuation lines onto their parent date-bearing line.
     */
    private List<String> stitchContinuationLines(List<String> lines) {
        List<String> result  = new ArrayList<>();
        String       current = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            boolean startsWithDate = line.matches("^\\d{2}-\\d{2}-\\d{4}.*");
            boolean hasDecimal     = AMOUNT_PAT.matcher(line).find();

            if (startsWithDate) {
                if (current != null) result.add(current);
                current = line;
            } else if (current != null && !hasDecimal) {
                current = current + " " + line;
            } else {
                if (current != null) { result.add(current); current = null; }
            }
        }
        if (current != null) result.add(current);
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CSV PARSER
    // ═════════════════════════════════════════════════════════════════════════

    private List<Map<String, String>> parseCsv(Path filePath) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> all = reader.readAll();
            if (all.size() < 2) return rows;

            int      headerIdx = findHeaderRow(all);
            String[] headers   = all.get(headerIdx);

            int colDate = -1, colDesc = -1, colDebit = -1, colCredit = -1, colBalance = -1;
            for (int j = 0; j < headers.length; j++) {
                String h = headers[j].trim().toLowerCase();
                if ((h.contains("tran date") || h.equals("date")) && colDate < 0)         colDate    = j;
                else if ((h.contains("particulars") || h.contains("description")) && colDesc < 0)
                    colDesc = j;
                else if (h.equals("debit") || h.contains("withdrawal"))                   colDebit   = j;
                else if (h.equals("credit") || h.contains("deposit"))                     colCredit  = j;
                else if (h.contains("balance"))                                            colBalance = j;
            }

            if (colDate < 0 || colDesc < 0) {
                log.warn("AXIS CSV: could not find date/description columns in headers: {}",
                        Arrays.toString(headers));
                return rows;
            }

            BigDecimal prevBal        = null;
            BigDecimal openingBalance = null;
            BigDecimal closingBalance = null;

            for (int i = headerIdx + 1; i < all.size(); i++) {
                String[] vals = all.get(i);
                if (vals.length <= Math.max(colDate, colDesc)) continue;

                String dateRaw   = safe(vals, colDate);
                String desc      = safe(vals, colDesc);
                if (dateRaw.isEmpty() || desc.isEmpty()) continue;

                String debitRaw  = colDebit   >= 0 ? safe(vals, colDebit)   : "";
                String creditRaw = colCredit  >= 0 ? safe(vals, colCredit)  : "";
                String balRaw    = colBalance >= 0 ? safe(vals, colBalance) : "";

                BigDecimal debit   = parseBD(cleanAmt(debitRaw));
                BigDecimal credit  = parseBD(cleanAmt(creditRaw));
                BigDecimal balance = parseBD(cleanAmt(balRaw));

                if (debit  != null && debit.compareTo(BigDecimal.ZERO)  == 0) debit  = null;
                if (credit != null && credit.compareTo(BigDecimal.ZERO) == 0) credit = null;

                boolean isCredit;
                if (prevBal != null && balance != null && (debit != null || credit != null)) {
                    isCredit = balance.compareTo(prevBal) > 0;
                } else if (debit != null && credit == null) {
                    isCredit = false;
                } else if (credit != null && debit == null) {
                    isCredit = true;
                } else {
                    isCredit = isCreditKeyword(desc);
                }

                if (openingBalance == null && prevBal == null && balance != null) {
                    // Infer opening balance from first row:
                    // openingBalance = balance +/- firstTxAmount
                    BigDecimal firstAmt = isCredit
                            ? (credit != null ? credit : debit)
                            : (debit  != null ? debit  : credit);
                    if (firstAmt != null) {
                        openingBalance = isCredit
                                ? balance.subtract(firstAmt)
                                : balance.add(firstAmt);
                    }
                }

                prevBal       = balance;
                closingBalance = balance;   // last non-null balance = closing

                BigDecimal amount = isCredit
                        ? (credit != null ? credit : debit)
                        : (debit  != null ? debit  : credit);
                if (amount == null) continue;

                String isoDate = toIsoDate(dateRaw.trim());

                Map<String, String> row = new LinkedHashMap<>();
                row.put("date",        isoDate);
                row.put("description", desc.trim());
                row.put(isCredit ? "credit" : "debit",   amount.toPlainString());
                row.put(isCredit ? "debit"  : "credit",  "");
                row.put("balance", balance != null ? balance.toPlainString() : "");
                rows.add(row);
            }

            injectBalanceMetadata(rows, openingBalance, closingBalance);
        }

        log.info("AXIS CSV → {} transactions parsed", rows.size());
        return rows;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private BigDecimal extractOpeningBalance(String text) {
        Matcher m = OPENING_BAL.matcher(text);
        if (m.find()) return parseBD(m.group(1));

        // Fallback: scan lines for "OPENING" followed by an amount on the same
        // or next line (some PDF renders split the label and value).
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toUpperCase().contains("OPENING")) {
                for (int j = i; j <= Math.min(i + 1, lines.length - 1); j++) {
                    Matcher am = AMOUNT_PAT.matcher(lines[j]);
                    if (am.find()) return parseBD(am.group());
                }
            }
        }
        log.warn("AXIS: opening balance not found");
        return null;
    }

    private BigDecimal extractClosingBalance(String text) {
        Matcher m = CLOSING_BAL.matcher(text);
        if (m.find()) return parseBD(m.group(1));

        // Fallback: last occurrence of an amount near "CLOSING"
        String[] lines = text.split("\r?\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].toUpperCase().contains("CLOSING")) {
                for (int j = i; j <= Math.min(i + 1, lines.length - 1); j++) {
                    Matcher am = AMOUNT_PAT.matcher(lines[j]);
                    BigDecimal last = null;
                    while (am.find()) last = parseBD(am.group());
                    if (last != null) return last;
                }
            }
        }
        log.warn("AXIS: closing balance not found");
        return null;
    }

    private int findHeaderRow(List<String[]> rows) {
        for (int i = 0; i < Math.min(rows.size(), 20); i++) {
            String joined = String.join("|", rows.get(i)).toLowerCase();
            if (joined.contains("particulars") || joined.contains("tran date")
                    || joined.contains("description")) return i;
        }
        return 0;
    }

    /** Convert DD-MM-YYYY or DD/MM/YYYY → YYYY-MM-DD */
    private String toIsoDate(String raw) {
        if (raw == null || raw.length() < 8) return raw;
        if (raw.matches("\\d{2}[-/]\\d{2}[-/]\\d{4}")) {
            String[] p = raw.split("[-/]");
            return p[2] + "-" + p[1] + "-" + p[0];
        }
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) return raw;
        return raw;
    }

    private boolean isCreditKeyword(String desc) {
        if (desc == null) return false;
        String lo = desc.toLowerCase();
        return lo.contains("salary")    || lo.contains("stipend")   ||
                lo.contains("neft cr")   || lo.contains("/cr/")      ||
                lo.contains("credit")    || lo.contains("refund")    ||
                lo.contains("cashback")  || lo.contains("reversal")  ||
                lo.contains("interest")  || lo.contains("dividend")  ||
                lo.contains("deposit")   || lo.contains("inward")    ||
                lo.contains("receipt")   || lo.contains("received")  ||
                lo.contains("payroll")   || (lo.contains("trf") && lo.contains("school"));
    }

    private BigDecimal parseBD(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return new BigDecimal(raw.replace(",", "").trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String cleanAmt(String v) {
        return v == null ? "" : v.replace(",", "").replace("-", "").trim();
    }

    private String safe(String[] arr, int idx) {
        return (arr == null || idx < 0 || idx >= arr.length)
                ? "" : (arr[idx] == null ? "" : arr[idx].trim());
    }
}