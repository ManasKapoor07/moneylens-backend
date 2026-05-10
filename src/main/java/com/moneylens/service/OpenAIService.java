

package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.moneylens.dto.request.OpenAIRequest;
import com.moneylens.dto.response.OpenAIResponse;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.springframework.stereotype.Service;

import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class OpenAIService {

    @Value("${openai.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate =
            new RestTemplate();

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    public String analyze(String prompt) {

        try {

            HttpHeaders headers =
                    new HttpHeaders();

            headers.setContentType(
                    MediaType.APPLICATION_JSON
            );

            headers.setBearerAuth(apiKey);

            OpenAIRequest request =
                    new OpenAIRequest(

                            "gpt-4.1-mini",

                            List.of(
                                    new OpenAIRequest.Message(
                                            "user",
                                            prompt
                                    )
                            ),

                            0.4
                    );

            HttpEntity<OpenAIRequest> entity =
                    new HttpEntity<>(
                            request,
                            headers
                    );

            OpenAIResponse response =
                    restTemplate.postForObject(

                            "https://api.openai.com/v1/chat/completions",

                            entity,

                            OpenAIResponse.class
                    );

            if (
                    response == null
                            || response.choices() == null
                            || response.choices().isEmpty()
            ) {
                throw new RuntimeException(
                        "Empty OpenAI response"
                );
            }

            return response
                    .choices()
                    .get(0)
                    .message()
                    .content();

        } catch (Exception e) {

            throw new RuntimeException(
                    "OpenAI request failed",
                    e
            );
        }
    }
}