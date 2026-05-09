package com.moneylens.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "statements",
        uniqueConstraints = {
                // Prevents re-uploading the exact same file bytes
                @UniqueConstraint(
                        name  = "uq_statement_file_hash",
                        columnNames = {"user_id", "file_hash"}
                ),
                // Prevents uploading a different file for the same account + period
                @UniqueConstraint(
                        name  = "uq_statement_account_period",
                        columnNames = {"user_id", "account_number", "period_from", "period_to"}
                )
        }
)
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "saved_file_name", nullable = false)
    private String savedFileName;

    @Column(name = "file_type")
    private String fileType;

    // MD5 of the uploaded file bytes — used for exact-duplicate detection
    @Column(name = "file_hash")
    private String fileHash;

    // ── Account metadata (populated by StatementParser after parsing) ──

    @Column(name = "bank_name")
    private String bankName;

    // Last 4 digits or full number — whatever the PDF exposes
    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "account_name")
    private String accountName;

    // Derived from the earliest and latest transaction dates in the file
    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UPLOADED;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Status {
        UPLOADED,    // File saved to disk
        PARSING,     // StatementParser running
        EXTRACTING,  // TransactionExtractor running
        ANALYSING,   // Insight derivation running
        COMPLETED,   // All done, dashboard ready
        FAILED       // Something went wrong
    }

    // ===== BUILDER =====

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Statement s = new Statement();

        public Builder user(User v)                { s.user             = v; return this; }
        public Builder originalFileName(String v)  { s.originalFileName = v; return this; }
        public Builder savedFileName(String v)     { s.savedFileName    = v; return this; }
        public Builder fileType(String v)          { s.fileType         = v; return this; }
        public Builder fileHash(String v)          { s.fileHash         = v; return this; }
        public Builder bankName(String v)          { s.bankName         = v; return this; }
        public Builder accountNumber(String v)     { s.accountNumber    = v; return this; }
        public Builder accountName(String v)       { s.accountName      = v; return this; }
        public Builder periodFrom(LocalDate v)     { s.periodFrom       = v; return this; }
        public Builder periodTo(LocalDate v)       { s.periodTo         = v; return this; }
        public Builder status(Status v)            { s.status           = v; return this; }

        public Statement build() { return s; }
    }

    // ===== Getters =====

    public UUID getId()                  { return id; }
    public User getUser()                { return user; }
    public String getOriginalFileName()  { return originalFileName; }
    public String getSavedFileName()     { return savedFileName; }
    public String getFileType()          { return fileType; }
    public String getFileHash()          { return fileHash; }
    public String getBankName()          { return bankName; }
    public String getAccountNumber()     { return accountNumber; }
    public String getAccountName()       { return accountName; }
    public LocalDate getPeriodFrom()     { return periodFrom; }
    public LocalDate getPeriodTo()       { return periodTo; }
    public Status getStatus()            { return status; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    // ===== Setters =====

    public void setStatus(Status status)           { this.status        = status; }
    public void setBankName(String bankName)        { this.bankName      = bankName; }
    public void setAccountNumber(String v)          { this.accountNumber = v; }
    public void setAccountName(String v)            { this.accountName   = v; }
    public void setPeriodFrom(LocalDate v)          { this.periodFrom    = v; }
    public void setPeriodTo(LocalDate v)            { this.periodTo      = v; }
    public void setFileHash(String v)               { this.fileHash      = v; }
}