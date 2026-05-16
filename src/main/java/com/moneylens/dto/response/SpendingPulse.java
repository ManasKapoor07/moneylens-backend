package com.moneylens.dto.response;

public record SpendingPulse(

        // "volatile" | "stable" | "declining" | "improving"
        String status,

        // Single punchy sentence — the vibe of their cash flow
        // "Your money moves in bursts, not streams."
        String summary,

        // Honest 0–100 score. Not flattering.
        int stabilityScore
) {}