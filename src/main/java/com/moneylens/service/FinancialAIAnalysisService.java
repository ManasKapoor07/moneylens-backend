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
        // RETURN SAVED ANALYSIS
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
            // LOAD PROFILE
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

            String context =
                    profile.getContextJson();

            // =============================================
            // PROMPT
            // =============================================

            String prompt = """
        You are MoneyLens AI.

        MoneyLens is an AI-powered financial observability platform.

        Your role is NOT to behave like:
        - a budgeting app
        - a bank assistant
        - customer support
        - a motivational coach
        - a generic finance chatbot

        Your role is to function like a behavioral financial intelligence analyst.

        Analyze:
        - financial behavior
        - spending psychology
        - cash flow discipline
        - recurring money habits
        - dependency patterns
        - spending velocity
        - impulse tendencies
        - lifestyle inflation
        - financial stress signals
        - savings behavior
        - financial stability trajectory

        Think beyond transactions.

        Interpret what the financial behavior reveals about the user's money habits and decision patterns.

        Your analysis should make the user feel:
        - understood
        - financially aware
        - emotionally connected to their money behavior
        - more conscious of hidden spending patterns

        The user should feel:
        "this explains my financial behavior better than banking apps ever did."

        Avoid generic budgeting advice.

        Avoid obvious observations.

        Prefer:
        - behavioral interpretation
        - pattern recognition
        - timing analysis
        - spending dynamics
        - habit detection
        - financial trajectory analysis
        - hidden risk detection

        Focus especially on:
        - post-salary spending behavior
        - rapid money depletion patterns
        - recurring merchant dependency
        - peer-to-peer transfer behavior
        - emotional spending signals
        - discretionary spending concentration
        - recurring low-visibility expenses
        - spending spikes
        - financial volatility
        - weak savings discipline
        - behavioral inconsistencies

        Detect:
        - opportunity leakage
        - hidden lifestyle creep
        - dependency risks
        - unstable cash flow behavior
        - recurring financial friction

        Also generate long-term financial projection insights.

        Projection insights should:
        - feel subtle
        - feel intelligent
        - feel calm
        - feel analytical
        - feel premium
        - avoid sounding preachy

        Focus projections on:
        - recurring spending leakage
        - compounding opportunity
        - habit-based investment potential
        - savings automation impact
        - long-term financial resilience
        - future stability improvements

        Avoid:
        - unrealistic wealth projections
        - exaggerated investment claims
        - guilt-driven advice
        - fake motivational language

        Recommendations must:
        - feel highly personalized
        - prioritize highest impact actions first
        - be behavior-specific
        - be practical
        - be financially intelligent
        - improve awareness before optimization

        Important:
        Do not merely summarize numbers.

        Explain:
        - what the behavior means
        - why the pattern matters
        - where the financial risk is forming
        - how habits are shaping future financial stability

        Surface observations users usually fail to notice themselves.

        ALL JSON FIELDS ARE MANDATORY.

        You MUST return:
        - summary
        - risks
        - positiveHabits
        - recommendations
        - nextActions
        - projections
        - behavioralSignals
        - hiddenPatterns

        Never omit any field.

        If insight quality is low for a section,
        still return meaningful observations.

        Minimum requirements:
        - risks: minimum 4 items
        - recommendations: minimum 4 items
        - nextActions: minimum 4 items
        - projections: minimum 3 items
        - behavioralSignals: minimum 3 items
        - hiddenPatterns: minimum 3 items

        The response is consumed programmatically
        by the MoneyLens intelligence engine,
        so schema consistency is critical.

        Return STRICT RAW JSON ONLY.

        DO NOT:
        - wrap response in markdown
        - use ```json
        - explain anything outside JSON

        JSON FORMAT:

        {
          "summary": "...",

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
            "..."
          ],

          "behavioralSignals": [
            "..."
          ],

          "hiddenPatterns": [
            "..."
          ]
        }

        FIELD GUIDELINES:

        summary:
        - 1 strong high-signal overview
        - emotionally intelligent
        - insight-driven
        - concise

        risks:
        - real financial risks only
        - behavior-backed
        - practical

        positiveHabits:
        - highlight stabilizing behaviors
        - avoid fake positivity

        recommendations:
        - high leverage improvements
        - behavior-specific
        - financially meaningful

        nextActions:
        - immediate practical actions
        - simple and realistic

        projections:
        - subtle opportunity-cost observations
        - future impact analysis
        - compounding awareness
        - long-term stability insights

        behavioralSignals:
        - patterns about how money is emotionally or behaviorally used
        - spending timing insights
        - discipline observations
        - recurring behavior loops

        hiddenPatterns:
        - observations users likely don't notice
        - non-obvious financial dynamics
        - recurring unnoticed habits
        - behavioral contradictions

        Tone:
        - premium
        - observant
        - concise
        - sharp
        - human
        - analytical
        - insight-first

        PROFILE:
        %s
        """.formatted(context);
// =============================================
            // OPENAI
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
            // SAVE ANALYSIS
            // =============================================

            FinancialAIAnalysis analysis =
                    new FinancialAIAnalysis();

            analysis.setUser(
                    statement.getUser()
            );

            analysis.setStatement(
                    statement
            );

            analysis.setAnalysisJson(
                    cleaned
            );

            analysis.setModel(
                    "gpt-4o-mini"
            );

            analysis.setPromptVersion(
                    "v1"
            );

            analysis.setUpdatedAt(
                    LocalDateTime.now()
            );

            analysisRepository.save(
                    analysis
            );

            return parsed;

        } catch (Exception e) {

            throw new RuntimeException(
                    "AI analysis failed",
                    e
            );
        }
    }
}