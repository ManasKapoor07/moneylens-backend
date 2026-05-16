package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.response.GoalPlanDto;
import com.moneylens.entity.*;
import com.moneylens.exception.PlanDurationExceededException;
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
public class GoalPlanService {

    private static final Logger log = LoggerFactory.getLogger(GoalPlanService.class);

    // ── BEHAVIORAL DIAGNOSIS PROMPT ───────────────────────────────────────────
    private static final String BEHAVIOR_DIAGNOSIS_PROMPT = """
            You are a behavioral finance analyst. Study this user's financial profile and
            produce a DIAGNOSIS before any plan is written.

            ═══════════════════════════════════════════════════════════
            USER'S FINANCIAL PROFILE
            ═══════════════════════════════════════════════════════════
            %s

            ═══════════════════════════════════════════════════════════
            DIAGNOSE THE FOLLOWING — be brutally specific, not generic:
            ═══════════════════════════════════════════════════════════

            1. TOP 3 SPENDING LEAKS (rank by ₹ impact, name actual merchants):
               For each: merchant name, estimated monthly ₹ waste, WHY it's a leak
               (impulse? convenience? habit loop? social pressure? boredom?)

            2. BEHAVIORAL PATTERN (pick the ONE that best fits):
               - "Weekend splurger" (spending spikes Fri-Sun)
               - "App addict" (subscriptions + delivery apps dominate)
               - "Convenience spender" (pays premium to avoid friction)
               - "Social spender" (spends when with friends/eating out)
               - "Stress spender" (spikes after salary, stress periods)
               - "Micro-leaker" (hundreds of small UPI transactions)
               - "Subscription hoarder" (multiple overlapping services)
               State pattern name + one sentence of evidence from their data.

            3. HIGHEST IMPACT INTERVENTION (the ONE change that gives max savings):
               Be specific: "Cutting Swiggy to 2x/week saves ₹1,800/month" not "reduce delivery"

            4. EMOTIONAL SPENDING TRIGGER (if detectable):
               What emotion or situation likely drives their biggest overspend?

            5. EXISTING STRENGTHS (what they're already doing well):
               1-2 genuine positives from the data — this maintains motivation.

            6. SAVINGS CAPACITY ESTIMATE:
               Realistic monthly savings they could achieve WITHOUT lifestyle collapse.
               Express as a range: "₹X,XXX – ₹Y,YYY/month"

            Respond ONLY with valid JSON. No markdown, no explanation:
            {
              "leaks": [
                {"merchant": "...", "monthlyWaste": <number>, "reason": "...", "leakType": "..."},
                {"merchant": "...", "monthlyWaste": <number>, "reason": "...", "leakType": "..."},
                {"merchant": "...", "monthlyWaste": <number>, "reason": "...", "leakType": "..."}
              ],
              "behaviorPattern": "...",
              "behaviorEvidence": "...",
              "highestImpactIntervention": "...",
              "emotionalTrigger": "...",
              "strengths": ["...", "..."],
              "savingsCapacityMin": <number>,
              "savingsCapacityMax": <number>
            }
            """;

    // ── WEEKLY PLAN GENERATION PROMPT ─────────────────────────────────────────
    private static final String WEEKLY_PLAN_PROMPT_TEMPLATE = """
            You are MoneyLens — a behavioral finance coach who just diagnosed this user.
            Now create a PERSONALIZED week-by-week intervention plan.

            ═══════════════════════════════════════════════════════════
            GOAL
            ═══════════════════════════════════════════════════════════
            Name:           %s
            Target:         %s
            Already saved:  %s
            Target date:    %s
            Weekly saving:  %s
            Weeks:          %d

            ═══════════════════════════════════════════════════════════
            BEHAVIORAL DIAGNOSIS (use this to personalize EVERY action)
            ═══════════════════════════════════════════════════════════
            Behavior pattern:     %s
            Evidence:             %s
            Top leak #1:          %s (₹%s/month, type: %s)
            Top leak #2:          %s (₹%s/month, type: %s)
            Top leak #3:          %s (₹%s/month, type: %s)
            Highest impact move:  %s
            Emotional trigger:    %s
            Strengths:            %s
            Savings capacity:     ₹%s – ₹%s/month

            ═══════════════════════════════════════════════════════════
            MANDATORY RULES — violations make this useless
            ═══════════════════════════════════════════════════════════

            PERSONALIZATION (non-negotiable):
            ✗ BANNED phrases: "set up auto-transfer", "review subscriptions", "track expenses",
              "reduce food delivery", "cut unnecessary spending", "create a budget",
              "save more", "spend less", "be mindful"
            ✓ Every action must name a SPECIFIC merchant, amount, or behavior from the diagnosis
            ✓ If behavior pattern is "App addict" → every plan must address apps specifically
            ✓ If emotional trigger is stress → include one stress-decoupling action per week

            PROGRESSION ARC (each phase must feel different):
            Week 1:    AWARENESS — audit the exact leak, put a ₹ number on it, feel the pain
            Week 2:    FRICTION — make the leak harder (delete app, remove saved card, set limit)
            Week 3:    SUBSTITUTION — replace the behavior with a cheaper alternative
            Week 4:    REWARD — acknowledge progress, treat yourself within budget (name the treat)
            Week 5-8:  OPTIMIZATION — attack leak #2, then #3, stack savings
            Week 9-12: ACCELERATION — surplus redirect, challenge mode, milestone celebration
            Week 13+:  SUSTAIN — habit lock-in, identity shift ("I'm someone who...")

            ACTION FORMAT:
            • 3 actions per week. Each = one sentence with ₹ amount and specific behavior.
            • Action 1: Target the DOMINANT leak for this phase
            • Action 2: Savings transfer or redirect (specific amount, specific account/goal)
            • Action 3: Tracking or accountability (specific metric to check)
            • NEVER repeat the same action across weeks — vary merchants, amounts, tactics

            TIP FORMAT:
            • One sentence. Must reference their actual pattern or a specific ₹ number.
            • Week 1,3,5... = motivating ("At this pace, you'll have ₹X by [date]")
            • Week 2,4,6... = reality check ("Your [merchant] spend is 3x the national average")

            ═══════════════════════════════════════════════════════════
            OUTPUT — STRICT JSON
            ═══════════════════════════════════════════════════════════
            Respond ONLY with a JSON array of exactly %d objects. No markdown, no explanation:
            [
              {
                "savingTarget": <₹ amount for this week>,
                "actions": "<action1>\\n<action2>\\n<action3>",
                "tip": "<one sharp sentence>"
              }
            ]
            """;

    // ── MONTHLY PLAN GENERATION PROMPT ────────────────────────────────────────
    private static final String MONTHLY_PLAN_PROMPT_TEMPLATE = """
            You are MoneyLens — a behavioral finance coach who just diagnosed this user.
            Now create a PERSONALIZED month-by-month intervention plan.

            ═══════════════════════════════════════════════════════════
            GOAL
            ═══════════════════════════════════════════════════════════
            Name:           %s
            Target:         %s
            Already saved:  %s
            Target date:    %s
            Monthly saving: %s
            Months:         %d

            ═══════════════════════════════════════════════════════════
            BEHAVIORAL DIAGNOSIS (use this to personalize EVERY action)
            ═══════════════════════════════════════════════════════════
            Behavior pattern:     %s
            Evidence:             %s
            Top leak #1:          %s (₹%s/month, type: %s)
            Top leak #2:          %s (₹%s/month, type: %s)
            Top leak #3:          %s (₹%s/month, type: %s)
            Highest impact move:  %s
            Emotional trigger:    %s
            Strengths:            %s
            Savings capacity:     ₹%s – ₹%s/month

            ═══════════════════════════════════════════════════════════
            MANDATORY RULES
            ═══════════════════════════════════════════════════════════

            PERSONALIZATION (non-negotiable):
            ✗ BANNED: "set up auto-transfer", "review subscriptions", "track expenses",
              "reduce food delivery", "cut unnecessary spending", "create a budget"
            ✓ Every action must name a SPECIFIC merchant, amount, or behavior from diagnosis
            ✓ Phase each month around a different behavioral lever

            PROGRESSION ARC:
            Month 1:  AUDIT & SHOCK — quantify the exact leak in ₹, feel the real cost
            Month 2:  FRICTION & REPLACE — structural changes to make overspending harder
            Month 3:  HABIT LOCK — the new behavior should feel automatic by now
            Month 4+: OPTIMIZE & ACCELERATE — attack next leak, compound wins
            Last month: SPRINT — push for 110%% of target, celebrate identity shift

            ACTION FORMAT:
            • 4 actions per month. Each = one specific sentence with ₹ and merchant.
            • Action 1: Attack the dominant leak for this phase
            • Action 2: Savings automation or redirect
            • Action 3: Structural change (delete, limit, negotiate, batch)
            • Action 4: Progress review with specific metric
            • NEVER repeat identical actions across months

            TIP FORMAT:
            • One sentence citing real ₹ numbers or their specific pattern.
            • Odd months = motivating, even months = reality-check.

            ═══════════════════════════════════════════════════════════
            OUTPUT — STRICT JSON
            ═══════════════════════════════════════════════════════════
            Respond ONLY with a JSON array of exactly %d objects. No markdown, no explanation:
            [
              {
                "savingTarget": <₹ amount for this month>,
                "actions": "<action1>\\n<action2>\\n<action3>\\n<action4>",
                "tip": "<one sharp sentence>"
              }
            ]
            """;

    private final GoalPlanRepository     planRepository;
    private final GoalPlanTaskRepository taskRepository;
    private final UserGoalRepository     goalRepository;
    private final UserRepository         userRepository;
    private final ObjectMapper           objectMapper;
    private final RestTemplate           restTemplate = new RestTemplate();

    @Value("${openai.api.key}")
    private String openaiApiKey;

    public GoalPlanService(GoalPlanRepository planRepository,
                           GoalPlanTaskRepository taskRepository,
                           UserGoalRepository goalRepository,
                           UserRepository userRepository,
                           ObjectMapper objectMapper) {
        this.planRepository  = planRepository;
        this.taskRepository  = taskRepository;
        this.goalRepository  = goalRepository;
        this.userRepository  = userRepository;
        this.objectMapper    = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PLAN GENERATION — main entry point
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public GoalPlan generateAndSavePlan(UserGoal goal, UUID userId,
                                        String financialContext, boolean weekly) {
        if (weekly) {
            return generateWeeklyPlan(goal, userId, financialContext);
        } else {
            return generateMonthlyPlan(goal, userId, financialContext);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEEKLY PLAN
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public GoalPlan generateWeeklyPlan(UserGoal goal, UUID userId, String financialContext) {

        long totalWeeks = goal.getTargetDate() != null
                ? Math.max(1, ChronoUnit.WEEKS.between(LocalDate.now(), goal.getTargetDate()))
                : 12L;

        if (totalWeeks > 52) {
            throw new PlanDurationExceededException(
                    "Plans longer than 1 year require MoneyLens Premium, coming soon! " +
                            "Try setting a target date within the next 12 months for now."
            );
        }
        long planWeeks = Math.min(totalWeeks, 52);

        BigDecimal remaining = computeRemaining(goal);
        BigDecimal weeklySaving = (remaining != null && planWeeks > 0)
                ? remaining.divide(BigDecimal.valueOf(planWeeks), 2, RoundingMode.CEILING)
                : null;

        List<PlanItem> aiPlan = callAIForWeeklyPlan(goal, (int) planWeeks, weeklySaving, financialContext);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        GoalPlan plan = new GoalPlan();
        plan.setGoal(goal);
        plan.setUser(user);
        plan.setFrequency(GoalPlan.Frequency.WEEKLY);
        plan.setTotalWeeks((int) planWeeks);
        plan.setWeeklySavingTarget(weeklySaving);
        plan.setStartDate(LocalDate.now());
        plan.setEndDate(goal.getTargetDate() != null
                ? goal.getTargetDate()
                : LocalDate.now().plusWeeks(planWeeks));
        plan.setSummary(buildWeeklySummary(goal, (int) planWeeks, weeklySaving));
        plan.setStatus(GoalPlan.Status.ACTIVE);
        plan.setProgressPct(0);

        GoalPlan savedPlan = planRepository.save(plan);

        LocalDate cursor = LocalDate.now();
        for (int i = 0; i < aiPlan.size(); i++) {
            PlanItem item = aiPlan.get(i);
            GoalPlanTask task = new GoalPlanTask();
            task.setPlan(savedPlan);
            task.setWeekNumber(i + 1);
            task.setWeekStart(cursor);
            task.setWeekEnd(cursor.plusDays(6));
            task.setSavingTarget(item.savingTarget() != null ? item.savingTarget() : weeklySaving);
            task.setActions(item.actions());
            task.setTip(item.tip());
            task.setCheckinStatus(GoalPlanTask.CheckinStatus.PENDING);
            taskRepository.save(task);
            cursor = cursor.plusWeeks(1);
        }

        return planRepository.findById(savedPlan.getId()).orElse(savedPlan);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONTHLY PLAN
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public GoalPlan generateMonthlyPlan(UserGoal goal, UUID userId, String financialContext) {

        long totalMonths = goal.getTargetDate() != null
                ? Math.max(1, ChronoUnit.MONTHS.between(LocalDate.now(), goal.getTargetDate()))
                : 6L;

        if (totalMonths > 12) {
            throw new PlanDurationExceededException(
                    "Plans longer than 1 year require MoneyLens Premium, coming soon! " +
                            "Try setting a target date within the next 12 months for now."
            );
        }
        long planMonths = Math.min(totalMonths, 24);

        BigDecimal remaining = computeRemaining(goal);
        BigDecimal monthlySaving = (remaining != null && planMonths > 0)
                ? remaining.divide(BigDecimal.valueOf(planMonths), 2, RoundingMode.CEILING)
                : null;

        List<PlanItem> aiPlan = callAIForMonthlyPlan(goal, (int) planMonths, monthlySaving, financialContext);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        GoalPlan plan = new GoalPlan();
        plan.setGoal(goal);
        plan.setUser(user);
        plan.setFrequency(GoalPlan.Frequency.MONTHLY);
        plan.setTotalWeeks((int) planMonths);
        plan.setWeeklySavingTarget(monthlySaving);
        plan.setStartDate(LocalDate.now());
        plan.setEndDate(goal.getTargetDate() != null
                ? goal.getTargetDate()
                : LocalDate.now().plusMonths(planMonths));
        plan.setSummary(buildMonthlySummary(goal, (int) planMonths, monthlySaving));
        plan.setStatus(GoalPlan.Status.ACTIVE);
        plan.setProgressPct(0);

        GoalPlan savedPlan = planRepository.save(plan);

        LocalDate cursor = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i < aiPlan.size(); i++) {
            PlanItem item = aiPlan.get(i);
            GoalPlanTask task = new GoalPlanTask();
            task.setPlan(savedPlan);
            task.setWeekNumber(i + 1);
            task.setWeekStart(cursor);
            task.setWeekEnd(cursor.plusMonths(1).minusDays(1));
            task.setSavingTarget(item.savingTarget() != null ? item.savingTarget() : monthlySaving);
            task.setActions(item.actions());
            task.setTip(item.tip());
            task.setCheckinStatus(GoalPlanTask.CheckinStatus.PENDING);
            taskRepository.save(task);
            cursor = cursor.plusMonths(1);
        }

        return planRepository.findById(savedPlan.getId()).orElse(savedPlan);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BEHAVIORAL DIAGNOSIS
    // ═══════════════════════════════════════════════════════════════════════════

    private record LeakInfo(String merchant, String monthlyWaste, String leakType) {}

    private record BehaviorDiagnosis(
            String pattern, String evidence,
            LeakInfo leak1, LeakInfo leak2, LeakInfo leak3,
            String highestImpact, String emotionalTrigger,
            String strengths,
            String capacityMin, String capacityMax
    ) {}

    @SuppressWarnings("unchecked")
    private BehaviorDiagnosis diagnoseBehavior(String financialContext) {
        try {
            String prompt = BEHAVIOR_DIAGNOSIS_PROMPT.formatted(financialContext);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "gpt-4o");
            body.put("max_tokens", 1000);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/chat/completions",
                    new HttpEntity<>(body, headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null)
                return defaultDiagnosis();

            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) response.getBody().get("choices");
            String rawJson = ((String) ((Map<String, Object>) choices.get(0).get("message"))
                    .get("content")).trim()
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            Map<String, Object> d = objectMapper.readValue(rawJson, Map.class);
            List<Map<String, Object>> leaks = (List<Map<String, Object>>) d.getOrDefault("leaks", List.of());

            LeakInfo l1 = leaks.size() > 0 ? toLeakInfo(leaks.get(0)) : new LeakInfo("misc spending", "unknown", "habit");
            LeakInfo l2 = leaks.size() > 1 ? toLeakInfo(leaks.get(1)) : new LeakInfo("subscriptions", "unknown", "habit");
            LeakInfo l3 = leaks.size() > 2 ? toLeakInfo(leaks.get(2)) : new LeakInfo("impulse purchases", "unknown", "impulse");

            List<?> strengthsList = (List<?>) d.getOrDefault("strengths", List.of("consistent income"));
            String strengths = strengthsList.stream().map(Object::toString).collect(Collectors.joining(", "));

            return new BehaviorDiagnosis(
                    str(d, "behaviorPattern", "Micro-leaker"),
                    str(d, "behaviorEvidence", "multiple small transactions detected"),
                    l1, l2, l3,
                    str(d, "highestImpactIntervention", "reduce top merchant spend"),
                    str(d, "emotionalTrigger", "convenience"),
                    strengths,
                    String.valueOf(d.getOrDefault("savingsCapacityMin", 3000)),
                    String.valueOf(d.getOrDefault("savingsCapacityMax", 8000))
            );
        } catch (Exception e) {
            log.warn("Behavior diagnosis failed, using default: {}", e.getMessage());
            return defaultDiagnosis();
        }
    }

    private LeakInfo toLeakInfo(Map<String, Object> leak) {
        return new LeakInfo(
                str(leak, "merchant", "misc"),
                String.valueOf(leak.getOrDefault("monthlyWaste", 0)),
                str(leak, "leakType", "habit")
        );
    }

    private BehaviorDiagnosis defaultDiagnosis() {
        return new BehaviorDiagnosis(
                "Micro-leaker", "multiple small UPI transactions detected",
                new LeakInfo("food delivery apps", "1500", "convenience"),
                new LeakInfo("subscriptions", "800", "habit"),
                new LeakInfo("impulse UPI payments", "600", "impulse"),
                "Reduce food delivery to 2x/week to save ~₹1,500/month",
                "convenience and habit",
                "consistent income, some savings discipline",
                "3000", "7000"
        );
    }

    private String str(Map<?, ?> map, String key, String fallback) {
        Object v = map.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : fallback;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AI CALLS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<PlanItem> callAIForWeeklyPlan(UserGoal goal, int totalWeeks,
                                               BigDecimal weeklySaving,
                                               String financialContext) {
        BehaviorDiagnosis d = diagnoseBehavior(financialContext);

        String prompt = WEEKLY_PLAN_PROMPT_TEMPLATE.formatted(
                goal.getName(),
                goal.getTargetAmount() != null ? "₹" + goal.getTargetAmount().toPlainString() : "not set",
                goal.getCurrentSaved() != null ? "₹" + goal.getCurrentSaved().toPlainString() : "₹0",
                goal.getTargetDate() != null ? goal.getTargetDate().toString() : "not set",
                weeklySaving != null ? "₹" + weeklySaving.toPlainString() : "flexible",
                totalWeeks,
                d.pattern(), d.evidence(),
                d.leak1().merchant(), d.leak1().monthlyWaste(), d.leak1().leakType(),
                d.leak2().merchant(), d.leak2().monthlyWaste(), d.leak2().leakType(),
                d.leak3().merchant(), d.leak3().monthlyWaste(), d.leak3().leakType(),
                d.highestImpact(), d.emotionalTrigger(), d.strengths(),
                d.capacityMin(), d.capacityMax(),
                totalWeeks
        );
        return callAIForPlan(prompt, totalWeeks, weeklySaving, "weekly");
    }

    private List<PlanItem> callAIForMonthlyPlan(UserGoal goal, int totalMonths,
                                                BigDecimal monthlySaving,
                                                String financialContext) {
        BehaviorDiagnosis d = diagnoseBehavior(financialContext);

        String prompt = MONTHLY_PLAN_PROMPT_TEMPLATE.formatted(
                goal.getName(),
                goal.getTargetAmount() != null ? "₹" + goal.getTargetAmount().toPlainString() : "not set",
                goal.getCurrentSaved() != null ? "₹" + goal.getCurrentSaved().toPlainString() : "₹0",
                goal.getTargetDate() != null ? goal.getTargetDate().toString() : "not set",
                monthlySaving != null ? "₹" + monthlySaving.toPlainString() : "flexible",
                totalMonths,
                d.pattern(), d.evidence(),
                d.leak1().merchant(), d.leak1().monthlyWaste(), d.leak1().leakType(),
                d.leak2().merchant(), d.leak2().monthlyWaste(), d.leak2().leakType(),
                d.leak3().merchant(), d.leak3().monthlyWaste(), d.leak3().leakType(),
                d.highestImpact(), d.emotionalTrigger(), d.strengths(),
                d.capacityMin(), d.capacityMax(),
                totalMonths
        );
        return callAIForPlan(prompt, totalMonths, monthlySaving, "monthly");
    }

    @SuppressWarnings("unchecked")
    private List<PlanItem> callAIForPlan(String prompt, int totalPeriods,
                                         BigDecimal periodSaving, String periodLabel) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "gpt-4o");
            body.put("max_tokens", 4000);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/chat/completions",
                    new HttpEntity<>(body, headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null)
                return fallbackPlan(totalPeriods, periodSaving);

            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) response.getBody().get("choices");
            String rawJson = ((String) ((Map<String, Object>) choices.get(0).get("message"))
                    .get("content")).trim()
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            List<Map<String, Object>> periods = objectMapper.readValue(rawJson, List.class);
            return periods.stream().map(w -> new PlanItem(
                    w.get("savingTarget") != null
                            ? new BigDecimal(w.get("savingTarget").toString()) : periodSaving,
                    (String) w.get("actions"),
                    (String) w.get("tip")
            )).collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("AI {} plan generation failed, using fallback: {}", periodLabel, e.getMessage());
            return fallbackPlan(totalPeriods, periodSaving);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHECK-IN
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public GoalPlanDto checkIn(UUID planId, UUID taskId, BigDecimal savedAmount,
                               String note, GoalPlanTask.CheckinStatus status) {

        GoalPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found"));
        GoalPlanTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        task.setSavedAmount(savedAmount);
        task.setCheckinStatus(status);
        task.setCheckinNote(note);
        task.setCheckedInAt(LocalDateTime.now());
        taskRepository.save(task);

        List<GoalPlanTask> allTasks = taskRepository.findByPlanIdOrderByWeekNumberAsc(planId);
        long done = allTasks.stream()
                .filter(t -> t.getCheckinStatus() == GoalPlanTask.CheckinStatus.DONE)
                .count();
        plan.setProgressPct((int) (done * 100L / allTasks.size()));
        plan.setUpdatedAt(LocalDateTime.now());

        if (done == allTasks.size()) {
            plan.setStatus(GoalPlan.Status.COMPLETED);
        }
        planRepository.save(plan);

        if (savedAmount != null && status == GoalPlanTask.CheckinStatus.DONE) {
            UserGoal goal = plan.getGoal();
            BigDecimal totalSavedViaPlan = allTasks.stream()
                    .filter(t -> t.getSavedAmount() != null
                            && t.getCheckinStatus() == GoalPlanTask.CheckinStatus.DONE)
                    .map(GoalPlanTask::getSavedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            goal.setCurrentSaved(totalSavedViaPlan);
            goal.setUpdatedAt(LocalDateTime.now());
            if (goal.getTargetAmount() != null
                    && totalSavedViaPlan.compareTo(goal.getTargetAmount()) >= 0) {
                goal.setStatus(UserGoal.Status.COMPLETED);
            }
            goalRepository.save(goal);
        }

        return GoalPlanDto.from(planRepository.findById(planId).orElse(plan));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // READ
    // ═══════════════════════════════════════════════════════════════════════════

    public GoalPlanDto getPlan(UUID planId) {
        return GoalPlanDto.from(planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found")));
    }

    public List<GoalPlanDto> getPlansForUser(UUID userId) {
        return planRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(GoalPlanDto::from).collect(Collectors.toList());
    }

    public Optional<GoalPlanDto> getActivePlanForGoal(UUID goalId) {
        return planRepository.findByGoalIdAndStatus(goalId, GoalPlan.Status.ACTIVE)
                .map(GoalPlanDto::from);
    }

    @Transactional
    public void abandonPlan(UUID planId) {
        GoalPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found"));
        plan.setStatus(GoalPlan.Status.ABANDONED);
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONTEXT BUILDER
    // ═══════════════════════════════════════════════════════════════════════════

    public String buildPlanContext(UUID userId) {
        List<GoalPlan> activePlans = planRepository.findByUserIdAndStatus(userId, GoalPlan.Status.ACTIVE);
        if (activePlans.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder("\nUSER'S ACTIVE SAVINGS PLANS:\n");
        for (GoalPlan plan : activePlans) {
            ctx.append("• Plan for: ").append(plan.getGoal().getName())
                    .append(" | Type: ").append(plan.getFrequency() != null
                            ? plan.getFrequency().name() : "WEEKLY")
                    .append(" | Progress: ").append(plan.getProgressPct()).append("%")
                    .append(" | Period ").append(currentPeriodNumber(plan))
                    .append(" of ").append(plan.getTotalWeeks());

            if (plan.getWeeklySavingTarget() != null)
                ctx.append(" | Period target: ₹").append(plan.getWeeklySavingTarget().toPlainString());

            currentPeriodTask(plan).ifPresent(task -> {
                ctx.append("\n  This period's actions:\n");
                if (task.getActions() != null) {
                    Arrays.stream(task.getActions().split("\n"))
                            .map(String::trim).filter(s -> !s.isBlank())
                            .forEach(a -> ctx.append("    - ").append(a).append("\n"));
                }
                ctx.append("  Check-in status: ").append(task.getCheckinStatus().name()).append("\n");
            });
        }
        ctx.append("Reference this plan progress when the user asks about goals or saving habits.\n");
        return ctx.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private BigDecimal computeRemaining(UserGoal goal) {
        if (goal.getTargetAmount() == null) return null;
        BigDecimal saved = goal.getCurrentSaved() != null
                ? goal.getCurrentSaved() : BigDecimal.ZERO;
        BigDecimal remaining = goal.getTargetAmount().subtract(saved);
        return remaining.compareTo(BigDecimal.ZERO) > 0 ? remaining : null;
    }

    private String buildWeeklySummary(UserGoal goal, int weeks, BigDecimal weeklySaving) {
        StringBuilder sb = new StringBuilder();
        sb.append("A ").append(weeks).append("-week plan to reach your ").append(goal.getName());
        if (goal.getTargetAmount() != null)
            sb.append(" goal (₹").append(goal.getTargetAmount().toPlainString()).append(")");
        if (weeklySaving != null)
            sb.append(", saving ₹").append(weeklySaving.toPlainString()).append(" per week");
        if (goal.getTargetDate() != null)
            sb.append(", targeting ").append(goal.getTargetDate());
        sb.append(".");
        return sb.toString();
    }

    private String buildMonthlySummary(UserGoal goal, int months, BigDecimal monthlySaving) {
        StringBuilder sb = new StringBuilder();
        sb.append("A ").append(months).append("-month plan to reach your ").append(goal.getName());
        if (goal.getTargetAmount() != null)
            sb.append(" goal (₹").append(goal.getTargetAmount().toPlainString()).append(")");
        if (monthlySaving != null)
            sb.append(", saving ₹").append(monthlySaving.toPlainString()).append(" per month");
        if (goal.getTargetDate() != null)
            sb.append(", targeting ").append(goal.getTargetDate());
        sb.append(".");
        return sb.toString();
    }

    private int currentPeriodNumber(GoalPlan plan) {
        boolean monthly = GoalPlan.Frequency.MONTHLY.equals(plan.getFrequency());
        long periods = monthly
                ? ChronoUnit.MONTHS.between(plan.getStartDate(), LocalDate.now())
                : ChronoUnit.WEEKS.between(plan.getStartDate(), LocalDate.now());
        return (int) Math.min(periods + 1, plan.getTotalWeeks());
    }

    private Optional<GoalPlanTask> currentPeriodTask(GoalPlan plan) {
        int periodNum = currentPeriodNumber(plan);
        return plan.getTasks().stream()
                .filter(t -> t.getWeekNumber() == periodNum)
                .findFirst();
    }

    private List<PlanItem> fallbackPlan(int totalPeriods, BigDecimal periodSaving) {
        List<PlanItem> result = new ArrayList<>();
        String[] tips = {
                "Consistent transfers are the single biggest predictor of reaching your goal.",
                "Every rupee saved now compounds into confidence — keep going.",
                "You're building a habit that will outlast this goal.",
                "Track every transaction this period — awareness is half the battle.",
        };
        for (int i = 1; i <= totalPeriods; i++) {
            String amt = periodSaving != null ? periodSaving.toPlainString() : "target";
            result.add(new PlanItem(
                    periodSaving,
                    "Transfer ₹" + amt + " to your dedicated savings goal\n"
                            + "Open your UPI app and categorize every transaction from last week\n"
                            + "Identify the one merchant you spent on impulsively and set a weekly cap",
                    tips[(i - 1) % tips.length]
            ));
        }
        return result;
    }

    // ── VALUE OBJECTS ─────────────────────────────────────────────────────────
    private record PlanItem(BigDecimal savingTarget, String actions, String tip) {}
}