package com.moneylens.dto.request;

import java.util.UUID;

public class ChatRequest {

    private UUID   statementId;  // which statement this chat is about
    private UUID   chatId;       // null = start a new chat, non-null = continue existing
    private String message;      // the user's message

    public UUID getStatementId() { return statementId; }
    public void setStatementId(UUID statementId) { this.statementId = statementId; }

    public UUID getChatId() { return chatId; }
    public void setChatId(UUID chatId) { this.chatId = chatId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}