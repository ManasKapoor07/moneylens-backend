package com.moneylens.service;

import com.moneylens.entity.MerchantRule;
import com.moneylens.repository.MerchantRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MerchantRegistry
 *
 * Single source of truth for merchant pattern → (normalizedName, category, confidence).
 *
 * Replaces:
 *   - TransactionMapper.KEYWORD_RULES  (static List<String[]>)
 *   - AIContextBuilderService.MERCHANT_ALIAS  (static Map<String, String>)
 *
 * Design:
 *   - All active rules are loaded into a CopyOnWriteArrayList at startup.
 *   - Lookups are O(n) linear scan — fast enough for a few hundred rules.
 *   - Cache is refreshed via refreshCache() after any write operation.
 *   - USER_CORRECTION rules have priority=0 so they always win over SEED (100).
 *
 * Usage:
 *   CategoryResult result = registry.resolve(narration, Transaction.Type.DEBIT);
 *   String cleanName      = registry.normalize(narration);
 */
@Service
public class MerchantRegistry {

    private static final Logger log = LoggerFactory.getLogger(MerchantRegistry.class);

    private final MerchantRuleRepository ruleRepository;

    // In-memory cache — sorted by priority asc at load time.
    private final CopyOnWriteArrayList<MerchantRule> cache = new CopyOnWriteArrayList<>();

    // Known service keywords used in P2P detection (populated from cache).
    private volatile List<String> knownServicePatterns = List.of();

    public MerchantRegistry(MerchantRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        refreshCache();
        log.info("MerchantRegistry initialised — {} active rules loaded", cache.size());
    }

    /**
     * Reload all active rules from DB into the in-memory cache.
     * Call this after any write (add / deactivate / correction).
     */
    public synchronized void refreshCache() {
        List<MerchantRule> fresh = ruleRepository.findAllActiveOrderByPriority();
        cache.clear();
        cache.addAll(fresh);
        // Rebuild the known-services list for P2P detection
        knownServicePatterns = fresh.stream()
                .map(MerchantRule::getPattern)
                .toList();
        log.debug("MerchantRegistry cache refreshed — {} rules", cache.size());
    }

    // ── Primary API ───────────────────────────────────────────────────────────

    /**
     * Resolve a raw bank narration string to a CategoryResult.
     *
     * Strategy (in order):
     *   1. Credit-side income signals (salary, refund, interest…)
     *   2. Registry pattern scan (first match wins, priority-ordered)
     *   3. P2P heuristic (UPI to individual, no merchant matched)
     *   4. Generic bank transfer (NEFT/RTGS/IMPS)
     *   5. Fallback → Other
     */
    public CategoryResult resolve(String narration, com.moneylens.entity.Transaction.Type type) {
        if (narration == null || narration.isBlank()) return CategoryResult.fallback();

        String lo = narration.toLowerCase();

        // ── Step 1: Credit-side signals ───────────────────────────────────────
        if (type == com.moneylens.entity.Transaction.Type.CREDIT) {
            if (lo.contains("salary") || lo.contains("payroll") || lo.contains("stipend"))
                return CategoryResult.creditSignal("Salary");
            if (lo.contains("interest") && !lo.contains("loan"))
                return CategoryResult.creditSignal("Interest");
            if (lo.contains("refund") || lo.contains("cashback") || lo.contains("reversal"))
                return CategoryResult.creditSignal("Refund");
            if (lo.contains("dividend"))
                return CategoryResult.creditSignal("Dividend");
            if (lo.contains("school") || lo.contains("college") || lo.contains("university"))
                return CategoryResult.creditSignal("Income");
        }

        // ── Step 2: Registry scan ─────────────────────────────────────────────
        for (MerchantRule rule : cache) {
            if (lo.contains(rule.getPattern())) {
                return CategoryResult.fromRule(
                        rule.getCategory(),
                        rule.getSubCategory(),
                        rule.getConfidence()
                );
            }
        }

        // ── Step 3: P2P heuristic ─────────────────────────────────────────────
        if (isP2P(lo)) return CategoryResult.p2pHeuristic();

        // ── Step 4: Generic bank transfer ─────────────────────────────────────
        if (lo.contains("neft") || lo.contains("rtgs") || lo.contains("imps")
                || lo.contains("trf") || lo.contains("transfer"))
            return CategoryResult.bankTransfer();

        // ── Step 5: HDFC collection catch ────────────────────────────────────
        if (lo.contains("collec") && lo.contains("hdfc"))
            return new CategoryResult("Merchant Payment", null, 0.65, "RULE");

        return CategoryResult.fallback();
    }

    /**
     * Normalize a raw narration to a clean merchant display name.
     *
     * First checks the registry for a match and returns its normalizedName.
     * Falls back to the heuristic token extractor for unknown merchants.
     *
     * Replaces:
     *   - AIContextBuilderService.normalizeMerchant()
     *   - TransactionMapper.cleanMerchant()
     */
    public String normalize(String narration) {
        if (narration == null) return "Unknown";
        String lo = narration.toLowerCase();

        for (MerchantRule rule : cache) {
            if (lo.contains(rule.getPattern())) {
                return rule.getNormalizedName();
            }
        }

        return extractFallbackName(narration);
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Record a user correction.
     *
     * If a USER_CORRECTION rule already exists for this exact pattern, update it.
     * Otherwise, insert a new rule with priority=0 (beats all SEED rules).
     * Refreshes the cache after saving.
     */
    @Transactional
    public MerchantRule recordUserCorrection(
            String pattern,
            String normalizedName,
            String category,
            String subCategory
    ) {
        String lowerPattern = pattern.toLowerCase();

        MerchantRule rule = ruleRepository
                .findByPatternAndActiveTrue(lowerPattern)
                .map(existing -> {
                    // Update existing rule
                    existing.setNormalizedName(normalizedName);
                    existing.setCategory(category);
                    existing.setSubCategory(subCategory);
                    existing.setConfidence(1.00);
                    existing.setSource(MerchantRule.Source.USER_CORRECTION);
                    existing.setPriority(0);
                    existing.setUpdatedAt(LocalDateTime.now());
                    return existing;
                })
                .orElseGet(() -> MerchantRule.userCorrection(
                        lowerPattern, normalizedName, category, subCategory
                ));

        MerchantRule saved = ruleRepository.save(rule);
        refreshCache();
        log.info("User correction recorded: '{}' → {} / {}", lowerPattern, category, normalizedName);
        return saved;
    }

    /**
     * Soft-delete a rule by ID (deactivates, does not remove).
     */
    @Transactional
    public void deactivate(Long ruleId) {
        ruleRepository.findById(ruleId).ifPresent(rule -> {
            rule.setActive(false);
            rule.setUpdatedAt(LocalDateTime.now());
            ruleRepository.save(rule);
            refreshCache();
            log.info("MerchantRule {} deactivated", ruleId);
        });
    }

    // ── Inspection ────────────────────────────────────────────────────────────

    /** Returns a snapshot of the current in-memory cache (read-only). */
    public List<MerchantRule> getCachedRules() {
        return List.copyOf(cache);
    }

    /** How many active rules are loaded. */
    public int size() {
        return cache.size();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * P2P heuristic: UPI transfer to an individual (no known merchant matched).
     * A transaction is P2P if:
     *   - description contains "upi"
     *   - AND none of the known merchant patterns matched (already checked above)
     *   - AND it looks like a person-to-account transfer (p2a/p2p prefix, or @handle)
     */
    private boolean isP2P(String lower) {
        if (!lower.contains("upi")) return false;
        // At this point no registry rule matched, so we know it's not a known merchant.
        // Check for person-to-account signals.
        return lower.contains("p2a")
                || lower.contains("/p2p/")
                || lower.matches(".*upi.*/[a-z]{2,}@.*");
    }

    /**
     * Heuristic name extractor for unknown merchants.
     * Strips UPI noise, reference numbers, bank suffixes, then title-cases
     * the first 3 meaningful tokens.
     */
    private String extractFallbackName(String raw) {
        String s = raw
                .replaceAll("(?i)UPI/P2[AMP]/\\d+/", " ")
                .replaceAll("(?i)UPI/P2[AMP]/",       " ")
                .replaceAll("(?i)/UPIInt/",            " ")
                .replaceAll("(?i)/Collec/",            " ")
                .replaceAll("(?i)/Sent u/",            " ")
                .replaceAll("(?i)/Pay to/",            " ")
                .replaceAll("(?i)TRF/",                " ")
                .replaceAll("@[^\\s/]+",               " ")
                .replaceAll("/[A-Z]{2,4} BANK.*",      " ")
                .replaceAll("/[A-Z]{2,}$",             " ")
                .replaceAll("\\b\\d{6,}\\b",           " ")
                .replaceAll("[^a-zA-Z\\s]",            " ")
                .replaceAll("\\s+",                    " ")
                .trim();

        String[] parts = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        int words = 0;
        for (String p : parts) {
            if (p.length() <= 1) continue;
            if (!out.isEmpty()) out.append(" ");
            out.append(Character.toUpperCase(p.charAt(0)))
                    .append(p.substring(1).toLowerCase());
            if (++words == 3) break;
        }
        return !out.isEmpty() ? out.toString()
                : raw.substring(0, Math.min(raw.length(), 25));
    }
}