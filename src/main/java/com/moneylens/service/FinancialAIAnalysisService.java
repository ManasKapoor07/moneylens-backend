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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FinancialAIAnalysisService {

    private final FinancialProfileRepository    financialProfileRepository;
    private final FinancialAIAnalysisRepository analysisRepository;
    private final StatementRepository           statementRepository;
    private final OpenAIService                 openAIService;
    private final ObjectMapper                  objectMapper;

    // The analysis prompt — kept here so both analyze() overloads use
    // the exact same prompt template.
    private static final String ANALYSIS_PROMPT_TEMPLATE = """
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
BEHAVIORAL SIGNALS — NARRATE, DO NOT INVENT
==================================================
The BEHAVIORAL SIGNALS section in the profile above contains pre-computed,
evidence-backed signals. Each has a severity (HIGH / MEDIUM / LOW) and an
evidence string containing the actual numbers.
Your job:
- Translate each fired signal into a 1–2 sentence human observation
- Use the evidence string as your source of truth
- Do NOT add signals that aren't listed
- Do NOT soften HIGH severity signals
- Do NOT speculate about emotional causes — describe observable behavior
For behavioralSignals in the JSON response:
  label       = signal type name in title case (e.g. "Salary Day Spike")
  observation = 1–2 sentences using the evidence provided
  emotion     = only if the behavioral pattern has a clear observable trigger
                (e.g. "post-salary relief spending") — otherwise omit
  intensity   = map severity: HIGH → 8-10, MEDIUM → 4-7, LOW → 1-3
For hiddenPatterns:
  Surface non-obvious connections between the signals.
  Example: if SALARY_DAY_SPIKE + FOOD_DELIVERY_HABIT both fired,
  the hidden pattern might be "stress relief spending concentrated post-payday."
  Only state what the evidence supports.

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

  "risks": [ "..." ],

  "positiveHabits": [ "..." ],

  "recommendations": [ "..." ],

  "nextActions": [ "..." ],

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
RETURN STRICT RAW JSON ONLY
No markdown. No json wrapper. No text outside JSON.
==================================================

PROFILE:
{{CONTEXT}}
""";

    public FinancialAIAnalysisService(
            FinancialProfileRepository financialProfileRepository,
            FinancialAIAnalysisRepository analysisRepository,
            StatementRepository statementRepository,
            OpenAIService openAIService,
            ObjectMapper objectMapper
    ) {
        this.financialProfileRepository = financialProfileRepository;
        this.analysisRepository         = analysisRepository;
        this.statementRepository        = statementRepository;
        this.openAIService              = openAIService;
        this.objectMapper               = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Original per-statement analysis.
     *
     * Returns cached result if one exists, otherwise runs AI on the
     * statement's FinancialProfile.contextJson.
     *
     * Still used for:
     *   - Per-statement drill-down pages
     *   - FinancialAIAnalysis entity (statement-scoped)
     */
    public AIAnalysisResponse analyze(UUID statementId) {

        // Return saved per-statement analysis if it exists
        FinancialAIAnalysis existing = analysisRepository
                .findByStatementId(statementId)
                .orElse(null);

        try {
            if (existing != null) {
                return objectMapper.readValue(
                        existing.getAnalysisJson(),
                        AIAnalysisResponse.class
                );
            }

            // Load the per-statement profile context
            FinancialProfile profile = financialProfileRepository
                    .findByStatementId(statementId)
                    .orElseThrow(() -> new RuntimeException("Financial profile not found"));

            Statement statement = statementRepository
                    .findById(statementId)
                    .orElseThrow(() -> new RuntimeException("Statement not found"));

            // Run analysis
            AIAnalysisResponse parsed = analyzeFromContext(profile.getContextJson());

            // Persist per-statement analysis
            String cleaned = objectMapper.writeValueAsString(parsed);
            FinancialAIAnalysis analysis = new FinancialAIAnalysis();
            analysis.setUser(statement.getUser());
            analysis.setStatement(statement);
            analysis.setAnalysisJson(cleaned);
            analysis.setModel("gpt-4o-mini");
            analysis.setPromptVersion("v2");
            analysis.setUpdatedAt(LocalDateTime.now());
            analysisRepository.save(analysis);

            return parsed;

        } catch (Exception e) {
            throw new RuntimeException("AI analysis failed", e);
        }
    }

    /**
     * NEW: Analyze from a pre-built context string.
     *
     * Used by UserProfileAggregatorService to run analysis on the merged,
     * cross-statement context without needing a statementId.
     *
     * Does NOT persist — the caller (UserProfileAggregatorService) is
     * responsible for writing the result to UserFinancialProfile.
     *
     * @param contextJson the rendered prompt context from AIContextBuilderService
     * @return parsed AIAnalysisResponse
     */
    private AIContextBuilderService.HealthScore extractHealthScore(
            String contextJson
    ) {

        try {

            // Example line:
            // Score: 42/100 (D — At Risk)

            Pattern pattern = Pattern.compile(
                    "Score:\\s*(\\d+)/100\\s*\\((.*?)\\s*—\\s*(.*?)\\)"
            );

            Matcher matcher = pattern.matcher(contextJson);

            if (matcher.find()) {

                int score = Integer.parseInt(
                        matcher.group(1)
                );

                String grade = matcher.group(2).trim();

                String label = matcher.group(3).trim();

                return new AIContextBuilderService.HealthScore(
                        score,
                        grade,
                        label
                );
            }

        } catch (Exception ignored) {}

        return new AIContextBuilderService.HealthScore(
                50,
                "C",
                "Needs Attention"
        );
    }

    public AIAnalysisResponse analyzeFromContext(String contextJson) {
        try {
            String prompt = ANALYSIS_PROMPT_TEMPLATE.replace("{{CONTEXT}}", contextJson);

            String aiResponse = openAIService.analyze(prompt);

            String cleaned = aiResponse
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            AIAnalysisResponse response =
                    objectMapper.readValue(
                            cleaned,
                            AIAnalysisResponse.class
                    );

// inject deterministic backend score
            response.setHealthScore(
                    extractHealthScore(contextJson)
            );

            return response;

        } catch (Exception e) {
            throw new RuntimeException("AI analysis from context failed", e);
        }
    }
}