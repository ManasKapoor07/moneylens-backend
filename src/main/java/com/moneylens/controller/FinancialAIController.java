package com.moneylens.controller;

import com.moneylens.dto.response.AIAnalysisResponse;

import com.moneylens.service.FinancialAIAnalysisService;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/financial-profiles")
public class FinancialAIController {

    private final FinancialAIAnalysisService service;

    public FinancialAIController(
            FinancialAIAnalysisService service
    ) {
        this.service = service;
    }

    @PostMapping("/{statementId}/analyze")
    public AIAnalysisResponse analyze(
            @PathVariable UUID statementId
    ) {

        return service.analyze(statementId);
    }
}