package com.moneylens.dto.response;

public record SpendingPulse(

        // "volatile" | "stable" | "declining" | "improving"
        String status,

        // Single punchy sentence — the vibe of their cash flow
        String summary

) {}