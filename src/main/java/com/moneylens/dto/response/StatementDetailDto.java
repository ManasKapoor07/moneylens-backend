package com.moneylens.dto.response;

import com.moneylens.entity.Statement;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class StatementDetailDto {

    private UUID id;
    private String originalFileName;
    private String fileType;
    private String status;

    private LocalDate periodFrom;
    private LocalDate periodTo;

    private LocalDateTime createdAt;

    private List<TransactionDto> transactions;
    private List<TransactionInsightDto> insights;

    private StatementDetailDto() {}

    public static StatementDetailDto from(
            Statement statement,
            List<TransactionDto> transactions,
            List<TransactionInsightDto> insights
    ) {
        StatementDetailDto dto = new StatementDetailDto();

        dto.id               = statement.getId();
        dto.originalFileName = statement.getOriginalFileName();
        dto.fileType         = statement.getFileType();
        dto.status           = statement.getStatus().name();

        dto.periodFrom       = statement.getPeriodFrom();
        dto.periodTo         = statement.getPeriodTo();

        dto.createdAt        = statement.getCreatedAt();

        dto.transactions     = transactions;
        dto.insights         = insights;

        return dto;
    }

    public UUID getId()                              { return id; }
    public String getOriginalFileName()              { return originalFileName; }
    public String getFileType()                      { return fileType; }
    public String getStatus()                        { return status; }

    public LocalDate getPeriodFrom()                 { return periodFrom; }
    public LocalDate getPeriodTo()                   { return periodTo; }

    public LocalDateTime getCreatedAt()              { return createdAt; }

    public List<TransactionDto> getTransactions()    { return transactions; }
    public List<TransactionInsightDto> getInsights() { return insights; }
}