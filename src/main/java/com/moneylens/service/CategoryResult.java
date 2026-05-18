package com.moneylens.service;

/**
 * Result of a merchant categorization lookup.
 *
 * Replaces the bare {@code String} returned by the old {@code categorise()} method.
 * Carries confidence and source so downstream code can:
 *   - Flag low-confidence transactions for user review
 *   - Power the feedback loop (user corrections only override low-confidence results)
 *   - Drive future active-learning pipelines
 *
 * Source values:
 *   RULE             — matched a pattern in the MerchantRegistry
 *   CREDIT_SIGNAL    — credit-side keyword match (salary, refund, etc.)
 *   P2P_HEURISTIC    — UPI P2P heuristic fired, no merchant matched
 *   BANK_TRANSFER    — NEFT / RTGS / IMPS with no merchant match
 *   FALLBACK         — nothing matched; category is "Other"
 */
public record CategoryResult(
        String category,
        String subCategory,
        double confidence,
        String source
) {
    // ── Factory helpers ───────────────────────────────────────────────────────

    public static CategoryResult fromRule(String category, String subCategory, double confidence) {
        return new CategoryResult(category, subCategory, confidence, "RULE");
    }

    public static CategoryResult creditSignal(String category) {
        return new CategoryResult(category, null, 0.92, "CREDIT_SIGNAL");
    }

    public static CategoryResult p2pHeuristic() {
        return new CategoryResult("P2P Transfer", null, 0.75, "P2P_HEURISTIC");
    }

    public static CategoryResult bankTransfer() {
        return new CategoryResult("Bank Transfer", null, 0.80, "BANK_TRANSFER");
    }

    public static CategoryResult fallback() {
        return new CategoryResult("Other", null, 0.30, "FALLBACK");
    }

    // ── Convenience predicates ────────────────────────────────────────────────

    /** True if confidence is high enough that we should NOT prompt user to correct. */
    public boolean isHighConfidence() {
        return confidence >= 0.85;
    }

    /** True if this result should be surfaced for user review. */
    public boolean needsReview() {
        return confidence < 0.60;
    }
}