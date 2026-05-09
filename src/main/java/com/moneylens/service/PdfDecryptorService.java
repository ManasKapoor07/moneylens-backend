package com.moneylens.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Service
public class PdfDecryptorService {

    private static final Logger log = LoggerFactory.getLogger(PdfDecryptorService.class);

    /**
     * Returns the raw bytes of the PDF, decrypted if a password is supplied.
     *
     * If the PDF is not password-protected, the original bytes are returned as-is.
     * If the PDF is password-protected and no password (or wrong password) is given,
     * throws IllegalArgumentException so the controller can return a clean 400.
     *
     * @param file     uploaded multipart file
     * @param password nullable — sent by the user from the frontend
     * @return decrypted PDF bytes ready for text extraction
     */
    public byte[] decrypt(MultipartFile file, String password) throws IOException {

        byte[] fileBytes = file.getBytes();

        // Try opening without a password first (handles non-protected PDFs)
        try (PDDocument doc = Loader.loadPDF(fileBytes)) {
            if (!doc.isEncrypted()) {
                // Not encrypted — return original bytes directly
                return fileBytes;
            }
        } catch (InvalidPasswordException e) {
            // Document is encrypted and needs a password — fall through
        }

        // Document is encrypted — attempt with the provided password
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "This PDF is password-protected. Please enter the password (e.g. your date of birth).");
        }

        try (PDDocument doc = Loader.loadPDF(fileBytes, password)) {
            // Decryption succeeded — save to an unencrypted byte array
            doc.setAllSecurityToBeRemoved(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("PDF decrypted successfully: {}", file.getOriginalFilename());
            return out.toByteArray();
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException(
                    "Incorrect PDF password. Please check and try again.");
        }
    }
}