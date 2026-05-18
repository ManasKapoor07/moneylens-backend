package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.repository.StatementRepository;
import com.moneylens.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Layer 4: Takes extracted transactions and runs AI analysis.
 *
 * Pipeline:
 *   1. Categorise every transaction via GPT (batched, 50 at a time)
 *   2. Detect money leaks (subscriptions, duplicates, impulse patterns)
 *   3. Generate saving recommendations with ₹ amounts + SIP projections
 *   4. Mark statement COMPLETED
 *   5. Trigger async rebuild of user-level UserFinancialProfile
 */
@Service
public class AIAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AIAnalysisService.class);

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL      = "gpt-4o-mini";
    private static final int    BATCH_SIZE = 50;

    private final StatementRepository          statementRepository;
    private final TransactionRepository        transactionRepository;
    private final UserProfileAggregatorService userProfileAggregatorService;
    private final ObjectMapper                 objectMapper;
    private final RestTemplate                 restTemplate;

    @Value("${openai.api.key}")
    private String openaiApiKey;

    public AIAnalysisService(
            StatementRepository statementRepository,
            TransactionRepository transactionRepository,
            UserProfileAggregatorService userProfileAggregatorService,
            ObjectMapper objectMapper
    ) {
        this.statementRepository          = statementRepository;
        this.transactionRepository        = transactionRepository;
        this.userProfileAggregatorService = userProfileAggregatorService;
        this.objectMapper                 = objectMapper;
        this.restTemplate                 = new RestTemplate();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════

    public void analyse(UUID statementId, List<Transaction> transactions) {

        log.info("Starting AI analysis for statement: {} ({} transactions)",
                statementId, transactions.size());

        Statement statement = statementRepository.findById(statementId).orElse(null);
        if (statement == null) return;

        try {
            statement.setStatus(Statement.Status.ANALYSING);
            statementRepository.save(statement);

            // Step 1 — Categorise every transaction
            categoriseTransactions(transactions);

            // Step 2 — Detect money leaks
            detectLeaks(statementId, transactions);

            // Step 3 — Generate saving recommendations
            generateRecommendations(statementId, transactions);

            // Step 4 — Mark complete
            statement.setStatus(Statement.Status.COMPLETED);
            statementRepository.save(statement);
            log.info("Per-statement AI analysis complete for statement: {}", statementId);

            // Step 5 — Rebuild user-level profile (async, fire-and-forget)
            UUID userId = statement.getUser().getId();
            log.info("Triggering async user profile rebuild for user {} after statement {} completed",
                    userId, statementId);
            userProfileAggregatorService.recomputeAsync(
                    userId,
                    UserProfileAggregatorService.REASON_NEW_STATEMENT
            );

        } catch (Exception e) {
            log.error("AI analysis failed for statement: {}", statementId, e);
            statement.setStatus(Statement.Status.FAILED);
            statementRepository.save(statement);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP 1 — CATEGORISATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sends transactions to GPT in batches of 50.
     *
     * GPT returns a JSON array mapping each transaction ID to a category
     * and subCategory. Results are written back to Transaction fields and saved.
     *
     * Valid categories:
     *   Food & Dining, Shopping, Transport, Subscriptions, Entertainment,
     *   Utilities, Healthcare, Education, Investment, EMI / Loan,
     *   P2P Transfer, Payroll Disbursed, Other
     */
    @SuppressWarnings("unchecked")
    private void categoriseTransactions(List<Transaction> transactions) {

        log.info("Categorising {} transactions in batches of {}", transactions.size(), BATCH_SIZE);

        // Partition into batches
        List<List<Transaction>> batches = new ArrayList<>();
        for (int i = 0; i < transactions.size(); i += BATCH_SIZE) {
            batches.add(transactions.subList(i, Math.min(i + BATCH_SIZE, transactions.size())));
        }

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<Transaction> batch = batches.get(batchIdx);
            log.info("Categorising batch {}/{} ({} transactions)", batchIdx + 1, batches.size(), batch.size());

            try {
                // Compact representation: only the fields GPT needs
                List<Map<String, Object>> txRepresentations = batch.stream()
                        .map(t -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id",          t.getId().toString());
                            m.put("date",        t.getDate().toString());
                            m.put("description", t.getDescription());
                            m.put("amount",      t.getAmount());
                            m.put("type",        t.getType().name());
                            return m;
                        })
                        .collect(Collectors.toList());

                String txJson = objectMapper.writeValueAsString(txRepresentations);

                String systemPrompt = """
                        You are a financial transaction categoriser for Indian bank statements.
                        
                        Categorise each transaction into EXACTLY one of these categories:
                        Food & Dining, Shopping, Transport, Subscriptions, Entertainment,
                        Utilities, Healthcare, Education, Investment, EMI / Loan,
                        P2P Transfer, Payroll Disbursed, Other
                        
                        Also provide a subCategory (e.g. "Food Delivery", "Grocery", "Cab", "OTT").
                        
                        Rules:
                        - UPI payments to people (names) → P2P Transfer
                        - Salary / payroll credits → Payroll Disbursed
                        - Zomato / Swiggy / EatSure → Food & Dining, subCategory: Food Delivery
                        - Blinkit / Zepto / BigBasket → Shopping, subCategory: Grocery
                        - Uber / Ola / Rapido → Transport, subCategory: Cab
                        - Netflix / Spotify / Disney+ / YouTube → Subscriptions, subCategory: OTT
                        - EMI / loan words in description → EMI / Loan
                        - Electricity / gas / internet / water bills → Utilities
                        - Hospital / pharmacy / doctor → Healthcare
                        - School / college / tuition / course → Education
                        - MF / SIP / stock / mutual fund → Investment
                        
                        Return ONLY a JSON array. No markdown. No explanation.
                        Format: [{"id": "...", "category": "...", "subCategory": "..."}]
                        """;

                String userMessage = "Categorise these transactions:\n" + txJson;
                String rawResponse = callOpenAI(systemPrompt, userMessage, 2000);

                // Parse and apply
                String cleaned = rawResponse.replace("```json", "").replace("```", "").trim();
                List<Map<String, Object>> results = objectMapper.readValue(cleaned, List.class);

                Map<String, Map<String, Object>> resultMap = results.stream()
                        .collect(Collectors.toMap(
                                r -> (String) r.get("id"),
                                r -> r,
                                (a, b) -> a
                        ));

                List<Transaction> toSave = new ArrayList<>();
                for (Transaction tx : batch) {
                    Map<String, Object> result = resultMap.get(tx.getId().toString());
                    if (result != null) {
                        tx.setCategory((String) result.get("category"));
                        tx.setSubCategory((String) result.get("subCategory"));
                        toSave.add(tx);
                    }
                }

                transactionRepository.saveAll(toSave);
                log.info("Batch {}/{} categorised — {} transactions updated",
                        batchIdx + 1, batches.size(), toSave.size());

            } catch (Exception e) {
                log.error("Categorisation failed for batch {}/{}: {}",
                        batchIdx + 1, batches.size(), e.getMessage(), e);
                // Continue — partial categorisation is better than full failure
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP 2 — LEAK DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Detects three types of money leaks:
     *
     *   A) Rule-based: duplicate payments (same merchant, same amount, within 5 days)
     *   B) AI-based: phantom subscriptions (forgotten recurring charges)
     *   C) AI-based: impulse spending patterns (food delivery over-ordering, etc.)
     *
     * In production: save results to a MoneyLeak entity.
     */
    @SuppressWarnings("unchecked")
    private void detectLeaks(UUID statementId, List<Transaction> transactions) {

        log.info("Detecting leaks for statement: {}", statementId);

        List<Transaction> debits = transactions.stream()
                .filter(t -> t.getType() == Transaction.Type.DEBIT)
                .collect(Collectors.toList());

        // ── A. Rule-based: duplicate payments ────────────────────────────────
        List<String> duplicates = findDuplicatePayments(debits);
        if (!duplicates.isEmpty()) {
            log.info("Duplicate payments detected for statement {}: {}", statementId, duplicates);
            // TODO: moneyLeakRepository.saveAll(duplicates.stream()
            //     .map(d -> MoneyLeak.of(statementId, "DUPLICATE_PAYMENT", d))
            //     .collect(toList()));
        }

        // ── B & C. AI-based: subscriptions + impulse leaks ────────────────────

        // Category totals
        Map<String, BigDecimal> byCategory = debits.stream()
                .filter(t -> t.getCategory() != null)
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ));

        // Top 20 merchants by total spend
        List<String> topMerchants = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getDescription().substring(0, Math.min(t.getDescription().length(), 40)),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(20)
                .map(e -> e.getKey() + ": ₹" + e.getValue())
                .collect(Collectors.toList());

        // Subscription-looking transactions (small recurring amounts)
        List<Map<String, Object>> subscriptionCandidates = debits.stream()
                .filter(t -> "Subscriptions".equals(t.getCategory())
                        || (t.getAmount().compareTo(BigDecimal.valueOf(1000)) < 0
                        && t.getAmount().compareTo(BigDecimal.valueOf(50))  > 0))
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date",        t.getDate().toString());
                    m.put("description", t.getDescription().substring(0, Math.min(t.getDescription().length(), 60)));
                    m.put("amount",      t.getAmount());
                    m.put("category",    t.getCategory());
                    return m;
                })
                .collect(Collectors.toList());

        try {
            String systemPrompt = """
                    You are a financial leak detector for Indian bank statements.
                    
                    Analyse the spending data and identify money leaks across three types:
                    
                    1. PHANTOM_SUBSCRIPTION — recurring charges the user likely forgot about.
                       Look for: small amounts (₹50–₹1000), monthly pattern, service names.
                       Examples: forgotten OTT, expired trial that kept charging, old app subscription.
                    
                    2. IMPULSE_PATTERN — behavioural spending leaks driven by habits or emotion.
                       Look for: food delivery 10+ times/month, late-night UPI transfers,
                       weekend spend spikes, micro-payment accumulation (100+ small UPI payments).
                    
                    3. CATEGORY_LEAK — one category consuming a disproportionate share of income.
                       Flag if any single category exceeds 30% of total debit spend.
                    
                    Be specific. Reference actual merchant names and amounts from the data.
                    Estimate a realistic monthly loss for each leak.
                    
                    Return ONLY a JSON array. No markdown. No explanation.
                    Format:
                    [
                      {
                        "type": "PHANTOM_SUBSCRIPTION | IMPULSE_PATTERN | CATEGORY_LEAK",
                        "merchant": "merchant or pattern name",
                        "estimatedMonthlyLoss": 500,
                        "description": "plain English explanation of the leak",
                        "severity": "LOW | MEDIUM | HIGH"
                      }
                    ]
                    """;

            String userMessage = String.format("""
                    Spending by category: %s
                    
                    Top 20 merchants: %s
                    
                    Subscription candidates (small recurring amounts): %s
                    
                    Duplicate payments already found: %s
                    """,
                    objectMapper.writeValueAsString(byCategory),
                    objectMapper.writeValueAsString(topMerchants),
                    objectMapper.writeValueAsString(subscriptionCandidates),
                    String.join("; ", duplicates)
            );

            String rawResponse = callOpenAI(systemPrompt, userMessage, 1500);
            String cleaned = rawResponse.replace("```json", "").replace("```", "").trim();

            List<Map<String, Object>> leaks = objectMapper.readValue(cleaned, List.class);
            log.info("AI detected {} leaks for statement {}", leaks.size(), statementId);

            // TODO: save to MoneyLeak entity
            // leaks.forEach(leak -> moneyLeakRepository.save(MoneyLeak.from(statementId, leak)));

            leaks.forEach(leak -> log.info("  Leak [{} / {}]: {} — ₹{}/month — {}",
                    leak.get("type"), leak.get("severity"),
                    leak.get("merchant"), leak.get("estimatedMonthlyLoss"),
                    leak.get("description")));

        } catch (Exception e) {
            log.error("AI leak detection failed for statement {}: {}", statementId, e.getMessage(), e);
        }
    }

    /**
     * Rule-based duplicate payment detection.
     *
     * Flags cases where the same merchant description (first 20 chars) appears
     * more than once within a 5-day window with the same amount (±5%).
     */
    private List<String> findDuplicatePayments(List<Transaction> debits) {
        List<String> duplicates = new ArrayList<>();

        List<Transaction> sorted = debits.stream()
                .sorted(Comparator.comparing(Transaction::getDate))
                .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            Transaction a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                Transaction b = sorted.get(j);

                long daysDiff = Math.abs(a.getDate().toEpochDay() - b.getDate().toEpochDay());
                if (daysDiff > 5) break;

                String descA = a.getDescription()
                        .substring(0, Math.min(a.getDescription().length(), 20)).toLowerCase();
                String descB = b.getDescription()
                        .substring(0, Math.min(b.getDescription().length(), 20)).toLowerCase();

                if (descA.equals(descB) && isWithinPercent(a.getAmount(), b.getAmount(), 5)) {
                    duplicates.add(String.format(
                            "Possible duplicate: '%s' ₹%s on %s and %s",
                            a.getDescription().substring(0, Math.min(a.getDescription().length(), 40)),
                            a.getAmount(), a.getDate(), b.getDate()
                    ));
                }
            }
        }

        return duplicates;
    }

    private boolean isWithinPercent(BigDecimal a, BigDecimal b, int pct) {
        if (a.compareTo(BigDecimal.ZERO) == 0) return b.compareTo(BigDecimal.ZERO) == 0;
        BigDecimal diff      = a.subtract(b).abs();
        BigDecimal threshold = a.multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100));
        return diff.compareTo(threshold) <= 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP 3 — RECOMMENDATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generates 3–5 specific, actionable saving recommendations.
     *
     * Each recommendation includes:
     *   - The exact behaviour to change
     *   - A concrete ₹ monthly saving amount
     *   - SIP projection at 12% p.a. compounded monthly for 5 / 10 / 20 years
     *     Formula: M × ((1.01^n - 1) / 0.01) where n = months
     *
     * In production: save to a Recommendation entity.
     */
    @SuppressWarnings("unchecked")
    private void generateRecommendations(UUID statementId, List<Transaction> transactions) {

        log.info("Generating recommendations for statement: {}", statementId);

        List<Transaction> debits  = transactions.stream()
                .filter(t -> t.getType() == Transaction.Type.DEBIT)
                .collect(Collectors.toList());
        List<Transaction> credits = transactions.stream()
                .filter(t -> t.getType() == Transaction.Type.CREDIT)
                .collect(Collectors.toList());

        BigDecimal totalIncome = credits.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSpend = debits.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Category breakdown
        Map<String, BigDecimal> byCategory = debits.stream()
                .filter(t -> t.getCategory() != null)
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ));

        // Top 5 largest single transactions
        List<String> largestTx = debits.stream()
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .map(t -> String.format("₹%s — %s (%s)",
                        t.getAmount(),
                        t.getDescription().substring(0, Math.min(t.getDescription().length(), 50)),
                        t.getDate()))
                .collect(Collectors.toList());

        // Food delivery count (high signal for recommendations)
        long foodDeliveryCount = debits.stream()
                .filter(t -> "Food & Dining".equals(t.getCategory())
                        && "Food Delivery".equals(t.getSubCategory()))
                .count();

        try {
            String systemPrompt = """
                    You are MoneyLens — a behavioral financial advisor for Indian users.
                    
                    Generate exactly 3 to 5 specific, actionable saving recommendations
                    based on the user's actual spending data.
                    
                    For each recommendation:
                    - Identify the exact behaviour causing the leak (name the merchant/category)
                    - State a specific ₹ monthly saving amount (realistic, not aspirational)
                    - Compute SIP projection at 12% p.a. compounded monthly:
                        5 years  (60 months):  M × ((1.01^60  - 1) / 0.01)
                        10 years (120 months): M × ((1.01^120 - 1) / 0.01)
                        20 years (240 months): M × ((1.01^240 - 1) / 0.01)
                      Round to nearest ₹100.
                    - Give ONE specific action step for this week (not "track your spending")
                    
                    Tone rules:
                    - Never say "consider" or "try to" — be direct
                    - Use ₹ amounts, not percentages, in the action step
                    - Start action steps with a verb: "Set", "Cancel", "Move", "Delete", "Call"
                    
                    Priority = HIGH if monthly saving > ₹3000 or behaviour is high-risk.
                    Priority = MEDIUM if monthly saving ₹1000–₹3000.
                    Priority = LOW if monthly saving < ₹1000.
                    
                    Return ONLY a JSON array. No markdown. No explanation.
                    Format:
                    [
                      {
                        "title": "short punchy title (max 6 words)",
                        "behaviour": "the specific behaviour to change, with merchant name",
                        "monthlySaving": 3000,
                        "actionStep": "exact thing to do this week",
                        "sipProjection": {
                          "fiveYears": 245000,
                          "tenYears": 693000,
                          "twentyYears": 2993000
                        },
                        "priority": "HIGH | MEDIUM | LOW"
                      }
                    ]
                    """;

            String userMessage = String.format("""
                    Total income this month: ₹%s
                    Total spending: ₹%s
                    Net savings: ₹%s
                    Food delivery order count: %d
                    
                    Spending by category:
                    %s
                    
                    5 largest single transactions:
                    %s
                    """,
                    totalIncome,
                    totalSpend,
                    totalIncome.subtract(totalSpend),
                    foodDeliveryCount,
                    objectMapper.writeValueAsString(byCategory),
                    String.join("\n", largestTx)
            );

            String rawResponse = callOpenAI(systemPrompt, userMessage, 2000);
            String cleaned = rawResponse.replace("```json", "").replace("```", "").trim();

            List<Map<String, Object>> recommendations = objectMapper.readValue(cleaned, List.class);
            log.info("Generated {} recommendations for statement {}", recommendations.size(), statementId);

            // TODO: save to Recommendation entity
            // recommendations.forEach(r ->
            //     recommendationRepository.save(Recommendation.from(statementId, r)));

            recommendations.forEach(r -> log.info("  Rec [{}]: {} — save ₹{}/month — {}",
                    r.get("priority"), r.get("title"),
                    r.get("monthlySaving"), r.get("actionStep")));

        } catch (Exception e) {
            log.error("Recommendation generation failed for statement {}: {}",
                    statementId, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPENAI HELPER
    // ═══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private String callOpenAI(String systemPrompt, String userMessage, int maxTokens) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model",      MODEL);
            body.put("max_tokens", maxTokens);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user",   "content", userMessage)
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    OPENAI_URL, new HttpEntity<>(body, headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("OpenAI returned: " + response.getStatusCode());
            }

            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) response.getBody().get("choices");

            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Empty choices from OpenAI");
            }

            return ((String) ((Map<String, Object>) choices.get(0).get("message"))
                    .get("content")).trim();

        } catch (Exception e) {
            throw new RuntimeException("OpenAI call failed: " + e.getMessage(), e);
        }
    }
}