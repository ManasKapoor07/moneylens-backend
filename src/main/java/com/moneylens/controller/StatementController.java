package com.moneylens.controller;

import com.moneylens.dto.response.ApiResponse;
import com.moneylens.dto.response.StatementDetailDto;
import com.moneylens.service.StatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/statements")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    /**
     * GET /api/v1/statements
     *
     * Returns all statements uploaded by the authenticated user,
     * each with its full transaction list and insights.
     *
     * Response shape:
     * {
     *   "success": true,
     *   "message": "Statements fetched successfully",
     *   "data": [ { StatementDetailDto }, ... ]
     * }
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<StatementDetailDto>>> getAll(
            Authentication authentication
    ) {
        String email = authentication.getName();
        List<StatementDetailDto> data = statementService.getAllForUser(email);
        return ResponseEntity.ok(ApiResponse.success("Statements fetched successfully", data));
    }

    /**
     * GET /api/v1/statements/{id}
     *
     * Returns a single statement with its transactions and insights.
     * Returns 404 if the statement doesn't exist.
     * Returns 403 if the statement belongs to a different user.
     *
     * Response shape:
     * {
     *   "success": true,
     *   "message": "Statement fetched successfully",
     *   "data": { StatementDetailDto }
     * }
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StatementDetailDto>> getOne(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String email = authentication.getName();
        StatementDetailDto data = statementService.getOneForUser(id, email);
        return ResponseEntity.ok(ApiResponse.success("Statement fetched successfully", data));
    }
}