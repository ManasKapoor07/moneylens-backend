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
public class HdfcStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(HdfcStatementParser.class);

    // HDFC format: dd/MM/yy Narration Ref# ValueDate Withdrawal Deposit Balance
    private static final Pattern HDFC_PATTERN = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{2,4})" +       // date
                    "\\s+(.+?)" +                          // narration
                    "(?:\\s+(\\d{10,16}))?" +             // ref (optional)
                    "\\s+(\\d{2}/\\d{2}/\\d{2,4})" +     // value date
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +       // withdrawal (optional)
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +       // deposit (optional)
                    "\\s+([\\d,]+\\.\\d{2})$"             // balance
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
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.matches("^\\d{2}/\\d{2}/\\d{2,4}.*")) continue;
            Matcher m = HDFC_PATTERN.matcher(line);
            if (!m.matches()) continue;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        m.group(1));
            row.put("description", m.group(2).trim());
            if (m.group(5) != null) row.put("debit",  m.group(5).replace(",", ""));
            if (m.group(6) != null) row.put("credit", m.group(6).replace(",", ""));
            if (m.group(7) != null) row.put("balance",m.group(7).replace(",", ""));
            rows.add(row);
        }

        log.info("HDFC PDF parsed {} rows", rows.size());
        return rows;
    }

    private List<Map<String, String>> parseCsv(Path filePath) throws Exception {
        // HDFC CSV format:
        // Date,Narration,Value Dat,Debit Amount,Credit Amount,Chq/Ref Number,Closing Balance
        List<Map<String, String>> rows = new ArrayList<>();
        try (var reader = new com.opencsv.CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> all = reader.readAll();
            if (all.size() < 2) return rows;

            // Find header row
            int headerIdx = 0;
            for (int i = 0; i < Math.min(all.size(), 10); i++) {
                String joined = String.join(",", all.get(i)).toLowerCase();
                if (joined.contains("narration") || joined.contains("date")) {
                    headerIdx = i; break;
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
                        row.put("debit", v.replace(",", ""));
                    else if (h.contains("credit"))
                        row.put("credit", v.replace(",", ""));
                    else if (h.contains("balance"))
                        row.put("balance", v.replace(",", ""));
                }
                if (row.containsKey("date") && !row.get("date").isBlank())
                    rows.add(row);
            }
        }
        log.info("HDFC CSV parsed {} rows", rows.size());
        return rows;
    }
}