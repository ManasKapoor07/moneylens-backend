package com.moneylens;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.request.LoginRequest;
import com.moneylens.dto.request.RegisterRequest;
import com.moneylens.repository.RefreshTokenRepository;
import com.moneylens.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

    // Wipe DB before each test so there's no state bleed between tests
    @BeforeEach
    void cleanDb() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void register_shouldReturn201_withTokens() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setFullName("Manas Test");
        request.setEmail("manas.test@moneylens.com");
        request.setPassword("SecurePass123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andExpect(jsonPath("$.data.user.email").value("manas.test@moneylens.com"));
    }

    @Test
    void register_shouldReturn409_onDuplicateEmail() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setFullName("Duplicate User");
        request.setEmail("duplicate@moneylens.com");
        request.setPassword("SecurePass123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void login_shouldReturn200_withValidCredentials() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setFullName("Login Test User");
        registerRequest.setEmail("login.test@moneylens.com");
        registerRequest.setPassword("SecurePass123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("login.test@moneylens.com");
        loginRequest.setPassword("SecurePass123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    void login_shouldReturn401_withWrongPassword() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("nonexistent@moneylens.com");
        loginRequest.setPassword("WrongPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_shouldReturn401_withoutToken() throws Exception {
        // Uses a real protected GET endpoint instead of GET /logout-all
        // which is a POST endpoint and returns 405 before auth is checked
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_shouldReturn400_withInvalidEmail() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setFullName("Test");
        request.setEmail("not-an-email");
        request.setPassword("SecurePass123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.email").exists());
    }
}