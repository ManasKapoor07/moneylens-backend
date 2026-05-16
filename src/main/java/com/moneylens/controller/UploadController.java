package com.moneylens.controller;

import com.moneylens.dto.response.ApiResponse;
import com.moneylens.dto.response.UploadResponse;
import com.moneylens.service.UploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/statements/upload")
    public ResponseEntity<ApiResponse<UploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "bankName", required = false) String bankName,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();

        if (file.isEmpty())
            return ResponseEntity.badRequest().body(ApiResponse.error("File is empty"));

        if (file.getSize() > MAX_FILE_SIZE)
            return ResponseEntity.badRequest().body(ApiResponse.error("File exceeds 10MB limit"));

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType))
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ApiResponse.error("Only PDF, CSV, or Excel files are accepted"));

        try {
            UploadResponse response = uploadService.handleUpload(file, password, userEmail, bankName);
            return ResponseEntity.ok(ApiResponse.success("Statement uploaded successfully", response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}