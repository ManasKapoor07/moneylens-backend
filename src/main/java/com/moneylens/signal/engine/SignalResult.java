package com.moneylens.signal.engine;

import com.moneylens.entity.BehavioralSignal;

import java.math.BigDecimal;

/**
 * SignalResult
 *
 * Lightweight value object produced by each signal detector.
 * Converted to a BehavioralSignal entity and persisted by BehavioralSignalEngine.
 *
 * Keeps the detector logic clean — detectors don't touch the DB or UUID generation.
 */
public record SignalResult(
        BehavioralSignal.SignalType signalType,
        BehavioralSignal.Severity   severity,
        boolean                     fired,
        double                      confidence,
        BigDecimal                  value,
        String                      unit,
        String                      evidence
) {

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static SignalResult fired(
            BehavioralSignal.SignalType type,
            BehavioralSignal.Severity severity,
            double confidence,
            BigDecimal value,
            String unit,
            String evidence
    ) {
        return new SignalResult(type, severity, true, confidence, value, unit, evidence);
    }

    public static SignalResult notFired(
            BehavioralSignal.SignalType type,
            BigDecimal value,
            String unit
    ) {
        // Not-fired signals stored at LOW severity with the measured value,
        // so longitudinal tracking can see "this month it didn't fire either."
        return new SignalResult(type, BehavioralSignal.Severity.LOW, false, 1.0, value, unit, null);
    }
}