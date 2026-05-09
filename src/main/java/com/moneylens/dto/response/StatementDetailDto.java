package com.moneylens.dto.response;

import com.moneylens.entity.Statement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class StatementDetailDto {

    private UUID id;
    private String originalFileName;
    private String fileType;
    private String status;
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
        dto.originalFileName = statement.getOriginalFileName(); // was getFileName()
        dto.fileType         = statement.getFileType();
        dto.status           = statement.getStatus().name();
        dto.createdAt        = statement.getCreatedAt();        // was getUploadedAt()
        dto.transactions     = transactions;
        dto.insights         = insights;
        return dto;
    }

    public UUID getId()                              { return id; }
    public String getOriginalFileName()              { return originalFileName; }
    public String getFileType()                      { return fileType; }
    public String getStatus()                        { return status; }
    public LocalDateTime getCreatedAt()              { return createdAt; }
    public List<TransactionDto> getTransactions()    { return transactions; }
    public List<TransactionInsightDto> getInsights() { return insights; }
}