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
    private final ChatRepository                chatRepository;
    private final FinancialProfileRepository    profileRepository;
    private final FinancialAIAnalysisRepository analysisRepository;
    private final StatementRepository           statementRepository;
    private final UserRepository                userRepository;
    private final GoalService                   goalService;
    private final GoalPlanService               goalPlanService;
    private final TokenBudgetService            tokenBudgetService;
    private final ObjectMapper                  objectMapper;
    private final RestTemplate                  restTemplate;
    private final PlanCreationHelper            planCreationHelper;

    @Value("${openai.api.key}")
    private String openaiApiKey;

    public ChatService(
            ChatRepository chatRepository,
            FinancialProfileRepository profileRepository,
            FinancialAIAnalysisRepository analysisRepository,
            StatementRepository statementRepository,
            UserRepository userRepository,
            PlanCreationHelper planCreationHelper,
            GoalService goalService,
            GoalPlanService goalPlanService,
            TokenBudgetService tokenBudgetService,
            ObjectMapper objectMapper
    ) {
        this.chatRepository      = chatRepository;
        this.profileRepository   = profileRepository;
        this.analysisRepository  = analysisRepository;
        this.statementRepository = statementRepository;
        this.userRepository      = userRepository;
        this.goalService         = goalService;
        this.goalPlanService     = goalPlanService;
        this.tokenBudgetService  = tokenBudgetService;
        this.objectMapper        = objectMapper;
        this.planCreationHelper  = planCreationHelper;
        this.restTemplate        = new RestTemplate();
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
        //
        // We snapshot here so that all routing decisions in steps 6 and 7
        // are based on the state at the START of this turn, not any
        // intermediate state we may write during the turn.
        final String pendingPlanGoalName      = chat.getPendingPlanGoalName();
        final String justCreatedGoalName      = chat.getJustCreatedGoalName();
        final String pendingSuggestedGoalName = chat.getPendingSuggestedGoalName();

        // ── 6. P1: PENDING-SUGGESTION AUTO-CONFIRM ────────────────────────────
        //
        // A goal suggestion card is on screen (pendingSuggestedGoalName != null).
        // If the user sends a short acceptance phrase ("weekly", "yes", "monthly"),
        // auto-create the goal and jump straight to plan generation.
        //
        // We deliberately only do this for SHORT messages (≤ 40 chars).  A long
        // message like "I want to save for an iPhone but also go to Goa" is a new
        // goal mention, not a plan acceptance — it must flow through normal AI.
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
        //
        // A plan offer is pending for an already-confirmed goal.
        // If the user accepts → create the plan.
        // If the user rejects → clear state and fall through to normal AI.
        // If pendingSuggestedGoalName is also set, that means a NEW goal was
        // suggested after the plan offer was sent; we skip P2 so the user can
        // deal with the new suggestion first.
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
            // REJECT or ambiguous — fall through to normal AI turn
        }

        // ── 8. P3: NORMAL AI TURN ─────────────────────────────────────────────

        // 8a. Clear the one-turn justCreatedGoalName at the start of every new turn
        //     (it was consumed in the previous turn's context injection)
        chat.setJustCreatedGoalName(null);

        // 8b. Build financial context
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

        // 8e. Clean markers from the reply the user will see
        String cleanReply = rawReply
                .replaceAll("---SUGGEST_GOAL---",        "")
                .replaceAll("---OFFER_PLAN:[^\\n]*---",  "")
                .replaceAll("---CREATE_PLAN:[^\\n]*---", "")
                .trim();

        // 8f. Persist clean assistant reply
        appendMessage(chat, ChatMessage.Role.ASSISTANT, cleanReply);

        // ── 9. GOAL DETECTION ─────────────────────────────────────────────────
        //
        // Runs after the AI turn so we always have the full conversation context
        // (including the AI's just-persisted reply) available.
        SuggestedGoal suggestedGoal    = null;
        boolean       isDuplicateGoal  = false;
        String        duplicateGoalName = null;

        if (shouldSuggestGoal) {
            String conversationContext = buildConversationContext(chat, userMessage);

            // Pass the current pending suggestion name so the LLM suppresses it.
            // Note: we use the CURRENT (live) value, not the snapshotted one,
            // because the suggestion may have been updated already in this turn.
            GoalService.GoalSuggestionResult result =
                    goalService.detectGoalSuggestion(
                            userId,
                            conversationContext,
                            chat.getPendingSuggestedGoalName()
                    );

            if (result.isDuplicate()) {
                isDuplicateGoal   = true;
                duplicateGoalName = result.duplicateGoalName();
                // Only clear the pending suggestion if IT is the duplicate.
                // If the pending suggestion is a DIFFERENT goal, preserve it.
                if (duplicateGoalName != null
                        && duplicateGoalName.equalsIgnoreCase(chat.getPendingSuggestedGoalName())) {
                    chat.clearPendingSuggestion();
                }
                log.info("Duplicate goal detected: '{}' already exists", duplicateGoalName);

            } else if (result.goal() != null) {
                String newGoalName = result.goal().name();

                // Replace the pending suggestion ONLY if it's a genuinely different goal.
                // This prevents overwriting an unconfirmed suggestion with itself on
                // follow-up messages in the same topic thread.
                if (!newGoalName.equalsIgnoreCase(chat.getPendingSuggestedGoalName())) {
                    log.info("New goal suggestion: '{}' (replacing pending suggestion '{}')",
                            newGoalName, chat.getPendingSuggestedGoalName());
                    chat.replacePendingSuggestion(
                            newGoalName,
                            result.goal().targetAmount(),
                            result.goal().targetDate()
                    );
                    // replacePendingSuggestion() already clears pendingPlanGoalName
                } else {
                    log.debug("Same goal re-detected: '{}' — returning suggestion without state change",
                            newGoalName);
                }
                suggestedGoal = result.goal();

            } else {
                // AI appended ---SUGGEST_GOAL--- but extraction found nothing meaningful.
                // This happens on vague messages like "I want to save something".
                // Do NOT clear the pending suggestion — the user may still need to act on it.
                log.debug("SUGGEST_GOAL marker fired but extraction found no goal; " +
                        "preserving pending suggestion '{}'", chat.getPendingSuggestedGoalName());
            }
        }

        // ── 10. UPDATE CONVERSATION STATE FLAGS ───────────────────────────────
        if (shouldOfferPlan && offerPlanGoalName != null) {
            // Entering plan-offer state: clear any stale suggestion first
            chat.clearPendingSuggestion();
            chat.setPendingPlanGoalName(offerPlanGoalName);

        } else if (shouldCreatePlan && createPlanGoalName != null) {
            // AI decided to create the plan inline (rare — usually driven by
            // explicit ---CREATE_PLAN--- in a follow-up where we already know
            // frequency from context).
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

    /**
     * Called when the user clicks the confirm button on a goal suggestion card.
     * Creates the goal, transitions to plan-offer state, and returns the offer message.
     */
    @Transactional
    public ChatResponse confirmGoalAndOfferPlan(UUID userId, UUID chatId, SuggestedGoal suggested) {

        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found: " + chatId));
        UUID statementId = chat.getStatement().getId();

        UserGoal saved = goalService.createGoal(
                userId, statementId,
                suggested.name(),
                suggested.targetAmount(),
                suggested.currentSaved(),
                suggested.targetDate(),
                UserGoal.Source.AI_EXTRACTED
        );

        // Transition: clear State A → enter State B
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

    public List<Map<String, Object>> listChats(UUID userId, UUID statementId) {
        return chatRepository
                .findByUserIdAndStatementIdOrderByUpdatedAtDesc(userId, statementId)
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

    /**
     * The user typed "weekly" / "yes" / "monthly" while a suggestion card was on
     * screen.  We create the goal from the pending suggestion state and go straight
     * to plan generation — no extra round-trip.
     */
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
                null,     // currentSaved — not known at this stage
                goalDate,
                UserGoal.Source.AI_EXTRACTED
        );
        log.info("Auto-confirmed pending suggestion '{}' for user {}", goalName, userId);

        // Transition: clear State A and any stale State B before entering new State B
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

    /**
     * Classifies a user message as ACCEPT_WEEKLY, ACCEPT_MONTHLY, or REJECT.
     *
     * Fast-path: common words are handled without an LLM call.
     * Slow-path: ambiguous messages go to GPT for classification.
     */
    private String detectPlanAcceptance(String userMessage, UUID userId, User.Role role) {
        String lower = userMessage.toLowerCase().trim();

        // Fast-path acceptances
        if (lower.matches("yes|yeah|yep|yup|sure|ok|okay|go ahead|please|" +
                "sounds good|let's do it|do it|make it|create it|build it|yes please")) {
            return "ACCEPT_WEEKLY";
        }
        if (lower.contains("weekly"))  return "ACCEPT_WEEKLY";
        if (lower.contains("monthly")) return "ACCEPT_MONTHLY";

        // Fast-path rejections
        if (lower.matches("no|nope|not now|skip|cancel|never mind|maybe later")) {
            return "REJECT";
        }

        // Anything longer than a typical acceptance phrase is unlikely to be one.
        // Treat it as a regular message without burning tokens on classification.
        if (lower.length() > 60) return "REJECT";

        // Slow-path: call LLM for genuinely ambiguous short messages
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
                log.debug("No correction detected for statement {}", statementId);
                return;
            }
            log.info("Correction detected for statement {} — triggering re-analysis + plan regen",
                    statementId);

            String updatedContext =
                    reanalyseAndPersist(userId, statementId, userMessage, currentFinancialContext);

            if (updatedContext != null) {
                goalPlanService.regeneratePendingTasksAfterCorrection(
                        userId, userMessage, updatedContext);
            }
        } catch (Exception e) {
            log.error("Profile re-analysis failed for statement {}", statementId, e);
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
    // FINANCIAL CONTEXT BUILDER
    // ═══════════════════════════════════════════════════════════════════════════

    private String buildFinancialContext(UUID statementId, UUID userId,
                                         String justCreatedGoalName) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("[FINANCIAL CONTEXT — use this to answer the user's question]\n\n");

        if (justCreatedGoalName != null) {
            ctx.append("[GOAL_JUST_CREATED: ").append(justCreatedGoalName).append("]\n\n");
        }

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
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Statement not found: " + statementId));
        Chat chat = new Chat();
        chat.setUser(user);
        chat.setStatement(statement);
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