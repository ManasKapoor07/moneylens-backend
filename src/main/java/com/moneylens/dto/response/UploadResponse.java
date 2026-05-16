package com.moneylens.dto.response;

import java.util.UUID;

public class UploadResponse {
    private UUID statementId;
    private String fileName;
    private String status;
    private boolean hasStatement;
    private String detectedBank;

    public UUID getStatementId()     { return statementId; }
    public String getFileName()      { return fileName; }
    public String getStatus()        { return status; }
    public boolean isHasStatement()  { return hasStatement; }
    public String getDetectedBank()  { return detectedBank; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final UploadResponse r = new UploadResponse();
        public Builder statementId(UUID v)    { r.statementId  = v; return this; }
        public Builder fileName(String v)     { r.fileName     = v; return this; }
        public Builder status(String v)       { r.status       = v; return this; }
        public Builder hasStatement(boolean v){ r.hasStatement = v; return this; }
        public Builder detectedBank(String v) { r.detectedBank = v; return this; }
        public UploadResponse build()         { return r; }
    }
}