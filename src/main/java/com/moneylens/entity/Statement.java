package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "statements",
        uniqueConstraints = {
                @UniqueConstraint(
                        name        = "uq_statement_file_hash",
                        columnNames = {"user_id", "file_hash"}
                ),
                @UniqueConstraint(
                        name = "uq_statement_file_period",
                        columnNames = {"user_id", "file_name", "period_from", "period_to"}
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

    @Column(name = "file_hash")
    private String fileHash;

    // ── Account metadata ─────────────────────────────────────────

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    // ── Balance fields (populated by parser) ─────────────────────

    @Column(name = "opening_balance", precision = 15, scale = 2)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance", precision = 15, scale = 2)
    private BigDecimal closingBalance;

    // ── Status ───────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UPLOADED;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Status {
        UPLOADED,
        PARSING,
        EXTRACTING,
        ANALYSING,
        COMPLETED,
        FAILED
    }

    // ── Builder ──────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Statement s = new Statement();

        public Builder user(User v)                 { s.user             = v; return this; }
        public Builder originalFileName(String v)   { s.originalFileName = v; return this; }
        public Builder savedFileName(String v)      { s.savedFileName    = v; return this; }
        public Builder fileType(String v)           { s.fileType         = v; return this; }
        public Builder fileHash(String v)           { s.fileHash         = v; return this; }
        public Builder bankName(String v)           { s.bankName         = v; return this; }
        public Builder fileName(String v) { s.fileName = v; return this; }
        public Builder accountName(String v)        { s.accountName      = v; return this; }
        public Builder periodFrom(LocalDate v)      { s.periodFrom       = v; return this; }
        public Builder periodTo(LocalDate v)        { s.periodTo         = v; return this; }
        public Builder openingBalance(BigDecimal v) { s.openingBalance   = v; return this; }
        public Builder closingBalance(BigDecimal v) { s.closingBalance   = v; return this; }
        public Builder status(Status v)             { s.status           = v; return this; }

        public Statement build() { return s; }
    }

    // ── Getters ──────────────────────────────────────────────────

    public UUID getId()                  { return id; }
    public User getUser()                { return user; }
    public String getOriginalFileName()  { return originalFileName; }
    public String getSavedFileName()     { return savedFileName; }
    public String getFileType()          { return fileType; }
    public String getFileHash()          { return fileHash; }
    public String getBankName()          { return bankName; }
    public String getFileName() { return fileName; }
    public String getAccountName()       { return accountName; }
    public LocalDate getPeriodFrom()     { return periodFrom; }
    public LocalDate getPeriodTo()       { return periodTo; }
    public BigDecimal getOpeningBalance(){ return openingBalance; }
    public BigDecimal getClosingBalance(){ return closingBalance; }
    public Status getStatus()            { return status; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    // ── Setters ──────────────────────────────────────────────────

    public void setStatus(Status status)            { this.status         = status; }
    public void setBankName(String v)               { this.bankName       = v; }
    public void setFileName(String v) { this.fileName = v; }
    public void setAccountName(String v)            { this.accountName    = v; }
    public void setPeriodFrom(LocalDate v)          { this.periodFrom     = v; }
    public void setPeriodTo(LocalDate v)            { this.periodTo       = v; }
    public void setFileHash(String v)               { this.fileHash       = v; }
    public void setOpeningBalance(BigDecimal v)     { this.openingBalance = v; }
    public void setClosingBalance(BigDecimal v)     { this.closingBalance = v; }
}