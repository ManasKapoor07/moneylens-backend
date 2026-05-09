package com.moneylens.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Component
public class GenericStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(GenericStatementParser.class);

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
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .toList());
    }

    private List<Map<String, String>> parseHeuristic(List<String> lines) {
        List<Map<String, String>> rows = new ArrayList<>();
        Pattern datePat = Pattern.compile(
                "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\b");
        Pattern amtPat  = Pattern.compile("[\\d,]+\\.\\d{2}");

        for (String line : lines) {
            Matcher dm = datePat.matcher(line);
            if (!dm.find()) continue;

            String date = dm.group();
            String rest = line.substring(dm.end()).trim();
            Matcher amtM = amtPat.matcher(rest);
            List<String> amounts = new ArrayList<>();
            String description = rest;

            while (amtM.find()) {
                if (amounts.isEmpty()) description = rest.substring(0, amtM.start()).trim();
                amounts.add(amtM.group().replace(",", ""));
            }

            if (amounts.isEmpty()) continue;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("date",        date);
            row.put("description", description.isBlank() ? "Unknown" : description);
            if (amounts.size() >= 2) {
                row.put("balance", amounts.get(amounts.size() - 1));
                row.put("credit",  amounts.get(amounts.size() - 2));
            } else {
                row.put("credit", amounts.get(0));
            }
            rows.add(row);
        }

        log.info("Generic heuristic parsed {} rows", rows.size());
        return rows;
    }
}