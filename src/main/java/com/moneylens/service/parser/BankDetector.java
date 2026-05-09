package com.moneylens.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class BankDetector {

    private static final Logger log = LoggerFactory.getLogger(BankDetector.class);

    // Entry point — filename try karo, phir content
    public String detect(String fileName, String contentType) {
        String fromFileName = detectFromFileName(fileName);
        if (!"GENERIC".equals(fromFileName)) return fromFileName;
        return fromFileName; // content detection parse() mein hogi
    }

    public String detectFromFileName(String fileName) {
        if (fileName == null) return "GENERIC";
        String lower = fileName.toLowerCase();
        if (lower.contains("hdfc"))  return "HDFC";
        if (lower.contains("icici")) return "ICICI";
        if (lower.contains("axis"))  return "AXIS";
        if (lower.contains("sbi"))   return "SBI";
        if (lower.contains("pnb"))   return "PNB";
        return "GENERIC";
    }

    public String detectFromContent(Path filePath) {
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setEndPage(1);
            String text = s.getText(doc).toLowerCase();

            if (text.contains("hdfc bank"))                              return "HDFC";
            if (text.contains("icici bank"))                             return "ICICI";
            if (text.contains("axis bank"))                              return "AXIS";
            if (text.contains("state bank of india") || text.contains("sbi")) return "SBI";
            if (text.contains("punjab national bank") || text.contains("pnb")) return "PNB";

        } catch (Exception e) {
            log.warn("Could not read PDF for bank detection: {}", e.getMessage());
        }
        return "GENERIC";
    }
}