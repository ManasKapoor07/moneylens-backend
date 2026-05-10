

package com.moneylens.ai.openai;

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