package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.entity.FinancialProfile;
import com.moneylens.entity.Statement;
import com.moneylens.repository.FinancialProfileRepository;

import org.springframework.stereotype.Service;

@Service
public class FinancialProfilePersistenceService {

    private final FinancialProfileRepository repository;

    private final ObjectMapper objectMapper;

    public FinancialProfilePersistenceService(
            FinancialProfileRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public FinancialProfile saveProfile(
            Statement statement,
            AIContextBuilderService.AIContext context
    ) {

        try {

            String json =
                    objectMapper.writeValueAsString(
                            context
                    );

            FinancialProfile profile =
                    repository
                            .findByStatementId(
                                    statement.getId()
                            )
                            .orElse(new FinancialProfile());

            profile.setUser(
                    statement.getUser()
            );

            profile.setStatement(statement);

            profile.setContextJson(json);

            profile.setSchemaVersion("v1");

            // ==================================
            // SAVE HEALTH METADATA
            // ==================================

            if (context.healthScore() != null) {

                profile.setHealthScore(
                        context.healthScore().score()
                );

                profile.setRiskLevel(
                        context.healthScore().label()
                );
            }

            return repository.save(profile);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to persist financial profile",
                    e
            );
        }
    }
}