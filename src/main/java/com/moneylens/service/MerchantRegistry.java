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
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MerchantRegistry {

    private static final Logger log = LoggerFactory.getLogger(MerchantRegistry.class);

    private final MerchantRuleRepository ruleRepository;

    private final CopyOnWriteArrayList<MerchantRule> cache = new CopyOnWriteArrayList<>();

    // ── Known merchant VPA suffixes / prefixes that appear in UPI strings ─────
    // These are NOT P2P even though they come through UPI
    private static final List<String> KNOWN_MERCHANT_SIGNALS = List.of(
            // Food
            "zomato", "swiggy", "blinkit", "zepto", "dunzo", "magicpin",
            "eatsure", "faasos", "behrouz", "box8", "freshmenu", "lunchbox",
            // E-commerce
            "amazon", "flipkart", "meesho", "myntra", "ajio", "nykaa",
            "snapdeal", "shopsy", "glowroad", "mamaearth",
            // Streaming / subscriptions
            "netflix", "spotify", "hotstar", "disneyplus", "disney",
            "primevideo", "prime", "jiocinema", "sonyliv", "zee5",
            "youtube", "apple", "google", "microsoft",
            // Travel
            "irctc", "redbus", "makemytrip", "goibibo", "cleartrip",
            "ixigo", "easemytrip", "abhibus", "yatra",
            // Ride / delivery
            "uber", "ola", "rapido", "indriver", "blusmart",
            "zomato", "swiggy", "dunzo",
            // Fuel
            "petrol", "diesel", "hpcl", "bpcl", "iocl", "indianoil", "hp pump",
            // Utilities
            "bescom", "msedcl", "bses", "tata power", "adani electric",
            "airtel", "jio", "bsnl", "vodafone", "vi ", "ideacellular",
            // Finance
            "lic", "hdfc life", "icici pru", "sbi life", "max life",
            "bajaj allianz", "star health", "niacl",
            // Payments / wallets (merchant-side, not personal)
            "billdesk", "razorpay", "cashfree", "payu", "instamojo",
            // Grocery / retail
            "bigbasket", "jiomart", "dmart", "reliance", "more supermarket",
            "spencers", "lulu", "star bazaar",
            // Health
            "apollo", "medplus", "1mg", "netmeds", "pharmeasy", "practo",
            // Education
            "byju", "unacademy", "vedantu", "coursera", "udemy",
            // Gaming
            "mpl", "dream11", "gamezy", "winzo",
            // Misc merchants
            "bookmyshow", "pvr", "inox", "paytm mall"
    );

    public MerchantRegistry(MerchantRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        refreshCache();
        log.info("MerchantRegistry initialised — {} active rules loaded", cache.size());
    }

    public synchronized void refreshCache() {
        List<MerchantRule> fresh = ruleRepository.findAllActiveOrderByPriority();
        cache.clear();
        cache.addAll(fresh);
        log.debug("MerchantRegistry cache refreshed — {} rules", cache.size());
    }

    // ── Primary API ───────────────────────────────────────────────────────────

    /**
     * Resolve a raw bank narration to a CategoryResult.
     *
     * Strategy (in order):
     *   1. Credit-side income signals
     *   2. Registry pattern scan (first match wins, priority-ordered)
     *   3. P2P heuristic (UPI to individual)
     *   4. Generic bank transfer (NEFT / RTGS / IMPS)
     *   5. UPI catch-all (anything UPI not yet classified → P2P Transfer)
     *   6. HDFC collection edge case
     *   7. Fallback → Other
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

        // ── Step 5: UPI catch-all ─────────────────────────────────────────────
        // Any UPI string that wasn't caught by a rule, P2P heuristic, or bank
        // transfer is still more accurately P2P Transfer than Other.
        if (lo.contains("upi")) return CategoryResult.p2pHeuristic();

        // ── Step 6: HDFC collection edge case ─────────────────────────────────
        if (lo.contains("collec") && lo.contains("hdfc"))
            return new CategoryResult("Merchant Payment", null, 0.65, "RULE");

        // ── Step 7: Fallback ──────────────────────────────────────────────────
        return CategoryResult.fallback();
    }

    /**
     * Normalize a raw narration to a clean merchant display name.
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

    public List<MerchantRule> getCachedRules() {
        return List.copyOf(cache);
    }

    public int size() {
        return cache.size();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * P2P heuristic.
     *
     * A UPI transaction is P2P when:
     *   - No registry rule matched (already checked in resolve())
     *   - AND it looks like a person-to-account transfer
     *
     * Guard: if the description contains a known merchant signal, it is NOT P2P
     * even if no rule matched — prevents unknown merchants landing in P2P.
     * Those will fall through to the UPI catch-all at Step 5 with p2pHeuristic
     * since P2P Transfer is still better than Other for unrecognized UPI.
     */
    private boolean isP2P(String lower) {
        if (!lower.contains("upi")) return false;

        // If it mentions a known merchant, it's not P2P — skip to catch-all
        for (String signal : KNOWN_MERCHANT_SIGNALS) {
            if (lower.contains(signal)) return false;
        }

        // Explicit P2P type indicators in description
        if (lower.contains("p2a") || lower.contains("p2p") || lower.contains("p2m")) return true;

        // VPA / @handle pattern — person transfer
        // Matches: name@okaxis, phone@paytm, 9876543210@upi, xyz@ybl
        if (lower.matches(".*\\b[a-z0-9._%+\\-]{3,}@[a-z]{2,}\\b.*")) return true;

        // 10-digit mobile number in description — person transfer
        if (lower.matches(".*\\b[6-9]\\d{9}\\b.*")) return true;

        // UPI with a person name pattern after slash — UPI/DR/refno/FirstName LastName/BANK
        // Catches: "upi/dr/123456/rahul kumar/sbin/0"
        if (lower.matches(".*upi/[a-z]{2,4}/\\d+/[a-z ]{3,}/.*")) return true;

        return false;
    }

    /**
     * Heuristic name extractor for unknown merchants.
     * Strips UPI noise, reference numbers, bank suffixes, then title-cases
     * the first 3 meaningful tokens.
     */
    private String extractFallbackName(String raw) {
        String s = raw
                // UPI structural prefixes
                .replaceAll("(?i)UPI/P2[AMP]/\\d+/",   " ")
                .replaceAll("(?i)UPI/P2[AMP]/",         " ")
                .replaceAll("(?i)UPI/DR/\\d+/",         " ")
                .replaceAll("(?i)UPI/CR/\\d+/",         " ")
                .replaceAll("(?i)UPI/",                  " ")
                .replaceAll("(?i)/UPIInt/",              " ")
                .replaceAll("(?i)/Collec/",              " ")
                .replaceAll("(?i)/Sent u/",              " ")
                .replaceAll("(?i)/Pay to/",              " ")
                .replaceAll("(?i)TRF/",                  " ")
                // Wallet / app prefixes
                .replaceAll("(?i)PHONEPE[-/]",           " ")
                .replaceAll("(?i)GPAY[-/]",              " ")
                .replaceAll("(?i)PAYTM[-/]",             " ")
                .replaceAll("(?i)BHIM[-/]",              " ")
                // VPA handles and bank codes
                .replaceAll("@[^\\s/]+",                 " ")
                .replaceAll("/[A-Z]{2,4} BANK.*",        " ")
                .replaceAll("/[A-Z]{2,}$",               " ")
                // Reference numbers (6+ digits)
                .replaceAll("\\b\\d{6,}\\b",             " ")
                // Non-alpha
                .replaceAll("[^a-zA-Z\\s]",              " ")
                .replaceAll("\\s+",                      " ")
                .trim();

        String[] parts = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        int words = 0;

        // Skip common noise words
        List<String> SKIP = List.of("upi", "neft", "rtgs", "imps", "trf", "transfer",
                "dr", "cr", "to", "by", "from", "via", "net", "banking", "bank", "paid");

        for (String p : parts) {
            if (p.length() <= 1) continue;
            if (SKIP.contains(p.toLowerCase())) continue;
            if (!out.isEmpty()) out.append(" ");
            out.append(Character.toUpperCase(p.charAt(0)))
                    .append(p.substring(1).toLowerCase());
            if (++words == 3) break;
        }

        return !out.isEmpty()
                ? out.toString()
                : raw.substring(0, Math.min(raw.length(), 25));
    }
}