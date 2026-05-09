package com.moneylens.service.parser;

import com.opencsv.CSVReader;
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
public class IciciStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(IciciStatementParser.class);

    // ICICI format: Transaction Date, Value Date, Description, Ref No, Debit, Credit, Balance
    private static final Pattern ICICI_PDF_PATTERN = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4})" +          // transaction date
                    "\\s+(\\d{2}-\\d{2}-\\d{4})?" +       // value date (optional)
                    "\\s+(.+?)" +                          // description
                    "\\s+(\\d{6,})" +                      // ref number
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +        // debit (optional)
                    "(?:\\s+([\\d,]+\\.\\d{2}))?" +        // credit (optional)
                    "\\s+([\\d,]+\\.\\d{2})$"              // balance
    );

    @Override
    public boolean supports(String bankName) {
        return "ICICI".equals(bankName);
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
            if (!line.matches("^\\d{2}-\\d{2}-\\d{4}.*")) continue;
            Matcher m = ICICI_PDF_PATTERN.matcher(line);
            if (!m.matches()) continue;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        m.group(1));
            row.put("description", m.group(3).trim());
            if (m.group(5) != null) row.put("debit",   m.group(5).replace(",", ""));
            if (m.group(6) != null) row.put("credit",  m.group(6).replace(",", ""));
            if (m.group(7) != null) row.put("balance", m.group(7).replace(",", ""));
            rows.add(row);
        }

        log.info("ICICI PDF parsed {} rows", rows.size());
        return rows;
    }

    private List<Map<String, String>> parseCsv(Path filePath) throws Exception {
        // ICICI CSV: Transaction Date,Value Date,Description,Ref No./Cheque No.,Debit,Credit,Balance
        List<Map<String, String>> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new FileReader(filePath.toFile()))) {
            List<String[]> all = reader.readAll();
            if (all.size() < 2) return rows;

            int headerIdx = 0;
            for (int i = 0; i < Math.min(all.size(), 10); i++) {
                String joined = String.join(",", all.get(i)).toLowerCase();
                if (joined.contains("description") && joined.contains("debit")) {
                    headerIdx = i; break;
                }
            }

            String[] headers = all.get(headerIdx);
            for (int i = headerIdx + 1; i < all.size(); i++) {
                String[] vals = all.get(i);
                if (vals.length < 5) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.length && j < vals.length; j++) {
                    String h = headers[j].trim().toLowerCase();
                    String v = vals[j].trim();
                    if (h.contains("transaction date"))
                        row.put("date", v);
                    else if (h.contains("description"))
                        row.put("description", v);
                    else if (h.equals("debit"))
                        row.put("debit", v.replace(",", ""));
                    else if (h.equals("credit"))
                        row.put("credit", v.replace(",", ""));
                    else if (h.equals("balance"))
                        row.put("balance", v.replace(",", ""));
                }
                if (row.containsKey("date") && !row.get("date").isBlank())
                    rows.add(row);
            }
        }
        log.info("ICICI CSV parsed {} rows", rows.size());
        return rows;
    }
}