package com.moneylens.service;

import com.moneylens.dto.response.UploadResponse;
import com.moneylens.entity.Statement;
import com.moneylens.entity.User;
import com.moneylens.exception.UserNotFoundException;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final String STORAGE_DIR = "storage/private";

    private final UserRepository userRepository;
    private final StatementRepository statementRepository;
    private final StatementParser statementParser;
    private final PdfDecryptorService pdfDecryptorService;

    public UploadService(
            UserRepository userRepository,
            StatementRepository statementRepository,
            StatementParser statementParser,
            PdfDecryptorService pdfDecryptorService
    ) {
        this.userRepository      = userRepository;
        this.statementRepository = statementRepository;
        this.statementParser     = statementParser;
        this.pdfDecryptorService = pdfDecryptorService;
    }

    @Transactional
    public UploadResponse handleUpload(MultipartFile file, String password, String userEmail) {

        // 1. Fetch user
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // 2. Compute file hash BEFORE decryption (raw bytes = best dedup key)
        String fileHash = computeHash(file);

        // 3. Duplicate check — same file uploaded again?
        if (statementRepository.existsByUserAndFileHash(user, fileHash)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This statement has already been uploaded. " +
                            "If this is a different period, please contact support.");
        }

        // 4. For PDFs: decrypt first (no-op if not encrypted), then save to disk.
        //    For CSV/Excel: save the raw stream directly.
        String savedFileName = isPdf(file)
                ? decryptAndSave(file, password)
                : saveFileToDisk(file);

        Path filePath = Paths.get(STORAGE_DIR).resolve(savedFileName);

        // 5. Create statement record in DB (metadata fields populated later by parser)
        Statement statement = Statement.builder()
                .user(user)
                .originalFileName(file.getOriginalFilename())
                .savedFileName(savedFileName)
                .fileType(file.getContentType())
                .fileHash(fileHash)
                .status(Statement.Status.UPLOADED)
                .build();

        statement = statementRepository.save(statement);
        log.info("Statement saved: {} for user: {}", statement.getId(), userEmail);

        // 6. Flip hasStatement flag on user
        user.setHasStatement(true);
        userRepository.save(user);

        // 7. Trigger parsing pipeline asynchronously
        //    Parser will populate bankName, accountNumber, periodFrom, periodTo
        //    and then check for overlapping period duplicates before proceeding.
        statementParser.parse(statement.getId(), filePath, file.getContentType());

        return UploadResponse.builder()
                .statementId(statement.getId())
                .fileName(file.getOriginalFilename())
                .status("PROCESSING")
                .hasStatement(true)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    private String computeHash(MultipartFile file) {
        try {
            return DigestUtils.md5DigestAsHex(file.getBytes());
        } catch (IOException e) {
            log.error("Failed to compute file hash", e);
            throw new RuntimeException("Failed to read file", e);
        }
    }

    private boolean isPdf(MultipartFile file) {
        return "application/pdf".equals(file.getContentType());
    }

    private String decryptAndSave(MultipartFile file, String password) {
        try {
            byte[] decryptedBytes = pdfDecryptorService.decrypt(file, password);
            return saveBytesToDisk(decryptedBytes, file.getOriginalFilename());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to decrypt/save PDF", e);
            throw new RuntimeException("Failed to process PDF", e);
        }
    }

    private String saveFileToDisk(MultipartFile file) {
        try {
            ensureStorageDir();
            String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path target = Paths.get(STORAGE_DIR).resolve(fileName);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return fileName;
        } catch (IOException e) {
            log.error("Failed to save file to disk", e);
            throw new RuntimeException("Failed to save file", e);
        }
    }

    private String saveBytesToDisk(byte[] bytes, String originalName) throws IOException {
        ensureStorageDir();
        String fileName = UUID.randomUUID() + "_" + originalName;
        Files.write(Paths.get(STORAGE_DIR).resolve(fileName), bytes);
        return fileName;
    }

    private void ensureStorageDir() throws IOException {
        Path uploadPath = Paths.get(STORAGE_DIR);
        if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);
    }
}