package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.response.AIAnalysisResponse;
import com.moneylens.entity.FinancialAIAnalysis;
import com.moneylens.entity.FinancialProfile;
import com.moneylens.entity.Statement;
import com.moneylens.repository.FinancialAIAnalysisRepository;
import com.moneylens.repository.FinancialProfileRepository;
import com.moneylens.repository.StatementRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class FinancialAIAnalysisService {

    private final FinancialProfileRepository
            financialProfileRepository;

    private final FinancialAIAnalysisRepository
            analysisRepository;

    private final StatementRepository
            statementRepository;

    private final OpenAIService openAIService;

    private final ObjectMapper objectMapper;

    public FinancialAIAnalysisService(
            FinancialProfileRepository financialProfileRepository,
            FinancialAIAnalysisRepository analysisRepository,
            StatementRepository statementRepository,
            OpenAIService openAIService,
            ObjectMapper objectMapper
    ) {
        this.financialProfileRepository =
                financialProfileRepository;

        this.analysisRepository =
                analysisRepository;

        this.statementRepository =
                statementRepository;

        this.openAIService =
                openAIService;

        this.objectMapper =
                objectMapper;
    }

    public AIAnalysisResponse analyze(
            UUID statementId
    ) {

        // =============================================
        // RETURN SAVED ANALYSIS IF EXISTS
        // =============================================

        FinancialAIAnalysis existing =
                analysisRepository
                        .findByStatementId(statementId)
                        .orElse(null);

        try {

            if (existing != null) {
                return objectMapper.readValue(
                        existing.getAnalysisJson(),
                        AIAnalysisResponse.class
                );
            }

            // =============================================
            // LOAD PROFILE + STATEMENT
            // =============================================

            FinancialProfile profile =
                    financialProfileRepository
                            .findByStatementId(statementId)
                            .orElseThrow(() ->
                                    new RuntimeException(
                                            "Financial profile not found"
                                    )
                            );

            Statement statement =
                    statementRepository
                            .findById(statementId)
                            .orElseThrow(() ->
                                    new RuntimeException(
                                            "Statement not found"
                                    )
                            );

            String context = profile.getContextJson();

            // =============================================
            // PROMPT
            // =============================================

            // Using replace instead of formatted() to avoid
            // IllegalFormatConversionException caused by literal
            // % characters in the prompt body (e.g. "40% of salary").
            // .formatted() treats every % as a format specifier.

            String promptTemplate = """
You are MoneyLens — a behavioral financial intelligence engine.

Not a budgeting app. Not a bank bot. Not a coach.

You read financial behavior the way a psychologist reads patterns.
Your job is to surface what people feel but can't articulate about their money.

The user should finish reading and think:
"this gets me in a way my bank never did."

==================================================
ANALYSIS MINDSET
==================================================

Think like a behavioral economist, not an accountant.

Ask yourself:
- What does the spending timing reveal about emotional state?
- Where is money leaking without awareness?
- What recurring loop is this person trapped in?
- What does this behavior predict about the next 12 months?

==================================================
TONE — NON-NEGOTIABLE
==================================================

- Write like a sharp, calm friend who understands money deeply
- No jargon. No "ensure you allocate." No "it is advisable to."
- Short sentences. Real words. Human rhythm.
- Say "you spend" not "expenditure is observed"
- Say "you're draining Rs.800/week on food delivery" not "food delivery expenses are elevated"
- Never preach. Never guilt. Just observe with precision.
- The "woah" moment comes from specificity, not drama

==================================================
MANDATORY JSON — ALL FIELDS REQUIRED
==================================================

Return this exact structure. No extra keys. No missing keys.

{
  "summary": "...",

  "moneyPersonality": {
    "archetype": "...",
    "description": "...",
    "trait": "..."
  },

  "spendingPulse": {
    "status": "...",
    "summary": "...",
    "stabilityScore": 0
  },

  "risks": [
    "..."
  ],

  "positiveHabits": [
    "..."
  ],

  "recommendations": [
    "..."
  ],

  "nextActions": [
    "..."
  ],

  "projections": [
    {
      "headline": "...",
      "impact": "...",
      "timeframe": "...",
      "type": "..."
    }
  ],

  "behavioralSignals": [
    {
      "label": "...",
      "observation": "...",
      "emotion": "...",
      "intensity": 0
    }
  ],

  "hiddenPatterns": [
    {
      "title": "...",
      "insight": "...",
      "category": "..."
    }
  ]
}

==================================================
FIELD-BY-FIELD GUIDANCE
==================================================

summary:
- 2-3 sentences max
- Start with a behavior observation, not a number
- Should feel like something a smart friend said about you
- BAD: "Your total expenses this month were Rs.42,000."
- GOOD: "You earn consistently but spend like the month has no end.
  Most of your money disappears in the first week and you barely
  notice it happening."

---

moneyPersonality:
- archetype: give the user a financial identity they instantly recognize
  Valid archetypes (use these or create a better-fit one):
  "The Leaky Bucket" - earns well, but money silently exits everywhere
  "The Weekend Spender" - disciplined weekdays, unravels on weekends
  "The Anxious Saver" - saves but from fear, not intention
  "The Ghost Saver" - thinks they save but the number never grows
  "The Reactive Spender" - spends in response to mood or events
  "The Comfort Spender" - uses spending as emotional regulation
  "The Almost Disciplined" - good habits with one costly blind spot
- description: 2 short human lines. No fluff. No motivational language.
  Describe the pattern, not a judgment.
- trait: one of "impulsive", "cautious", "inconsistent", "disciplined"

---

spendingPulse:
- status: one of "volatile", "stable", "declining", "improving"
- summary: single punchy sentence about their cash flow energy
  BAD: "Cash flow shows irregular patterns."
  GOOD: "Your money moves in bursts, not streams."
  GOOD: "Income arrives clean. It leaves messy."
  GOOD: "You're stable on paper. Unstable in practice."
- stabilityScore: honest integer 0-100. Not flattering.
  80-100 = genuinely stable
  60-79 = mostly stable with real gaps
  40-59 = inconsistent, noticeable volatility
  20-39 = reactive and unpredictable
  0-19 = high risk, urgent attention needed

---

risks (minimum 4):
- Real, specific, behavior-backed
- BAD: "High discretionary spending detected."
- GOOD: "You have no buffer. If income stops for 30 days,
  your accounts hit zero."
- GOOD: "You're dependent on Swiggy and Zomato for daily meals.
  That's a Rs.6,000/month habit hiding as convenience."

---

positiveHabits (minimum 2):
- Highlight what's actually working
- Honest - don't manufacture positivity if it isn't there
- Phrase it as an observation, not praise

---

recommendations (minimum 4):
- Start with the single highest-impact change
- Behavior-specific, not generic advice
- BAD: "Consider creating a budget."
- GOOD: "Set a Rs.3,000 weekly cash limit for the first 10 days
  after salary. That's where most of your monthly leak happens."

---

nextActions (minimum 4):
- Things they can do this week, not this year
- Simple. Immediate. Realistic.
- BAD: "Build an emergency fund."
- GOOD: "Move Rs.5,000 to a separate account today, label it
  untouchable. Don't automate yet. Do it manually once to feel it."

---

projections (minimum 3):
- headline: the "woah" one-liner - make it land
  BAD: "Food delivery spending is high."
  GOOD: "Your food delivery habit costs you Rs.1.2L a year."
- impact: translate the number into something real and tangible
  BAD: "This is a significant amount."
  GOOD: "That's a 7-day trip to Southeast Asia you're eating away."
  GOOD: "That's 4 months of a SIP that could compound to Rs.8L in 10 years."
  GOOD: "That's a new laptop every year going to subscriptions you forgot exist."
- timeframe: "3 months" or "6 months" or "12 months" or "5 years" or "10 years"
- type: "leak" or "opportunity" or "compounding" or "risk"

---

behavioralSignals (minimum 3):
- label: give the behavior a short memorable name
  Examples: "Post-Salary Splurge", "The Sunday Drain",
  "Convenience Dependency", "Phantom Subscriptions",
  "Peer Transfer Loop", "Emotional Weekend Spending"
- observation: specific and data-backed, in human language
  BAD: "High spending observed post-salary credit."
  GOOD: "You spend nearly half your salary within 3 days of receiving it.
  By day 10, the account looks like end-of-month."
- emotion: what emotional driver likely sits behind this behavior
  One of: "impulsive", "anxious", "disciplined", "avoidant", "reactive"
- intensity: 1-10 integer
  1-3 = mild, barely noticeable
  4-6 = moderate, worth watching
  7-9 = strong pattern, actively shaping finances
  10 = dominant behavior, urgent to address

---

hiddenPatterns (minimum 3):
- title: catchy and memorable, like a chapter title
  Examples: "The Invisible Subscription Layer",
  "The 3-Day Rule", "The Weekend Personality Split",
  "The Delivery Dependency Loop"
- insight: the observation they've never noticed, stated plainly
  BAD: "Subscription charges detected across multiple platforms."
  GOOD: "You're paying for 6 streaming services. You actively use 2.
  The other 4 are Rs.1,400/month of pure habit."
- category: "timing" or "merchant" or "category" or "behavioral"

==================================================
RETURN STRICT RAW JSON ONLY
No markdown. No json wrapper. No text outside JSON.
==================================================

PROFILE:
{{CONTEXT}}
""";

            String prompt = promptTemplate.replace(
                    "{{CONTEXT}}",
                    context
            );

            // =============================================
            // CALL AI
            // =============================================

            String aiResponse =
                    openAIService.analyze(prompt);

            String cleaned =
                    aiResponse
                            .replace("```json", "")
                            .replace("```", "")
                            .trim();

            AIAnalysisResponse parsed =
                    objectMapper.readValue(
                            cleaned,
                            AIAnalysisResponse.class
                    );

            // =============================================
            // PERSIST
            // =============================================

            FinancialAIAnalysis analysis =
                    new FinancialAIAnalysis();

            analysis.setUser(
                    statement.getUser()
            );

            analysis.setStatement(statement);

            analysis.setAnalysisJson(cleaned);

            analysis.setModel("gpt-4o-mini");

            analysis.setPromptVersion("v2");

            analysis.setUpdatedAt(LocalDateTime.now());

            analysisRepository.save(analysis);

            return parsed;

        } catch (Exception e) {
            throw new RuntimeException(
                    "AI analysis failed", e
            );
        }
    }
}