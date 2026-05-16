package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.response.GoalPlanDto;
import com.moneylens.entity.*;
import com.moneylens.exception.DailyLimitExceededException;
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

    /** Number of weeks/months generated per chunk. */
    static final int CHUNK_SIZE = 3;

    // ── BEHAVIORAL DIAGNOSIS PROMPT ───────────────────────────────────────────
    private static final String BEHAVIOR_DIAGNOSIS_PROMPT = """
            You are a behavioral finance analyst with access to this user's real transaction data.
            Your diagnosis will drive a personalized savings plan — vague output is useless.

            ═══════════════════════════════════════════════════════════
            USER'S FINANCIAL PROFILE
            ═══════════════════════════════════════════════════════════
            %s

            ═══════════════════════════════════════════════════════════
            CRITICAL CLASSIFICATION RULES (read before diagnosing)
            ═══════════════════════════════════════════════════════════
            Any merchant or category the user has EXPLICITLY labeled as essential/mandatory
            (rent, EMI, training fees, insurance, medicine, school fees) must NEVER appear
            as a "leak". These are fixed costs, not behavioral issues.

            Only flag as a leak if it is genuinely discretionary:
            food delivery, entertainment, impulse UPI, convenience spending,
            subscriptions the user doesn't actively use, social eating out.

            ═══════════════════════════════════════════════════════════
            DIAGNOSE — be brutally specific, cite actual ₹ amounts:
            ═══════════════════════════════════════════════════════════

            1. TOP 3 SPENDING LEAKS (rank by ₹ impact, name actual merchants from data):
               For each: merchant name, estimated monthly ₹ waste, leak type
               (impulse | convenience | habit-loop | social-pressure | boredom | subscription-creep)

            2. BEHAVIORAL PATTERN (pick exactly ONE):
               "Weekend splurger" | "App addict" | "Convenience spender" |
               "Social spender" | "Stress spender" | "Micro-leaker" | "Subscription hoarder"
               State pattern name + one sentence of hard evidence from the transaction data.

            3. HIGHEST IMPACT INTERVENTION — one specific action with ₹ impact:
               ✓ "Cap Swiggy to 2 orders/week → saves ₹1,800/month"
               ✗ "Reduce food delivery spending"

            4. EMOTIONAL SPENDING TRIGGER (if detectable from patterns):
               What emotion or situation precedes the biggest overspend?

            5. EXISTING STRENGTHS — 1-2 genuine positives from the data.

            6. REALISTIC SAVINGS CAPACITY — monthly range the user can save WITHOUT
               lifestyle collapse (account for their fixed costs + essential spend):
               Express as: "₹X,XXX – ₹Y,YYY/month"

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

    // ── WEEKLY CHUNK PROMPT ───────────────────────────────────────────────────
    private static final String WEEKLY_CHUNK_PROMPT = """
            You are MoneyLens — a brutally honest behavioral finance coach.
            Generate weeks %d through %d of a savings plan for this user.

            ═══════════════════════════════════════════════════════════
            GOAL
            ═══════════════════════════════════════════════════════════
            Name:          %s
            Target:        %s
            Already saved: %s
            Target date:   %s
            Weekly saving: %s
            Overall plan:  %d total weeks | generating weeks %d–%d

            ═══════════════════════════════════════════════════════════
            BEHAVIORAL DIAGNOSIS
            ═══════════════════════════════════════════════════════════
            Pattern:              %s
            Evidence:             %s
            Leak #1:              %s (₹%s/month — %s)
            Leak #2:              %s (₹%s/month — %s)
            Leak #3:              %s (₹%s/month — %s)
            Highest impact move:  %s
            Emotional trigger:    %s
            Strengths:            %s
            Realistic capacity:   ₹%s – ₹%s/month

            ═══════════════════════════════════════════════════════════
            PHASE FOR THESE WEEKS: %s
            ═══════════════════════════════════════════════════════════
            %s

            ═══════════════════════════════════════════════════════════
            HARD RULES — every violation wastes the user's money
            ═══════════════════════════════════════════════════════════

            BANNED PHRASES (never use these — they are useless):
            "set up auto-transfer", "review subscriptions", "track your expenses",
            "reduce food delivery", "cut unnecessary spending", "create a budget",
            "be mindful of spending", "save more", "spend less", "limit impulse buys"

            MANDATORY for every action:
            ✓ Name a specific merchant or app from the diagnosis
            ✓ State a specific ₹ amount or %% reduction
            ✓ State the exact behavior to change (not the category)
            ✓ Each week must attack a DIFFERENT aspect — no copy-paste across weeks

            ACTION FORMAT — exactly 3 per week:
            Action 1: Target the dominant leak for this phase (specific merchant + ₹)
            Action 2: Savings transfer or redirect (specific ₹ amount + where it goes)
            Action 3: Accountability metric (specific number to check or cap to set)

            TIP FORMAT — exactly 1 per week:
            • Odd weeks (1,3,...): forward-looking motivation with a real ₹ projection
            • Even weeks (2,4,...): reality-check with a comparison or cost-of-inaction fact
            • Must reference their actual pattern or a ₹ number from the data

            ═══════════════════════════════════════════════════════════
            OUTPUT — strict JSON array of exactly %d objects
            ═══════════════════════════════════════════════════════════
            No markdown, no explanation, no preamble:
            [
              {
                "weekNumber": <int>,
                "savingTarget": <₹ number>,
                "actions": "<action1>\\n<action2>\\n<action3>",
                "tip": "<one sharp sentence>"
              }
            ]
            """;

    // ── MONTHLY CHUNK PROMPT ──────────────────────────────────────────────────
    private static final String MONTHLY_CHUNK_PROMPT = """
            You are MoneyLens — a brutally honest behavioral finance coach.
            Generate months %d through %d of a savings plan for this user.

            ═══════════════════════════════════════════════════════════
            GOAL
            ═══════════════════════════════════════════════════════════
            Name:           %s
            Target:         %s
            Already saved:  %s
            Target date:    %s
            Monthly saving: %s
            Overall plan:   %d total months | generating months %d–%d

            ═══════════════════════════════════════════════════════════
            BEHAVIORAL DIAGNOSIS
            ═══════════════════════════════════════════════════════════
            Pattern:              %s
            Evidence:             %s
            Leak #1:              %s (₹%s/month — %s)
            Leak #2:              %s (₹%s/month — %s)
            Leak #3:              %s (₹%s/month — %s)
            Highest impact move:  %s
            Emotional trigger:    %s
            Strengths:            %s
            Realistic capacity:   ₹%s – ₹%s/month

            ═══════════════════════════════════════════════════════════
            PHASE FOR THESE MONTHS: %s
            ═══════════════════════════════════════════════════════════
            %s

            ═══════════════════════════════════════════════════════════
            HARD RULES
            ═══════════════════════════════════════════════════════════

            BANNED PHRASES:
            "set up auto-transfer", "review subscriptions", "track your expenses",
            "reduce food delivery", "cut unnecessary spending", "create a budget"

            MANDATORY for every action:
            ✓ Name a specific merchant or app from the diagnosis
            ✓ State a specific ₹ amount or %% reduction
            ✓ Each month targets a different behavioral lever

            ACTION FORMAT — exactly 4 per month:
            Action 1: Attack the dominant leak for this phase (merchant + ₹)
            Action 2: Savings redirect or automation (specific ₹)
            Action 3: Structural change (delete app, set limit, negotiate, batch purchase)
            Action 4: Progress review with one specific metric to verify

            TIP FORMAT:
            • Odd months: motivating with ₹ projection
            • Even months: reality-check with cost-of-inaction

            ═══════════════════════════════════════════════════════════
            OUTPUT — strict JSON array of exactly %d objects
            ═══════════════════════════════════════════════════════════
            No markdown, no explanation:
            [
              {
                "monthNumber": <int>,
                "savingTarget": <₹ number>,
                "actions": "<action1>\\n<action2>\\n<action3>\\n<action4>",
                "tip": "<one sharp sentence>"
              }
            ]
            """;

    // ── PLAN REGENERATION PROMPT ──────────────────────────────────────────────
    private static final String PLAN_REGEN_PROMPT = """
            You are MoneyLens. The user has corrected their financial profile.
            Regenerate ONLY the PENDING (not yet completed) tasks of their savings plan
            to reflect the updated understanding of their finances.

            ═══════════════════════════════════════════════════════════
            CORRECTION APPLIED
            ═══════════════════════════════════════════════════════════
            %s

            ═══════════════════════════════════════════════════════════
            UPDATED FINANCIAL PROFILE
            ═══════════════════════════════════════════════════════════
            %s

            ═══════════════════════════════════════════════════════════
            GOAL
            ═══════════════════════════════════════════════════════════
            Name:          %s
            Target:        %s
            Already saved: %s
            Period saving: %s

            ═══════════════════════════════════════════════════════════
            PENDING TASKS TO REGENERATE (week/month numbers: %s)
            ═══════════════════════════════════════════════════════════
            These tasks had actions referencing the now-corrected information.
            Regenerate them with corrected, specific, non-generic actions.

            Same rules as always:
            ✗ No banned phrases: "auto-transfer", "review subscriptions", "track expenses"
            ✓ Name specific merchants, specific ₹ amounts, specific behaviors
            ✓ Respect the corrected categorization — do NOT flag mandatory expenses as leaks

            ═══════════════════════════════════════════════════════════
            OUTPUT — strict JSON array of exactly %d objects
            ═══════════════════════════════════════════════════════════
            [
              {
                "periodNumber": <week or month number>,
                "savingTarget": <₹ number>,
                "actions": "<action1>\\n<action2>\\n<action3>",
                "tip": "<one sharp sentence>"
              }
            ]
            """;

    private final GoalPlanRepository     planRepository;
    private final GoalPlanTaskRepository taskRepository;
    private final UserGoalRepository     goalRepository;
    private final UserRepository         userRepository;
    private final TokenBudgetService     tokenBudgetService;
    private final ObjectMapper           objectMapper;
    private final RestTemplate           restTemplate = new RestTemplate();

    @Value("${openai.api.key}")
    private String openaiApiKey;

    public GoalPlanService(GoalPlanRepository planRepository,
                           GoalPlanTaskRepository taskRepository,
                           UserGoalRepository goalRepository,
                           UserRepository userRepository,
                           TokenBudgetService tokenBudgetService,
                           ObjectMapper objectMapper) {
        this.planRepository      = planRepository;
        this.taskRepository      = taskRepository;
        this.goalRepository      = goalRepository;
        this.userRepository      = userRepository;
        this.tokenBudgetService  = tokenBudgetService;
        this.objectMapper        = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public GoalPlan generateAndSavePlan(UserGoal goal, UUID userId,
                                        String financialContext, boolean weekly) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        tokenBudgetService.checkBudget(userId, user.getRole());
        return weekly
                ? generateWeeklyPlan(goal, userId, user, financialContext)
                : generateMonthlyPlan(goal, userId, user, financialContext);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UNLOCK NEXT CHUNK
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public GoalPlanDto unlockNextChunk(UUID planId, String financialContext) {
        GoalPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        User user = plan.getUser();
        tokenBudgetService.checkBudget(user.getId(), user.getRole());

        List<GoalPlanTask> allTasks   = taskRepository.findByPlanIdOrderByWeekNumberAsc(planId);
        int generatedSoFar = allTasks.size();
        int totalPeriods   = plan.getTotalWeeks();

        if (generatedSoFar >= totalPeriods) {
            log.info("Plan {} already fully generated ({}/{})", planId, generatedSoFar, totalPeriods);
            return GoalPlanDto.from(plan);
        }

        boolean allCurrentDone = allTasks.stream()
                .allMatch(t -> t.getCheckinStatus() == GoalPlanTask.CheckinStatus.DONE
                        || t.getCheckinStatus() == GoalPlanTask.CheckinStatus.SKIPPED);

        if (!allCurrentDone) {
            log.info("Plan {} has pending tasks — not unlocking next chunk yet", planId);
            return GoalPlanDto.from(plan);
        }

        int nextStart = generatedSoFar + 1;
        int nextEnd   = Math.min(generatedSoFar + CHUNK_SIZE, totalPeriods);

        log.info("Unlocking chunk: periods {} – {} for plan {}", nextStart, nextEnd, planId);

        boolean           weekly    = GoalPlan.Frequency.WEEKLY.equals(plan.getFrequency());
        UserGoal          goal      = plan.getGoal();
        BehaviorDiagnosis diagnosis = diagnoseBehavior(financialContext, user.getId(), user.getRole());

        List<PlanItem> items = weekly
                ? callAIForWeeklyChunk(goal, plan, nextStart, nextEnd, totalPeriods, diagnosis)
                : callAIForMonthlyChunk(goal, plan, nextStart, nextEnd, totalPeriods, diagnosis);

        persistChunk(plan, items, nextStart, weekly);

        return GoalPlanDto.from(planRepository.findById(planId).orElse(plan));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CORRECTION-AWARE PLAN REGENERATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void regeneratePendingTasksAfterCorrection(UUID userId,
                                                      String correctionSummary,
                                                      String updatedFinancialContext) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return;

        List<GoalPlan> activePlans =
                planRepository.findByUserIdAndStatus(userId, GoalPlan.Status.ACTIVE);
        if (activePlans.isEmpty()) return;

        for (GoalPlan plan : activePlans) {
            try {
                regeneratePendingForPlan(plan, user, correctionSummary, updatedFinancialContext);
            } catch (DailyLimitExceededException e) {
                log.warn("Token budget exceeded during plan regen for user {}, stopping.", userId);
                break;
            } catch (Exception e) {
                log.error("Failed to regenerate pending tasks for plan {}: {}",
                        plan.getId(), e.getMessage(), e);
            }
        }
    }

    private void regeneratePendingForPlan(GoalPlan plan, User user,
                                          String correctionSummary,
                                          String updatedFinancialContext) {
        tokenBudgetService.checkBudget(user.getId(), user.getRole());

        List<GoalPlanTask> pendingTasks =
                taskRepository.findByPlanIdOrderByWeekNumberAsc(plan.getId())
                        .stream()
                        .filter(t -> t.getCheckinStatus() == GoalPlanTask.CheckinStatus.PENDING)
                        .collect(Collectors.toList());

        if (pendingTasks.isEmpty()) return;

        UserGoal goal        = plan.getGoal();
        boolean  weekly      = GoalPlan.Frequency.WEEKLY.equals(plan.getFrequency());
        String   periodLabel = weekly ? "week" : "month";

        String periodNumbers = pendingTasks.stream()
                .map(t -> String.valueOf(t.getWeekNumber()))
                .collect(Collectors.joining(", "));

        String periodSavingStr = plan.getWeeklySavingTarget() != null
                ? "₹" + plan.getWeeklySavingTarget().toPlainString() + "/" + periodLabel
                : "flexible";

        String prompt = PLAN_REGEN_PROMPT.formatted(
                correctionSummary,
                updatedFinancialContext,
                goal.getName(),
                goal.getTargetAmount() != null ? "₹" + goal.getTargetAmount().toPlainString() : "not set",
                goal.getCurrentSaved() != null ? "₹" + goal.getCurrentSaved().toPlainString() : "₹0",
                periodSavingStr,
                periodNumbers,
                pendingTasks.size()
        );

        OpenAIResult result = callOpenAIRaw(prompt, 2000);
        tokenBudgetService.record(user.getId(), result.totalTokens());

        try {
            List<Map<String, Object>> items =
                    objectMapper.readValue(cleanJson(result.content()), List.class);

            Map<Integer, GoalPlanTask> taskByPeriod = pendingTasks.stream()
                    .collect(Collectors.toMap(GoalPlanTask::getWeekNumber, t -> t));

            for (Map<String, Object> item : items) {
                int periodNum = ((Number) item.get("periodNumber")).intValue();
                GoalPlanTask task = taskByPeriod.get(periodNum);
                if (task == null) continue;

                if (item.get("savingTarget") != null) {
                    task.setSavingTarget(new BigDecimal(item.get("savingTarget").toString()));
                }
                task.setActions((String) item.get("actions"));
                task.setTip((String) item.get("tip"));
                taskRepository.save(task);
                log.info("Regenerated {} {} for plan {}", periodLabel, periodNum, plan.getId());
            }
        } catch (Exception e) {
            log.error("Failed to parse plan regen JSON for plan {}: {}", plan.getId(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEEKLY PLAN — first chunk
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    protected GoalPlan generateWeeklyPlan(UserGoal goal, UUID userId, User user,
                                          String financialContext) {
        long totalWeeks = goal.getTargetDate() != null
                ? Math.max(1, ChronoUnit.WEEKS.between(LocalDate.now(), goal.getTargetDate()))
                : 12L;

        if (totalWeeks > 52) {
            throw new PlanDurationExceededException(
                    "Plans longer than 1 year require MoneyLens Premium. " +
                            "Try setting a target date within the next 12 months.");
        }

        int        planWeeks    = (int) Math.min(totalWeeks, 52);
        BigDecimal remaining    = computeRemaining(goal);
        BigDecimal weeklySaving = (remaining != null && planWeeks > 0)
                ? remaining.divide(BigDecimal.valueOf(planWeeks), 2, RoundingMode.CEILING)
                : null;

        BehaviorDiagnosis diagnosis  = diagnoseBehavior(financialContext, userId, user.getRole());
        int               chunkEnd   = Math.min(CHUNK_SIZE, planWeeks);
        List<PlanItem>    firstChunk = callAIForWeeklyChunk(goal, null, 1, chunkEnd, planWeeks, diagnosis);

        GoalPlan plan = new GoalPlan();
        plan.setGoal(goal);
        plan.setUser(user);
        plan.setFrequency(GoalPlan.Frequency.WEEKLY);
        plan.setTotalWeeks(planWeeks);
        plan.setWeeklySavingTarget(weeklySaving);
        plan.setStartDate(LocalDate.now());
        plan.setEndDate(goal.getTargetDate() != null
                ? goal.getTargetDate()
                : LocalDate.now().plusWeeks(planWeeks));
        plan.setSummary(buildWeeklySummary(goal, planWeeks, weeklySaving));
        plan.setStatus(GoalPlan.Status.ACTIVE);
        plan.setProgressPct(0);

        GoalPlan savedPlan = planRepository.save(plan);
        persistChunk(savedPlan, firstChunk, 1, true);

        return planRepository.findById(savedPlan.getId()).orElse(savedPlan);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONTHLY PLAN — first chunk
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    protected GoalPlan generateMonthlyPlan(UserGoal goal, UUID userId, User user,
                                           String financialContext) {
        long totalMonths = goal.getTargetDate() != null
                ? Math.max(1, ChronoUnit.MONTHS.between(LocalDate.now(), goal.getTargetDate()))
                : 6L;

        if (totalMonths > 12) {
            throw new PlanDurationExceededException(
                    "Plans longer than 1 year require MoneyLens Premium. " +
                            "Try setting a target date within the next 12 months.");
        }

        int        planMonths     = (int) Math.min(totalMonths, 24);
        BigDecimal remaining      = computeRemaining(goal);
        BigDecimal monthlySaving  = (remaining != null && planMonths > 0)
                ? remaining.divide(BigDecimal.valueOf(planMonths), 2, RoundingMode.CEILING)
                : null;

        BehaviorDiagnosis diagnosis  = diagnoseBehavior(financialContext, userId, user.getRole());
        int               chunkEnd   = Math.min(CHUNK_SIZE, planMonths);
        List<PlanItem>    firstChunk = callAIForMonthlyChunk(goal, null, 1, chunkEnd, planMonths, diagnosis);

        GoalPlan plan = new GoalPlan();
        plan.setGoal(goal);
        plan.setUser(user);
        plan.setFrequency(GoalPlan.Frequency.MONTHLY);
        plan.setTotalWeeks(planMonths);
        plan.setWeeklySavingTarget(monthlySaving);
        plan.setStartDate(LocalDate.now());
        plan.setEndDate(goal.getTargetDate() != null
                ? goal.getTargetDate()
                : LocalDate.now().plusMonths(planMonths));
        plan.setSummary(buildMonthlySummary(goal, planMonths, monthlySaving));
        plan.setStatus(GoalPlan.Status.ACTIVE);
        plan.setProgressPct(0);

        GoalPlan savedPlan = planRepository.save(plan);
        persistChunk(savedPlan, firstChunk, 1, false);

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
    private BehaviorDiagnosis diagnoseBehavior(String financialContext, UUID userId, User.Role role) {
        try {
            String prompt = BEHAVIOR_DIAGNOSIS_PROMPT.formatted(financialContext);
            OpenAIResult result = callOpenAIRaw(prompt, 1000);
            tokenBudgetService.record(userId, result.totalTokens());

            Map<String, Object> d = objectMapper.readValue(cleanJson(result.content()), Map.class);
            List<Map<String, Object>> leaks = (List<Map<String, Object>>) d.getOrDefault("leaks", List.of());

            LeakInfo l1 = leaks.size() > 0 ? toLeakInfo(leaks.get(0)) : defaultLeak("food delivery apps", 1500, "convenience");
            LeakInfo l2 = leaks.size() > 1 ? toLeakInfo(leaks.get(1)) : defaultLeak("subscriptions", 800, "habit-loop");
            LeakInfo l3 = leaks.size() > 2 ? toLeakInfo(leaks.get(2)) : defaultLeak("impulse UPI payments", 600, "impulse");

            List<?> strengthsList = (List<?>) d.getOrDefault("strengths", List.of("consistent income"));
            String strengths = strengthsList.stream().map(Object::toString).collect(Collectors.joining(", "));

            return new BehaviorDiagnosis(
                    str(d, "behaviorPattern",          "Micro-leaker"),
                    str(d, "behaviorEvidence",          "multiple small transactions detected"),
                    l1, l2, l3,
                    str(d, "highestImpactIntervention", "cap top delivery app to 2 orders/week"),
                    str(d, "emotionalTrigger",          "convenience"),
                    strengths,
                    String.valueOf(d.getOrDefault("savingsCapacityMin", 3000)),
                    String.valueOf(d.getOrDefault("savingsCapacityMax", 8000))
            );
        } catch (Exception e) {
            log.warn("Behavior diagnosis failed, using defaults: {}", e.getMessage());
            return defaultDiagnosis();
        }
    }

    private LeakInfo toLeakInfo(Map<String, Object> leak) {
        return new LeakInfo(
                str(leak, "merchant",    "misc spending"),
                String.valueOf(leak.getOrDefault("monthlyWaste", 0)),
                str(leak, "leakType",    "habit-loop")
        );
    }

    private LeakInfo defaultLeak(String merchant, int waste, String type) {
        return new LeakInfo(merchant, String.valueOf(waste), type);
    }

    private BehaviorDiagnosis defaultDiagnosis() {
        return new BehaviorDiagnosis(
                "Micro-leaker", "multiple small UPI transactions detected",
                defaultLeak("Swiggy/Zomato", 1800, "convenience"),
                defaultLeak("OTT subscriptions", 800, "habit-loop"),
                defaultLeak("impulse UPI payments", 600, "impulse"),
                "Cap food delivery to 2 orders/week → saves ~₹1,800/month",
                "convenience and stress",
                "consistent salary, some savings habit",
                "3000", "7000"
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AI CHUNK CALLS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<PlanItem> callAIForWeeklyChunk(UserGoal goal, GoalPlan existingPlan,
                                                int startWeek, int endWeek, int totalWeeks,
                                                BehaviorDiagnosis d) {
        String     phase     = resolveWeeklyPhase(startWeek);
        String     phaseDesc = resolveWeeklyPhaseDescription(startWeek);
        int        chunkSize = endWeek - startWeek + 1;
        BigDecimal saving    = existingPlan != null
                ? existingPlan.getWeeklySavingTarget()
                : computePerPeriodSaving(goal, totalWeeks);

        String prompt = WEEKLY_CHUNK_PROMPT.formatted(
                startWeek, endWeek,
                goal.getName(),
                goal.getTargetAmount() != null ? "₹" + goal.getTargetAmount().toPlainString() : "not set",
                goal.getCurrentSaved() != null ? "₹" + goal.getCurrentSaved().toPlainString() : "₹0",
                goal.getTargetDate()   != null ? goal.getTargetDate().toString() : "not set",
                saving != null ? "₹" + saving.toPlainString() : "flexible",
                totalWeeks, startWeek, endWeek,
                d.pattern(), d.evidence(),
                d.leak1().merchant(), d.leak1().monthlyWaste(), d.leak1().leakType(),
                d.leak2().merchant(), d.leak2().monthlyWaste(), d.leak2().leakType(),
                d.leak3().merchant(), d.leak3().monthlyWaste(), d.leak3().leakType(),
                d.highestImpact(), d.emotionalTrigger(), d.strengths(),
                d.capacityMin(), d.capacityMax(),
                phase, phaseDesc,
                chunkSize
        );

        return parseChunkResponse(prompt, chunkSize, saving, "week", startWeek);
    }

    private List<PlanItem> callAIForMonthlyChunk(UserGoal goal, GoalPlan existingPlan,
                                                 int startMonth, int endMonth, int totalMonths,
                                                 BehaviorDiagnosis d) {
        String     phase     = resolveMonthlyPhase(startMonth);
        String     phaseDesc = resolveMonthlyPhaseDescription(startMonth);
        int        chunkSize = endMonth - startMonth + 1;
        BigDecimal saving    = existingPlan != null
                ? existingPlan.getWeeklySavingTarget()
                : computePerPeriodSaving(goal, totalMonths);

        String prompt = MONTHLY_CHUNK_PROMPT.formatted(
                startMonth, endMonth,
                goal.getName(),
                goal.getTargetAmount() != null ? "₹" + goal.getTargetAmount().toPlainString() : "not set",
                goal.getCurrentSaved() != null ? "₹" + goal.getCurrentSaved().toPlainString() : "₹0",
                goal.getTargetDate()   != null ? goal.getTargetDate().toString() : "not set",
                saving != null ? "₹" + saving.toPlainString() : "flexible",
                totalMonths, startMonth, endMonth,
                d.pattern(), d.evidence(),
                d.leak1().merchant(), d.leak1().monthlyWaste(), d.leak1().leakType(),
                d.leak2().merchant(), d.leak2().monthlyWaste(), d.leak2().leakType(),
                d.leak3().merchant(), d.leak3().monthlyWaste(), d.leak3().leakType(),
                d.highestImpact(), d.emotionalTrigger(), d.strengths(),
                d.capacityMin(), d.capacityMax(),
                phase, phaseDesc,
                chunkSize
        );

        return parseChunkResponse(prompt, chunkSize, saving, "month", startMonth);
    }

    @SuppressWarnings("unchecked")
    private List<PlanItem> parseChunkResponse(String prompt, int expectedCount,
                                              BigDecimal periodSaving,
                                              String periodLabel, int startNumber) {
        try {
            OpenAIResult result = callOpenAIRaw(prompt, 1500);
            List<Map<String, Object>> periods =
                    objectMapper.readValue(cleanJson(result.content()), List.class);

            return periods.stream()
                    .map(p -> {
                        BigDecimal saving = p.get("savingTarget") != null
                                ? new BigDecimal(p.get("savingTarget").toString())
                                : periodSaving;
                        return new PlanItem(saving, (String) p.get("actions"), (String) p.get("tip"));
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("AI {} chunk generation failed (start={}): {}",
                    periodLabel, startNumber, e.getMessage());
            return fallbackChunk(expectedCount, startNumber, periodSaving, periodLabel);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSIST CHUNK
    // ═══════════════════════════════════════════════════════════════════════════

    private void persistChunk(GoalPlan plan, List<PlanItem> items,
                              int startPeriod, boolean weekly) {
        List<GoalPlanTask> existing = taskRepository.findByPlanIdOrderByWeekNumberAsc(plan.getId());
        LocalDate cursor;
        if (!existing.isEmpty()) {
            cursor = existing.get(existing.size() - 1).getWeekEnd().plusDays(1);
        } else {
            cursor = plan.getStartDate();
            if (!weekly) cursor = cursor.withDayOfMonth(1);
        }

        for (int i = 0; i < items.size(); i++) {
            PlanItem item      = items.get(i);
            int      periodNum = startPeriod + i;

            GoalPlanTask task = new GoalPlanTask();
            task.setPlan(plan);
            task.setWeekNumber(periodNum);
            task.setWeekStart(cursor);
            task.setWeekEnd(weekly ? cursor.plusDays(6) : cursor.plusMonths(1).minusDays(1));
            task.setSavingTarget(item.savingTarget() != null
                    ? item.savingTarget() : plan.getWeeklySavingTarget());
            task.setActions(item.actions());
            task.setTip(item.tip());
            task.setCheckinStatus(GoalPlanTask.CheckinStatus.PENDING);
            taskRepository.save(task);

            cursor = weekly ? cursor.plusWeeks(1) : cursor.plusMonths(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHECK-IN
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public GoalPlanDto checkIn(UUID planId, UUID taskId, BigDecimal savedAmount,
                               String note, GoalPlanTask.CheckinStatus status,
                               String financialContext) {

        GoalPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        GoalPlanTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        task.setSavedAmount(savedAmount);
        task.setCheckinStatus(status);
        task.setCheckinNote(note);
        task.setCheckedInAt(LocalDateTime.now());
        taskRepository.save(task);

        List<GoalPlanTask> allTasks = taskRepository.findByPlanIdOrderByWeekNumberAsc(planId);
        long doneCount = allTasks.stream()
                .filter(t -> t.getCheckinStatus() == GoalPlanTask.CheckinStatus.DONE
                        || t.getCheckinStatus() == GoalPlanTask.CheckinStatus.SKIPPED)
                .count();

        plan.setProgressPct((int) (doneCount * 100L / plan.getTotalWeeks()));
        plan.setUpdatedAt(LocalDateTime.now());

        if (doneCount >= plan.getTotalWeeks()) {
            plan.setStatus(GoalPlan.Status.COMPLETED);
        }
        planRepository.save(plan);

        if (savedAmount != null && status == GoalPlanTask.CheckinStatus.DONE) {
            UserGoal goal = plan.getGoal();
            BigDecimal totalSaved = allTasks.stream()
                    .filter(t -> t.getSavedAmount() != null
                            && t.getCheckinStatus() == GoalPlanTask.CheckinStatus.DONE)
                    .map(GoalPlanTask::getSavedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            goal.setCurrentSaved(totalSaved);
            goal.setUpdatedAt(LocalDateTime.now());
            if (goal.getTargetAmount() != null
                    && totalSaved.compareTo(goal.getTargetAmount()) >= 0) {
                goal.setStatus(UserGoal.Status.COMPLETED);
            }
            goalRepository.save(goal);
        }

        tryAutoUnlockNextChunk(plan, allTasks, financialContext);

        return GoalPlanDto.from(planRepository.findById(planId).orElse(plan));
    }

    private void tryAutoUnlockNextChunk(GoalPlan plan,
                                        List<GoalPlanTask> allTasks,
                                        String financialContext) {
        if (plan.getStatus() == GoalPlan.Status.COMPLETED) return;

        int generatedCount = allTasks.size();
        if (generatedCount >= plan.getTotalWeeks()) return;

        int start = Math.max(0, generatedCount - CHUNK_SIZE);
        boolean chunkDone = allTasks.subList(start, generatedCount).stream()
                .allMatch(t -> t.getCheckinStatus() == GoalPlanTask.CheckinStatus.DONE
                        || t.getCheckinStatus() == GoalPlanTask.CheckinStatus.SKIPPED);

        if (!chunkDone) return;

        log.info("Chunk complete for plan {} — unlocking next {} periods", plan.getId(), CHUNK_SIZE);
        try {
            unlockNextChunk(plan.getId(), financialContext);
        } catch (DailyLimitExceededException e) {
            log.warn("Token budget exceeded — cannot auto-unlock next chunk for plan {}", plan.getId());
        } catch (Exception e) {
            log.error("Failed to auto-unlock next chunk for plan {}: {}", plan.getId(), e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // READ
    // ═══════════════════════════════════════════════════════════════════════════

    public GoalPlanDto getPlan(UUID planId) {
        return GoalPlanDto.from(planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId)));
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
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        plan.setStatus(GoalPlan.Status.ABANDONED);
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONTEXT BUILDER — injected into every chat AI call
    // ═══════════════════════════════════════════════════════════════════════════

    public String buildPlanContext(UUID userId) {
        List<GoalPlan> activePlans =
                planRepository.findByUserIdAndStatus(userId, GoalPlan.Status.ACTIVE);
        if (activePlans.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder("\nUSER'S ACTIVE SAVINGS PLANS:\n");
        for (GoalPlan plan : activePlans) {
            int generated = taskRepository.findByPlanIdOrderByWeekNumberAsc(plan.getId()).size();
            ctx.append("• Plan for: ").append(plan.getGoal().getName())
                    .append(" | Type: ").append(plan.getFrequency() != null
                            ? plan.getFrequency().name() : "WEEKLY")
                    .append(" | Progress: ").append(plan.getProgressPct()).append("%")
                    .append(" | Period ").append(currentPeriodNumber(plan))
                    .append(" of ").append(plan.getTotalWeeks())
                    .append(" (").append(generated).append(" generated so far)");

            if (plan.getWeeklySavingTarget() != null) {
                ctx.append(" | Target: ₹").append(plan.getWeeklySavingTarget().toPlainString());
            }

            currentPeriodTask(plan).ifPresent(t -> {
                ctx.append("\n  Current period actions:\n");
                if (t.getActions() != null) {
                    for (String action : t.getActions().split("\n")) {
                        String trimmed = action.trim();
                        if (!trimmed.isBlank()) {
                            ctx.append("    - ").append(trimmed).append("\n");
                        }
                    }
                }
                ctx.append("  Status: ").append(t.getCheckinStatus().name()).append("\n");
            });
        }

        ctx.append("Reference this when the user asks about goals or saving habits.\n");
        return ctx.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPENAI WRAPPER
    // ═══════════════════════════════════════════════════════════════════════════

    private record OpenAIResult(String content, int totalTokens) {}

    @SuppressWarnings("unchecked")
    private OpenAIResult callOpenAIRaw(String userPrompt, int maxTokens) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model",      "gpt-4o");
            body.put("max_tokens", maxTokens);
            body.put("messages",   List.of(Map.of("role", "user", "content", userPrompt)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/chat/completions",
                    new HttpEntity<>(body, headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return new OpenAIResult("[]", 0);
            }

            Map<?, ?> responseBody = response.getBody();

            int totalTokens = 0;
            Object usageObj = responseBody.get("usage");
            if (usageObj instanceof Map<?, ?> usage) {
                Object total = usage.get("total_tokens");
                if (total instanceof Number n) totalTokens = n.intValue();
            }

            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) responseBody.get("choices");
            if (choices == null || choices.isEmpty()) return new OpenAIResult("[]", totalTokens);

            String content = ((String) ((Map<String, Object>) choices.get(0).get("message"))
                    .get("content")).trim();

            return new OpenAIResult(content, totalTokens);

        } catch (Exception e) {
            log.error("OpenAI call failed in GoalPlanService: {}", e.getMessage(), e);
            return new OpenAIResult("[]", 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE LABELS
    // ═══════════════════════════════════════════════════════════════════════════

    private String resolveWeeklyPhase(int startWeek) {
        if (startWeek <= 1)  return "AWARENESS";
        if (startWeek <= 4)  return "FRICTION & SUBSTITUTION";
        if (startWeek <= 8)  return "OPTIMIZATION";
        if (startWeek <= 12) return "ACCELERATION";
        return "SUSTAIN";
    }

    private String resolveWeeklyPhaseDescription(int startWeek) {
        if (startWeek <= 1)  return "Audit the exact leak. Put a hard ₹ number on it. Make the cost visceral.";
        if (startWeek <= 4)  return "Add friction to the leak (delete app, remove saved card, set weekly cap). Substitute with cheaper alternative.";
        if (startWeek <= 8)  return "Attack leak #2 and #3. Stack wins from previous weeks. Build momentum.";
        if (startWeek <= 12) return "Redirect freed cash directly to goal. Compress timeline if possible. Introduce one challenge-mode action.";
        return "Lock in the habit identity. The behavior should feel automatic now. Celebrate the streak.";
    }

    private String resolveMonthlyPhase(int startMonth) {
        if (startMonth <= 1) return "AUDIT & SHOCK";
        if (startMonth <= 3) return "FRICTION & REPLACE";
        if (startMonth <= 6) return "HABIT LOCK";
        return "OPTIMIZE & ACCELERATE";
    }

    private String resolveMonthlyPhaseDescription(int startMonth) {
        if (startMonth <= 1) return "Quantify the exact leak in ₹. Make the real cost undeniable.";
        if (startMonth <= 3) return "Structural changes: delete apps, set payment limits, negotiate subscriptions.";
        if (startMonth <= 6) return "The new behavior should feel automatic. Reinforce the identity shift.";
        return "Attack the next leak. Compound savings. Sprint toward 110%% of target.";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private BigDecimal computeRemaining(UserGoal goal) {
        if (goal.getTargetAmount() == null) return null;
        BigDecimal saved     = goal.getCurrentSaved() != null ? goal.getCurrentSaved() : BigDecimal.ZERO;
        BigDecimal remaining = goal.getTargetAmount().subtract(saved);
        return remaining.compareTo(BigDecimal.ZERO) > 0 ? remaining : null;
    }

    private BigDecimal computePerPeriodSaving(UserGoal goal, int totalPeriods) {
        BigDecimal remaining = computeRemaining(goal);
        if (remaining == null || totalPeriods == 0) return null;
        return remaining.divide(BigDecimal.valueOf(totalPeriods), 2, RoundingMode.CEILING);
    }

    private String buildWeeklySummary(UserGoal goal, int weeks, BigDecimal weeklySaving) {
        StringBuilder sb = new StringBuilder("A ").append(weeks).append("-week plan for ").append(goal.getName());
        if (goal.getTargetAmount() != null)
            sb.append(" (₹").append(goal.getTargetAmount().toPlainString()).append(")");
        if (weeklySaving != null)
            sb.append(", saving ₹").append(weeklySaving.toPlainString()).append("/week");
        if (goal.getTargetDate() != null)
            sb.append(", by ").append(goal.getTargetDate());
        sb.append(". First ").append(CHUNK_SIZE)
                .append(" weeks generated — more unlock as you complete each chunk.");
        return sb.toString();
    }

    private String buildMonthlySummary(UserGoal goal, int months, BigDecimal monthlySaving) {
        StringBuilder sb = new StringBuilder("A ").append(months).append("-month plan for ").append(goal.getName());
        if (goal.getTargetAmount() != null)
            sb.append(" (₹").append(goal.getTargetAmount().toPlainString()).append(")");
        if (monthlySaving != null)
            sb.append(", saving ₹").append(monthlySaving.toPlainString()).append("/month");
        if (goal.getTargetDate() != null)
            sb.append(", by ").append(goal.getTargetDate());
        sb.append(". First ").append(CHUNK_SIZE)
                .append(" months generated — more unlock as you complete each chunk.");
        return sb.toString();
    }

    private int currentPeriodNumber(GoalPlan plan) {
        boolean monthly = GoalPlan.Frequency.MONTHLY.equals(plan.getFrequency());
        long periods    = monthly
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

    private String cleanJson(String raw) {
        return raw.replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();
    }

    private String str(Map<?, ?> map, String key, String fallback) {
        Object v = map.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : fallback;
    }

    private List<PlanItem> fallbackChunk(int count, int startNumber,
                                         BigDecimal periodSaving, String periodLabel) {
        List<PlanItem> result = new ArrayList<>();
        String[] tips = {
                "Consistent action this " + periodLabel + " compounds into a habit that outlasts this goal.",
                "Every ₹ saved now is a future version of yourself saying thank you.",
                "You're building financial muscle — it gets easier each " + periodLabel + ".",
        };
        for (int i = 0; i < count; i++) {
            String amt = periodSaving != null ? periodSaving.toPlainString() : "target";
            result.add(new PlanItem(
                    periodSaving,
                    "Transfer ₹" + amt + " to your dedicated savings pool for this goal\n"
                            + "Open your UPI history and count every transaction above ₹500 that wasn't essential\n"
                            + "Pick the single highest-spend discretionary merchant and set a weekly ₹ cap on it",
                    tips[i % tips.length]
            ));
        }
        return result;
    }

    private record PlanItem(BigDecimal savingTarget, String actions, String tip) {}
}