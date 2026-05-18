package com.moneylens.config;

import com.moneylens.service.UserProfileAggregatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * ApplicationStartupRunner
 *
 * Runs once after the Spring context is fully initialized.
 *
 * Responsibilities:
 *   1. Backfill dedup_hash on existing transactions (no-op on clean installs)
 *   2. Backfill UserFinancialProfile for users who have completed statements
 *      but no profile row (no-op after first run)
 *
 * Both operations are idempotent — safe to run on every deploy.
 * They skip rows that are already populated.
 *
 * On a clean install with no data, both jobs complete immediately.
 * On an existing install with 10k transactions, backfillDedupHashes()
 * may take 5–15 seconds — this is acceptable at startup.
 */
@Component
public class ApplicationStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApplicationStartupRunner.class);

    private final UserProfileAggregatorService userProfileAggregatorService;

    public ApplicationStartupRunner(UserProfileAggregatorService userProfileAggregatorService) {
        this.userProfileAggregatorService = userProfileAggregatorService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== ApplicationStartupRunner: beginning post-startup jobs ===");

        // Step 1: Stamp dedup hashes on any legacy transactions
        try {
            userProfileAggregatorService.backfillDedupHashes();
        } catch (Exception e) {
            log.error("dedup_hash backfill failed — non-fatal, continuing startup", e);
        }

        // Step 2: Build UserFinancialProfile for users who don't have one yet
        try {
            userProfileAggregatorService.backfillMissingProfiles();
        } catch (Exception e) {
            log.error("UserFinancialProfile backfill failed — non-fatal, continuing startup", e);
        }

        log.info("=== ApplicationStartupRunner: complete ===");
    }
}