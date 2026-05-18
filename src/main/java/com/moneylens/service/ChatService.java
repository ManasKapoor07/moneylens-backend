package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.response.ChatResponse;
import com.moneylens.dto.response.ChatResponse.SuggestedGoal;
import com.moneylens.dto.response.GoalPlanDto;
import com.moneylens.entity.*;
import com.moneylens.exception.DailyLimitExceededException;
import com.moneylens.exception.PlanDurationExceededException;
import com.moneylens.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Central chat orchestration service.
 *
 * ── What changed from the previous version ───────────────────────────────────
 *
 *   buildFinancialContext() now reads from UserFinancialProfile (user-level,
 *   cross-statement, merged) as the PRIMARY source of AI context.
 *
 *   The per-statement FinancialProfile and FinancialAIAnalysis are still used
 *   as a FALLBACK (for users who have no UserFinancialProfile yet, or for
 *   per-statement drill-down context enrichment).
 *
 *   The correction flow (reanalyseAndPersist) still updates the per-statement
 *   profile AND then triggers an async full-profile rebuild via
 *   UserProfileAggregatorService so both sources stay in sync.
 *
 *   Everything else — state machine, goal detection, plan creation, token
 *   budget, all marker logic — is completely unchanged.
 *
 * ── State machine summary ────────────────────────────────────────────────────
 *
 * Every inbound message is handled in a strict priority order:
 *
 *  P1  Pending-suggestion auto-confirm
 *      If a goal suggestion card is on screen AND the message is a short
 *      acceptance phrase ("weekly", "yes", "monthly") → create the goal
 *      and immediately generate the plan.  Do NOT fall through to P2/P3.
 *
 *  P2  Plan-offer response
 *      If a plan offer is pending (goal already confirmed) AND the message
 *      is an acceptance → generate the plan.  If it is a rejection → clear
 *      state and fall through to normal AI.
 *
 *  P3  Normal AI turn
 *      Build context, call the AI, parse markers, persist reply.
 *      After the turn, run goal detection if the AI appended ---SUGGEST_GOAL---.
 *
 * Key invariants:
 *  • pendingSuggestedGoalName and pendingPlanGoalName are NEVER both non-null.
 *  • pendingSuggestedGoalName is ALWAYS cleared before entering plan-offer state.
 *  • Goal detection always receives the current pendingSuggestedGoalName so the
 *    LLM suppresses re-suggesting the same goal while the card is on screen.
 *  • A suggestion is only cleared when it is confirmed, rejected, or a genuinely
 *    different goal supersedes it.  It is NOT cleared just because the user sent
 *    an analytical follow-up question.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String OPENAI_URL        = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL             = "gpt-4o";
    private static final int    MAX_TOKENS        = 1024;
    private static final int    MAX_HISTORY_TURNS = 10;
    private static final String PROMPT_VERSION    = "v4";

    // ── SYSTEM PROMPT ─────────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT = """
            You are MoneyLens, a deeply personalized financial reasoning assistant.

            Your job: help users understand their spending behavior, make confident financial
            decisions, and connect their present habits to future outcomes — using ONLY their
            actual transaction history and pre-computed financial profile.

            ═══════════════════════════════════════════════════════════════
            CORE RULES (non-negotiable)
            ═══════════════════════════════════════════════════════════════
            1. Use ONLY the financial context provided. Never invent numbers or fabricate data.
            2. Give specific ₹ amounts, dates, and counts from the data whenever possible.
            3. Do NOT give legal, tax, or guaranteed investment advice.
            4. Keep responses under 250 words unless a detailed breakdown is explicitly requested.
            5. NEVER prefix your reply with "MoneyLens:" or any label — speak directly.

            ═══════════════════════════════════════════════════════════════
            INTELLIGENCE & TONE RULES
            ═══════════════════════════════════════════════════════════════

            TAKE A POSITION. Don't hedge.
              ✗ Weak:  "This could potentially impact your savings rate."
              ✓ Strong: "This would consume 2.8x your usual monthly savings buffer."

            BE BEHAVIORALLY SPECIFIC. Use actual numbers from their data.
              ✗ Weak:  "Track your UPI transactions."
              ✓ Strong: "You made 140+ small UPI payments this month — together they account
                          for ₹X in discretionary leakage."

            CONNECT PRESENT TO FUTURE.
              ✓ "At your current savings pace, delaying this purchase 2–3 months means you
                  can buy it without touching your emergency buffer."

            GOAL AWARENESS. Reference active goals directly.
              ✓ "You're ₹6,200 ahead of your iPhone savings target."
              ✓ "At your current surplus, you'll hit your Goa trip goal in 2 months."

            EMOTIONAL INTELLIGENCE.
              "Can I buy iPhone?" = anxiety + financial guilt → be decisive, not preachy.
              "Why am I always broke?" = frustration → acknowledge briefly, then insight.

            NEVER SOUND LIKE AN ACCOUNTANT. Sound like a brilliant, empathetic friend
            with a CFP and access to all their bank data.

            ═══════════════════════════════════════════════════════════════
            GOAL SUGGESTION TRIGGER  ⚡ CRITICAL — READ CAREFULLY
            ═══════════════════════════════════════════════════════════════
            Append ---SUGGEST_GOAL--- on its own line at the VERY END of your reply in ANY
            of these cases — no exceptions, no skipping:

              a) User mentions buying something  ("want to buy iPhone", "planning to buy a bike")
              b) User mentions saving for something ("save for MBA", "save for Goa trip")
              c) User says "make a goal / create a goal / add a goal / set a goal"
              d) User asks if they can afford something in a future timeframe
                 ("can I buy X in 6 months / by December / in 1 year")
              e) User said "make a goal" without naming anything — look at the LAST FEW
                 MESSAGES to find the most recently discussed purchase/savings topic and
                 use that as the goal. Still append ---SUGGEST_GOAL---.

            DO NOT append ---SUGGEST_GOAL--- for:
              - Pure analytical questions ("why am I broke", "show my food spend")
              - Questions about an ALREADY-ACTIVE goal (goal is listed in context)
              - General budgeting advice with no specific purchase intent

            ═══════════════════════════════════════════════════════════════
            PLAN OFFER TRIGGER  ⚡ CRITICAL — READ CAREFULLY
            ═══════════════════════════════════════════════════════════════
            After the user CONFIRMS or CREATES a goal (the conversation context will contain
            [GOAL_JUST_CREATED: <goal_name>]), you MUST:
              1. Acknowledge the goal creation naturally (one sentence).
              2. Ask: "Would you like me to build a **weekly** or **monthly** savings plan
                 for [goal name] so you have a concrete week-by-week roadmap?"
              3. Append ---OFFER_PLAN:<goal_name>--- on its own line at the very end.

            When the user responds YES / "weekly" / "monthly" / "sure" / "yes please"
            to a plan offer (the conversation context will contain [PLAN_OFFER_PENDING: <goal_name>]),
            append ---CREATE_PLAN:<goal_name>:<frequency>--- where frequency is WEEKLY or MONTHLY.
            Extract frequency from the user's message; default to WEEKLY if ambiguous.

            ═══════════════════════════════════════════════════════════════
            FORMAT
            ═══════════════════════════════════════════════════════════════
            - Lead with the direct answer or verdict.
            - Support with 2–3 specific data points.
            - End with a forward-looking behavioral insight.
            - Use formatting only when a breakdown genuinely helps.
            - Marker lines (---SUGGEST_GOAL---, ---OFFER_PLAN:---, ---CREATE_PLAN:---)
              must always appear on their own line at the very end. Never inline.
            """;

    // ── CORRECTION DETECTION PROMPT ───────────────────────────────────────────
    private static final String CORRECTION_DETECTION_PROMPT = """
            You are a strict binary classifier. Decide whether the user's message contains a
            CORRECTION or NEW FACTUAL CONTEXT about their own finances.

            ✅ These ARE corrections / new factual context:
            - "That's my rent, not food spending"
            - "Noina is my training fees, it's essential — not discretionary"
            - "I have a second income from freelancing"
            - "That subscription was cancelled last month"
            - "I share expenses with my flatmate"
            - "That was a one-time medical emergency expense"
            - "My salary is ₹X, not ₹Y"

            ❌ These are NOT corrections:
            - "How can I save more?"
            - "What are my biggest expenses?"
            - "Make a goal for iPhone"
            - "Can I afford a vacation?"
            - "Yes" / "No" / "Okay" / "Sure"
            - "Create a weekly plan for my goal"
            - Anything that is a question, an action request, or a simple affirmation

            Rules:
            - Only return YES if the message EXPLICITLY corrects a previous AI classification
              or adds a factual detail that changes how the user's finances should be interpreted.
            - When in doubt, return NO.

            Respond with EXACTLY one word: YES or NO
            """;

    // ── RE-ANALYSIS SYSTEM PROMPT ─────────────────────────────────────────────
    private static final String REANALYSIS_SYSTEM_PROMPT = """
            You are a precise financial profile updater. You will receive:
            1. The existing financial context (spending summary, profile)
            2. The existing analysisJson (AI analysis)
            3. The existing contextJson (profile context)
            4. A user correction or new factual information

            Your task: produce an updated version of BOTH JSON objects that accurately
            incorporates the correction. Be conservative — only change what the user's
            new information materially affects.

            ═══════════════════════════════════════════════════════════════
            CORRECTION HANDLING RULES
            ═══════════════════════════════════════════════════════════════
            - Merchant re-categorized as essential (e.g. "Noina is training fees"):
                → Remove from risks; add to positiveHabits or remove the mention entirely.
                → If it was inflating discretionary spend, recalculate healthScore upward.
            - New income source revealed:
                → Update the profile context to reflect higher income; adjust risk assessment.
            - Category correction (e.g. "that's not food, it's gym fees"):
                → Update spending classifications in the relevant sections.
            - One-time expense (e.g. "that was a medical emergency"):
                → Remove from recurring risk patterns; note as anomaly.
            - Expense sharing (e.g. "I split bills with my flatmate"):
                → Halve the relevant category's attributed amount in the profile.

            ═══════════════════════════════════════════════════════════════
            healthScore RECALCULATION RULES
            ═══════════════════════════════════════════════════════════════
            Only adjust healthScore if the correction materially changes financial health.
            If an "unnecessary" expense turns out to be a professional investment or
            essential cost, increase healthScore proportionally (typical range: +3 to +12).
            Never decrease healthScore due to a user correction.

            ═══════════════════════════════════════════════════════════════
            OUTPUT FORMAT — STRICT
            ═══════════════════════════════════════════════════════════════
            Respond ONLY with valid JSON in this exact structure.
            No markdown fences, no explanation, no preamble:
            {
              "analysisJson": { ...full updated FinancialAIAnalysis fields... },
              "contextJson": "...updated context string (can be JSON string or plain text)...",
              "healthScore": <integer 0–100>,
              "riskLevel": "LOW" | "MEDIUM" | "HIGH"
            }

            Keep ALL existing keys. Do not drop any fields.
            Do NOT invent new numbers — work with what exists plus the correction.
            """;

    // ── PLAN ACCEPTANCE DETECTION PROMPT ─────────────────────────────────────
    private static final String PLAN_ACCEPTANCE_PROMPT = """
            You are a binary classifier. The user was just asked whether they want a
            weekly or monthly savings plan for a financial goal.

            Determine if the user's message is:
              - An ACCEPTANCE: "yes", "sure", "weekly", "monthly", "go ahead", "yeah",
                "please", "create it", "make it", "do it", "weekly plan", "monthly plan",
                "sounds good", "let's do it", "yes please", "ok", "okay", "yep", "yup"
              - A REJECTION or DEFLECTION: "no", "not now", "maybe later", "skip",
                "cancel", "never mind", "nope", or any unrelated question

            User message: "%s"

            Respond with exactly one of: ACCEPT_WEEKLY | ACCEPT_MONTHLY | REJECT

            Rules:
            - If they say "weekly" → ACCEPT_WEEKLY
            - If they say "monthly" → ACCEPT_MONTHLY
            - If they say yes/sure/ok with no frequency preference → ACCEPT_WEEKLY (default)
            - If they say no or change subject → REJECT
            """;

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final ChatRepository                    chatRepository;
    private final FinancialProfileRepository        profileRepository;
    private final FinancialAIAnalysisRepository     analysisRepository;
    private final StatementRepository               statementRepository;
    private final UserRepository                    userRepository;
    private final UserFinancialProfileRepository    userProfileRepository;        // ← NEW
    private final UserProfileAggregatorService      userProfileAggregatorService; // ← NEW
    private final GoalService                       goalService;
    private final GoalPlanService                   goalPlanService;
    private final TokenBudgetService                tokenBudgetService;
    private final ObjectMapper                      objectMapper;
    private final RestTemplate                      restTemplate;
    private final PlanCreationHelper                planCreationHelper;

    @Value("${openai.api.key}")
    private String openaiApiKey;

    public ChatService(
            ChatRepository chatRepository,
            FinancialProfileRepository profileRepository,
            FinancialAIAnalysisRepository analysisRepository,
            StatementRepository statementRepository,
            UserRepository userRepository,
            UserFinancialProfileRepository userProfileRepository,              // ← NEW
            UserProfileAggregatorService userProfileAggregatorService,        // ← NEW
            PlanCreationHelper planCreationHelper,
            GoalService goalService,
            GoalPlanService goalPlanService,
            TokenBudgetService tokenBudgetService,
            ObjectMapper objectMapper
    ) {
        this.chatRepository               = chatRepository;
        this.profileRepository            = profileRepository;
        this.analysisRepository           = analysisRepository;
        this.statementRepository          = statementRepository;
        this.userRepository               = userRepository;
        this.userProfileRepository        = userProfileRepository;             // ← NEW
        this.userProfileAggregatorService = userProfileAggregatorService;     // ← NEW
        this.goalService                  = goalService;
        this.goalPlanService              = goalPlanService;
        this.tokenBudgetService           = tokenBudgetService;
        this.objectMapper                 = objectMapper;
        this.planCreationHelper           = planCreationHelper;
        this.restTemplate                 = new RestTemplate();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public ChatResponse chat(UUID userId, UUID statementId, UUID chatId, String userMessage) {

        // ── 1. Load user ──────────────────────────────────────────────────────
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // ── 2. Budget check ───────────────────────────────────────────────────
        tokenBudgetService.checkBudget(userId, user.getRole());

        // ── 3. Load or create Chat ────────────────────────────────────────────
        boolean newChat;
        Chat    chat;
        if (chatId != null) {
            chat    = chatRepository.findById(chatId)
                    .orElseThrow(() -> new IllegalArgumentException("Chat not found: " + chatId));
            newChat = false;
        } else {
            chat    = createChat(user, statementId, userMessage);
            newChat = true;
        }

        // ── 4. Persist user message ───────────────────────────────────────────
        appendMessage(chat, ChatMessage.Role.USER, userMessage);

        // ── 5. Snapshot conversation state BEFORE any mutations ───────────────
        final String pendingPlanGoalName      = chat.getPendingPlanGoalName();
        final String justCreatedGoalName      = chat.getJustCreatedGoalName();
        final String pendingSuggestedGoalName = chat.getPendingSuggestedGoalName();

        // ── 6. P1: PENDING-SUGGESTION AUTO-CONFIRM ────────────────────────────
        if (pendingSuggestedGoalName != null && userMessage.trim().length() <= 40) {
            String acceptance = detectPlanAcceptance(userMessage, userId, user.getRole());
            if ("ACCEPT_WEEKLY".equals(acceptance) || "ACCEPT_MONTHLY".equals(acceptance)) {
                boolean weekly = "ACCEPT_WEEKLY".equals(acceptance);
                log.info("P1: Auto-confirming pending suggestion '{}' and creating {} plan",
                        pendingSuggestedGoalName, weekly ? "WEEKLY" : "MONTHLY");
                return autoConfirmGoalAndCreatePlan(chat, user, statementId, weekly, newChat);
            }
        }

        // ── 7. P2: PLAN-OFFER RESPONSE ────────────────────────────────────────
        if (pendingPlanGoalName != null && pendingSuggestedGoalName == null) {
            String acceptance = detectPlanAcceptance(userMessage, userId, user.getRole());
            if ("ACCEPT_WEEKLY".equals(acceptance) || "ACCEPT_MONTHLY".equals(acceptance)) {
                boolean weekly = "ACCEPT_WEEKLY".equals(acceptance);
                log.info("P2: Creating {} plan for confirmed goal '{}'",
                        weekly ? "WEEKLY" : "MONTHLY", pendingPlanGoalName);
                return handlePlanCreation(chat, user, statementId,
                        pendingPlanGoalName, weekly, newChat);
            } else if ("REJECT".equals(acceptance)) {
                log.info("P2: Plan offer rejected for '{}'", pendingPlanGoalName);
                chat.setPendingPlanGoalName(null);
                chatRepository.save(chat);
            }
        }

        // ── 8. P3: NORMAL AI TURN ─────────────────────────────────────────────

        // 8a. Clear one-turn justCreatedGoalName
        chat.setJustCreatedGoalName(null);

        // 8b. Build financial context — reads from UserFinancialProfile first
        String financialContext = buildFinancialContext(statementId, userId, justCreatedGoalName);

        // 8c. Build and call OpenAI
        List<Map<String, String>> messages = buildOpenAIMessages(chat, financialContext);
        OpenAIResult aiResult = callOpenAITracked(messages, MAX_TOKENS);
        tokenBudgetService.record(userId, aiResult.totalTokens());
        String rawReply = aiResult.content();

        // 8d. Parse control markers
        boolean shouldSuggestGoal = rawReply.contains("---SUGGEST_GOAL---");
        boolean shouldOfferPlan   = rawReply.contains("---OFFER_PLAN:");
        boolean shouldCreatePlan  = rawReply.contains("---CREATE_PLAN:");

        String offerPlanGoalName = shouldOfferPlan
                ? extractMarkerValue(rawReply, "---OFFER_PLAN:")
                : null;

        String  createPlanGoalName = null;
        boolean createPlanWeekly   = true;
        if (shouldCreatePlan) {
            String val = extractMarkerValue(rawReply, "---CREATE_PLAN:");
            if (val != null && val.contains(":")) {
                String[] parts = val.split(":", 2);
                createPlanGoalName = parts[0].trim();
                createPlanWeekly   = !"MONTHLY".equalsIgnoreCase(parts[1].trim());
            } else {
                createPlanGoalName = val;
            }
        }

        // 8e. Clean markers from reply
        String cleanReply = rawReply
                .replaceAll("---SUGGEST_GOAL---",        "")
                .replaceAll("---OFFER_PLAN:[^\\n]*---",  "")
                .replaceAll("---CREATE_PLAN:[^\\n]*---", "")
                .trim();

        // 8f. Persist clean assistant reply
        appendMessage(chat, ChatMessage.Role.ASSISTANT, cleanReply);

        // ── 9. GOAL DETECTION ─────────────────────────────────────────────────
        SuggestedGoal suggestedGoal    = null;
        boolean       isDuplicateGoal  = false;
        String        duplicateGoalName = null;

        if (shouldSuggestGoal) {
            String conversationContext = buildConversationContext(chat, userMessage);

            GoalService.GoalSuggestionResult result =
                    goalService.detectGoalSuggestion(
                            userId,
                            conversationContext,
                            chat.getPendingSuggestedGoalName()
                    );

            if (result.isDuplicate()) {
                isDuplicateGoal   = true;
                duplicateGoalName = result.duplicateGoalName();
                if (duplicateGoalName != null
                        && duplicateGoalName.equalsIgnoreCase(chat.getPendingSuggestedGoalName())) {
                    chat.clearPendingSuggestion();
                }
                log.info("Duplicate goal detected: '{}' already exists", duplicateGoalName);

            } else if (result.goal() != null) {
                String newGoalName = result.goal().name();

                if (!newGoalName.equalsIgnoreCase(chat.getPendingSuggestedGoalName())) {
                    log.info("New goal suggestion: '{}' (replacing pending suggestion '{}')",
                            newGoalName, chat.getPendingSuggestedGoalName());
                    chat.replacePendingSuggestion(
                            newGoalName,
                            result.goal().targetAmount(),
                            result.goal().targetDate()
                    );
                } else {
                    log.debug("Same goal re-detected: '{}' — returning without state change",
                            newGoalName);
                }
                suggestedGoal = result.goal();

            } else {
                log.debug("SUGGEST_GOAL fired but no goal extracted; preserving pending '{}'",
                        chat.getPendingSuggestedGoalName());
            }
        }

        // ── 10. UPDATE CONVERSATION STATE FLAGS ───────────────────────────────
        if (shouldOfferPlan && offerPlanGoalName != null) {
            chat.clearPendingSuggestion();
            chat.setPendingPlanGoalName(offerPlanGoalName);

        } else if (shouldCreatePlan && createPlanGoalName != null) {
            chat.setPendingPlanGoalName(null);
            chat.clearPendingSuggestion();
            chat.setUpdatedAt(LocalDateTime.now());
            if (newChat) chat.setTitle(truncate(userMessage, 80));
            chatRepository.save(chat);

            GoalPlanDto createdPlan = tryCreatePlanForGoalName(
                    userId, statementId, createPlanGoalName, createPlanWeekly, user);

            triggerProfileUpdateIfNeeded(user, statementId, userMessage, financialContext);
            return buildResponse(chat, cleanReply, newChat, suggestedGoal, createdPlan,
                    isDuplicateGoal, duplicateGoalName, userId, user.getRole());
        }

        // ── 11. FINALISE AND RETURN ───────────────────────────────────────────
        chat.setUpdatedAt(LocalDateTime.now());
        if (newChat) chat.setTitle(truncate(userMessage, 80));
        chatRepository.save(chat);

        triggerProfileUpdateIfNeeded(user, statementId, userMessage, financialContext);

        return buildResponse(chat, cleanReply, newChat, suggestedGoal, null,
                isDuplicateGoal, duplicateGoalName, userId, user.getRole());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIRM GOAL VIA FRONTEND BUTTON
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public ChatResponse confirmGoalAndOfferPlan(UUID userId, UUID chatId, SuggestedGoal suggested) {

        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found: " + chatId));

        UUID statementId = chat.getStatement() != null ? chat.getStatement().getId() : null;

        UserGoal saved = goalService.createGoal(
                userId, statementId,
                suggested.name(),
                suggested.targetAmount(),
                suggested.currentSaved(),
                suggested.targetDate(),
                UserGoal.Source.AI_EXTRACTED
        );

        chat.clearPendingSuggestion();
        chat.setJustCreatedGoalName(saved.getName());
        chat.setPendingPlanGoalName(saved.getName());

        String offerMessage = buildGoalConfirmedOfferMessage(saved);
        appendMessage(chat, ChatMessage.Role.ASSISTANT, offerMessage);
        chat.setUpdatedAt(LocalDateTime.now());
        chatRepository.save(chat);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return buildResponse(chat, offerMessage, false, null, null,
                false, null, userId, user.getRole());
    }

    public List<ChatResponse.MsgDto> getHistory(UUID chatId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found: " + chatId));
        return toMsgDtos(chat.getMessages());
    }

    public List<Map<String, Object>> listChats(UUID userId) {   // ← drop statementId param
        return chatRepository
                .findByUserIdOrderByUpdatedAtDesc(userId)       // ← new query
                .stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",        c.getId());
                    m.put("title",     c.getTitle());
                    m.put("updatedAt", c.getUpdatedAt());
                    return m;
                }).collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // P1 HELPER: AUTO-CONFIRM PENDING SUGGESTION + CREATE PLAN
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    protected ChatResponse autoConfirmGoalAndCreatePlan(Chat chat, User user,
                                                        UUID statementId,
                                                        boolean weekly, boolean newChat) {
        UUID userId = user.getId();

        String                  goalName   = chat.getPendingSuggestedGoalName();
        java.math.BigDecimal    goalAmount = chat.getPendingSuggestedGoalAmount();
        java.time.LocalDate     goalDate   = chat.getPendingSuggestedGoalDate();

        UserGoal autoCreated = goalService.createGoal(
                userId, statementId,
                goalName, goalAmount,
                null,
                goalDate,
                UserGoal.Source.AI_EXTRACTED
        );
        log.info("Auto-confirmed pending suggestion '{}' for user {}", goalName, userId);

        chat.clearPendingSuggestion();
        chat.setPendingPlanGoalName(null);
        chatRepository.save(chat);

        return handlePlanCreation(chat, user, statementId, autoCreated.getName(), weekly, newChat);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PLAN CREATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    protected ChatResponse handlePlanCreation(Chat chat, User user, UUID statementId,
                                              String goalName, boolean weekly, boolean newChat) {
        UUID         userId      = user.getId();
        GoalPlanDto  createdPlan = null;
        String       replyText;

        try {
            createdPlan = tryCreatePlanForGoalName(userId, statementId, goalName, weekly, user);
            replyText   = createdPlan != null
                    ? buildPlanOfferReply(createdPlan, goalName, weekly)
                    : "I couldn't find an active goal named \"" + goalName + "\". " +
                    "Please check your goals list or create the goal first.";
        } catch (PlanDurationExceededException | DailyLimitExceededException e) {
            replyText = "⚠️ " + e.getMessage();
        }

        appendMessage(chat, ChatMessage.Role.ASSISTANT, replyText);
        chat.setPendingPlanGoalName(null);
        chat.setUpdatedAt(LocalDateTime.now());
        chatRepository.save(chat);

        return buildResponse(chat, replyText, newChat, null, createdPlan,
                false, null, userId, user.getRole());
    }

    private GoalPlanDto tryCreatePlanForGoalName(UUID userId, UUID statementId,
                                                 String goalName, boolean weekly, User user) {
        try {
            String financialContext = buildFinancialContext(statementId, userId, null);
            return planCreationHelper.tryCreate(userId, statementId, goalName, weekly, financialContext);
        } catch (PlanDurationExceededException | DailyLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.error("Plan creation failed for goal '{}': {}", goalName, e.getMessage(), e);
            return null;
        }
    }

    private String buildPlanOfferReply(GoalPlanDto plan, String goalName, boolean weekly) {
        String frequency    = weekly ? "weekly" : "monthly";
        String periodPlural = weekly ? "weeks" : "months";
        int    displayCount = (plan.weeks != null && !plan.weeks.isEmpty())
                ? Math.min(plan.weeks.size(), GoalPlanService.CHUNK_SIZE)
                : GoalPlanService.CHUNK_SIZE;

        StringBuilder sb = new StringBuilder();
        sb.append("Here's your ").append(frequency).append(" savings plan for **")
                .append(goalName).append("** — I've generated the first ")
                .append(displayCount).append(" ").append(periodPlural)
                .append(" to start. Complete each chunk and I'll unlock the next set with updated actions.\n\n");
        sb.append(plan.summary).append("\n\n");

        if (plan.weeks != null) {
            plan.weeks.stream().limit(GoalPlanService.CHUNK_SIZE).forEach(w -> {
                sb.append("**Week ").append(w.weekNumber).append("** (")
                        .append(w.weekStart).append(" – ").append(w.weekEnd).append(")\n");
                if (w.actions != null) {
                    w.actions.stream()
                            .map(String::trim)
                            .filter(a -> !a.isBlank())
                            .forEach(a -> sb.append("  • ").append(a).append("\n"));
                }
                if (w.tip != null && !w.tip.isBlank()) {
                    sb.append("  💡 ").append(w.tip).append("\n");
                }
                sb.append("\n");
            });
        }

        if (plan.totalWeeks > GoalPlanService.CHUNK_SIZE) {
            sb.append("_").append(weekly ? "Weeks" : "Months").append(" ")
                    .append(GoalPlanService.CHUNK_SIZE + 1).append("–").append(plan.totalWeeks)
                    .append(" will unlock automatically as you complete each chunk._");
        }
        return sb.toString();
    }

    private String buildGoalConfirmedOfferMessage(UserGoal goal) {
        return String.format(
                "✅ Goal created: **%s**%s%s\n\n" +
                        "Would you like me to build a **weekly** or **monthly** savings plan for this goal? " +
                        "I'll give you a concrete roadmap based on your actual spending behavior — " +
                        "3 periods at a time so you get focused, achievable actions.",
                goal.getName(),
                goal.getTargetAmount() != null ? " — ₹" + goal.getTargetAmount().toPlainString() : "",
                goal.getTargetDate()   != null ? " by " + goal.getTargetDate() : ""
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PLAN ACCEPTANCE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    private String detectPlanAcceptance(String userMessage, UUID userId, User.Role role) {
        String lower = userMessage.toLowerCase().trim();

        if (lower.matches("yes|yeah|yep|yup|sure|ok|okay|go ahead|please|" +
                "sounds good|let's do it|do it|make it|create it|build it|yes please")) {
            return "ACCEPT_WEEKLY";
        }
        if (lower.contains("weekly"))  return "ACCEPT_WEEKLY";
        if (lower.contains("monthly")) return "ACCEPT_MONTHLY";

        if (lower.matches("no|nope|not now|skip|cancel|never mind|maybe later")) {
            return "REJECT";
        }

        if (lower.length() > 60) return "REJECT";

        String prompt = PLAN_ACCEPTANCE_PROMPT.formatted(userMessage);
        OpenAIResult result = callOpenAITracked(List.of(msg("user", prompt)), 10);
        tokenBudgetService.record(userId, result.totalTokens());
        String answer = result.content().trim().toUpperCase();
        if (answer.startsWith("ACCEPT_MONTHLY")) return "ACCEPT_MONTHLY";
        if (answer.startsWith("ACCEPT_WEEKLY"))  return "ACCEPT_WEEKLY";
        return "REJECT";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CORRECTION DETECTION + ASYNC RE-ANALYSIS + PLAN REGEN
    // ═══════════════════════════════════════════════════════════════════════════

    @Async
    @Transactional
    public void triggerProfileUpdateIfNeeded(User user, UUID statementId,
                                             String userMessage,
                                             String currentFinancialContext) {
        UUID userId = user.getId();
        try {
            if (!isCorrection(userMessage, userId, user.getRole())) {
                log.debug("No correction detected for user {}", userId);
                return;
            }
            log.info("Correction detected for user {} (statementId={})", userId, statementId);

            if (statementId != null) {
                // Full flow: update per-statement profile AND rebuild user profile
                String updatedContext = reanalyseAndPersist(
                        userId, statementId, userMessage, currentFinancialContext);

                if (updatedContext != null) {
                    goalPlanService.regeneratePendingTasksAfterCorrection(
                            userId, userMessage, updatedContext);
                }
            }

            // Always rebuild the user-level profile when a correction is detected,
            // regardless of whether a statementId is present
            log.info("Triggering full user profile rebuild after correction for user {}", userId);
            userProfileAggregatorService.recomputeAsync(
                    userId,
                    UserProfileAggregatorService.REASON_USER_CORRECTION
            );

        } catch (Exception e) {
            log.error("Profile re-analysis failed for user {} (statementId={})", userId, statementId, e);
        }
    }

    private boolean isCorrection(String userMessage, UUID userId, User.Role role) {
        String lower = userMessage.toLowerCase().trim();
        if (lower.length() < 8) return false;
        if (lower.matches("yes|no|ok|okay|sure|thanks|great|perfect|fine|good")) return false;

        OpenAIResult result = callOpenAITracked(List.of(
                msg("system", CORRECTION_DETECTION_PROMPT),
                msg("user",   userMessage)
        ), 5);
        tokenBudgetService.record(userId, result.totalTokens());
        return result.content().trim().toUpperCase().startsWith("YES");
    }

    @SuppressWarnings("unchecked")
    private String reanalyseAndPersist(UUID userId, UUID statementId,
                                       String userMessage,
                                       String currentFinancialContext) {
        String currentAnalysisJson = analysisRepository.findByStatementId(statementId)
                .map(FinancialAIAnalysis::getAnalysisJson).orElse("{}");
        String currentContextJson = profileRepository.findByStatementId(statementId)
                .map(FinancialProfile::getContextJson).orElse("{}");

        String userContent = """
                === EXISTING FINANCIAL CONTEXT ===
                %s

                === EXISTING ANALYSIS JSON ===
                %s

                === EXISTING CONTEXT JSON ===
                %s

                === USER CORRECTION / NEW INFORMATION ===
                "%s"

                Update both JSON objects to incorporate this correction precisely.
                Follow all rules in your system prompt.
                """.formatted(currentFinancialContext, currentAnalysisJson,
                currentContextJson, userMessage);

        OpenAIResult result = callOpenAITracked(List.of(
                msg("system", REANALYSIS_SYSTEM_PROMPT),
                msg("user",   userContent)
        ), 3000);
        tokenBudgetService.record(userId, result.totalTokens());

        String rawJson = result.content()
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        Map<String, Object> reanalysisResult;
        try {
            reanalysisResult = objectMapper.readValue(rawJson, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse re-analysis JSON for statement {}: {}", statementId, rawJson, e);
            return null;
        }

        analysisRepository.findByStatementId(statementId).ifPresent(analysis -> {
            try {
                Object analysisJsonObj = reanalysisResult.get("analysisJson");
                if (analysisJsonObj != null) {
                    String updated = analysisJsonObj instanceof String s
                            ? s : objectMapper.writeValueAsString(analysisJsonObj);
                    analysis.setAnalysisJson(updated);
                    analysis.setModel(MODEL);
                    analysis.setPromptVersion(PROMPT_VERSION);
                    analysis.setUpdatedAt(LocalDateTime.now());
                    analysisRepository.save(analysis);
                    log.info("FinancialAIAnalysis updated for statement {}", statementId);
                }
            } catch (Exception e) {
                log.error("Failed to persist analysisJson for statement {}", statementId, e);
            }
        });

        String[] updatedContextJson = {null};
        profileRepository.findByStatementId(statementId).ifPresent(profile -> {
            try {
                Object contextJsonObj = reanalysisResult.get("contextJson");
                if (contextJsonObj != null) {
                    String updated = contextJsonObj instanceof String s
                            ? s : objectMapper.writeValueAsString(contextJsonObj);
                    profile.setContextJson(updated);
                    updatedContextJson[0] = updated;
                }
                Object healthScoreObj = reanalysisResult.get("healthScore");
                if (healthScoreObj instanceof Number n) {
                    profile.setHealthScore(n.intValue());
                }
                Object riskLevelObj = reanalysisResult.get("riskLevel");
                if (riskLevelObj instanceof String riskLevel
                        && Set.of("LOW", "MEDIUM", "HIGH").contains(riskLevel.toUpperCase())) {
                    profile.setRiskLevel(riskLevel.toUpperCase());
                }
                profileRepository.save(profile);
                log.info("FinancialProfile updated for statement {} — healthScore={}, riskLevel={}",
                        statementId, profile.getHealthScore(), profile.getRiskLevel());
            } catch (Exception e) {
                log.error("Failed to persist profile for statement {}", statementId, e);
            }
        });

        return updatedContextJson[0] != null
                ? buildFinancialContext(statementId, userId, null)
                : null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FINANCIAL CONTEXT BUILDER — UPDATED: UserFinancialProfile FIRST
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds the financial context string injected into every AI prompt.
     *
     * Priority:
     *   1. UserFinancialProfile (user-level, cross-statement, merged) — PRIMARY
     *   2. Per-statement FinancialProfile + FinancialAIAnalysis — FALLBACK
     *
     * Goal context is always appended regardless of which source was used.
     */
    private String buildFinancialContext(UUID statementId, UUID userId,
                                         String justCreatedGoalName) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("[FINANCIAL CONTEXT — use this to answer the user's question]\n\n");

        if (justCreatedGoalName != null) {
            ctx.append("[GOAL_JUST_CREATED: ").append(justCreatedGoalName).append("]\n\n");
        }

        // ── PRIMARY: UserFinancialProfile ─────────────────────────────────────
        Optional<UserFinancialProfile> userProfileOpt =
                userProfileRepository.findByUserId(userId);

        if (userProfileOpt.isPresent()) {
            UserFinancialProfile up = userProfileOpt.get();

            if (up.getPeriodFrom() != null && up.getPeriodTo() != null) {
                ctx.append("DATA PERIOD: ")
                        .append(up.getPeriodFrom()).append(" to ").append(up.getPeriodTo()).append("\n");
            }
            if (up.getStatementCount() != null) {
                ctx.append("STATEMENTS ANALYSED: ").append(up.getStatementCount()).append("\n");
            }
            if (up.getTransactionCount() != null) {
                ctx.append("TRANSACTIONS ANALYSED: ").append(up.getTransactionCount()).append("\n");
            }
            if (up.getHealthScore() != null) {
                ctx.append("HEALTH SCORE: ").append(up.getHealthScore()).append("/100");
            }
            if (up.getRiskLevel() != null) {
                ctx.append(" | RISK: ").append(up.getRiskLevel());
            }
            ctx.append("\n\n");

            if (up.getContextJson() != null && !up.getContextJson().isBlank()) {
                ctx.append("FINANCIAL CONTEXT:\n").append(up.getContextJson()).append("\n\n");
            }

            if (up.getAnalysisJson() != null && !up.getAnalysisJson().isBlank()) {
                try {
                    Map<?, ?> map = objectMapper.readValue(up.getAnalysisJson(), Map.class);
                    appendSection(ctx, "PERSONALITY",    map, "moneyPersonality");
                    appendSection(ctx, "SPENDING PULSE", map, "spendingPulse");
                    appendList(ctx,    "KEY RISKS",       map, "risks");
                    appendList(ctx,    "POSITIVE HABITS", map, "positiveHabits");
                    appendList(ctx,    "HIDDEN PATTERNS", map, "hiddenPatterns");
                } catch (Exception e) {
                    log.warn("Failed to parse UserFinancialProfile analysisJson for user {}", userId, e);
                    ctx.append("AI ANALYSIS:\n").append(up.getAnalysisJson()).append("\n\n");
                }
            }

            log.debug("Financial context built from UserFinancialProfile for user {}", userId);

        } else {
            // ── FALLBACK: per-statement ───────────────────────────────────────
            log.debug("No UserFinancialProfile for user {} — using per-statement fallback", userId);

            profileRepository.findByStatementId(statementId).ifPresent(profile -> {
                ctx.append("HEALTH SCORE: ").append(profile.getHealthScore()).append("/100")
                        .append(" | RISK: ").append(profile.getRiskLevel()).append("\n\n");
                try {
                    String raw = profile.getContextJson();
                    if (raw != null && raw.trim().startsWith("{")) {
                        Map<?, ?> map = objectMapper.readValue(raw, Map.class);
                        Object text = map.get("promptText");
                        ctx.append(text != null ? text.toString() : raw);
                    } else if (raw != null) {
                        ctx.append(raw);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse contextJson for statement {}", statementId, e);
                    profileRepository.findByStatementId(statementId)
                            .ifPresent(p -> ctx.append(p.getContextJson()));
                }
                ctx.append("\n");
            });

            analysisRepository.findByStatementId(statementId).ifPresent(analysis -> {
                try {
                    Map<?, ?> map = objectMapper.readValue(analysis.getAnalysisJson(), Map.class);
                    appendSection(ctx, "PERSONALITY",    map, "moneyPersonality");
                    appendSection(ctx, "SPENDING PULSE", map, "spendingPulse");
                    appendList(ctx,    "KEY RISKS",       map, "risks");
                    appendList(ctx,    "POSITIVE HABITS", map, "positiveHabits");
                    appendList(ctx,    "HIDDEN PATTERNS", map, "hiddenPatterns");
                } catch (Exception e) {
                    log.warn("Failed to parse analysisJson for statement {}", statementId, e);
                }
            });
        }

        // ── ALWAYS: goal context ──────────────────────────────────────────────
        String goalsCtx = goalService.buildGoalsContext(userId);
        if (goalsCtx != null && !goalsCtx.isBlank()) ctx.append(goalsCtx);

        ctx.append("\n[END FINANCIAL CONTEXT]");
        return ctx.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPENAI MESSAGE BUILDER
    // ═══════════════════════════════════════════════════════════════════════════

    private List<Map<String, String>> buildOpenAIMessages(Chat chat, String financialContext) {
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(msg("system", SYSTEM_PROMPT));
        messages.add(msg("user", financialContext));
        messages.add(msg("assistant",
                "Understood. I have your complete financial profile, active goals, and all " +
                        "conversational state. I'll give specific, personalized answers and follow " +
                        "all marker rules precisely."));

        if (chat.getPendingPlanGoalName() != null) {
            messages.add(msg("system",
                    "[PLAN_OFFER_PENDING: " + chat.getPendingPlanGoalName() + "] " +
                            "The user was just offered a savings plan for this goal. " +
                            "If they accept, append ---CREATE_PLAN:<goal>:<WEEKLY|MONTHLY>---"));
        }

        List<ChatMessage> history  = chat.getMessages();
        int               lastIdx  = history.size() - 1;
        List<ChatMessage> prior    = lastIdx > 0 ? history.subList(0, lastIdx) : List.of();
        int               startIdx = Math.max(0, prior.size() - MAX_HISTORY_TURNS * 2);

        prior.subList(startIdx, prior.size())
                .forEach(m -> messages.add(msg(m.getRole().name().toLowerCase(), m.getContent())));

        messages.add(msg("user", history.get(lastIdx).getContent()));
        return messages;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVERSATION CONTEXT FOR GOAL DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    private String buildConversationContext(Chat chat, String latestUserMessage) {
        List<ChatMessage> history = chat.getMessages();
        int end   = history.size();
        int start = Math.max(0, end - 8);

        StringBuilder sb = new StringBuilder("Recent conversation:\n");
        history.subList(start, end).forEach(m ->
                sb.append(m.getRole().name()).append(": ").append(m.getContent()).append("\n")
        );
        sb.append("\nCurrent user message: ").append(latestUserMessage);
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPENAI CALL
    // ═══════════════════════════════════════════════════════════════════════════

    private record OpenAIResult(String content, int totalTokens) {}

    @SuppressWarnings("unchecked")
    private OpenAIResult callOpenAITracked(List<Map<String, String>> messages, int maxTokens) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model",      MODEL);
            body.put("max_tokens", maxTokens);
            body.put("messages",   messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    OPENAI_URL, new HttpEntity<>(body, headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("OpenAI API error: {}", response.getStatusCode());
                return new OpenAIResult(
                        "I'm having trouble connecting right now. Please try again in a moment.", 0);
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
            if (choices == null || choices.isEmpty()) {
                return new OpenAIResult("No response from AI.", totalTokens);
            }

            String content = ((String) ((Map<String, Object>) choices.get(0).get("message"))
                    .get("content")).trim();

            return new OpenAIResult(content, totalTokens);

        } catch (Exception e) {
            log.error("OpenAI API call failed", e);
            return new OpenAIResult("Something went wrong. Please try again.", 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SMALL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private String extractMarkerValue(String text, String markerPrefix) {
        int start = text.indexOf(markerPrefix);
        if (start < 0) return null;
        int valueStart = start + markerPrefix.length();
        int end        = text.indexOf("---", valueStart);
        if (end < 0) {
            int newline = text.indexOf('\n', valueStart);
            end = newline < 0 ? text.length() : newline;
        }
        return text.substring(valueStart, end).trim();
    }

    private Chat createChat(User user, UUID statementId, String firstMessage) {
        Chat chat = new Chat();
        chat.setUser(user);
        if (statementId != null) {
            Statement statement = statementRepository.findById(statementId)
                    .orElseThrow(() -> new IllegalArgumentException("Statement not found"));
            chat.setStatement(statement);
        }
        chat.setTitle(truncate(firstMessage, 80));
        return chatRepository.save(chat);
    }

    private void appendMessage(Chat chat, ChatMessage.Role role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setChat(chat);
        msg.setRole(role);
        msg.setContent(content);
        chat.getMessages().add(msg);
    }

    private Map<String, String> msg(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role",    role);
        m.put("content", content);
        return m;
    }

    private ChatResponse buildResponse(Chat chat, String reply, boolean newChat,
                                       SuggestedGoal suggestedGoal, GoalPlanDto createdPlan,
                                       boolean isDuplicateGoal, String duplicateGoalName,
                                       UUID userId, User.Role role) {
        ChatResponse resp = new ChatResponse();
        resp.setChatId(chat.getId());
        resp.setReply(reply);
        resp.setNewChat(newChat);
        resp.setHistory(toMsgDtos(chat.getMessages()));
        resp.setSuggestedGoal(suggestedGoal);
        resp.setCreatedPlan(createdPlan);
        resp.setPlanOfferPending(chat.hasPendingPlanOffer());
        resp.setPendingPlanGoalName(chat.getPendingPlanGoalName());
        resp.setIsDuplicateGoal(isDuplicateGoal);
        resp.setDuplicateGoalName(duplicateGoalName);

        if (reply != null && reply.startsWith("⚠️")) {
            resp.setPlanLimitError(reply.replace("⚠️ ", ""));
        }

        resp.setTokensUsedToday(tokenBudgetService.getUsedToday(userId));
        resp.setTokensRemainingToday(tokenBudgetService.getRemainingToday(userId, role));
        return resp;
    }

    private List<ChatResponse.MsgDto> toMsgDtos(List<ChatMessage> msgs) {
        return msgs.stream()
                .map(m -> new ChatResponse.MsgDto(
                        m.getId(), m.getRole().name(), m.getContent(), m.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private void appendSection(StringBuilder ctx, String label, Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val == null) return;
        ctx.append(label).append(":\n");
        if (val instanceof Map) {
            ((Map<String, Object>) val).forEach((k, v) ->
                    ctx.append("  ").append(k).append(": ").append(v).append("\n"));
        } else {
            ctx.append("  ").append(val).append("\n");
        }
        ctx.append("\n");
    }

    @SuppressWarnings("unchecked")
    private void appendList(StringBuilder ctx, String label, Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof List)) return;
        List<Object> list = (List<Object>) val;
        if (list.isEmpty()) return;
        ctx.append(label).append(":\n");
        list.forEach(item -> ctx.append("  • ").append(item).append("\n"));
        ctx.append("\n");
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}