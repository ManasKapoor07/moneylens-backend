package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.response.ChatResponse.SuggestedGoal;
import com.moneylens.entity.*;
import com.moneylens.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);

    // ── GOAL EXTRACTION PROMPT ────────────────────────────────────────────────
    private static final String GOAL_EXTRACTION_PROMPT = """
            You are a precise financial goal extractor. Analyze the conversation below and
            identify the MOST RECENT financial goal the user is expressing or referring to.

            ═══════════════════════════════════════════════════════════
            WHAT COUNTS AS A GOAL
            ═══════════════════════════════════════════════════════════
            - Buying a product: iPhone, laptop, bike, car, camera, AirPods, TV, furniture
            - Saving for an experience: Goa trip, Europe trip, concert, wedding, anniversary
            - Education funding: MBA, online course, certification, college fees, bootcamp
            - Financial safety nets: emergency fund, 3-month buffer, medical fund
            - Any other explicit purchase intent or savings target with a named outcome

            ═══════════════════════════════════════════════════════════
            EXTRACTION RULES
            ═══════════════════════════════════════════════════════════
            1. Look at the ENTIRE conversation, not just the last message.
            2. If the user says "make a goal" / "create a goal" with no item specified,
               find the most recently discussed purchase/savings topic in the conversation
               and use that as the goal. This is MANDATORY — never return hasGoal: false
               when the user explicitly asks to create a goal and a topic exists.
            3. targetAmount extraction:
               - Use explicit amount if stated ("save ₹80,000 for a trip")
               - Use well-known market prices if strongly implied:
                 • iPhone 16 Pro Max ≈ ₹159,900
                 • iPhone 16 Pro ≈ ₹119,900
                 • iPhone 16 ≈ ₹79,900
                 • MacBook Air M3 ≈ ₹114,900
                 • Samsung Galaxy S24 Ultra ≈ ₹129,999
                 • Royal Enfield Classic 350 ≈ ₹195,000
                 • Honda Activa ≈ ₹80,000
               - Use null if truly unknown (niche products, vague references)
            4. targetDate extraction:
               - "in 6 months" → today + 6 months
               - "by December" → December 31 of current or next year (whichever is future)
               - "in 1 year" / "next year" → today + 12 months
               - "in 3 months" → today + 3 months
               - "before Diwali" → estimate based on current month
               - If no timeframe mentioned → null
            5. currentSaved: extract ONLY if the user explicitly states an amount already saved.
               Do NOT infer or estimate. Use null if not stated.
            6. Goal name must be short (1–4 words), specific, and title-cased.
               ✓ "iPhone 16 Pro", "Goa Trip", "MBA Fees", "Emergency Fund"
               ✗ "a nice phone", "going somewhere", "my course"

            ═══════════════════════════════════════════════════════════
            DUPLICATE SUPPRESSION
            ═══════════════════════════════════════════════════════════
            USER'S EXISTING ACTIVE GOALS (do NOT suggest duplicates):
            %s

            If the identified goal is the SAME TOPIC as an existing goal (even with slight
            name variation — e.g. "iPhone 16 Pro" vs "iPhone"), return hasGoal: false.
            The user already has that goal; do not suggest it again.

            ═══════════════════════════════════════════════════════════
            CONVERSATION TO ANALYZE
            ═══════════════════════════════════════════════════════════
            %s

            Today's date: %s

            ═══════════════════════════════════════════════════════════
            OUTPUT FORMAT — STRICT
            ═══════════════════════════════════════════════════════════
            Respond ONLY with valid JSON. No markdown, no explanation, no preamble:
            {
              "hasGoal": true | false,
              "name": "Short Goal Name",
              "targetAmount": <number in INR> | null,
              "targetDate": "YYYY-MM-DD" | null,
              "currentSaved": <number in INR> | null,
              "reasoning": "<one sentence explaining why hasGoal is true or false>"
            }

            hasGoal must be false ONLY when:
              a) The conversation contains no purchase, savings, or education intent whatsoever.
              b) The goal is already covered by an existing active goal in the list above.
            hasGoal must be true in ALL other cases where a goal can be identified.
            """;

    private final UserGoalRepository  goalRepository;
    private final UserRepository      userRepository;
    private final StatementRepository statementRepository;
    private final ObjectMapper        objectMapper;
    private final RestTemplate        restTemplate = new RestTemplate();

    @Value("${openai.api.key}")
    private String openaiApiKey;

    public GoalService(UserGoalRepository goalRepository,
                       UserRepository userRepository,
                       StatementRepository statementRepository,
                       ObjectMapper objectMapper) {
        this.goalRepository      = goalRepository;
        this.userRepository      = userRepository;
        this.statementRepository = statementRepository;
        this.objectMapper        = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CRUD
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public UserGoal createGoal(UUID userId, UUID statementId, String name,
                               BigDecimal targetAmount, BigDecimal currentSaved,
                               LocalDate targetDate, UserGoal.Source source) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        UserGoal goal = new UserGoal();
        goal.setUser(user);
        goal.setName(name);
        goal.setTargetAmount(targetAmount);
        goal.setCurrentSaved(currentSaved != null ? currentSaved : BigDecimal.ZERO);
        goal.setTargetDate(targetDate);
        goal.setSource(source);

        if (statementId != null)
            statementRepository.findById(statementId).ifPresent(goal::setStatement);

        computeMonthlyContribution(goal);
        return goalRepository.save(goal);
    }

    public List<UserGoal> getActiveGoals(UUID userId) {
        return goalRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                userId, UserGoal.Status.ACTIVE);
    }

    public List<UserGoal> getAllGoals(UUID userId) {
        return goalRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    // ── GOAL SUGGESTION RESULT ────────────────────────────────────────────────
    public record GoalSuggestionResult(
            SuggestedGoal goal,        // null if duplicate or no goal
            boolean isDuplicate,
            String duplicateGoalName   // name of the existing goal that matched
    ) {}

    @SuppressWarnings("unchecked")
    public GoalSuggestionResult detectGoalSuggestion(UUID userId, String conversationContext) {
        try {
            String existingGoalsSummary = buildExistingGoalsSummary(userId);

            String prompt = GOAL_EXTRACTION_PROMPT.formatted(
                    existingGoalsSummary,
                    conversationContext,
                    LocalDate.now()
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "gpt-4o");
            body.put("max_tokens", 300);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/chat/completions",
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null)
                return new GoalSuggestionResult(null, false, null);

            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) response.getBody().get("choices");
            String json = ((String) ((Map<String, Object>) choices.get(0).get("message"))
                    .get("content")).trim()
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

            String detectedName = (String) parsed.get("name");

            log.debug("Goal extraction result: hasGoal={}, name={}, reasoning={}",
                    parsed.get("hasGoal"), detectedName, parsed.get("reasoning"));

            // ── DUPLICATE DETECTED ────────────────────────────────────────────
            if (!Boolean.TRUE.equals(parsed.get("hasGoal")) && detectedName != null && !detectedName.isBlank()) {
                String matchedGoalName = getActiveGoals(userId).stream()
                        .filter(g -> g.getName() != null &&
                                (g.getName().equalsIgnoreCase(detectedName) ||
                                        g.getName().toLowerCase().contains(detectedName.toLowerCase()) ||
                                        detectedName.toLowerCase().contains(g.getName().toLowerCase())))
                        .map(UserGoal::getName)
                        .findFirst()
                        .orElse(detectedName);

                log.info("Duplicate goal detected: '{}' matches existing goal '{}'", detectedName, matchedGoalName);
                return new GoalSuggestionResult(null, true, matchedGoalName);
            }
            // ─────────────────────────────────────────────────────────────────

            if (!Boolean.TRUE.equals(parsed.get("hasGoal")))
                return new GoalSuggestionResult(null, false, null);

            if (detectedName == null || detectedName.isBlank())
                return new GoalSuggestionResult(null, false, null);

            BigDecimal targetAmount = parsed.get("targetAmount") != null
                    ? new BigDecimal(parsed.get("targetAmount").toString()) : null;
            BigDecimal currentSaved = parsed.get("currentSaved") != null
                    ? new BigDecimal(parsed.get("currentSaved").toString()) : null;
            LocalDate targetDate = parsed.get("targetDate") != null
                    ? LocalDate.parse((String) parsed.get("targetDate")) : null;

            log.info("Goal detected: '{}' amount={} date={}", detectedName, targetAmount, targetDate);
            return new GoalSuggestionResult(
                    new SuggestedGoal(detectedName, targetAmount, currentSaved, targetDate),
                    false, null
            );

        } catch (Exception e) {
            log.warn("Goal detection failed silently: {}", e.getMessage());
            return new GoalSuggestionResult(null, false, null);
        }
    }

    @Transactional
    public UserGoal updateSaved(UUID goalId, BigDecimal newSaved) {
        UserGoal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("Goal not found"));
        goal.setCurrentSaved(newSaved);
        goal.setUpdatedAt(LocalDateTime.now());
        computeMonthlyContribution(goal);
        if (goal.getTargetAmount() != null
                && newSaved.compareTo(goal.getTargetAmount()) >= 0)
            goal.setStatus(UserGoal.Status.COMPLETED);
        return goalRepository.save(goal);
    }

    @Transactional
    public UserGoal updateGoal(UUID goalId, String name, BigDecimal targetAmount,
                               BigDecimal currentSaved, LocalDate targetDate) {
        UserGoal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("Goal not found"));
        if (name != null)         goal.setName(name);
        if (targetAmount != null) goal.setTargetAmount(targetAmount);
        if (currentSaved != null) goal.setCurrentSaved(currentSaved);
        if (targetDate != null)   goal.setTargetDate(targetDate);
        goal.setUpdatedAt(LocalDateTime.now());
        computeMonthlyContribution(goal);
        return goalRepository.save(goal);
    }

    @Transactional
    public void cancelGoal(UUID goalId) {
        UserGoal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("Goal not found"));
        goal.setStatus(UserGoal.Status.CANCELLED);
        goal.setUpdatedAt(LocalDateTime.now());
        goalRepository.save(goal);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GOALS CONTEXT — injected into every AI call
    // ═══════════════════════════════════════════════════════════════════════════

    public String buildGoalsContext(UUID userId) {
        List<UserGoal> goals = getActiveGoals(userId);
        if (goals.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder("\nUSER'S ACTIVE FINANCIAL GOALS:\n");
        for (UserGoal g : goals) {
            ctx.append("• Goal: ").append(g.getName());

            if (g.getTargetAmount() != null)
                ctx.append(" | Target: ₹").append(g.getTargetAmount().toPlainString());

            if (g.getCurrentSaved() != null
                    && g.getCurrentSaved().compareTo(BigDecimal.ZERO) > 0) {
                ctx.append(" | Saved: ₹").append(g.getCurrentSaved().toPlainString());
                if (g.getTargetAmount() != null) {
                    BigDecimal remaining = g.getTargetAmount().subtract(g.getCurrentSaved());
                    ctx.append(" | Remaining: ₹").append(remaining.toPlainString());
                }
            }

            if (g.getMonthlyContribution() != null)
                ctx.append(" | Needs ₹")
                        .append(g.getMonthlyContribution().toPlainString())
                        .append("/month");

            if (g.getTargetDate() != null) {
                long monthsLeft = ChronoUnit.MONTHS.between(LocalDate.now(), g.getTargetDate());
                ctx.append(" | By: ").append(g.getTargetDate())
                        .append(" (").append(Math.max(0, monthsLeft)).append(" months left)");
            }

            ctx.append("\n");
        }
        ctx.append("Reference these goals when answering. Give progress-aware, specific advice.\n");
        return ctx.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE
    // ═══════════════════════════════════════════════════════════════════════════

    private String buildExistingGoalsSummary(UUID userId) {
        List<UserGoal> active = getActiveGoals(userId);
        if (active.isEmpty()) return "(none)";

        return active.stream()
                .map(g -> {
                    StringBuilder sb = new StringBuilder("- ").append(g.getName());
                    if (g.getTargetAmount() != null)
                        sb.append(" (target: ₹").append(g.getTargetAmount().toPlainString()).append(")");
                    if (g.getTargetDate() != null)
                        sb.append(" (by: ").append(g.getTargetDate()).append(")");
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    private void computeMonthlyContribution(UserGoal goal) {
        if (goal.getTargetAmount() == null || goal.getTargetDate() == null) return;
        long monthsLeft = ChronoUnit.MONTHS.between(LocalDate.now(), goal.getTargetDate());
        if (monthsLeft <= 0) return;
        BigDecimal saved = goal.getCurrentSaved() != null
                ? goal.getCurrentSaved() : BigDecimal.ZERO;
        BigDecimal remaining = goal.getTargetAmount().subtract(saved);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) return;
        goal.setMonthlyContribution(
                remaining.divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.CEILING)
        );
    }
}