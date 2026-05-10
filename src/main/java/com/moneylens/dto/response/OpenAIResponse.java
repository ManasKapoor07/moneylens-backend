package com.moneylens.dto.response;

import java.util.List;

public record OpenAIResponse(
        List<Choice> choices
) {

    public record Choice(
            Message message
    ) {}

    public record Message(
            String role,
            String content
    ) {}
}