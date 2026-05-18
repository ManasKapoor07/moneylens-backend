package com.moneylens.controller;

import com.moneylens.dto.response.*;
import com.moneylens.service.DashboardService;
import com.moneylens.service.StatementService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class StatementController {

    private final StatementService statementService;
    private final DashboardService dashboardService;

    public StatementController(
            StatementService statementService,
            DashboardService dashboardService
    ) {
        this.statementService = statementService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardSummaryDto>> getDashboard(
            Authentication authentication
    ) {
        String email = authentication.getName();
        return ResponseEntity.ok(
                ApiResponse.success(
                        "Dashboard loaded",
                        dashboardService.buildSummary(email, null)
                )
        );
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<PagedTransactionResponse>> getAllTransactions(
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(required = false)      String type,
            @RequestParam(required = false)      String category,
            Authentication authentication
    ) {
        int safeSize = Math.min(size, 100);
        Sort.Direction direction = "asc".equalsIgnoreCase(dir)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, safeSize, Sort.by(direction, sort));
        String email = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(
                "Transactions fetched successfully",
                statementService.getAllTransactionsPaged(email, type, category, pageable)
        ));
    }

    @GetMapping("/weekly-spend")
    public ResponseEntity<ApiResponse<List<WeeklySpendDto>>> getAllWeeklySpend(
            Authentication authentication
    ) {
        String email = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(
                "Weekly spend fetched successfully",
                statementService.getAllWeeklySpend(email)
        ));
    }

    @GetMapping("/recurring")
    public ResponseEntity<ApiResponse<List<RecurringChargeDto>>> getAllRecurringCharges(
            Authentication authentication
    ) {
        String email = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(
                "Recurring charges fetched successfully",
                statementService.getAllRecurringCharges(email)
        ));
    }

    @GetMapping("/statements")
    public ResponseEntity<ApiResponse<List<StatementDetailDto>>> getAll(
            Authentication authentication
    ) {
        String email = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(
                "Statements fetched successfully",
                statementService.getAllForUser(email)
        ));
    }

    @GetMapping("/statements/ids")
    public ResponseEntity<ApiResponse<List<StatementIdWithBankDto>>> getStatementIds(
            Authentication authentication
    ) {
        String email = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(
                "Statement IDs fetched successfully",
                statementService.getIdsWithBankForUser(email)
        ));
    }

    @GetMapping("/statements/{id}")
    public ResponseEntity<ApiResponse<StatementDetailDto>> getOne(
            @PathVariable java.util.UUID id,
            Authentication authentication
    ) {
        String email = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(
                "Statement fetched successfully",
                statementService.getOneForUser(id, email)
        ));
    }
}