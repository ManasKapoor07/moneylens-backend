package com.moneylens.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transaction_clarifications")
public class TransactionClarification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Lazy proxies — tell Jackson to ignore the Hibernate internals
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Statement statement;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "clarification_type", nullable = false)
    private ClarificationType clarificationType;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", nullable = false, columnDefinition = "jsonb")
    private List<String> options;

    @Column(name = "selected_answer")
    private String selectedAnswer;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum ClarificationType {
        RECURRING_P2P,
        UNCONFIRMED_SALARY,
        LOW_CONFIDENCE_CATEGORY
    }

    public enum Status {
        PENDING,
        RESOLVED,
        SKIPPED
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final TransactionClarification c = new TransactionClarification();
        public Builder user(User v)                          { c.user                = v; return this; }
        public Builder statement(Statement v)                { c.statement           = v; return this; }
        public Builder transactionId(UUID v)                 { c.transactionId       = v; return this; }
        public Builder clarificationType(ClarificationType v){ c.clarificationType   = v; return this; }
        public Builder questionText(String v)                { c.questionText        = v; return this; }
        public Builder options(List<String> v)               { c.options             = v; return this; }
        public TransactionClarification build()              { return c; }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()                             { return id; }
    public User getUser()                           { return user; }
    public Statement getStatement()                 { return statement; }
    public UUID getTransactionId()                  { return transactionId; }
    public ClarificationType getClarificationType() { return clarificationType; }
    public String getQuestionText()                 { return questionText; }
    public List<String> getOptions()                { return options; }
    public String getSelectedAnswer()               { return selectedAnswer; }
    public Status getStatus()                       { return status; }
    public LocalDateTime getCreatedAt()             { return createdAt; }
    public LocalDateTime getResolvedAt()            { return resolvedAt; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setSelectedAnswer(String v)   { this.selectedAnswer = v; }
    public void setStatus(Status v)           { this.status         = v; }
    public void setResolvedAt(LocalDateTime v){ this.resolvedAt     = v; }
}