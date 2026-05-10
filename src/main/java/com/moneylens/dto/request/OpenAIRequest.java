package com.moneylens.dto.request;


import java.util.List;

public record OpenAIRequest(
        String model,
        List<Message> messages,
        double temperature
) {
    public record Message(
            String role,
            String content
    ) {}
}