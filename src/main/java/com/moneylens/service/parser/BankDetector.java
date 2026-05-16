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
            String text = s.getText(doc);
            String lower = text.toLowerCase();

            // ── AXIS ─────────────────────────────────────────────────────────
            // Check IFSC prefix (UTIB = Axis Bank), their registered domain,
            // and scheme names unique to Axis. These are identity markers that
            // can't appear in counterparty descriptions inside UPI strings.
            if (lower.contains("utib")                        // IFSC prefix
                    || lower.contains("axis.bank.in")         // domain in footer
                    || lower.contains("liberty salary")       // axis-specific scheme
                    || lower.contains("trishul")              // axis HQ landmark
                    || lower.contains("axisbank.com")) {
                return "AXIS";
            }

            // ── ICICI ─────────────────────────────────────────────────────────
            if (lower.contains("icic")                        // IFSC prefix
                    || lower.contains("icicibank.com")) {
                return "ICICI";
            }

            // ── SBI ───────────────────────────────────────────────────────────
            if (lower.contains("sbin")                        // IFSC prefix
                    || lower.contains("onlinesbi.com")
                    || lower.contains("state bank of india")) {
                return "SBI";
            }

            // ── PNB ───────────────────────────────────────────────────────────
            if (lower.contains("punb")                        // IFSC prefix
                    || lower.contains("pnbindia.in")
                    || lower.contains("punjab national bank")) {
                return "PNB";
            }

            // ── HDFC — checked LAST ───────────────────────────────────────────
            // "HDFC Bank" is extremely common in UPI descriptions on *other*
            // banks' statements (e.g. "/Collec/HDFC BANK LTD"). Only classify
            // as HDFC when there's a clear account-ownership signal: the IFSC
            // prefix (HDFC), their domain, or their product scheme name.
            if (lower.contains("hdfc0")                       // IFSC prefix (HDFC0...)
                    || lower.contains("hdfcbank.com")         // domain
                    || lower.contains("hdfc bank acct")       // statement header phrase
                    || lower.contains("millennia")            // hdfc-specific card/scheme
                    || lower.contains("regalia")) {           // hdfc-specific card
                return "HDFC";
            }

        } catch (Exception e) {
            log.warn("Could not read PDF for bank detection: {}", e.getMessage());
        }
        return "GENERIC";
    }
}